/**
 * Users feature API wrapper (Phase 9 — Agent C).
 *
 * Re-exports the typed mock handlers so the hooks layer never reaches into
 * `@/mocks/api/*` directly. Importing this module also triggers the
 * side-effect route registration in `@/mocks/api/users` (the Phase 8 /
 * Phase 9 self-registration pattern — `users.ts` is NOT imported by
 * `@/mocks/api/index.ts`).
 */
import {
  createUser as createUserImpl,
  listUsers as listUsersImpl,
  resetUserPassword as resetUserPasswordImpl,
  updateUser as updateUserImpl,
} from "@/mocks/api/users";
import type {
  CreateUserInput,
  CreateUserResponse,
  ResetUserPasswordInput,
  ResetUserPasswordResponse,
  UpdateUserInput,
  UserMutationResponse,
  UsersListFilters,
  UsersListResponse,
} from "./types";

export async function listUsers(
  filters: UsersListFilters = {},
): Promise<UsersListResponse> {
  return listUsersImpl(filters);
}

export async function createUser(
  input: CreateUserInput,
): Promise<CreateUserResponse> {
  return createUserImpl(input);
}

export async function updateUser(
  id: string,
  input: UpdateUserInput,
): Promise<UserMutationResponse> {
  return updateUserImpl(id, input);
}

export async function resetUserPassword(
  id: string,
  input: ResetUserPasswordInput,
): Promise<ResetUserPasswordResponse> {
  return resetUserPasswordImpl(id, input);
}
