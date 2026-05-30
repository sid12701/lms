/**
 * Audit explorer handler (Phase 8 / SYSTEM_ADMIN-only per D4).
 *
 * Surfaces every row from the five distinct audit tables defined in
 * `@/schemas/audit` through a single endpoint:
 *
 *   GET /api/v1/audit/events
 *     ?streams[]=APPLICATION&streams[]=PII_REVEAL&...
 *     &actorId=<uuid>
 *     &correlationId=<id>
 *     &dateFrom=YYYY-MM-DD&dateTo=YYYY-MM-DD
 *     &q=<free-text over headline + actorName>
 *     &page=0&pageSize=50
 *
 * Returns `{ items, total, page, pageSize }` — newest first.
 *
 * Role gate: only SYSTEM_ADMIN sessions are admitted. Every other role
 * (including OPS_USER, PRODUCT_ADMIN, LSP_*) raises `UnauthorizedError`
 * (HTTP 401) per the locked decision D4.
 *
 * PII discipline (BR-7): PII reveal rows carry the field NAME (e.g. "PAN")
 * — never the value — in the projected `headline`. The raw payload mirrors
 * `PiiRevealEvent` exactly: it captures only the reason, the actor, and the
 * subject id, never the cleartext value, so re-displaying `raw` is safe.
 *
 * No mutations are registered here. The audit log is append-only and is
 * written by the other handlers (loan-applications, borrowers, …).
 */
import { z } from "zod";
import { IsoDate, Uuid } from "@/schemas/common";
import type {
  ApplicationAuditEvent,
  DocumentAccessEvent,
  IntakeAuditEvent,
  PiiRevealEvent,
  ProductAuditEvent,
  Role,
} from "@/types";
import {
  AuditEventsResponseSchema,
  AUDIT_STREAMS,
  type AuditEventsResponse,
  type AuditRow,
  type AuditStream,
} from "@/features/audit/types";
import { dispatch, registerRoute, type MockRequest } from "../router";
import { BadRequestError, UnauthorizedError, newCorrelationId } from "../errors";
import type { MockDb } from "../db/state";

// ─── Session / role gate ─────────────────────────────────────────────────────

interface ActiveSession {
  userId: string;
  role: Role;
  lspId: string | null;
}

function requireSystemAdmin(db: MockDb, correlationId: string): ActiveSession {
  if (!db.currentSession) {
    throw new UnauthorizedError(correlationId, "no active session");
  }
  const user = db.users.get(db.currentSession.userId);
  if (!user) {
    throw new UnauthorizedError(correlationId, "user no longer exists");
  }
  if (user.role !== "SYSTEM_ADMIN") {
    throw new UnauthorizedError(
      correlationId,
      `role ${user.role} cannot access the audit explorer`,
    );
  }
  return { userId: user.id, role: user.role, lspId: user.lspId };
}

// ─── Filter parsing ──────────────────────────────────────────────────────────

const ListFiltersSchema = z.object({
  streams: z.array(z.enum(AUDIT_STREAMS)).optional(),
  actorId: Uuid.optional(),
  correlationId: z.string().min(1).max(80).optional(),
  dateFrom: IsoDate.optional(),
  dateTo: IsoDate.optional(),
  q: z.string().trim().min(1).max(120).optional(),
  page: z.coerce.number().int().min(0).optional(),
  pageSize: z.coerce.number().int().min(5).max(200).optional(),
});

type ListFilters = z.infer<typeof ListFiltersSchema>;

function parseStreamsParam(value: string | string[] | undefined): string[] | undefined {
  if (value === undefined) return undefined;
  if (Array.isArray(value)) {
    const flat = value.flatMap((v) => v.split(",")).filter(Boolean);
    return flat.length > 0 ? flat : undefined;
  }
  const parts = value.split(",").filter(Boolean);
  return parts.length > 0 ? parts : undefined;
}

function parseQuery(req: MockRequest, correlationId: string): ListFilters {
  const raw: Record<string, unknown> = { ...(req.query ?? {}) };

  // Accept either ?streams[]=A&streams[]=B or ?streams=A,B (FilterBar uses
  // repeated params; the API client uses comma-joined).
  const streamsRaw =
    (raw["streams[]"] as string | string[] | undefined) ??
    (raw["streams"] as string | string[] | undefined);
  const streams = parseStreamsParam(streamsRaw);
  if (streams) raw["streams"] = streams;
  delete raw["streams[]"];

  const parsed = ListFiltersSchema.safeParse(raw);
  if (!parsed.success) {
    throw new BadRequestError(correlationId, "invalid audit filters", parsed.error.flatten());
  }
  return parsed.data;
}

