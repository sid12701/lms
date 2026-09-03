/**
 * `ApiClientsTable` — TanStack-table view of the api-clients admin list.
 *
 * Columns: Client ID, Name, LSP, Status, Last used. Each row exposes "Edit"
 * (rename / enable-disable) and "Rotate secret" actions; clicking the
 * client-id cell opens the edit dialog. The parent owns the dialog state.
 */
import { useCallback, useMemo } from "react";
import type { ColumnDef, PaginationState } from "@tanstack/react-table";
import { CheckCircle2, MinusCircle } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { AdminEntityDataTable } from "@/components/app/data/AdminEntityDataTable";
import { AbsoluteRelativeTime } from "@/components/app/misc/AbsoluteRelativeTime";
import { EntityRowActions } from "@/components/app/data/EntityRowActions";
import { EmptyState } from "@/components/app/feedback/EmptyState";
import { cn } from "@/lib/utils";
import type { ApiClientRow, ApiClientsListFilters, ApiClientsListResponse } from "../types";

export interface ApiClientsTableProps {
  data: ApiClientsListResponse | undefined;
  isLoading: boolean;
  filters: ApiClientsListFilters;
  onFiltersChange: (next: ApiClientsListFilters) => void;
  /** Called when the operator wants to edit (rename / enable-disable) a row. */
  onEdit: (row: ApiClientRow) => void;
  /** Called when the operator wants to rotate a row's secret. */
  onRotate: (row: ApiClientRow) => void;
  className?: string;
}

export function ApiClientsTable({
  data,
  isLoading,
  filters,
  onFiltersChange,
  onEdit,
  onRotate,
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
        meta: { label: "Client ID", mobileCard: "primary" },
        cell: ({ row }) => (
          <button
            type="button"
            data-slot="api-clients-row-link"
            onClick={() => onEdit(row.original)}
            className="text-foreground focus-visible:ring-ring rounded-control font-mono text-sm tabular-nums outline-none hover:underline focus-visible:underline focus-visible:ring-2"
          >
            {row.original.clientId}
          </button>
        ),
      },
      {
        id: "name",
        header: "Name",
        meta: { label: "Name", mobileCard: "secondary" },
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
        meta: { label: "Status", mobileCard: "primary" },
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
          return <AbsoluteRelativeTime iso={v} variant="relative" compact />;
        },
      },
      {
        id: "actions",
        header: () => <span className="sr-only">Actions</span>,
        meta: { mobileCard: "actions" },
        cell: ({ row }) => (
          <EntityRowActions
            mode="inline"
            items={[
              {
                id: "edit",
                label: "Edit",
                ariaLabel: `Edit ${row.original.name}`,
                dataSlot: "api-clients-edit-button",
                onSelect: () => onEdit(row.original),
              },
              {
                id: "rotate",
                label: "Rotate secret",
                ariaLabel: `Rotate secret for ${row.original.name}`,
                dataSlot: "api-clients-rotate-button",
                onSelect: () => onRotate(row.original),
              },
            ]}
          />
        ),
      },
    ],
    [onEdit, onRotate],
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
      dataSlot="api-clients-table"
      className={className}
      columns={columns}
      rows={rows}
      total={total}
      page={page}
      pageSize={pageSize}
      loading={isLoading}
      rowIdKey="id"
      ariaLabel="API clients"
      onPaginationChange={handlePaginationChange}
      empty={
        <EmptyState
          variant="filtered-empty"
          title="No API clients"
          description="Loosen the filters or create a new client."
        />
      }
    />
  );
}
