/**
 * Alerts handler (Phase 8 — Agent A).
 *
 * Implements the two endpoints the `/alerts` page consumes:
 *
 *   GET  /api/v1/alerts                    → list + filters
 *   POST /api/v1/alerts/:id/acknowledge    → ack a single alert
 *
 * Role gate (both endpoints): SYSTEM_ADMIN or OPS_USER. Other internal /
 * LSP roles raise 401 — operational alerts are an internal-ops surface,
 * not a tenant-facing one.
 *
 * BR coverage:
 *   - BR-5 idempotency — POST /:id/acknowledge replays through the router-
 *     level cache (mutating route + Idempotency-Key header).
 *
 * Self-registers routes on import. `alerts.test.ts` imports this module
 * directly; the page wiring imports it transitively via
 * `src/features/alerts/api.ts`.
 */
import { z } from "zod";
import { AlertSeverity, AlertStatus, AlertSubjectType } from "@/schemas/alert";
import type { OperationalAlert, Role } from "@/types";
import { dispatch, registerRoute, type MockRequest } from "../router";
import { BadRequestError, NotFoundError, UnauthorizedError } from "../errors";
import type { MockDb } from "../db/state";
import type {
  AcknowledgeAlertInput,
  AcknowledgeAlertResponse,
  AlertRow,
  AlertsListFilters,
  AlertsListResponse,
} from "@/features/alerts/types";

// ─── Role helpers ────────────────────────────────────────────────────────────

const ALERT_ROLES = new Set<Role>(["SYSTEM_ADMIN", "OPS_USER"]);

interface ActiveSession {
  userId: string;
  role: Role;
}

function requireAlertSession(db: MockDb, correlationId: string): ActiveSession {
  if (!db.currentSession) {
    throw new UnauthorizedError(correlationId, "no active session");
  }
  const user = db.users.get(db.currentSession.userId);
  if (!user) {
    throw new UnauthorizedError(correlationId, "user no longer exists");
  }
  if (!ALERT_ROLES.has(user.role)) {
    throw new UnauthorizedError(correlationId, `role ${user.role} cannot access alerts`);
  }
  return { userId: user.id, role: user.role };
}

// ─── Projection ──────────────────────────────────────────────────────────────

function projectRow(db: MockDb, alert: OperationalAlert): AlertRow {
  const ack = alert.acknowledgedBy ? db.users.get(alert.acknowledgedBy) : null;
  return {
    ...alert,
    acknowledgedByName: ack?.username ?? null,
  };
}

// ─── List handler ────────────────────────────────────────────────────────────

const ListFiltersSchema = z.object({
  status: AlertStatus.optional(),
  severity: z.array(AlertSeverity).optional(),
  subjectType: AlertSubjectType.optional(),
  q: z.string().trim().min(1).max(120).optional(),
  page: z.coerce.number().int().min(0).optional(),
  pageSize: z.coerce.number().int().min(5).max(100).optional(),
});

function parseListQuery(
  req: MockRequest,
  correlationId: string,
): z.infer<typeof ListFiltersSchema> {
  const raw: Record<string, unknown> = { ...(req.query ?? {}) };
  if (typeof raw["severity"] === "string" && raw["severity"]) {
    raw["severity"] = (raw["severity"] as string).split(",").filter(Boolean);
  }
  const parsed = ListFiltersSchema.safeParse(raw);
  if (!parsed.success) {
    throw new BadRequestError(correlationId, "invalid list filters", parsed.error.flatten());
  }
  return parsed.data;
}

function listHandler(req: MockRequest, db: MockDb, correlationId: string): AlertsListResponse {
  requireAlertSession(db, correlationId);
  const filters = parseListQuery(req, correlationId);

  let rows: OperationalAlert[] = Array.from(db.alerts.values());

  if (filters.status) {
    rows = rows.filter((a) => a.status === filters.status);
  }
  if (filters.severity && filters.severity.length > 0) {
    const set = new Set(filters.severity);
    rows = rows.filter((a) => set.has(a.severity));
  }
  if (filters.subjectType) {
    rows = rows.filter((a) => a.subjectType === filters.subjectType);
  }
  if (filters.q) {
    const needle = filters.q.toLowerCase();
    rows = rows.filter(
      (a) => a.title.toLowerCase().includes(needle) || a.message.toLowerCase().includes(needle),
    );
  }

  // Newest first by createdAt.
  rows.sort((a, b) => (a.createdAt < b.createdAt ? 1 : -1));

  const total = rows.length;
  const page = filters.page ?? 0;
  const pageSize = filters.pageSize ?? 25;
  const slice = rows.slice(page * pageSize, page * pageSize + pageSize);

  return {
    items: slice.map((a) => projectRow(db, a)),
    total,
    page,
    pageSize,
  };
}

// ─── Acknowledge handler ─────────────────────────────────────────────────────

const AcknowledgeBodySchema = z.object({
  note: z.string().max(1000).optional().nullable(),
  idempotencyKey: z.string().min(1).max(80),
});