// ─── Projection ──────────────────────────────────────────────────────────────

function actorName(db: MockDb, actorId: string): string {
  // `User` schema has no `fullName` field; `username` is the closest
  // human-readable identifier and is what the UI surfaces elsewhere.
  const user = db.users.get(actorId);
  return user?.username ?? "Unknown actor";
}

const PII_FIELD_LABEL: Record<string, string> = {
  PAN: "PAN",
  AADHAAR: "Aadhaar",
  MOBILE: "Mobile",
  ACCOUNT_NUMBER: "Account number",
  EMAIL: "Email",
};

const DOC_ACTION_LABEL: Record<string, string> = {
  VIEWED: "Document viewed",
  DOWNLOADED: "Document downloaded",
  VERIFIED: "Document verified",
  REJECTED: "Document rejected",
};

const PRODUCT_ACTION_LABEL: Record<string, string> = {
  CREATED: "Product created",
  UPDATED: "Product updated",
  MAPPING_CHANGED: "Product mapping changed",
  STATUS_CHANGED: "Product status changed",
};

function humaniseStatus(status: string): string {
  // "AWAITING_APPROVAL" → "Awaiting approval"
  const lower = status.toLowerCase().replace(/_/g, " ");
  return lower.charAt(0).toUpperCase() + lower.slice(1);
}

function projectApplication(event: ApplicationAuditEvent, db: MockDb): AuditRow {
  const headline = event.fromStatus
    ? `${humaniseStatus(event.fromStatus)} → ${humaniseStatus(event.toStatus)}`
    : `Created (${humaniseStatus(event.toStatus)})`;
  return {
    id: event.id,
    stream: "APPLICATION",
    createdAt: event.createdAt,
    actorId: event.actorId,
    actorName: actorName(db, event.actorId),
    actorRole: event.actorRole,
    correlationId: event.correlationId,
    subjectType: "LOAN_APPLICATION",
    subjectId: event.applicationId,
    headline,
    raw: event,
  };
}

function projectIntake(event: IntakeAuditEvent, db: MockDb): AuditRow {
  return {
    id: event.id,
    stream: "INTAKE",
    createdAt: event.createdAt,
    actorId: event.actorId,
    actorName: actorName(db, event.actorId),
    actorRole: null,
    correlationId: event.correlationId,
    subjectType: "LOAN_APPLICATION",
    subjectId: event.applicationId,
    headline: `Intake snapshot recorded (${event.channel})`,
    raw: event,
  };
}

function projectPiiReveal(event: PiiRevealEvent, db: MockDb): AuditRow {
  // BR-7 — surface the field NAME only, never any value.
  const label = PII_FIELD_LABEL[event.fieldName] ?? event.fieldName;
  return {
    id: event.id,
    stream: "PII_REVEAL",
    createdAt: event.revealedAt,
    actorId: event.actorId,
    actorName: actorName(db, event.actorId),
    actorRole: event.actorRole,
    correlationId: event.correlationId,
    subjectType: "BORROWER",
    subjectId: event.subjectId,
    headline: `${label} revealed`,
    raw: event,
  };
}

function projectDocumentAccess(event: DocumentAccessEvent, db: MockDb): AuditRow {
  return {
    id: event.id,
    stream: "DOCUMENT_ACCESS",
    createdAt: event.accessedAt,
    actorId: event.actorId,
    actorName: actorName(db, event.actorId),
    actorRole: event.actorRole,
    correlationId: event.correlationId,
    subjectType: "LOAN_DOCUMENT",
    subjectId: event.documentId,
    headline: DOC_ACTION_LABEL[event.action] ?? `Document ${event.action.toLowerCase()}`,
    raw: event,
  };
}

function projectProduct(event: ProductAuditEvent, db: MockDb): AuditRow {
  return {
    id: event.id,
    stream: "PRODUCT",
    createdAt: event.createdAt,
    actorId: event.actorId,
    actorName: actorName(db, event.actorId),
    actorRole: event.actorRole,
    correlationId: event.correlationId,
    subjectType: "LOAN_PRODUCT",
    subjectId: event.productId,
    headline: PRODUCT_ACTION_LABEL[event.action] ?? `Product ${event.action.toLowerCase()}`,
    raw: event,
  };
}

function collectRows(db: MockDb, streams: ReadonlySet<AuditStream>): AuditRow[] {
  const rows: AuditRow[] = [];
  if (streams.has("APPLICATION")) {
    for (const e of db.auditApplication) rows.push(projectApplication(e, db));
  }
  if (streams.has("INTAKE")) {
    for (const e of db.auditIntake) rows.push(projectIntake(e, db));
  }
  if (streams.has("PII_REVEAL")) {
    for (const e of db.auditPiiReveal) rows.push(projectPiiReveal(e, db));
  }
  if (streams.has("DOCUMENT_ACCESS")) {
    for (const e of db.auditDocumentAccess) rows.push(projectDocumentAccess(e, db));
  }
  if (streams.has("PRODUCT")) {
    for (const e of db.auditProduct) rows.push(projectProduct(e, db));
  }
  return rows;
}

