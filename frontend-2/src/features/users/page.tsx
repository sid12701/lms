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
 * Role enforcement is server-side (mock handler 401s non-admin) AND
 * client-side (router-level RequireRole). When a non-admin somehow lands
 * here we surface a friendly EmptyState instead of an ErrorState.
 *
 * Density default = comfortable per D7.
 */
import { useMemo, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { Plus, ShieldAlert, Users } from "lucide-react";
import { z } from "zod";
import { PageHeader } from "@/components/app/layout/PageHeader";
import { EmptyState } from "@/components/app/feedback/EmptyState";
import { ErrorState } from "@/components/app/feedback/ErrorState";
import { Button } from "@/components/ui/button";
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
import { useLsps } from "@/features/lsps/hooks/useLsps";
import type { UserRow, UsersListFilters } from "./types";
import type { Role as RoleT } from "@/schemas/role";
import type { UserStatus as UserStatusT } from "@/schemas/user";

function isUnauthorized(err: unknown): boolean {
  if (!err) return false;
  if (typeof err === "object" && err !== null && "code" in err) {
    const code = (err as { code?: unknown }).code;
    if (code === "UNAUTHORIZED") return true;
  }
  const msg = err instanceof Error ? err.message : String(err);
  return /UNAUTHORIZED/i.test(msg);
}

function extractErrorMessage(err: unknown): string | null {
  if (!err) return null;
  if (err instanceof Error && err.message) return err.message;
  return "Something went wrong. Try again in a moment.";
}

const VALID_ROLES: readonly RoleT[] = Role.options;
const VALID_STATUSES: readonly UserStatusT[] = UserStatus.options;
const UuidSchema = z.string().uuid();

function parseFiltersFromUrl(params: URLSearchParams): UsersListFilters {
  const filters: UsersListFilters = {};
  const role = params.get("role");
  if (role && (VALID_ROLES as readonly string[]).includes(role)) {
    filters.role = role as RoleT;
  }
  const status = params.get("status");
  if (status && (VALID_STATUSES as readonly string[]).includes(status)) {
    filters.status = status as UserStatusT;
  }
  const lspId = params.get("lspId");
  if (lspId && UuidSchema.safeParse(lspId).success) {
    filters.lspId = lspId;
  }
  const q = params.get("q");
  if (q && q.trim() !== "") filters.q = q.trim();
  const page = params.get("page");
  if (page !== null) {
    const n = Number(page);
    if (Number.isInteger(n) && n >= 0) filters.page = n;
  }
  const pageSize = params.get("pageSize");
  if (pageSize !== null) {
    const n = Number(pageSize);
    if (Number.isInteger(n) && n >= 5 && n <= 100) filters.pageSize = n;
  }
  return filters;
}

function filtersToParams(filters: UsersListFilters): URLSearchParams {
  const params = new URLSearchParams();
  if (filters.role) params.set("role", filters.role);
  if (filters.status) params.set("status", filters.status);
  if (filters.lspId) params.set("lspId", filters.lspId);
  if (filters.q) params.set("q", filters.q);
  if (typeof filters.page === "number" && filters.page > 0) {
    params.set("page", String(filters.page));
  }
  if (typeof filters.pageSize === "number") {
    params.set("pageSize", String(filters.pageSize));
  }
  return params;
}

export function UsersPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const filters = useMemo(
    () => parseFiltersFromUrl(searchParams),
    [searchParams],
  );

  const setFilters = (next: UsersListFilters) => {
    setSearchParams(filtersToParams(next), { replace: false });
  };

  const [createOpen, setCreateOpen] = useState(false);
  const [editTarget, setEditTarget] = useState<UserRow | null>(null);
  const [resetTarget, setResetTarget] = useState<UserRow | null>(null);
  const [revealedTempPassword, setRevealedTempPassword] = useState<{
    username: string;
    password: string;
  } | null>(null);

  const list = useUsers(filters);
  const create = useCreateUser();
  const update = useUpdateUser();
  const reset = useResetUserPassword();

  // LSP options for the filter bar + create/edit dialogs. Pull a wide page —
  // the admin LSP list is short enough in practice (BRD §1) to fit without
  // a typeahead.
  const lspsQuery = useLsps({ pageSize: 100 });
  const lspOptions = useMemo(
    () =>
      (lspsQuery.data?.items ?? []).map((l) => ({ id: l.id, name: l.name })),
    [lspsQuery.data],
  );

  const clearRevealed = () => setRevealedTempPassword(null);

  // ── Create dialog handlers ──────────────────────────────────────────────────
  const handleCreateOpenChange = (open: boolean) => {
    if (!open) {
      if (create.isPending) return;
      setCreateOpen(false);
      create.reset();
      clearRevealed();
    } else {
      setCreateOpen(true);
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
    setCreateOpen(false);
    create.reset();
    clearRevealed();
  };

  // ── Edit dialog handlers ────────────────────────────────────────────────────
  const handleEditOpenChange = (open: boolean) => {
    if (!open) {
      if (update.isPending) return;
      setEditTarget(null);
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
      setEditTarget(null);
      update.reset();
    } catch {
      // Surfaced via update.error.
    }
  };

  // ── Reset-password dialog handlers ──────────────────────────────────────────
  const handleResetOpenChange = (open: boolean) => {
    if (!open) {
      if (reset.isPending) return;
      setResetTarget(null);
      reset.reset();
      clearRevealed();
    }
  };
  const handleResetConfirm = async ({
    idempotencyKey,
  }: {
    idempotencyKey: string;
  }) => {
    if (!resetTarget) return;
    try {
      const result = await reset.mutateAsync({
        id: resetTarget.id,
        idempotencyKey,
      });
      setRevealedTempPassword({
        username: result.user.username,
        password: result.temporaryPassword,
      });
    } catch {
      // Surfaced via reset.error.
    }
  };
  const handleResetAcknowledge = () => {
    setResetTarget(null);
    reset.reset();
    clearRevealed();
  };

  // ── Toggle status (Enable / Disable) ────────────────────────────────────────
  const handleToggleStatus = (row: UserRow) => {
    const nextStatus: UserStatusT =
      row.status === "DISABLED" ? "ACTIVE" : "DISABLED";
    update.mutate({
      id: row.id,
      status: nextStatus,
      idempotencyKey: newIdempotencyKey(),
    });
  };

  return (
    <div
      data-testid="users-page"
      className="flex flex-col gap-6 p-6"
      data-density="comfortable"
    >
      <PageHeader
        eyebrow="Administration"
        title="Users"
        description="Internal and LSP users — manage roles and tenant scope."
        actions={
          <Button
            type="button"
            onClick={() => setCreateOpen(true)}
            data-slot="users-new-button"
          >
            <Plus aria-hidden="true" className="size-4" />
            New user
          </Button>
        }
      />

      {revealedTempPassword !== null ? (
        <TempPasswordRevealCard
          password={revealedTempPassword.password}
          username={revealedTempPassword.username}
          onAcknowledge={clearRevealed}
        />
      ) : null}

      {list.isError && isUnauthorized(list.error) ? (
        <EmptyState
          variant="no-permission"
          icon={ShieldAlert}
          title="No access to users"
          description="The users admin surface is restricted to system administrators."
        />
      ) : list.isError ? (
        <ErrorState
          title="Couldn't load users"
          description="The user list couldn't be fetched. Try again in a moment."
          retry={{
            label: "Retry",
            onClick: () => {
              void list.refetch();
            },
          }}
        />
      ) : (
        <>
          <UsersFilterBar
            filters={filters}
            onChange={setFilters}
            lspOptions={lspOptions}
          />
          {!list.isPending &&
          (list.data?.total ?? 0) === 0 &&
          !filters.q &&
          !filters.role &&
          !filters.status &&
          !filters.lspId ? (
            <EmptyState
              icon={Users}
              title="No users yet"
              description="Invite the first user to get started."
            />
          ) : (
            <UsersTable
              data={list.data}
              isLoading={list.isPending}
              filters={filters}
              onFiltersChange={setFilters}
              onEdit={(row) => setEditTarget(row)}
              onResetPassword={(row) => setResetTarget(row)}
              onToggleStatus={handleToggleStatus}
            />
          )}
        </>
      )}

      <UserCreateDialog
        open={createOpen}
        onOpenChange={handleCreateOpenChange}
        lspOptions={lspOptions}
        onConfirm={handleCreateConfirm}
        temporaryPassword={revealedTempPassword?.password ?? null}
        createdUsername={revealedTempPassword?.username ?? null}
        onAcknowledgePassword={handleCreateAcknowledge}
        loading={create.isPending}
        errorMessage={
          create.isError ? extractErrorMessage(create.error) : null
        }
      />

      <UserEditDialog
        open={editTarget !== null}
        onOpenChange={handleEditOpenChange}
        user={editTarget}
        lspOptions={lspOptions}
        onConfirm={handleEditConfirm}
        loading={update.isPending}
        errorMessage={
          update.isError ? extractErrorMessage(update.error) : null
        }
      />

      <ResetPasswordDialog
        open={resetTarget !== null}
        onOpenChange={handleResetOpenChange}
        username={resetTarget?.username ?? ""}
        onConfirm={handleResetConfirm}
        temporaryPassword={
          resetTarget !== null
            ? revealedTempPassword?.password ?? null
            : null
        }
        onAcknowledgePassword={handleResetAcknowledge}
        loading={reset.isPending}
        errorMessage={reset.isError ? extractErrorMessage(reset.error) : null}
      />
    </div>
  );
}

export default UsersPage;
export const Component = UsersPage;
