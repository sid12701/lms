/**
 * `LspsTable` — TanStack-table view of the LSP admin list.
 *
 * Status is colour-coded via a small intent-mapped `Badge` (LSP status is a
 * separate enum from LoanStatus, so we don't reuse `StatusBadge` here).
 * Row actions: Details, Status, Audit, Webhook — delegated to the parent.
 */
import { useCallback, useMemo } from "react";
import type { ColumnDef, PaginationState } from "@tanstack/react-table";
import { CheckCircle2, MinusCircle, PauseCircle, Webhook } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { AdminEntityDataTable } from "@/components/app/data/AdminEntityDataTable";
import { EntityRowActions } from "@/components/app/data/EntityRowActions";
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
  onDetails: (row: LspRow) => void;
  onChangeStatus: (row: LspRow) => void;
  onViewAudit: (row: LspRow) => void;
  onEditWebhook: (row: LspRow) => void;
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
          <span data-slot="lsps-code" className="text-foreground font-mono text-sm font-medium">
            {row.original.code}
          </span>
        ),
      },
      {
        id: "name",
        header: "Name",
        cell: ({ row }) => <span className="text-foreground text-sm">{row.original.name}</span>,
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
          return <span className="text-foreground-muted text-xs">Not configured</span>;
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
                  dataSlot: "lsps-details-button",
                  onSelect: () => onDetails(lsp),
                },
                {
                  id: "status",
                  label: "Status",
                  dataSlot: "lsps-status-button",
                  onSelect: () => onChangeStatus(lsp),
                },
                {
                  id: "audit",
                  label: "Audit",
                  variant: "ghost",
                  dataSlot: "lsps-audit-button",
                  onSelect: () => onViewAudit(lsp),
                },
                {
                  id: "webhook",
                  label: "Webhook",
                  variant: "ghost",
                  dataSlot: "lsps-webhook-button",
                  onSelect: () => onEditWebhook(lsp),
                },
              ]}
            />
          );
        },
      },
    ],
    [onDetails, onChangeStatus, onViewAudit, onEditWebhook],
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
