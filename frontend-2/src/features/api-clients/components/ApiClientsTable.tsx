/**
 * `ApiClientsTable` — TanStack-table view of the api-clients admin list.
 *
 * Columns: Client ID, Name, LSP, Status, IP rules count, Last used. Each
 * row exposes a "Manage" button that bubbles up via `onSelect`. Click on
 * the client-id cell also opens the edit dialog (the parent owns the
 * dialog state).
 */
import { useMemo } from "react";
import {
  getCoreRowModel,
  useReactTable,
  type ColumnDef,
  type PaginationState,
} from "@tanstack/react-table";
import { CheckCircle2, MinusCircle } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { DataTable } from "@/components/app/data/DataTable";
import { DataTablePagination } from "@/components/app/data/DataTablePagination";
import { EmptyState } from "@/components/app/feedback/EmptyState";
import { formatDateTime, formatRelative } from "@/lib/format";
import { cn } from "@/lib/utils";
import type { ApiClientRow, ApiClientsListFilters, ApiClientsListResponse } from "../types";

export interface ApiClientsTableProps {
  data: ApiClientsListResponse | undefined;
  isLoading: boolean;
  filters: ApiClientsListFilters;
  onFiltersChange: (next: ApiClientsListFilters) => void;
  /** Called when the operator wants to edit a row. */
  onSelect: (row: ApiClientRow) => void;
  className?: string;
}

export function ApiClientsTable({
  data,
  isLoading,
  filters,
  onFiltersChange,
  onSelect,
  className,
}: ApiClientsTableProps) {
  const rows = data?.items ?? [];
  const total = data?.total ?? 0;
  const pageSize = filters.pageSize ?? 25;
  const page = filters.page ?? 0;

  const columns = useMemo<ColumnDef<ApiClientRow>[]>(
    () => [
      {
        id: "clientId",
        header: "Client ID",
        cell: ({ row }) => (
          <button
            type="button"
            data-slot="api-clients-row-link"
            onClick={() => onSelect(row.original)}
            className="text-foreground focus-visible:ring-ring/50 rounded-sm font-mono text-sm tabular-nums outline-none hover:underline focus-visible:underline focus-visible:ring-2"
          >
            {row.original.clientId}
          </button>
        ),
      },
      {
        id: "name",
        header: "Name",
        cell: ({ row }) => <span className="text-foreground text-sm">{row.original.name}</span>,
      },
      {
        id: "lsp",
        header: "LSP",
        cell: ({ row }) => (
          <span className="text-foreground-muted text-sm">{row.original.lspName}</span>
        ),
      },
      {
        id: "status",
        header: "Status",
        cell: ({ row }) => {
          const isActive = row.original.status === "ACTIVE";
          const Icon = isActive ? CheckCircle2 : MinusCircle;
          return (
            <Badge
              data-slot="api-clients-status-badge"
              data-status={row.original.status}
              className={cn(
                "gap-1",
                isActive
                  ? "border-success/30 bg-success/10 text-success"
                  : "border-border bg-surface-muted text-foreground-muted",
              )}
            >
              <Icon aria-hidden="true" className="size-3" />
              <span>{isActive ? "Active" : "Disabled"}</span>
            </Badge>
          );
        },
      },
      {
        id: "lastUsedAt",
        header: "Last used",
        cell: ({ row }) => {
          const v = row.original.lastUsedAt;
          if (!v) {
            return <span className="text-foreground-muted text-xs">—</span>;
          }
          return (
            <span title={formatDateTime(v)} className="text-foreground-muted text-xs">
              {formatRelative(v)}
            </span>
          );
        },
      },
      {
        id: "actions",
        header: () => <span className="sr-only">Actions</span>,
        cell: ({ row }) => (
          <Button
            type="button"
            variant="outline"
            size="sm"
            data-slot="api-clients-manage-button"
            onClick={() => onSelect(row.original)}
          >
            Manage
          </Button>
        ),
      },
    ],
    [onSelect],
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
    <div data-slot="api-clients-table" className={cn("flex flex-col gap-2", className)}>
      <DataTable<ApiClientRow, unknown>
        columns={columns}
        data={rows}
        loading={isLoading}
        rowIdKey="id"
        ariaLabel="API clients"
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
            title="No API clients"
            description="Loosen the filters or create a new client."
          />
        }
      />
      <DataTablePagination table={table} totalRows={total} />
    </div>
  );
}
