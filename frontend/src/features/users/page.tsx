/**
 * Phase 9 — `/users` admin surface (SYSTEM_ADMIN-only).
 *
 * Composes:
 *   - `UsersFilterBar` (URL-bound filters via `useSearchParams`)
 *   - `UsersTable` (server-paged TanStack table with row actions)
 *   - `UsersDialogs` (create, edit, reset, revoke, and disable workflows)
 *   - `TempPasswordRevealCard` (page-level reveal-once card)
 *
 * Role enforcement is server-side (backend 401s non-admin) AND
 * client-side (router-level RequireRole). When a non-admin somehow lands
 * here we surface a friendly EmptyState instead of an ErrorState.
 *
 * Density default = comfortable per D7.
 */
import { useMemo } from "react";
import { useSearchParams } from "react-router-dom";
import { Users } from "lucide-react";
import { AdminEntityListPage } from "@/components/app/layout/AdminEntityListPage";
import { extractAdminErrorMessage } from "@/lib/admin-page-utils";
import { Role } from "@/schemas/role";
import { UserStatus } from "@/schemas/user";
import { UsersFilterBar } from "./components/UsersFilterBar";
import { UsersTable } from "./components/UsersTable";
import { TempPasswordRevealCard } from "./components/TempPasswordRevealCard";
import { UsersDialogs } from "./components/UsersDialogs";
import { useUsers } from "./hooks/useUsers";
import { useUsersDialogController } from "./hooks/useUsersDialogController";
import { useLsps } from "@/features/lsps/hooks/useLsps";
import type { UsersListFilters } from "./types";
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

export function UsersPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const filters = useMemo(() => parseFiltersFromUrl(searchParams), [searchParams]);
  const setFilters = (next: UsersListFilters) => {
    setSearchParams(filtersToParams(next), { replace: false });
  };

  const list = useUsers(filters);
  const dialogs = useUsersDialogController();
  const lspsQuery = useLsps({ pageSize: 100 });
  const lspOptions = useMemo(
    () => (lspsQuery.data?.items ?? []).map((l) => ({ id: l.id, name: l.name })),
    [lspsQuery.data],
  );

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
        onClick: dialogs.openCreate,
      }}
      banner={
        dialogs.revealedTempPassword !== null ? (
          <TempPasswordRevealCard
            password={dialogs.revealedTempPassword.password}
            username={dialogs.revealedTempPassword.username}
            onAcknowledge={dialogs.clearRevealed}
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
          onEdit={dialogs.openEdit}
          onResetPassword={dialogs.openResetPassword}
          onRevokeSessions={dialogs.openRevokeSessions}
          onToggleStatus={dialogs.handleToggleStatus}
        />
      }
      dialogs={
        <UsersDialogs
          dialog={dialogs.dialog}
          revealedTempPassword={dialogs.revealedTempPassword}
          lspOptions={lspOptions}
          onCreateOpenChange={dialogs.handleCreateOpenChange}
          onCreateConfirm={dialogs.handleCreateConfirm}
          onCreateAcknowledge={dialogs.handleCreateAcknowledge}
          createLoading={dialogs.create.isPending}
          createErrorMessage={
            dialogs.create.isError ? extractAdminErrorMessage(dialogs.create.error) : null
          }
          onEditOpenChange={dialogs.handleEditOpenChange}
          onEditConfirm={dialogs.handleEditConfirm}
          updateLoading={dialogs.update.isPending}
          updateErrorMessage={
            dialogs.update.isError ? extractAdminErrorMessage(dialogs.update.error) : null
          }
          onResetOpenChange={dialogs.handleResetOpenChange}
          onResetConfirm={dialogs.handleResetConfirm}
          onResetAcknowledge={dialogs.handleResetAcknowledge}
          resetLoading={dialogs.reset.isPending}
          resetErrorMessage={
            dialogs.reset.isError ? extractAdminErrorMessage(dialogs.reset.error) : null
          }
          onRevokeOpenChange={dialogs.handleRevokeOpenChange}
          onRevokeConfirm={dialogs.handleRevokeConfirm}
          revokeLoading={dialogs.revokeSessions.isPending}
          revokeErrorMessage={
            dialogs.revokeSessions.isError
              ? extractAdminErrorMessage(dialogs.revokeSessions.error)
              : null
          }
          onDisableOpenChange={dialogs.handleDisableOpenChange}
          onDisableConfirm={dialogs.handleDisableConfirm}
        />
      }
    />
  );
}

export default UsersPage;
export const Component = UsersPage;
