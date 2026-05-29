/**
 * Users-admin handler (Phase 9 — Agent C).
 *
 * SYSTEM_ADMIN-only surface for managing console + LSP users. Backs the
 * `/users` page.
 *
 * Endpoints (all under `/api/v1/admin/`):
 *   GET   /api/v1/admin/users                            → list + filters
 *   POST  /api/v1/admin/users                            → create (returns temp pw)
 *   PATCH /api/v1/admin/users/:id                        → update (status / role / email / lspId)
 *   POST  /api/v1/admin/users/:id/reset-password         → reveal-once temp pw
 *
 * BR coverage:
 *   - BR-5 idempotency  — every mutating route registered with `{ mutating: true }`.
 *
 * Security:
 *   - Every endpoint requires an authenticated SYSTEM_ADMIN session
 *     (UnauthorizedError otherwise).
 *   - Username uniqueness is enforced at create — 409 ConflictError on dupe.
 *   - The temporary password is rendered exactly once in the response — it is
 *     NEVER persisted on the user row. `mustChangePassword: true` is the only
 *     server-side hint that a reset is pending.
 *
 * Self-registers routes on import. `users.test.ts` imports this module
 * directly; the feature `api.ts` imports it transitively, which triggers
 * the same registration in the running app.
 */
import { z } from "zod";
import { Email, Uuid } from "@/schemas/common";
import { Role } from "@/schemas/role";
import { User, UserStatus } from "@/schemas/user";
import type { Role as RoleType, User as UserType } from "@/types";
import { dispatch, registerRoute, type MockRequest } from "../router";
import { BadRequestError, ConflictError, NotFoundError, UnauthorizedError } from "../errors";
import type { MockDb } from "../db/state";
import type {
  CreateUserInput,
  CreateUserResponse,
  ResetUserPasswordInput,
  ResetUserPasswordResponse,
  UpdateUserInput,
  UserMutationResponse,
  UserRow,
  UsersListFilters,
  UsersListResponse,
} from "@/features/users/types";

// ─── Session helpers ─────────────────────────────────────────────────────────

interface ActiveSession {
  userId: string;
  role: RoleType;
}

function requireSystemAdminSession(db: MockDb, correlationId: string): ActiveSession {
  if (!db.currentSession) {
    throw new UnauthorizedError(correlationId, "no active session");
  }
  const user = db.users.get(db.currentSession.userId);
  if (!user) {
    throw new UnauthorizedError(correlationId, "user no longer exists");
  }
  if (user.role !== "SYSTEM_ADMIN") {
    throw new UnauthorizedError(correlationId, `role ${user.role} cannot manage users`);
  }
  return { userId: user.id, role: user.role };
}

// ─── Tenant-scoped role helpers ──────────────────────────────────────────────

const TENANT_SCOPED_ROLES = new Set<RoleType>(["LSP_UI_READ", "LSP_UI_WRITE", "LSP_API_CLIENT"]);

function requireRoleLspConsistency(
  role: RoleType,
  lspId: string | null,
  correlationId: string,
): void {
  if (TENANT_SCOPED_ROLES.has(role) && (lspId === null || lspId === "")) {
    throw new BadRequestError(correlationId, `role ${role} requires a non-null lspId`, {
      path: ["lspId"],
    });
  }
  if (!TENANT_SCOPED_ROLES.has(role) && lspId !== null) {
    throw new BadRequestError(correlationId, `role ${role} cannot have an lspId`, {
      path: ["lspId"],
    });
  }
}

// ─── Projection ──────────────────────────────────────────────────────────────

function projectRow(db: MockDb, user: UserType): UserRow {
  const lsp = user.lspId ? db.lsps.get(user.lspId) : null;
  return { ...user, lspName: lsp?.name ?? null };
}

// ─── List handler ────────────────────────────────────────────────────────────