// ─── Filtering ───────────────────────────────────────────────────────────────

function withinDateRange(iso: string, from: string | undefined, to: string | undefined): boolean {
  if (from === undefined && to === undefined) return true;
  const date = iso.slice(0, 10);
  if (from !== undefined && date < from) return false;
  if (to !== undefined && date > to) return false;
  return true;
}

function applyFilters(rows: ReadonlyArray<AuditRow>, filters: ListFilters): AuditRow[] {
  let out = [...rows];
  if (filters.actorId) {
    out = out.filter((r) => r.actorId === filters.actorId);
  }
  if (filters.correlationId) {
    const needle = filters.correlationId.toLowerCase();
    out = out.filter((r) => r.correlationId.toLowerCase().includes(needle));
  }
  if (filters.dateFrom !== undefined || filters.dateTo !== undefined) {
    out = out.filter((r) => withinDateRange(r.createdAt, filters.dateFrom, filters.dateTo));
  }
  if (filters.q) {
    const needle = filters.q.toLowerCase();
    out = out.filter(
      (r) =>
        r.headline.toLowerCase().includes(needle) || r.actorName.toLowerCase().includes(needle),
    );
  }
  return out;
}

// ─── Handler ─────────────────────────────────────────────────────────────────

function eventsHandler(req: MockRequest, db: MockDb, correlationId: string): AuditEventsResponse {
  requireSystemAdmin(db, correlationId);
  const filters = parseQuery(req, correlationId);

  const streamSet: ReadonlySet<AuditStream> = new Set<AuditStream>(
    filters.streams && filters.streams.length > 0
      ? (filters.streams as AuditStream[])
      : (AUDIT_STREAMS as readonly AuditStream[]),
  );

  const collected = collectRows(db, streamSet);
  const filtered = applyFilters(collected, filters);
  // Newest first — every stream uses ISO timestamps so lexical = chronological.
  filtered.sort((a, b) => (a.createdAt < b.createdAt ? 1 : -1));

  const total = filtered.length;
  const page = filters.page ?? 0;
  const pageSize = filters.pageSize ?? 50;
  const items = filtered.slice(page * pageSize, page * pageSize + pageSize);

  return { items, total, page, pageSize };
}

// ─── Route registration ──────────────────────────────────────────────────────

let registered = false;
export function registerAuditRoutes(): void {
  if (registered) return;
  registered = true;
  registerRoute("GET", "/api/v1/audit/events", eventsHandler);
}

registerAuditRoutes();

// ─── Public API surface ──────────────────────────────────────────────────────

export type AuditEventsQuery = {
  streams?: ReadonlyArray<AuditStream>;
  actorId?: string;
  correlationId?: string;
  dateFrom?: string;
  dateTo?: string;
  q?: string;
  page?: number;
  pageSize?: number;
};

interface RequestOptions {
  correlationId?: string;
}

function buildAuditEventsQueryParams(query: AuditEventsQuery): Record<string, string> {
  const out: Record<string, string> = {};
  if (query.streams && query.streams.length > 0) {
    out["streams"] = query.streams.join(",");
  }
  if (query.actorId) out["actorId"] = query.actorId;
  if (query.correlationId) out["correlationId"] = query.correlationId;
  if (query.dateFrom) out["dateFrom"] = query.dateFrom;
  if (query.dateTo) out["dateTo"] = query.dateTo;
  if (query.q) out["q"] = query.q;
  if (typeof query.page === "number") out["page"] = String(query.page);
  if (typeof query.pageSize === "number") out["pageSize"] = String(query.pageSize);
  return out;
}

/** Serialise to a query string (used by the wrapper at @/features/audit/api). */
export function buildAuditEventsQuery(query: AuditEventsQuery): string {
  const params = new URLSearchParams(buildAuditEventsQueryParams(query));
  return params.toString();
}

export async function events(
  query: AuditEventsQuery = {},
  opts: RequestOptions = {},
): Promise<AuditEventsResponse> {
  return dispatch(
    {
      method: "GET",
      path: "/api/v1/audit/events",
      query: buildAuditEventsQueryParams(query),
      headers: {
        "X-Correlation-Id": opts.correlationId ?? newCorrelationId(),
      },
    },
    AuditEventsResponseSchema,
  );
}
