/**
 * `LspsTable` — TanStack-table view of the LSP admin list.
 *
 * Status is colour-coded via a small intent-mapped `Badge` (LSP status is a
 * separate enum from LoanStatus, so we don't reuse `StatusBadge` here).
 * Row actions: Details, Status, Audit — delegated to the parent.
 */
import { useCallback, useMemo } from "react";
import type { ColumnDef, PaginationState } from "@tanstack/react-table";
import { CheckCircle2, MinusCircle, PauseCircle } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { AdminEntityDataTable } from "@/components/app/data/AdminEntityDataTable";
import { EntityRowActions } from "@/components/app/data/EntityRowActions";
import { EmptyState } from "@/components/app/feedback/EmptyState";
import { formatDateTime } from "@/lib/format";
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
  onDetails: (row: LspRow) => void;
  onChangeStatus: (row: LspRow) => void;
  onViewAudit: (row: LspRow) => void;
  className?: string;
}

export function LspsTable({
  data,
  isLoading,
  filters,
  onFiltersChange,
  onDetails,
  onChangeStatus,
  onViewAudit,
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
        meta: { label: "Code", mobileCard: "primary" },
        cell: ({ row }) => (
          <span data-slot="lsps-code" className="text-foreground font-mono text-sm font-medium">
            {row.original.code}
          </span>
        ),
      },
      {
        id: "name",
        header: "Name",
        meta: { label: "Name", mobileCard: "secondary" },
        cell: ({ row }) => <span className="text-foreground text-sm">{row.original.name}</span>,
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
        id: "users",
        header: "Users",
        cell: ({ row }) => (
          <span className="text-foreground-muted text-xs tabular-nums">
            {row.original.userCount}
          </span>
        ),
        meta: { numeric: true },
      },
      {
        id: "createdAt",
        header: "Created",
        cell: ({ row }) => (
          <span className="text-foreground-muted text-xs">
            {row.original.createdAt ? formatDateTime(row.original.createdAt) : "—"}
          </span>
        ),
      },
      {
        id: "actions",
        header: () => <span className="sr-only">Actions</span>,
        meta: { mobileCard: "actions" },
        cell: ({ row }) => {
          const lsp = row.original;
          return (
            <EntityRowActions
              mode="inline"
              align="end"
              items={[
                {
                  id: "details",
                  label: "Details",
                  ariaLabel: `Details for ${lsp.code}`,
                  dataSlot: "lsps-details-button",
                  onSelect: () => onDetails(lsp),
                },
                {
                  id: "status",
                  label: "Status",
                  ariaLabel: `Change status of ${lsp.code}`,
                  dataSlot: "lsps-status-button",
                  onSelect: () => onChangeStatus(lsp),
                },
                {
                  id: "audit",
                  label: "Audit",
                  ariaLabel: `Audit trail for ${lsp.code}`,
                  variant: "ghost",
                  dataSlot: "lsps-audit-button",
                  onSelect: () => onViewAudit(lsp),
                },
              ]}
            />
          );
        },
      },
    ],
    [onDetails, onChangeStatus, onViewAudit],
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
      dataSlot="lsps-table"
      className={className}
      columns={columns}
      rows={rows}
      total={total}
      page={page}
      pageSize={pageSize}
      loading={isLoading}
      rowIdKey="id"
      ariaLabel="LSP tenants"
      onPaginationChange={handlePaginationChange}
      empty={
        <EmptyState
          variant="filtered-empty"
          title="No LSPs"
          description="Loosen the filters or create the first LSP tenant."
        />
      }
    />
  );
}
