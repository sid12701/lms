/**
 * Users admin surface, wired to the live backend.
 *
 * Backend contract: `UserAdminController` under
 * `/api/v1/internal/admin/users` (SYSTEM_ADMIN only).
 *
 * Backend gaps vs. the frontend projection (documented in
 * docs/INTEGRATION-STATUS.md):
 *   - Backend status enum is ACTIVE / INACTIVE; frontend uses ACTIVE /
 *     DISABLED. We translate INACTIVE <-> DISABLED on both directions.
 *   - `PUT /{id}` persists email, role(s), status, and lspId. Role changes
 *     invalidate existing sessions server-side via `token_version`.
 *   - `passwordChangeRequired` on the backend maps to `mustChangePassword`.
 *   - The frontend's single `role` field is filled from the backend's
 *     `roles[]` by picking the highest-priority role (SYSTEM_ADMIN >
 *     OPS_USER > PRODUCT_ADMIN > LSP_UI_WRITE > LSP_UI_READ > LSP_API_CLIENT).
 */
import { buildQueryPath, requestJson, requestJsonWithHeaders } from "@/lib/api/http-client";
import { readPaginationHeaders } from "@/lib/api/pagination-headers";
import type { Role } from "@/schemas/role";
import type { User, UserStatus } from "@/schemas/user";
import type {
  CreateUserInput,
  CreateUserResponse,
  ResetUserPasswordInput,
  ResetUserPasswordResponse,
  RevokeUserSessionsInput,
  RevokeUserSessionsResponse,
  UpdateUserInput,
  UserMutationResponse,
  UserRow,
  UsersListFilters,
  UsersListResponse,
} from "./types";

const BASE = "/api/v1/internal/admin/users";

const ROLE_PRIORITY: Role[] = [
  "SYSTEM_ADMIN",
  "OPS_USER",
  "PRODUCT_ADMIN",
  "LSP_UI_WRITE",
  "LSP_UI_READ",
  "LSP_API_CLIENT",
];

function pickPrimaryRole(roles: readonly string[]): Role {
  for (const candidate of ROLE_PRIORITY) {
    if (roles.includes(candidate)) return candidate;
  }
  return "OPS_USER";
}

function frontendToBackendStatus(value: UserStatus | undefined): string | undefined {
  if (!value) return undefined;
  return value === "DISABLED" ? "INACTIVE" : "ACTIVE";
}

function backendToFrontendStatus(value: string): UserStatus {
  return value === "INACTIVE" ? "DISABLED" : "ACTIVE";
}

interface BackendUserResponse {
  id: string;
  username: string;
  email: string;
  status: string;
  lspId: string | null;
  lspName: string | null;
  roles: string[];
  lockedAt?: string | null;
  lockReason?: string | null;
  passwordChangeRequired?: boolean;
  createdAt: string;
}

interface BackendCreateUserResponse {
  id: string;
  username: string;
  email: string;
  status: string;
  lspId: string | null;
  lspName: string | null;
  roles: string[];
  lockedAt?: string | null;
  lockReason?: string | null;
  passwordChangeRequired?: boolean;
  createdAt: string;
  /**
   * One-time temporary password, present only in the first authorized create
   * response. Replays under the same idempotency key return null without
   * rotating the credential — recover via a deliberate reset-password command.
   */
  temporaryPassword: string | null;
}

function toUserRow(payload: BackendUserResponse | BackendCreateUserResponse): UserRow {
  const user: User = {
    id: payload.id,
    username: payload.username,
    email: payload.email,
    status: backendToFrontendStatus(payload.status),
    role: pickPrimaryRole(payload.roles),
    lspId: payload.lspId,
    mustChangePassword: payload.passwordChangeRequired ?? false,
    createdAt: payload.createdAt,
    lockedAt: payload.lockedAt ?? null,
    lockReason: payload.lockReason ?? null,
  };
  return { ...user, lspName: payload.lspName ?? null };
}

export async function createUser(input: CreateUserInput): Promise<CreateUserResponse> {
  // The server mints the temporary password (SecureRandom) and reveals it
  // once in the create response. The browser never mints or sends a password —
  // the request below intentionally carries no `password` field (generated mode).
  const body = {
    username: input.username,
    email: input.email,
    status: "ACTIVE",
    lspId: input.lspId,
    roles: [input.role],
  };
  const payload = await requestJson<BackendCreateUserResponse>(
    BASE,
    { method: "POST", body: JSON.stringify(body) },
    { idempotencyKey: input.idempotencyKey },
  );
  return {
    user: { ...toUserRow(payload), mustChangePassword: payload.passwordChangeRequired ?? true },
    temporaryPassword: payload.temporaryPassword,
  };
}

/**
 * M20 — the directory is server-paginated and server-filtered. Every filter
 * (status, role membership, LSP, text) travels to the backend, which applies
 * it to the full dataset before paginating; the total comes from the
 * pagination headers. Filtering/paginating only a fetched slice locally hid
 * matching users on other pages and understated totals, so the local path is
 * gone.
 *
 * `role` filters by granted-role membership server-side — a multi-role user
 * appears under every role they hold, not only under the collapsed primary
 * role this feature previously filtered by (consistent with M19).
 */
export async function listUsers(
  filters: UsersListFilters = {},
  signal?: AbortSignal,
): Promise<UsersListResponse> {
  const pageSize = filters.pageSize ?? 25;
  const page = filters.page ?? 0;
  const path = buildQueryPath(BASE, {
    status: frontendToBackendStatus(filters.status),
    role: filters.role,
    lspId: filters.lspId,
    q: filters.q,
    offset: page * pageSize,
    limit: pageSize,
    paginationDetails: "ON",
  });
  const { data, headers } = await requestJsonWithHeaders<BackendUserResponse[]>(path, {
    signal,
  });
  const pagination = readPaginationHeaders(headers);
  return {
    items: data.map(toUserRow),
    total: pagination.totalCount ?? data.length,
    page,
    pageSize,
  };
}

export async function updateUser(
  id: string,
  input: UpdateUserInput,
): Promise<UserMutationResponse> {
  const body: Record<string, unknown> = {};
  if (input.email) body.email = input.email;
  if (input.role) body.roles = [input.role];
  if (input.status) body.status = frontendToBackendStatus(input.status);
  if (typeof input.lspId !== "undefined") body.lspId = input.lspId;

  const payload = await requestJson<BackendUserResponse>(
    `${BASE}/${id}`,
    { method: "PUT", body: JSON.stringify(body) },
    { idempotencyKey: input.idempotencyKey },
  );
  return { user: toUserRow(payload) };
}

export async function revokeUserSessions(
  id: string,
  input: RevokeUserSessionsInput,
): Promise<RevokeUserSessionsResponse> {
  const body: Record<string, string> = {};
  if (input.reason?.trim()) {
    body.reason = input.reason.trim();
  }
  return requestJson<RevokeUserSessionsResponse>(
    `${BASE}/${id}/revoke-sessions`,
    { method: "POST", body: JSON.stringify(body) },
    { idempotencyKey: input.idempotencyKey },
  );
}

export async function resetUserPassword(
  id: string,
  _input: ResetUserPasswordInput,
): Promise<ResetUserPasswordResponse> {
  const payload = await requestJson<{
    id: string;
    username: string;
    temporaryPassword: string | null;
  }>(`${BASE}/${id}/reset-password`, { method: "POST" }, { idempotencyKey: _input.idempotencyKey });
  return {
    temporaryPassword: payload.temporaryPassword,
  };
}