function acknowledgeHandler(
  req: MockRequest,
  db: MockDb,
  correlationId: string,
): AcknowledgeAlertResponse {
  const session = requireAlertSession(db, correlationId);
  const id = req.params?.["id"] ?? "";
  const alert = db.alerts.get(id);
  if (!alert) {
    throw new NotFoundError(correlationId, `alert ${id} not found`);
  }

  const parsed = AcknowledgeBodySchema.safeParse(req.body);
  if (!parsed.success) {
    throw new BadRequestError(correlationId, "invalid acknowledge body", parsed.error.flatten());
  }

  // Already-ACKNOWLEDGED: no-op, return current row. This keeps the UI
  // idempotent across double-clicks even without the BR-5 cache hit.
  if (alert.status === "ACKNOWLEDGED") {
    return { alert: projectRow(db, alert) };
  }

  const nowIso = new Date().toISOString();
  const note = parsed.data.note ?? null;
  const updated: OperationalAlert = {
    ...alert,
    status: "ACKNOWLEDGED",
    acknowledgedAt: nowIso,
    acknowledgedBy: session.userId,
    acknowledgmentNote: note,
  };
  db.alerts.set(alert.id, updated);
  return { alert: projectRow(db, updated) };
}

// ─── Route registration ──────────────────────────────────────────────────────

let registered = false;
export function registerAlertRoutes(): void {
  if (registered) return;
  registered = true;
  registerRoute("GET", "/api/v1/alerts", listHandler);
  registerRoute("POST", "/api/v1/alerts/:id/acknowledge", acknowledgeHandler, {
    mutating: true,
  });
}
registerAlertRoutes();

// ─── Dispatch parsers ────────────────────────────────────────────────────────

const OperationalAlertParser: z.ZodType<OperationalAlert> = z
  .object({
    id: z.string(),
    type: z.string(),
    severity: AlertSeverity,
    status: AlertStatus,
    title: z.string(),
    message: z.string(),
    subjectType: AlertSubjectType,
    subjectId: z.string(),
    correlationId: z.string(),
    contextJson: z.union([z.record(z.unknown()), z.string()]).optional(),
    createdAt: z.string(),
    acknowledgedAt: z.string().nullable(),
    acknowledgedBy: z.string().nullable(),
    acknowledgmentNote: z.string().nullable(),
  })
  .passthrough() as unknown as z.ZodType<OperationalAlert>;

const AlertRowParser: z.ZodType<AlertRow> = z
  .object({
    id: z.string(),
    type: z.string(),
    severity: AlertSeverity,
    status: AlertStatus,
    title: z.string(),
    message: z.string(),
    subjectType: AlertSubjectType,
    subjectId: z.string(),
    correlationId: z.string(),
    contextJson: z.union([z.record(z.unknown()), z.string()]).optional(),
    createdAt: z.string(),
    acknowledgedAt: z.string().nullable(),
    acknowledgedBy: z.string().nullable(),
    acknowledgmentNote: z.string().nullable(),
    acknowledgedByName: z.string().nullable(),
  })
  .passthrough() as unknown as z.ZodType<AlertRow>;

void OperationalAlertParser;

const ListResponseSchema: z.ZodType<AlertsListResponse> = z.object({
  items: z.array(AlertRowParser),
  total: z.number().int().nonnegative(),
  page: z.number().int().nonnegative(),
  pageSize: z.number().int().positive(),
}) as unknown as z.ZodType<AlertsListResponse>;

const AcknowledgeResponseSchema: z.ZodType<AcknowledgeAlertResponse> = z.object({
  alert: AlertRowParser,
}) as unknown as z.ZodType<AcknowledgeAlertResponse>;

// ─── Public wrappers ─────────────────────────────────────────────────────────

function queryFromFilters(filters: AlertsListFilters): Record<string, string> {
  const out: Record<string, string> = {};
  if (filters.status) out["status"] = filters.status;
  if (filters.severity && filters.severity.length > 0) {
    out["severity"] = filters.severity.join(",");
  }
  if (filters.subjectType) out["subjectType"] = filters.subjectType;
  if (filters.q) out["q"] = filters.q;
  if (typeof filters.page === "number") out["page"] = String(filters.page);
  if (typeof filters.pageSize === "number") {
    out["pageSize"] = String(filters.pageSize);
  }
  return out;
}

export async function listAlerts(filters: AlertsListFilters = {}): Promise<AlertsListResponse> {
  return dispatch(
    {
      method: "GET",
      path: "/api/v1/alerts",
      query: queryFromFilters(filters),
    },
    ListResponseSchema,
  );
}

export async function acknowledgeAlert(
  id: string,
  input: AcknowledgeAlertInput,
): Promise<AcknowledgeAlertResponse> {
  return dispatch(
    {
      method: "POST",
      path: `/api/v1/alerts/${id}/acknowledge`,
      body: input,
      headers: { "Idempotency-Key": input.idempotencyKey },
    },
    AcknowledgeResponseSchema,
  );
}
