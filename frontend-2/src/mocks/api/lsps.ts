/**
 * LSPs admin handler (Phase 9 — Agent A).
 *
 * Exposes the SYSTEM_ADMIN-only `/api/v1/admin/lsps` surface:
 *
 *   GET    /api/v1/admin/lsps                                — list + filters
 *   POST   /api/v1/admin/lsps                                — create
 *   PATCH  /api/v1/admin/lsps/:id                            — partial update
 *   GET    /api/v1/admin/lsps/:id/webhook-subscription       — read (nullable)
 *   PUT    /api/v1/admin/lsps/:id/webhook-subscription       — upsert
 *
 * Every mutation is router-`mutating:true` so the BR-5 idempotency-key cache
 * replays double-submits within 30s. Code uniqueness conflicts raise 409.
 *
 * Self-registers routes on import. The feature wrapper (`features/lsps/api.ts`)
 * side-effect imports this module; the orchestrator will also re-export this
 * namespace from `src/mocks/api/index.ts` after Agent A ships.
 */
import { z } from "zod";
import {
  Lsp as LspSchema,
  LspStatus,
  LspWebhookSubscription as LspWebhookSubscriptionSchema,
  WebhookEventType,
} from "@/schemas/lsp";
import type { Role, Lsp } from "@/types";
import type { LspWebhookSubscription } from "@/schemas/lsp";
import { dispatch, registerRoute, type MockRequest } from "../router";
import {
  BadRequestError,
  ConflictError,
  NotFoundError,
  UnauthorizedError,
} from "../errors";
import type { MockDb } from "../db/state";
import type {
  CreateLspInput,
  LspMutationResponse,
  LspRow,
  LspsListFilters,
  LspsListResponse,
  UpdateLspInput,
  UpsertWebhookSubscriptionInput,
  WebhookSubscriptionResponse,
} from "@/features/lsps/types";

// ─── Role helpers ────────────────────────────────────────────────────────────

const ADMIN_ONLY: Set<Role> = new Set<Role>(["SYSTEM_ADMIN"]);

interface ActiveSession {
  userId: string;
  role: Role;
}

function requireAdminSession(db: MockDb, correlationId: string): ActiveSession {
  if (!db.currentSession) {
    throw new UnauthorizedError(correlationId, "no active session");
  }
  const user = db.users.get(db.currentSession.userId);
  if (!user) {
    throw new UnauthorizedError(correlationId, "user no longer exists");
  }
  if (!ADMIN_ONLY.has(user.role)) {
    throw new UnauthorizedError(
      correlationId,
      `role ${user.role} cannot manage LSPs`,
    );
  }
  return { userId: user.id, role: user.role };
}

// ─── Projection ──────────────────────────────────────────────────────────────

function projectRow(db: MockDb, lsp: Lsp): LspRow {
  let apiClientCount = 0;
  for (const c of db.apiClients.values()) {
    if (c.lspId === lsp.id) apiClientCount += 1;
  }
  const sub = db.lspWebhookSubscriptions.get(lsp.id);
  return {
    ...lsp,
    apiClientCount,
    webhookEnabled: sub?.enabled === true,
  };
}

// ─── List handler ────────────────────────────────────────────────────────────

const ListFiltersSchema = z.object({
  status: LspStatus.optional(),
  q: z.string().trim().min(1).max(120).optional(),
  page: z.coerce.number().int().min(0).optional(),
  pageSize: z.coerce.number().int().min(5).max(100).optional(),
});

function parseListQuery(
  req: MockRequest,
  correlationId: string,
): z.infer<typeof ListFiltersSchema> {
  const raw: Record<string, unknown> = { ...(req.query ?? {}) };
  const parsed = ListFiltersSchema.safeParse(raw);
  if (!parsed.success) {
    throw new BadRequestError(
      correlationId,
      "invalid list filters",
      parsed.error.flatten(),
    );
  }
  return parsed.data;
}

function listHandler(
  req: MockRequest,
  db: MockDb,
  correlationId: string,
): LspsListResponse {
  requireAdminSession(db, correlationId);
  const filters = parseListQuery(req, correlationId);

  let rows: Lsp[] = Array.from(db.lsps.values());

  if (filters.status) {
    rows = rows.filter((l) => l.status === filters.status);
  }
  if (filters.q) {
    const needle = filters.q.toLowerCase();
    rows = rows.filter(
      (l) =>
        l.code.toLowerCase().includes(needle) ||
        l.name.toLowerCase().includes(needle),
    );
  }

  // Newest first by createdAt.
  rows.sort((a, b) => (a.createdAt < b.createdAt ? 1 : -1));

  const total = rows.length;
  const page = filters.page ?? 0;
  const pageSize = filters.pageSize ?? 25;
  const slice = rows.slice(page * pageSize, page * pageSize + pageSize);

  return {
    items: slice.map((l) => projectRow(db, l)),
    total,
    page,
    pageSize,
  };
}

