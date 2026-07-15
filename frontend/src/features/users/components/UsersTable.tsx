/**
 * `UsersTable` — TanStack-table view of the admin users list.
 *
 * Columns: Username, Email, Role, LSP, Status, Created. Row-level actions
 * (Edit, Reset password, Disable / Enable) delegate to the parent via
 * callbacks. Density is comfortable per D7.
 *
 * Username is NOT PII (per the agent prompt). Email is NOT masked on this
 * admin surface (the admin already sees email in the auth seed).
 */
import { useCallback, useMemo } from "react";
import type { ColumnDef, PaginationState } from "@tanstack/react-table";
import { CheckCircle2, Lock, PauseCircle } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { AdminEntityDataTable } from "@/components/app/data/AdminEntityDataTable";
import { EntityRowActions } from "@/components/app/data/EntityRowActions";
import { EmptyState } from "@/components/app/feedback/EmptyState";
import { useSession } from "@/features/auth/session-context";
import { formatDateTime, formatRelative } from "@/lib/format";
import type { Role } from "@/schemas/role";
import type { UserStatus } from "@/schemas/user";
import type { UserRow, UsersListFilters, UsersListResponse } from "../types";

const ROLE_LABEL: Record<Role, string> = {
  SYSTEM_ADMIN: "System admin",
  OPS_USER: "Ops user",
  PRODUCT_ADMIN: "Product admin",
  LSP_UI_READ: "LSP UI — read",
  LSP_UI_WRITE: "LSP UI — write",
  LSP_API_CLIENT: "LSP API client",
};

const STATUS_META: Record<
  UserStatus,
  { label: string; className: string; icon: typeof CheckCircle2 }
> = {
  ACTIVE: {
    label: "Active",
    icon: CheckCircle2,
    className: "gap-1 border-success/30 bg-success/10 text-success",
  },
  DISABLED: {
    label: "Disabled",
    icon: PauseCircle,
    className: "gap-1 border-border bg-surface-muted text-foreground-muted",
  },
};

export interface UsersTableProps {
  data: UsersListResponse | undefined;
  isLoading: boolean;
  filters: UsersListFilters;
  onFiltersChange: (next: UsersListFilters) => void;
  onEdit: (row: UserRow) => void;
  onResetPassword: (row: UserRow) => void;
  onRevokeSessions: (row: UserRow) => void;
  onToggleStatus: (row: UserRow) => void;
  className?: string;
}

