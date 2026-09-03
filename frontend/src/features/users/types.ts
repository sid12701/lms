/**
 * View-layer types for the `/users` admin surface (Phase 9 — Agent C).
 *
 * `User` is the canonical wire shape from `src/schemas/user.ts`. The row
 * projection adds the LSP name (resolved from `db.lsps`) so the table
 * doesn't have to second-fetch.
 *
 * Create and reset-password mutations may return a one-time temporary
 * password via `temporaryPassword`. The page surfaces this in a reveal-once
 * card (mirroring the `secrets/` primitive pattern). On any later refresh,
 * only metadata remains — the backend does NOT persist the
 * cleartext password (`mustChangePassword=true` is the only persisted hint).
 *
 * All mutations are SYSTEM_ADMIN-only and carry an idempotency key (BR-5).
 */
import { z } from "zod";
import { Role } from "@/schemas/role";
import { User, UserStatus } from "@/schemas/user";
import { ADMIN_LIST_FILTER_FIELDS } from "@/lib/admin-list-url-state";

export type { User };

/** Server-side filter shape for the list page. */
const UsersListFilters = z.object({
  role: Role.optional(),
  status: UserStatus.optional(),
  lspId: z.string().uuid().optional(),
  ...ADMIN_LIST_FILTER_FIELDS,
});
export type UsersListFilters = z.infer<typeof UsersListFilters>;

export interface UserRow extends User {
  /** Resolved from `db.lsps` — null for non-tenant-scoped roles. */
  lspName: string | null;
}

export type UserDialogState =
  | { kind: "none" }
  | { kind: "create" }
  | { kind: "edit"; user: UserRow }
  | { kind: "reset-password"; user: UserRow }
  | { kind: "revoke-sessions"; user: UserRow }
  | { kind: "disable"; user: UserRow };

export interface RevealedTemporaryPassword {
  username: string;
  password: string;
}

export interface UsersListResponse {
  items: UserRow[];
  total: number;
  page: number;
  pageSize: number;
}

/**
 * Create-user input. The server mints a temporary password and returns it
 * exactly once in `CreateUserResponse.temporaryPassword`. The form must
 * supply a non-null `lspId` whenever the role is tenant-scoped — the same
 * Zod refine as the `User` schema is enforced server-side.
 */
export interface CreateUserInput {
  username: string;
  email: string;
  role: z.infer<typeof Role>;
  lspId: string | null;
  idempotencyKey: string;
}

export interface CreateUserResponse {
  user: UserRow;
  /** Cleartext temp password — shown exactly once, never persisted. */
  temporaryPassword: string;
}

export interface UpdateUserInput {
  email?: string;
  role?: z.infer<typeof Role>;
  lspId?: string | null;
  status?: z.infer<typeof UserStatus>;
  idempotencyKey: string;
}

export interface UserMutationResponse {
  user: UserRow;
}

/** Reset-password: returns a fresh temp password + flips mustChangePassword. */
export interface ResetUserPasswordInput {
  idempotencyKey: string;
}

export interface ResetUserPasswordResponse {
  temporaryPassword: string;
}

export interface RevokeUserSessionsInput {
  reason?: string;
  idempotencyKey: string;
}

export interface RevokeUserSessionsResponse {
  status: string;
  previousTokenVersion: number;
  newTokenVersion: number;
  refreshTokensRevoked: number;
}
