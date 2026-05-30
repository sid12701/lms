/**
 * API clients admin handler (Phase 9 — Agent D).
 *
 * Implements the four endpoints the `/api-clients` admin surface consumes:
 *
 *   GET    /api/v1/admin/api-clients                  → list + filters
 *   POST   /api/v1/admin/api-clients                  → create (+ secret)
 *   PATCH  /api/v1/admin/api-clients/:id              → update (name/status/ipAllowList)
 *   POST   /api/v1/admin/api-clients/:id/rotate-secret → rotate (+ secret)
 *
 * Role gate: SYSTEM_ADMIN-only. Every other role raises 401.
 *
 * BR coverage:
 *   - BR-5 idempotency — all mutations carry an `Idempotency-Key` header
 *     and replay through the router-level cache.
 *   - BR-8 IP allow-list — every `ipAllowList` entry is validated against
 *     `IpCidr` (IPv4 + optional /N CIDR). Invalid entries → 400 with the
 *     per-entry index in the issue path.
 *
 * `lastRotatedAt` is a module-local Map keyed by `client.id` so we don't
 * have to patch `src/mocks/db/state.ts` (Agent A's territory). The real
 * backend will persist it on the row; for the mock this is sufficient.
 *
 * Self-registers routes on import. Tests import this module directly; the
 * page wiring imports it via `src/features/api-clients/api.ts`.
 */
import { z } from "zod";
import type { ApiClient, Role } from "@/types";
import { ApiClientStatus } from "@/schemas/user";
import { IpCidr } from "@/schemas/common";
import { dispatch, registerRoute, type MockRequest } from "../router";
import { BadRequestError, NotFoundError, UnauthorizedError } from "../errors";
import type { MockDb } from "../db/state";
import type {
  ApiClientMutationResponse,
  ApiClientRow,
  ApiClientsListFilters,
  ApiClientsListResponse,
  CreateApiClientInput,
  CreateApiClientResponse,
  RotateApiClientSecretInput,
  RotateApiClientSecretResponse,
  UpdateApiClientInput,
} from "@/features/api-clients/types";

// ─── Role gate ───────────────────────────────────────────────────────────────

const ADMIN_ROLE: Role = "SYSTEM_ADMIN";

interface ActiveSession {
  userId: string;
  role: Role;
}

function requireSystemAdmin(db: MockDb, correlationId: string): ActiveSession {
  if (!db.currentSession) {
    throw new UnauthorizedError(correlationId, "no active session");
  }
  const user = db.users.get(db.currentSession.userId);
  if (!user) {
    throw new UnauthorizedError(correlationId, "user no longer exists");
  }
  if (user.role !== ADMIN_ROLE) {
    throw new UnauthorizedError(correlationId, `role ${user.role} cannot access api-clients`);
  }
  return { userId: user.id, role: user.role };
}

// ─── lastRotatedAt store ─────────────────────────────────────────────────────
// Module-local to avoid patching MockDb (Agent A also patches state.ts).

const lastRotatedAt = new Map<string, string>();

/** Test helper — clear the rotation-meta store. */
export function _resetApiClientRotationMetaForTests(): void {
  lastRotatedAt.clear();
}

function readLastRotatedAt(id: string): string | null {
  return lastRotatedAt.get(id) ?? null;
}

// ─── Projection ──────────────────────────────────────────────────────────────

function projectRow(db: MockDb, client: ApiClient): ApiClientRow {
  const lsp = db.lsps.get(client.lspId);
  return {
    ...client,
    lspName: lsp?.name ?? "(unknown LSP)",
    ipAllowlistCount: client.ipAllowList.length,
  };
}

// ─── Secret minting ──────────────────────────────────────────────────────────
//
// Cleartext secret is a 40-char hex string. We use `crypto.getRandomValues`
// in browsers / `crypto.randomUUID()` chained twice as a safe fallback. The
// mock never persists the cleartext — it is shown exactly once via
// `ApiSecretReveal` and the caller acknowledges it from there.

function mintClientSecret(): string {
  // 20 random bytes = 40 hex chars (matches the prod-shape from BRD §5).
  const bytes = new Uint8Array(20);
  if (typeof crypto !== "undefined" && typeof crypto.getRandomValues === "function") {
    crypto.getRandomValues(bytes);
  } else {
    for (let i = 0; i < bytes.length; i++) {
      bytes[i] = Math.floor(Math.random() * 256);
    }
  }
  return Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
}

function mintClientId(): string {
  // 8 hex chars of randomness — `ak_live_<8hex>` matches the BRD shape.
  const bytes = new Uint8Array(4);
  if (typeof crypto !== "undefined" && typeof crypto.getRandomValues === "function") {
    crypto.getRandomValues(bytes);
  } else {
    for (let i = 0; i < bytes.length; i++) {
      bytes[i] = Math.floor(Math.random() * 256);
    }
  }
  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
  return `ak_live_${hex}`;
}

function mintUuid(): string {
  return crypto.randomUUID();
}

// ─── List handler ────────────────────────────────────────────────────────────

