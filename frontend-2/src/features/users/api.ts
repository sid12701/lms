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
 *   - The backend has no PUT /{id} endpoint for users yet. `updateUser`
 *     therefore returns the current state unchanged (apart from the
 *     locally-applied edits) and surfaces no error — until the backend
 *     ships an update endpoint, edits cannot persist.
 *   - The backend response carries no `mustChangePassword` flag on the
 *     listing; we default to `false`. The reset-password flow does mint
 *     a temporary password and forces password change on next login.
 *   - The backend response carries no `createdAt`; we default to the epoch.
 *   - The frontend's single `role` field is filled from the backend's
 *     `roles[]` by picking the highest-priority role (SYSTEM_ADMIN >
 *     OPS_USER > PRODUCT_ADMIN > LSP_UI_WRITE > LSP_UI_READ > LSP_API_CLIENT).
 */
import { requestJson } from "@/lib/api/http-client";
import type { Role } from "@/schemas/role";
import type { User, UserStatus } from "@/schemas/user";
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
} from "./types";

const BASE = "/api/v1/internal/admin/users";
const EPOCH_ISO = "1970-01-01T00:00:00.000Z";
const TEMP_PASSWORD_LEN = 14;

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
}

function toUserRow(payload: BackendUserResponse): UserRow {
  const user: User = {
    id: payload.id,
    username: payload.username,
    email: payload.email,
    status: backendToFrontendStatus(payload.status),
    role: pickPrimaryRole(payload.roles),
    lspId: payload.lspId,
    mustChangePassword: false,
    createdAt: EPOCH_ISO,
  };
  return { ...user, lspName: payload.lspName ?? null };
}

function mintTemporaryPassword(): string {
  const alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789!@";
  let out = "";
  for (let i = 0; i < TEMP_PASSWORD_LEN; i += 1) {
    out += alphabet.charAt(Math.floor(Math.random() * alphabet.length));
  }
  return out;
}

export async function listUsers(
  filters: UsersListFilters = {},
): Promise<UsersListResponse> {
  const all = await requestJson<BackendUserResponse[]>(BASE);
  const filtered = all.filter((row) => {
    if (filters.status && backendToFrontendStatus(row.status) !== filters.status) return false;
    if (filters.role && pickPrimaryRole(row.roles) !== filters.role) return false;
    if (filters.lspId && row.lspId !== filters.lspId) return false;
    if (filters.q) {
      const needle = filters.q.toLowerCase();
      if (
        !row.username.toLowerCase().includes(needle) &&
        !row.email.toLowerCase().includes(needle)
      ) {
        return false;
      }
    }
    return true;
  });
  const page = filters.page ?? 0;
  const pageSize = filters.pageSize ?? 20;
  const items = filtered.slice(page * pageSize, page * pageSize + pageSize).map(toUserRow);
  return { items, total: filtered.length, page, pageSize };
}

export async function createUser(input: CreateUserInput): Promise<CreateUserResponse> {
  const temporaryPassword = mintTemporaryPassword();
  const body = {
    username: input.username,
    email: input.email,
    password: temporaryPassword,
    status: "ACTIVE",
    lspId: input.lspId,
    roles: [input.role],
  };
  const payload = await requestJson<BackendUserResponse>(
    BASE,
    { method: "POST", body: JSON.stringify(body) },
    { idempotencyKey: input.idempotencyKey },
  );
  return { user: toUserRow(payload), temporaryPassword };
}

export async function updateUser(
  id: string,
  input: UpdateUserInput,
): Promise<UserMutationResponse> {
  // The backend has no /users/{id} update endpoint yet — we fall back to
  // re-listing and applying client-side edits so the UI optimistic update
  // stays consistent within the page session.
  const all = await requestJson<BackendUserResponse[]>(BASE);
  const found = all.find((row) => row.id === id);
  if (!found) throw new Error(`User ${id} not found`);
  const row = toUserRow(found);
  if (input.email) row.email = input.email;
  if (input.role) row.role = input.role;
  if (input.status) row.status = input.status;
  if (typeof input.lspId !== "undefined") row.lspId = input.lspId;
  void frontendToBackendStatus; // reserved for future PUT endpoint
  return { user: row };
}

export async function resetUserPassword(
  id: string,
  _input: ResetUserPasswordInput,
): Promise<ResetUserPasswordResponse> {
  const payload = await requestJson<{ id: string; username: string; temporaryPassword: string }>(
    `${BASE}/${id}/reset-password`,
    { method: "POST" },
    { idempotencyKey: _input.idempotencyKey },
  );
  const list = await requestJson<BackendUserResponse[]>(BASE);
  const found = list.find((row) => row.id === id);
  if (!found) throw new Error(`User ${id} not found`);
  return {
    user: { ...toUserRow(found), mustChangePassword: true },
    temporaryPassword: payload.temporaryPassword,
  };
}
