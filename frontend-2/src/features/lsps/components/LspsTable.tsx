/**
 * `LspsTable` — TanStack-table view of the LSP admin list.
 *
 * Status is colour-coded via a small intent-mapped `Badge` (LSP status is a
 * separate enum from LoanStatus, so we don't reuse `StatusBadge` here).
 * Row actions: Edit (opens edit dialog) + Webhook (opens subscription
 * dialog). Both delegate to the parent via callbacks.
 */
import { useMemo } from "react";
import {
  getCoreRowModel,
  useReactTable,
  type ColumnDef,
  type PaginationState,
} from "@tanstack/react-table";
import { CheckCircle2, MinusCircle, PauseCircle, Webhook } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { DataTable } from "@/components/app/data/DataTable";
import { DataTablePagination } from "@/components/app/data/DataTablePagination";
import { EmptyState } from "@/components/app/feedback/EmptyState";
import { formatDateTime, formatRelative } from "@/lib/format";
import { cn } from "@/lib/utils";
import type { LspsListFilters, LspsListResponse, LspRow } from "../types";
import type { LspStatus } from "@/schemas/lsp";

const STATUS_META: Record<
  LspStatus,
  { label: string; icon: typeof CheckCircle2; className: string }
> = {
  ACTIVE: {
    label: "Active",
    icon: CheckCircle2,
    className: "border-success/30 bg-success/10 text-success",
  },
  SUSPENDED: {
    label: "Suspended",
    icon: PauseCircle,
    className: "border-warning/30 bg-warning/10 text-warning",
  },
  INACTIVE: {
    label: "Inactive",
    icon: MinusCircle,
    className: "border-border bg-surface-muted text-foreground-muted",
  },
};

export interface LspsTableProps {
  data: LspsListResponse | undefined;
  isLoading: boolean;
  filters: LspsListFilters;
  onFiltersChange: (next: LspsListFilters) => void;
  onEdit: (row: LspRow) => void;
  onEditWebhook: (row: LspRow) => void;
  className?: string;
}

export function LspsTable({
  data,
  isLoading,
  filters,
  onFiltersChange,
  onEdit,
  onEditWebhook,
  className,
}: LspsTableProps) {
  const rows = data?.items ?? [];
  const total = data?.total ?? 0;
  const pageSize = filters.pageSize ?? 25;
  const page = filters.page ?? 0;

  const columns = useMemo<ColumnDef<LspRow>[]>(
    () => [
      {
        id: "code",
        header: "Code",
        cell: ({ row }) => (
          <span
            data-slot="lsps-code"
            className="text-foreground font-mono text-sm font-medium"
          >
            {row.original.code}
          </span>
        ),
      },
      {
        id: "name",
        header: "Name",
        cell: ({ row }) => (
          <span className="text-foreground text-sm">{row.original.name}</span>
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
              data-slot="lsps-status-badge"
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
        id: "apiClients",
        header: "API clients",
        cell: ({ row }) => (
          <span className="text-foreground-muted text-xs tabular-nums">
            {row.original.apiClientCount}
          </span>
        ),
        meta: { numeric: true },
      },
      {
        id: "webhook",
        header: "Webhook",
        cell: ({ row }) => {
          if (row.original.webhookEnabled) {
            return (
              <span
                data-slot="lsps-webhook-enabled"
                className="text-success flex items-center gap-1 text-xs font-medium"
              >
                <Webhook aria-hidden="true" className="size-3" />
                Enabled
              </span>
            );
          }
          return (
            <span className="text-foreground-muted text-xs">Not configured</span>
          );
        },
      },
      {
        id: "createdAt",
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
        cell: ({ row }) => (
          <div className="flex items-center justify-end gap-1">
            <Button
              type="button"
              variant="outline"
              size="sm"
              data-slot="lsps-edit-button"
              onClick={() => onEdit(row.original)}
            >
              Edit
            </Button>
            <Button
              type="button"
              variant="ghost"
              size="sm"
              data-slot="lsps-webhook-button"
              onClick={() => onEditWebhook(row.original)}
            >
              Webhook
            </Button>
          </div>
        ),
      },
    ],
    [onEdit, onEditWebhook],
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
    <div data-slot="lsps-table" className={cn("flex flex-col gap-2", className)}>
      <DataTable<LspRow, unknown>
        columns={columns}
        data={rows}
        loading={isLoading}
        rowIdKey="id"
        ariaLabel="LSP tenants"
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
            title="No LSPs"
            description="Loosen the filters or create the first LSP tenant."
          />
        }
      />
      <DataTablePagination table={table} totalRows={total} />
    </div>
  );
}