export function UsersTable({
  data,
  isLoading,
  filters,
  onFiltersChange,
  onEdit,
  onResetPassword,
  onRevokeSessions,
  onToggleStatus,
  className,
}: UsersTableProps) {
  const { session } = useSession();
  const currentUsername = session?.user.username ?? null;
  const rows = data?.items ?? [];
  const total = data?.total ?? 0;
  const pageSize = filters.pageSize ?? 25;
  const page = filters.page ?? 0;

  const columns = useMemo<ColumnDef<UserRow>[]>(
    () => [
      {
        id: "username",
        header: "Username",
        meta: { label: "Username", mobileCard: "primary" },
        cell: ({ row }) => (
          <div className="flex flex-col gap-1">
            <div className="flex flex-wrap items-center gap-2">
              <span className="text-foreground text-sm font-medium" data-slot="users-username">
                {row.original.username}
              </span>
              {row.original.lockedAt ? (
                <Tooltip>
                  <TooltipTrigger asChild>
                    <Badge
                      data-slot="users-locked-badge"
                      data-testid="users-locked-badge"
                      className="border-warning/30 bg-warning/10 text-warning gap-1"
                    >
                      <Lock aria-hidden="true" className="size-3" />
                      <span>Locked</span>
                    </Badge>
                  </TooltipTrigger>
                  <TooltipContent side="top">
                    {row.original.lockReason === "BRUTE_FORCE"
                      ? "Auto-locked after repeated failed logins. Reset password to unlock."
                      : "Account is locked. Reset password to unlock."}
                  </TooltipContent>
                </Tooltip>
              ) : null}
            </div>
            {row.original.mustChangePassword ? (
              <span data-slot="users-must-change-flag" className="text-warning text-xs">
                Must change password
              </span>
            ) : null}
          </div>
        ),
      },
      {
        id: "email",
        header: "Email",
        meta: { label: "Email", mobileCard: "secondary" },
        cell: ({ row }) => (
          <span className="text-foreground-muted text-xs">{row.original.email}</span>
        ),
      },
      {
        id: "role",
        header: "Role",
        meta: { label: "Role", mobileCard: "secondary" },
        cell: ({ row }) => (
          <span className="text-foreground text-xs">{ROLE_LABEL[row.original.role]}</span>
        ),
      },
      {
        id: "lsp",
        header: "LSP",
        cell: ({ row }) => (
          <span className="text-foreground-muted text-xs">{row.original.lspName ?? "—"}</span>
        ),
      },
      {
        id: "status",
        header: "Status",
        meta: { label: "Status", mobileCard: "primary" },
        cell: ({ row }) => {
          const meta = STATUS_META[row.original.status];
          const Icon = meta.icon;
          return (
            <Badge
              data-slot="users-status-badge"
              data-status={row.original.status}
              className={meta.className}
            >
              <Icon aria-hidden="true" className="size-3" />
              <span>{meta.label}</span>
            </Badge>
          );
        },
      },
      {
        id: "created",
        header: "Created",
        cell: ({ row }) => (
          <span
            title={formatDateTime(row.original.createdAt)}
            className="text-foreground-muted text-xs"
          >
            {formatRelative(row.original.createdAt)}
          </span>
        ),
      },
      {
        id: "actions",
        header: () => <span className="sr-only">Actions</span>,
        meta: { mobileCard: "actions" },
        cell: ({ row }) => {
          const u = row.original;
          const isDisabled = u.status === "DISABLED";
          const isSelf = currentUsername !== null && u.username === currentUsername;
          return (
            <EntityRowActions
              mode="inline"
              items={[
                {
                  id: "edit",
                  label: "Edit",
                  ariaLabel: `Edit ${u.username}`,
                  dataSlot: "users-edit-button",
                  onSelect: () => onEdit(u),
                },
                {
                  id: "reset",
                  label: "Reset password",
                  ariaLabel: `Reset password for ${u.username}`,
                  dataSlot: "users-reset-button",
                  onSelect: () => onResetPassword(u),
                },
                {
                  id: "revoke-sessions",
                  label: "Revoke sessions",
                  ariaLabel: `Revoke sessions for ${u.username}`,
                  dataSlot: "users-revoke-sessions-button",
                  onSelect: () => onRevokeSessions(u),
                },
                {
                  id: "toggle",
                  label: isDisabled ? "Enable" : "Disable",
                  ariaLabel: isDisabled ? `Enable ${u.username}` : `Disable ${u.username}`,
                  dataSlot: "users-toggle-status-button",
                  // Self-lockout guard: an admin disabling their own account
                  // (possibly the only SYSTEM_ADMIN) has no recovery path in-app.
                  disabled: isSelf && !isDisabled,
                  disabledTitle: "You can't disable your own account.",
                  onSelect: () => onToggleStatus(u),
                },
              ]}
            />
          );
        },
      },
    ],
    [onEdit, onResetPassword, onRevokeSessions, onToggleStatus, currentUsername],
  );

  const handlePaginationChange = useCallback(
    (next: PaginationState) => {
      onFiltersChange({
        ...filters,
        page: next.pageIndex,
        pageSize: next.pageSize,
      });
    },
    [filters, onFiltersChange],
  );

  return (
    <AdminEntityDataTable
      dataSlot="users-table"
      className={className}
      columns={columns}
      rows={rows}
      total={total}
      page={page}
      pageSize={pageSize}
      loading={isLoading}
      rowIdKey="id"
      ariaLabel="Admin users"
      onPaginationChange={handlePaginationChange}
      empty={
        <EmptyState
          variant="filtered-empty"
          title="No users"
          description="Loosen the filters or invite a new user."
        />
      }
    />
  );
}