// ─── Create handler ──────────────────────────────────────────────────────────

const CreateBodySchema = z.object({
  code: LspSchema.shape.code,
  name: LspSchema.shape.name,
  idempotencyKey: z.string().min(1).max(80),
});

function findByCode(db: MockDb, code: string): Lsp | undefined {
  const needle = code.toLowerCase();
  for (const lsp of db.lsps.values()) {
    if (lsp.code.toLowerCase() === needle) return lsp;
  }
  return undefined;
}

function createHandler(
  req: MockRequest,
  db: MockDb,
  correlationId: string,
): LspMutationResponse {
  requireAdminSession(db, correlationId);
  const parsed = CreateBodySchema.safeParse(req.body);
  if (!parsed.success) {
    throw new BadRequestError(
      correlationId,
      "invalid create body",
      parsed.error.flatten(),
    );
  }
  const { code, name } = parsed.data;
  if (findByCode(db, code)) {
    throw new ConflictError(correlationId, `LSP code "${code}" already exists`);
  }
  const lsp: Lsp = {
    id: crypto.randomUUID(),
    code,
    name,
    status: "ACTIVE",
    createdAt: new Date().toISOString(),
  };
  db.lsps.set(lsp.id, lsp);
  return { lsp: projectRow(db, lsp) };
}

// ─── Update handler ──────────────────────────────────────────────────────────

const UpdateBodySchema = z
  .object({
    name: LspSchema.shape.name.optional(),
    status: LspStatus.optional(),
    idempotencyKey: z.string().min(1).max(80),
  })
  .refine((v) => v.name !== undefined || v.status !== undefined, {
    message: "patch must include at least one of: name, status",
  });

function updateHandler(
  req: MockRequest,
  db: MockDb,
  correlationId: string,
): LspMutationResponse {
  requireAdminSession(db, correlationId);
  const id = req.params?.["id"] ?? "";
  const existing = db.lsps.get(id);
  if (!existing) {
    throw new NotFoundError(correlationId, `LSP ${id} not found`);
  }
  const parsed = UpdateBodySchema.safeParse(req.body);
  if (!parsed.success) {
    throw new BadRequestError(
      correlationId,
      "invalid update body",
      parsed.error.flatten(),
    );
  }
  const next: Lsp = {
    ...existing,
    ...(parsed.data.name !== undefined ? { name: parsed.data.name } : {}),
    ...(parsed.data.status !== undefined ? { status: parsed.data.status } : {}),
  };
  db.lsps.set(id, next);
  return { lsp: projectRow(db, next) };
}

// ─── Webhook subscription handlers ───────────────────────────────────────────

const UpsertSubscriptionBodySchema = z.object({
  enabled: LspWebhookSubscriptionSchema.shape.enabled,
  endpointUrl: LspWebhookSubscriptionSchema.shape.endpointUrl,
  signingSecret: LspWebhookSubscriptionSchema.shape.signingSecret,
  eventTypes: LspWebhookSubscriptionSchema.shape.eventTypes,
  idempotencyKey: z.string().min(1).max(80),
});

function readSubscriptionHandler(
  req: MockRequest,
  db: MockDb,
  correlationId: string,
): WebhookSubscriptionResponse {
  requireAdminSession(db, correlationId);
  const id = req.params?.["id"] ?? "";
  if (!db.lsps.has(id)) {
    throw new NotFoundError(correlationId, `LSP ${id} not found`);
  }
  const sub = db.lspWebhookSubscriptions.get(id) ?? null;
  return { subscription: sub };
}

function upsertSubscriptionHandler(
  req: MockRequest,
  db: MockDb,
  correlationId: string,
): WebhookSubscriptionResponse {
  requireAdminSession(db, correlationId);
  const id = req.params?.["id"] ?? "";
  if (!db.lsps.has(id)) {
    throw new NotFoundError(correlationId, `LSP ${id} not found`);
  }
  const parsed = UpsertSubscriptionBodySchema.safeParse(req.body);
  if (!parsed.success) {
    throw new BadRequestError(
      correlationId,
      "invalid subscription body",
      parsed.error.flatten(),
    );
  }
  const sub: LspWebhookSubscription = {
    lspId: id,
    enabled: parsed.data.enabled,
    endpointUrl: parsed.data.endpointUrl,
    signingSecret: parsed.data.signingSecret,
    eventTypes: parsed.data.eventTypes,
  };
  db.lspWebhookSubscriptions.set(id, sub);
  return { subscription: sub };
}