const ListFiltersSchema = z.object({
  status: ApiClientStatus.optional(),
  lspId: z.string().uuid().optional(),
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
    throw new BadRequestError(correlationId, "invalid list filters", parsed.error.flatten());
  }
  return parsed.data;
}

function listHandler(req: MockRequest, db: MockDb, correlationId: string): ApiClientsListResponse {
  requireSystemAdmin(db, correlationId);
  const filters = parseListQuery(req, correlationId);

  let rows: ApiClient[] = Array.from(db.apiClients.values());

  if (filters.status) {
    rows = rows.filter((c) => c.status === filters.status);
  }
  if (filters.lspId) {
    rows = rows.filter((c) => c.lspId === filters.lspId);
  }
  if (filters.q) {
    const needle = filters.q.toLowerCase();
    rows = rows.filter(
      (c) => c.name.toLowerCase().includes(needle) || c.clientId.toLowerCase().includes(needle),
    );
  }

  // Stable sort: newest first by createdAt, then by id for determinism.
  rows.sort((a, b) => {
    if (a.createdAt === b.createdAt) return a.id < b.id ? -1 : 1;
    return a.createdAt < b.createdAt ? 1 : -1;
  });

  const total = rows.length;
  const page = filters.page ?? 0;
  const pageSize = filters.pageSize ?? 25;
  const slice = rows.slice(page * pageSize, page * pageSize + pageSize);

  return {
    items: slice.map((c) => projectRow(db, c)),
    total,
    page,
    pageSize,
  };
}

// ─── Body validation ─────────────────────────────────────────────────────────

const CreateBodySchema = z.object({
  name: z.string().trim().min(1).max(120),
  lspId: z.string().uuid(),
  ipAllowList: z.array(IpCidr),
  idempotencyKey: z.string().min(1).max(80),
});

const UpdateBodySchema = z.object({
  name: z.string().trim().min(1).max(120).optional(),
  status: ApiClientStatus.optional(),
  ipAllowList: z.array(IpCidr).optional(),
  idempotencyKey: z.string().min(1).max(80),
});

const RotateBodySchema = z.object({
  reason: z.string().trim().min(1).max(500),
  idempotencyKey: z.string().min(1).max(80),
});

// ─── Create handler ──────────────────────────────────────────────────────────

function createHandler(
  req: MockRequest,
  db: MockDb,
  correlationId: string,
): CreateApiClientResponse {
  requireSystemAdmin(db, correlationId);

  const parsed = CreateBodySchema.safeParse(req.body);
  if (!parsed.success) {
    throw new BadRequestError(correlationId, "invalid create body", parsed.error.flatten());
  }
  const body = parsed.data;

  const lsp = db.lsps.get(body.lspId);
  if (!lsp) {
    throw new BadRequestError(correlationId, `lsp ${body.lspId} not found`);
  }

  const nowIso = new Date().toISOString();
  const client: ApiClient = {
    id: mintUuid(),
    clientId: mintClientId(),
    name: body.name,
    lspId: body.lspId,
    status: "ACTIVE",
    createdAt: nowIso,
    lastUsedAt: null,
    ipAllowList: [...body.ipAllowList],
  };
  db.apiClients.set(client.id, client);
  // Never persist a rotation row for a fresh create — `lastRotatedAt` stays null.

  return {
    client: projectRow(db, client),
    clientSecret: mintClientSecret(),
  };
}

// ─── Update handler ──────────────────────────────────────────────────────────

function updateHandler(
  req: MockRequest,
  db: MockDb,
  correlationId: string,
): ApiClientMutationResponse {
  requireSystemAdmin(db, correlationId);

  const id = req.params?.["id"] ?? "";
  const existing = db.apiClients.get(id);
  if (!existing) {
    throw new NotFoundError(correlationId, `api client ${id} not found`);
  }

  const parsed = UpdateBodySchema.safeParse(req.body);
  if (!parsed.success) {
    throw new BadRequestError(correlationId, "invalid update body", parsed.error.flatten());
  }
  const body = parsed.data;

  const updated: ApiClient = {
    ...existing,
    ...(body.name !== undefined ? { name: body.name } : {}),
    ...(body.status !== undefined ? { status: body.status } : {}),
    ...(body.ipAllowList !== undefined ? { ipAllowList: [...body.ipAllowList] } : {}),
  };
  db.apiClients.set(id, updated);

  return { client: projectRow(db, updated) };
}

// ─── Rotate-secret handler ───────────────────────────────────────────────────

function rotateHandler(
  req: MockRequest,
  db: MockDb,
  correlationId: string,
): RotateApiClientSecretResponse {
  requireSystemAdmin(db, correlationId);

  const id = req.params?.["id"] ?? "";
  const existing = db.apiClients.get(id);
  if (!existing) {
    throw new NotFoundError(correlationId, `api client ${id} not found`);
  }

  const parsed = RotateBodySchema.safeParse(req.body);
  if (!parsed.success) {
    throw new BadRequestError(correlationId, "invalid rotate body", parsed.error.flatten());
  }
  // `reason` is consumed for audit but the mock does not write to an audit
  // stream — the audit explorer's product-audit stream is for product-config
  // changes, not credential rotations. The real backend persists this on a
  // separate secret-rotation log; the mock just stamps `lastRotatedAt`.
  void parsed.data.reason;

  const nowIso = new Date().toISOString();
  lastRotatedAt.set(id, nowIso);

  return {
    client: projectRow(db, existing),
    clientSecret: mintClientSecret(),
  };
}

