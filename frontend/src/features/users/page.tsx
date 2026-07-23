/**
 * Phase 9 — `/users` admin surface (SYSTEM_ADMIN-only).
 *
 * Composes:
 *   - `UsersFilterBar` (URL-bound filters via `useSearchParams`)
 *   - `UsersTable` (server-paged TanStack table with row actions)
 *   - `UserCreateDialog` (POST /api/v1/admin/users — returns temp password)
 *   - `UserEditDialog` (PATCH /api/v1/admin/users/:id)
 *   - `ResetPasswordDialog` (POST /api/v1/admin/users/:id/reset-password)
 *   - `TempPasswordRevealCard` (page-level reveal-once card)
 *
 * Role enforcement is server-side (backend 401s non-admin) AND
 * client-side (router-level RequireRole). When a non-admin somehow lands
 * here we surface a friendly EmptyState instead of an ErrorState.
 *
 * Density default = comfortable per D7.
 */
import { useMemo, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { Users } from "lucide-react";
import { AdminEntityListPage } from "@/components/app/layout/AdminEntityListPage";
import { extractAdminErrorMessage } from "@/lib/admin-page-utils";
import { newIdempotencyKey } from "@/lib/idempotency";
import { Role } from "@/schemas/role";
import { UserStatus } from "@/schemas/user";
import { UsersFilterBar } from "./components/UsersFilterBar";
import { UsersTable } from "./components/UsersTable";
import { UserCreateDialog } from "./components/UserCreateDialog";
import { UserEditDialog } from "./components/UserEditDialog";
import { ResetPasswordDialog } from "./components/ResetPasswordDialog";
import { TempPasswordRevealCard } from "./components/TempPasswordRevealCard";
import { useUsers } from "./hooks/useUsers";
import { useCreateUser } from "./hooks/useCreateUser";
import { useUpdateUser } from "./hooks/useUpdateUser";
import { useResetUserPassword } from "./hooks/useResetUserPassword";
import { useRevokeUserSessions } from "./hooks/useRevokeUserSessions";
import { RevokeSessionsDialog } from "./components/RevokeSessionsDialog";
import { useLsps } from "@/features/lsps/hooks/useLsps";
import type { UserRow, UsersListFilters } from "./types";
import type { Role as RoleT } from "@/schemas/role";
import type { UserStatus as UserStatusT } from "@/schemas/user";
import {
  readAdminListParams,
  readAllowedParam,
  readUuidParam,
  writeAdminListParams,
} from "@/lib/admin-list-url-state";

const VALID_ROLES: readonly RoleT[] = Role.options;
const VALID_STATUSES: readonly UserStatusT[] = UserStatus.options;
function parseFiltersFromUrl(params: URLSearchParams): UsersListFilters {
  return {
    ...readAdminListParams(params),
    role: readAllowedParam(params, "role", VALID_ROLES),
    status: readAllowedParam(params, "status", VALID_STATUSES),
    lspId: readUuidParam(params, "lspId"),
  };
}

function filtersToParams(filters: UsersListFilters): URLSearchParams {
  const params = writeAdminListParams(filters);
  if (filters.role) params.set("role", filters.role);
  if (filters.status) params.set("status", filters.status);
  if (filters.lspId) params.set("lspId", filters.lspId);
  return params;
}

type UserDialogState =
  | { kind: "none" }
  | { kind: "create" }
  | { kind: "edit"; user: UserRow }
  | { kind: "reset-password"; user: UserRow }
  | { kind: "revoke-sessions"; user: UserRow };

export function UsersPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const filters = useMemo(() => parseFiltersFromUrl(searchParams), [searchParams]);

  const setFilters = (next: UsersListFilters) => {
    setSearchParams(filtersToParams(next), { replace: false });
  };

  const [dialog, setDialog] = useState<UserDialogState>({ kind: "none" });
  const [revealedTempPassword, setRevealedTempPassword] = useState<{
    username: string;
    password: string;
  } | null>(null);

  const list = useUsers(filters);
  const create = useCreateUser();
  const update = useUpdateUser();
  const reset = useResetUserPassword();
  const revokeSessions = useRevokeUserSessions();

  // LSP options for the filter bar + create/edit dialogs. Pull a wide page —
  // the admin LSP list is short enough in practice (BRD §1) to fit without
  // a typeahead.
  const lspsQuery = useLsps({ pageSize: 100 });
  const lspOptions = useMemo(
    () => (lspsQuery.data?.items ?? []).map((l) => ({ id: l.id, name: l.name })),
    [lspsQuery.data],
  );

  const clearRevealed = () => setRevealedTempPassword(null);
  const createOpen = dialog.kind === "create";
  const editTarget = dialog.kind === "edit" ? dialog.user : null;
  const resetTarget = dialog.kind === "reset-password" ? dialog.user : null;
  const revokeTarget = dialog.kind === "revoke-sessions" ? dialog.user : null;

  // ── Create dialog handlers ──────────────────────────────────────────────────
  const handleCreateOpenChange = (open: boolean) => {
    if (!open) {
      if (create.isPending) return;
      setDialog({ kind: "none" });
      create.reset();
      clearRevealed();
    } else {
      setDialog({ kind: "create" });
    }
  };
  const handleCreateConfirm = async ({
    username,
    email,
    role,
    lspId,
    idempotencyKey,
  }: {
    username: string;
    email: string;
    role: RoleT;
    lspId: string | null;
    idempotencyKey: string;
  }) => {
    try {
      const result = await create.mutateAsync({
        username,
        email,
        role,
        lspId,
        idempotencyKey,
      });
      setRevealedTempPassword({
        username: result.user.username,
        password: result.temporaryPassword,
      });
    } catch {
      // Surfaced via create.error.
    }
  };
  const handleCreateAcknowledge = () => {
    setDialog({ kind: "none" });
    create.reset();
    clearRevealed();
  };

  // ── Edit dialog handlers ────────────────────────────────────────────────────
  const handleEditOpenChange = (open: boolean) => {
    if (!open) {
      if (update.isPending) return;
      setDialog({ kind: "none" });
      update.reset();
    }
  };
  const handleEditConfirm = async ({
    email,
    role,
    lspId,
    status,
    idempotencyKey,
  }: {
    email: string;
    role: RoleT;
    lspId: string | null;
    status: UserStatusT;
    idempotencyKey: string;
  }) => {
    if (!editTarget) return;
    try {
      await update.mutateAsync({
        id: editTarget.id,
        email,
        role,
        lspId,
        status,
        idempotencyKey,
      });
      setDialog({ kind: "none" });
      update.reset();
    } catch {
      // Surfaced via update.error.
    }
  };

  // ── Reset-password dialog handlers ──────────────────────────────────────────
  const handleResetOpenChange = (open: boolean) => {
    if (!open) {
      if (reset.isPending) return;
      setDialog({ kind: "none" });
      reset.reset();
      clearRevealed();
    }
  };
  const handleResetConfirm = async ({ idempotencyKey }: { idempotencyKey: string }) => {
    if (!resetTarget) return;
    const username = resetTarget.username;
    try {
      const result = await reset.mutateAsync({
        id: resetTarget.id,
        idempotencyKey,
      });
      setRevealedTempPassword({
        username,
        password: result.temporaryPassword,
      });
    } catch {
      // Surfaced via reset.error.
    }
  };
  const handleResetAcknowledge = () => {
    setDialog({ kind: "none" });
    reset.reset();
    clearRevealed();
  };

  const handleRevokeOpenChange = (open: boolean) => {
    if (!open) {
      if (revokeSessions.isPending) return;
      setDialog({ kind: "none" });
      revokeSessions.reset();
    }
  };
  const handleRevokeConfirm = async ({
    reason,
    idempotencyKey,
  }: {
    reason?: string;
    idempotencyKey: string;
  }) => {
    if (!revokeTarget) return;
    try {
      await revokeSessions.mutateAsync({
        id: revokeTarget.id,
        username: revokeTarget.username,
        reason,
        idempotencyKey,
      });
      setDialog({ kind: "none" });
      revokeSessions.reset();
    } catch {
      // Surfaced via revokeSessions.error.
    }
  };

  // ── Toggle status (Enable / Disable) ────────────────────────────────────────
  const handleToggleStatus = (row: UserRow) => {
    const nextStatus: UserStatusT = row.status === "DISABLED" ? "ACTIVE" : "DISABLED";
    update.mutate({
      id: row.id,
      status: nextStatus,
      idempotencyKey: newIdempotencyKey(),
    });
  };

  const isCatalogueEmpty =
    !list.isPending &&
    (list.data?.total ?? 0) === 0 &&
    !filters.q &&
    !filters.role &&
    !filters.status &&
    !filters.lspId;

  return (
    <AdminEntityListPage
      testId="users-page"
      title="Users"
      description="Internal and LSP users — manage roles and tenant scope."
      primaryAction={{
        label: "New user",
        dataSlot: "users-new-button",
        onClick: () => setDialog({ kind: "create" }),
      }}
      banner={
        revealedTempPassword !== null ? (
          <TempPasswordRevealCard
            password={revealedTempPassword.password}
            username={revealedTempPassword.username}
            onAcknowledge={clearRevealed}
          />
        ) : null
      }
      list={list}
      unauthorized={{
        title: "No access to users",
        description: "The users admin surface is restricted to system administrators.",
      }}
      fetchError={{
        title: "Couldn't load users",
        description: "The user list couldn't be fetched. Try again in a moment.",
      }}
      isCatalogueEmpty={isCatalogueEmpty}
      catalogueEmpty={{
        icon: Users,
        title: "No users yet",
        description: "Invite the first user to get started.",
      }}
      filterBar={<UsersFilterBar filters={filters} onChange={setFilters} lspOptions={lspOptions} />}
      table={
        <UsersTable
          data={list.data}
          isLoading={list.isPending}
          filters={filters}
          onFiltersChange={setFilters}
          onEdit={(user) => setDialog({ kind: "edit", user })}
          onResetPassword={(user) => setDialog({ kind: "reset-password", user })}
          onRevokeSessions={(user) => setDialog({ kind: "revoke-sessions", user })}
          onToggleStatus={handleToggleStatus}
        />
      }
      dialogs={
        <>
          <UserCreateDialog
            open={createOpen}
            onOpenChange={handleCreateOpenChange}
            lspOptions={lspOptions}
            onConfirm={handleCreateConfirm}
            temporaryPassword={revealedTempPassword?.password ?? null}
            createdUsername={revealedTempPassword?.username ?? null}
            onAcknowledgePassword={handleCreateAcknowledge}
            loading={create.isPending}
            errorMessage={create.isError ? extractAdminErrorMessage(create.error) : null}
          />

          <UserEditDialog
            open={editTarget !== null}
            onOpenChange={handleEditOpenChange}
            user={editTarget}
            lspOptions={lspOptions}
            onConfirm={handleEditConfirm}
            loading={update.isPending}
            errorMessage={update.isError ? extractAdminErrorMessage(update.error) : null}
          />

          <ResetPasswordDialog
            open={resetTarget !== null}
            onOpenChange={handleResetOpenChange}
            username={resetTarget?.username ?? ""}
            onConfirm={handleResetConfirm}
            temporaryPassword={
              resetTarget !== null ? (revealedTempPassword?.password ?? null) : null
            }
            onAcknowledgePassword={handleResetAcknowledge}
            loading={reset.isPending}
            errorMessage={reset.isError ? extractAdminErrorMessage(reset.error) : null}
          />

          <RevokeSessionsDialog
            open={revokeTarget !== null}
            onOpenChange={handleRevokeOpenChange}
            username={revokeTarget?.username ?? ""}
            onConfirm={handleRevokeConfirm}
            loading={revokeSessions.isPending}
            errorMessage={
              revokeSessions.isError ? extractAdminErrorMessage(revokeSessions.error) : null
            }
          />
        </>
      }
    />
  );
}

export default UsersPage;
export const Component = UsersPage;