// ─── Route registration ──────────────────────────────────────────────────────

let registered = false;
export function registerLspRoutes(): void {
  if (registered) return;
  registered = true;
  registerRoute("GET", "/api/v1/admin/lsps", listHandler);
  registerRoute("POST", "/api/v1/admin/lsps", createHandler, { mutating: true });
  registerRoute("PATCH", "/api/v1/admin/lsps/:id", updateHandler, {
    mutating: true,
  });
  registerRoute(
    "GET",
    "/api/v1/admin/lsps/:id/webhook-subscription",
    readSubscriptionHandler,
  );
  registerRoute(
    "PUT",
    "/api/v1/admin/lsps/:id/webhook-subscription",
    upsertSubscriptionHandler,
    { mutating: true },
  );
}
registerLspRoutes();

// ─── Dispatch parsers ────────────────────────────────────────────────────────

const LspRowParser: z.ZodType<LspRow> = LspSchema.extend({
  apiClientCount: z.number().int().nonnegative(),
  webhookEnabled: z.boolean(),
}) as unknown as z.ZodType<LspRow>;

const ListResponseSchema: z.ZodType<LspsListResponse> = z.object({
  items: z.array(LspRowParser),
  total: z.number().int().nonnegative(),
  page: z.number().int().nonnegative(),
  pageSize: z.number().int().positive(),
}) as unknown as z.ZodType<LspsListResponse>;

const MutationResponseSchema: z.ZodType<LspMutationResponse> = z.object({
  lsp: LspRowParser,
}) as unknown as z.ZodType<LspMutationResponse>;

const SubscriptionResponseSchema: z.ZodType<WebhookSubscriptionResponse> =
  z.object({
    subscription: LspWebhookSubscriptionSchema.nullable(),
  }) as unknown as z.ZodType<WebhookSubscriptionResponse>;

// ─── Public wrappers ─────────────────────────────────────────────────────────

function queryFromFilters(
  filters: LspsListFilters,
): Record<string, string> {
  const out: Record<string, string> = {};
  if (filters.status) out["status"] = filters.status;
  if (filters.q) out["q"] = filters.q;
  if (typeof filters.page === "number") out["page"] = String(filters.page);
  if (typeof filters.pageSize === "number") {
    out["pageSize"] = String(filters.pageSize);
  }
  return out;
}

export async function listLsps(
  filters: LspsListFilters = {},
): Promise<LspsListResponse> {
  return dispatch(
    {
      method: "GET",
      path: "/api/v1/admin/lsps",
      query: queryFromFilters(filters),
    },
    ListResponseSchema,
  );
}

export async function createLsp(
  input: CreateLspInput,
): Promise<LspMutationResponse> {
  return dispatch(
    {
      method: "POST",
      path: "/api/v1/admin/lsps",
      body: input,
      headers: { "Idempotency-Key": input.idempotencyKey },
    },
    MutationResponseSchema,
  );
}

export async function updateLsp(
  id: string,
  input: UpdateLspInput,
): Promise<LspMutationResponse> {
  return dispatch(
    {
      method: "PATCH",
      path: `/api/v1/admin/lsps/${id}`,
      body: input,
      headers: { "Idempotency-Key": input.idempotencyKey },
    },
    MutationResponseSchema,
  );
}

export async function getLspWebhookSubscription(
  id: string,
): Promise<WebhookSubscriptionResponse> {
  return dispatch(
    {
      method: "GET",
      path: `/api/v1/admin/lsps/${id}/webhook-subscription`,
    },
    SubscriptionResponseSchema,
  );
}

export async function upsertLspWebhookSubscription(
  id: string,
  input: UpsertWebhookSubscriptionInput,
): Promise<WebhookSubscriptionResponse> {
  return dispatch(
    {
      method: "PUT",
      path: `/api/v1/admin/lsps/${id}/webhook-subscription`,
      body: input,
      headers: { "Idempotency-Key": input.idempotencyKey },
    },
    SubscriptionResponseSchema,
  );
}

// Avoid an unused-import error from WebhookEventType in the type-only re-export.
void WebhookEventType;
