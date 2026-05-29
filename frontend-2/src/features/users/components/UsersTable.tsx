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
import { useMemo } from "react";
import {
  getCoreRowModel,
  useReactTable,
  type ColumnDef,
  type PaginationState,
} from "@tanstack/react-table";
import { CheckCircle2, PauseCircle } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { DataTable } from "@/components/app/data/DataTable";
import { DataTablePagination } from "@/components/app/data/DataTablePagination";
import { EmptyState } from "@/components/app/feedback/EmptyState";
import { formatDateTime, formatRelative } from "@/lib/format";
import { cn } from "@/lib/utils";
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
    className: "border-success/30 bg-success/10 text-success",
  },
  DISABLED: {
    label: "Disabled",
    icon: PauseCircle,
    className: "border-border bg-surface-muted text-foreground-muted",
  },
};

export interface UsersTableProps {
  data: UsersListResponse | undefined;
  isLoading: boolean;
  filters: UsersListFilters;
  onFiltersChange: (next: UsersListFilters) => void;
  onEdit: (row: UserRow) => void;
  onResetPassword: (row: UserRow) => void;
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
  onToggleStatus,
  className,
}: UsersTableProps) {
  const rows = data?.items ?? [];
  const total = data?.total ?? 0;
  const pageSize = filters.pageSize ?? 25;
  const page = filters.page ?? 0;

  const columns = useMemo<ColumnDef<UserRow>[]>(
    () => [
      {
        id: "username",
        header: "Username",
        cell: ({ row }) => (
          <div className="flex flex-col gap-0.5">
            <span className="text-foreground text-sm font-medium" data-slot="users-username">
              {row.original.username}
            </span>
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
        cell: ({ row }) => (
          <span className="text-foreground-muted text-xs">{row.original.email}</span>
        ),
      },
      {
        id: "role",
        header: "Role",
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
        cell: ({ row }) => {
          const meta = STATUS_META[row.original.status];
          const Icon = meta.icon;
          return (
            <Badge
              data-slot="users-status-badge"
              data-status={row.original.status}
              className={cn("gap-1", meta.className)}
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
        cell: ({ row }) => {
          const u = row.original;
          const isDisabled = u.status === "DISABLED";
          return (
            <div className="flex flex-wrap items-center gap-1">
              <Button
                type="button"
                variant="outline"
                size="sm"
                data-slot="users-edit-button"
                onClick={() => onEdit(u)}
              >
                Edit
              </Button>
              <Button
                type="button"
                variant="outline"
                size="sm"
                data-slot="users-reset-button"
                onClick={() => onResetPassword(u)}
              >
                Reset password
              </Button>
              <Button
                type="button"
                variant="outline"
                size="sm"
                data-slot="users-toggle-status-button"
                aria-label={isDisabled ? `Enable ${u.username}` : `Disable ${u.username}`}
                onClick={() => onToggleStatus(u)}
              >
                {isDisabled ? "Enable" : "Disable"}
              </Button>
            </div>
          );
        },
      },
    ],
    [onEdit, onResetPassword, onToggleStatus],
  );

  const pagination = useMemo<PaginationState>(
    () => ({ pageIndex: page, pageSize }),
    [page, pageSize],
  );

  const table = useReactTable({
    data: rows,
    columns,
    state: { pagination },
    manualPagination: true,
    rowCount: total,
    getRowId: (row) => row.id,
    getCoreRowModel: getCoreRowModel(),
    onPaginationChange: (updater) => {
      const next = typeof updater === "function" ? updater(pagination) : updater;
      onFiltersChange({
        ...filters,
        page: next.pageIndex,
        pageSize: next.pageSize,
      });
    },
  });

  return (
    <div data-slot="users-table" className={cn("flex flex-col gap-2", className)}>
      <DataTable<UserRow, unknown>
        columns={columns}
        data={rows}
        loading={isLoading}
        rowIdKey="id"
        ariaLabel="Admin users"
        pagination={{
          manualPagination: true,
          rowCount: total,
          initialPageSize: pageSize,
        }}
        state={{
          pagination: { pageIndex: page, pageSize },
        }}
        onStateChange={(next) => {
          if (next.pagination) {
            onFiltersChange({
              ...filters,
              page: next.pagination.pageIndex,
              pageSize: next.pagination.pageSize,
            });
          }
        }}
        empty={
          <EmptyState
            variant="filtered-empty"
            title="No users"
            description="Loosen the filters or invite a new user."
          />
        }
      />
      <DataTablePagination table={table} totalRows={total} />
    </div>
  );
}