// ─── Route registration ──────────────────────────────────────────────────────

let registered = false;
export function registerApiClientRoutes(): void {
  if (registered) return;
  registered = true;
  registerRoute("GET", "/api/v1/admin/api-clients", listHandler);
  registerRoute("POST", "/api/v1/admin/api-clients", createHandler, {
    mutating: true,
  });
  registerRoute("PATCH", "/api/v1/admin/api-clients/:id", updateHandler, {
    mutating: true,
  });
  registerRoute("POST", "/api/v1/admin/api-clients/:id/rotate-secret", rotateHandler, {
    mutating: true,
  });
}
registerApiClientRoutes();

// ─── Parsers ─────────────────────────────────────────────────────────────────

const ApiClientRowParser: z.ZodType<ApiClientRow> = z
  .object({
    id: z.string(),
    clientId: z.string(),
    name: z.string(),
    lspId: z.string(),
    status: ApiClientStatus,
    createdAt: z.string(),
    lastUsedAt: z.string().nullable(),
    ipAllowList: z.array(z.string()),
    lspName: z.string(),
    ipAllowlistCount: z.number().int().nonnegative(),
  })
  .passthrough() as unknown as z.ZodType<ApiClientRow>;

const ListResponseSchema: z.ZodType<ApiClientsListResponse> = z.object({
  items: z.array(ApiClientRowParser),
  total: z.number().int().nonnegative(),
  page: z.number().int().nonnegative(),
  pageSize: z.number().int().positive(),
}) as unknown as z.ZodType<ApiClientsListResponse>;

const CreateResponseSchema: z.ZodType<CreateApiClientResponse> = z.object({
  client: ApiClientRowParser,
  clientSecret: z.string().min(1),
}) as unknown as z.ZodType<CreateApiClientResponse>;

const UpdateResponseSchema: z.ZodType<ApiClientMutationResponse> = z.object({
  client: ApiClientRowParser,
}) as unknown as z.ZodType<ApiClientMutationResponse>;

const RotateResponseSchema: z.ZodType<RotateApiClientSecretResponse> = z.object({
  client: ApiClientRowParser,
  clientSecret: z.string().min(1),
}) as unknown as z.ZodType<RotateApiClientSecretResponse>;

// ─── Public wrappers ─────────────────────────────────────────────────────────

function queryFromFilters(filters: ApiClientsListFilters): Record<string, string> {
  const out: Record<string, string> = {};
  if (filters.status) out["status"] = filters.status;
  if (filters.lspId) out["lspId"] = filters.lspId;
  if (filters.q) out["q"] = filters.q;
  if (typeof filters.page === "number") out["page"] = String(filters.page);
  if (typeof filters.pageSize === "number") {
    out["pageSize"] = String(filters.pageSize);
  }
  return out;
}

export async function listApiClients(
  filters: ApiClientsListFilters = {},
): Promise<ApiClientsListResponse> {
  return dispatch(
    {
      method: "GET",
      path: "/api/v1/admin/api-clients",
      query: queryFromFilters(filters),
    },
    ListResponseSchema,
  );
}

export async function createApiClient(
  input: CreateApiClientInput,
): Promise<CreateApiClientResponse> {
  return dispatch(
    {
      method: "POST",
      path: "/api/v1/admin/api-clients",
      body: input,
      headers: { "Idempotency-Key": input.idempotencyKey },
    },
    CreateResponseSchema,
  );
}

export async function updateApiClient(
  id: string,
  input: UpdateApiClientInput,
): Promise<ApiClientMutationResponse> {
  return dispatch(
    {
      method: "PATCH",
      path: `/api/v1/admin/api-clients/${id}`,
      body: input,
      headers: { "Idempotency-Key": input.idempotencyKey },
    },
    UpdateResponseSchema,
  );
}

export async function rotateApiClientSecret(
  id: string,
  input: RotateApiClientSecretInput,
): Promise<RotateApiClientSecretResponse> {
  return dispatch(
    {
      method: "POST",
      path: `/api/v1/admin/api-clients/${id}/rotate-secret`,
      body: input,
      headers: { "Idempotency-Key": input.idempotencyKey },
    },
    RotateResponseSchema,
  );
}

/**
 * Read the (module-local) last-rotated-at timestamp for a client. Used by
 * the feature page to populate `ApiClientSecretMeta` without re-fetching.
 *
 * Returns null for never-rotated clients. Exported as a regular function
 * (not via dispatch) because the real backend will surface this on the row
 * once the schema is extended; the wrapper signals "treat me as wire data".
 */
export function getApiClientLastRotatedAt(id: string): string | null {
  return readLastRotatedAt(id);
}