const ListFiltersSchema = z.object({
  role: Role.optional(),
  status: UserStatus.optional(),
  lspId: Uuid.optional(),
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

function listHandler(req: MockRequest, db: MockDb, correlationId: string): UsersListResponse {
  requireSystemAdminSession(db, correlationId);
  const filters = parseListQuery(req, correlationId);

  let rows: UserType[] = Array.from(db.users.values());

  if (filters.role) {
    rows = rows.filter((u) => u.role === filters.role);
  }
  if (filters.status) {
    rows = rows.filter((u) => u.status === filters.status);
  }
  if (filters.lspId) {
    rows = rows.filter((u) => u.lspId === filters.lspId);
  }
  if (filters.q) {
    const needle = filters.q.toLowerCase();
    rows = rows.filter(
      (u) => u.username.toLowerCase().includes(needle) || u.email.toLowerCase().includes(needle),
    );
  }

  // Newest first by createdAt; tie-break on username for stability.
  rows.sort((a, b) => {
    if (a.createdAt !== b.createdAt) return a.createdAt < b.createdAt ? 1 : -1;
    return a.username < b.username ? -1 : 1;
  });

  const total = rows.length;
  const page = filters.page ?? 0;
  const pageSize = filters.pageSize ?? 25;
  const slice = rows.slice(page * pageSize, page * pageSize + pageSize);

  return {
    items: slice.map((u) => projectRow(db, u)),
    total,
    page,
    pageSize,
  };
}

// ─── Temp password helper ────────────────────────────────────────────────────

/**
 * Mints a 12-char temp password with mixed case + a digit + a punctuation
 * char. The cryptographic source is `crypto.randomUUID()` (BR-5-aware).
 * Output is never persisted on the user row.
 */
function generateTemporaryPassword(): string {
  // 8 cryptographically-random hex chars from a fresh UUID.
  const rand = crypto.randomUUID().replace(/-/g, "").slice(0, 8);
  // Force the constraint set: at least one upper-case, lower-case, digit,
  // punctuation. The 8-char hex segment already mixes letters + digits.
  // Capitalise the first char to guarantee an upper-case.
  const head = rand.charAt(0).toUpperCase() + rand.slice(1);
  return `${head}Aa1!`;
}

// ─── Create handler ──────────────────────────────────────────────────────────

const CreateBodySchema = z.object({
  username: z.string().trim().min(3).max(64),
  email: Email,
  role: Role,
  lspId: Uuid.nullable(),
  idempotencyKey: z.string().min(1).max(80),
});

function createHandler(req: MockRequest, db: MockDb, correlationId: string): CreateUserResponse {
  requireSystemAdminSession(db, correlationId);

  const parsed = CreateBodySchema.safeParse(req.body);
  if (!parsed.success) {
    throw new BadRequestError(correlationId, "invalid create body", parsed.error.flatten());
  }

  const { username, email, role, lspId } = parsed.data;
  requireRoleLspConsistency(role, lspId, correlationId);

  // Username uniqueness.
  const usernameTaken = Array.from(db.users.values()).some(
    (u) => u.username.toLowerCase() === username.toLowerCase(),
  );
  if (usernameTaken) {
    throw new ConflictError(correlationId, `username "${username}" is already in use`);
  }

  // If lspId provided, it must point at an existing LSP.
  if (lspId !== null && !db.lsps.has(lspId)) {
    throw new BadRequestError(correlationId, `unknown lspId ${lspId}`);
  }

  const id = crypto.randomUUID();
  const nowIso = new Date().toISOString();

  const candidate: UserType = {
    id,
    username,
    email,
    status: "ACTIVE",
    role,
    lspId,
    mustChangePassword: true,
    createdAt: nowIso,
  };

  // Final guard via the canonical `User` schema's superRefine.
  const refined = User.safeParse(candidate);
  if (!refined.success) {
    throw new BadRequestError(
      correlationId,
      "user payload failed schema validation",
      refined.error.flatten(),
    );
  }

  db.users.set(id, refined.data);

  const temporaryPassword = generateTemporaryPassword();
  return {
    user: projectRow(db, refined.data),
    temporaryPassword,
  };
}

// ─── Update handler ──────────────────────────────────────────────────────────

const UpdateBodySchema = z
  .object({
    email: Email.optional(),
    role: Role.optional(),
    lspId: Uuid.nullable().optional(),
    status: UserStatus.optional(),
    idempotencyKey: z.string().min(1).max(80),
  })
  .refine(
    (b) =>
      b.email !== undefined ||
      b.role !== undefined ||
      b.lspId !== undefined ||
      b.status !== undefined,
    { message: "no fields to update" },
  );

function updateHandler(req: MockRequest, db: MockDb, correlationId: string): UserMutationResponse {
  requireSystemAdminSession(db, correlationId);

  const id = req.params?.["id"] ?? "";
  const existing = db.users.get(id);
  if (!existing) {
    throw new NotFoundError(correlationId, `user ${id} not found`);
  }

  const parsed = UpdateBodySchema.safeParse(req.body);
  if (!parsed.success) {
    throw new BadRequestError(correlationId, "invalid update body", parsed.error.flatten());
  }
  const body = parsed.data;

  const nextRole = body.role ?? existing.role;
  const nextLspId = body.lspId !== undefined ? body.lspId : existing.lspId;

  requireRoleLspConsistency(nextRole, nextLspId, correlationId);

  if (nextLspId !== null && !db.lsps.has(nextLspId)) {
    throw new BadRequestError(correlationId, `unknown lspId ${nextLspId}`);
  }

  const candidate: UserType = {
    ...existing,
    email: body.email ?? existing.email,
    role: nextRole,
    lspId: nextLspId,
    status: body.status ?? existing.status,
  };

  const refined = User.safeParse(candidate);
  if (!refined.success) {
    throw new BadRequestError(
      correlationId,
      "user payload failed schema validation",
      refined.error.flatten(),
    );
  }

  db.users.set(id, refined.data);
  return { user: projectRow(db, refined.data) };
}

// ─── Reset password handler ──────────────────────────────────────────────────

const ResetBodySchema = z.object({
  idempotencyKey: z.string().min(1).max(80),
});

function resetPasswordHandler(
  req: MockRequest,
  db: MockDb,
  correlationId: string,
): ResetUserPasswordResponse {
  requireSystemAdminSession(db, correlationId);

  const id = req.params?.["id"] ?? "";
  const existing = db.users.get(id);
  if (!existing) {
    throw new NotFoundError(correlationId, `user ${id} not found`);
  }

  const parsed = ResetBodySchema.safeParse(req.body);
  if (!parsed.success) {
    throw new BadRequestError(correlationId, "invalid reset-password body", parsed.error.flatten());
  }

  const updated: UserType = { ...existing, mustChangePassword: true };
  db.users.set(id, updated);

  return {
    user: projectRow(db, updated),
    temporaryPassword: generateTemporaryPassword(),
  };
}

// ─── Route registration ──────────────────────────────────────────────────────

let registered = false;
export function registerUserAdminRoutes(): void {
  if (registered) return;
  registered = true;
  registerRoute("GET", "/api/v1/admin/users", listHandler);
  registerRoute("POST", "/api/v1/admin/users", createHandler, {
    mutating: true,
  });
  registerRoute("PATCH", "/api/v1/admin/users/:id", updateHandler, {
    mutating: true,
  });
  registerRoute("POST", "/api/v1/admin/users/:id/reset-password", resetPasswordHandler, {
    mutating: true,
  });
}
registerUserAdminRoutes();

// ─── Dispatch parsers ────────────────────────────────────────────────────────

const UserRowParser: z.ZodType<UserRow> = z
  .object({
    id: z.string(),
    username: z.string(),
    email: z.string(),
    status: UserStatus,
    role: Role,
    lspId: z.string().nullable(),
    mustChangePassword: z.boolean(),
    createdAt: z.string(),
    lspName: z.string().nullable(),
  })
  .passthrough() as unknown as z.ZodType<UserRow>;

const ListResponseSchema: z.ZodType<UsersListResponse> = z.object({
  items: z.array(UserRowParser),
  total: z.number().int().nonnegative(),
  page: z.number().int().nonnegative(),
  pageSize: z.number().int().positive(),
}) as unknown as z.ZodType<UsersListResponse>;

const MutationResponseSchema: z.ZodType<UserMutationResponse> = z.object({
  user: UserRowParser,
}) as unknown as z.ZodType<UserMutationResponse>;

const CreateResponseSchema: z.ZodType<CreateUserResponse> = z.object({
  user: UserRowParser,
  temporaryPassword: z.string().min(8),
}) as unknown as z.ZodType<CreateUserResponse>;

const ResetResponseSchema: z.ZodType<ResetUserPasswordResponse> = z.object({
  user: UserRowParser,
  temporaryPassword: z.string().min(8),
}) as unknown as z.ZodType<ResetUserPasswordResponse>;

// ─── Public wrappers ─────────────────────────────────────────────────────────

function queryFromFilters(filters: UsersListFilters): Record<string, string> {
  const out: Record<string, string> = {};
  if (filters.role) out["role"] = filters.role;
  if (filters.status) out["status"] = filters.status;
  if (filters.lspId) out["lspId"] = filters.lspId;
  if (filters.q) out["q"] = filters.q;
  if (typeof filters.page === "number") out["page"] = String(filters.page);
  if (typeof filters.pageSize === "number") {
    out["pageSize"] = String(filters.pageSize);
  }
  return out;
}

export async function listUsers(filters: UsersListFilters = {}): Promise<UsersListResponse> {
  return dispatch(
    {
      method: "GET",
      path: "/api/v1/admin/users",
      query: queryFromFilters(filters),
    },
    ListResponseSchema,
  );
}

export async function createUser(input: CreateUserInput): Promise<CreateUserResponse> {
  return dispatch(
    {
      method: "POST",
      path: "/api/v1/admin/users",
      body: input,
      headers: { "Idempotency-Key": input.idempotencyKey },
    },
    CreateResponseSchema,
  );
}

export async function updateUser(
  id: string,
  input: UpdateUserInput,
): Promise<UserMutationResponse> {
  return dispatch(
    {
      method: "PATCH",
      path: `/api/v1/admin/users/${id}`,
      body: input,
      headers: { "Idempotency-Key": input.idempotencyKey },
    },
    MutationResponseSchema,
  );
}

export async function resetUserPassword(
  id: string,
  input: ResetUserPasswordInput,
): Promise<ResetUserPasswordResponse> {
  return dispatch(
    {
      method: "POST",
      path: `/api/v1/admin/users/${id}/reset-password`,
      body: input,
      headers: { "Idempotency-Key": input.idempotencyKey },
    },
    ResetResponseSchema,
  );
}
