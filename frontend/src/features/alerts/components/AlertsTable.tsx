/**
 * `AlertsTable` — TanStack-table view of the operational alerts inbox.
 *
 * Severity is colour-coded via a small intent-mapped `Badge`. The title
 * cell links to the alert's subject (loan application, borrower, audit
 * explorer, etc.). Each OPEN row exposes an "Acknowledge" action that
 * delegates to the parent via `onAcknowledge`.
 */
import { useCallback, useMemo } from "react";
import { Link } from "react-router-dom";
import type { ColumnDef, PaginationState } from "@tanstack/react-table";
import { AlertOctagon, AlertTriangle, CheckCircle2, Info } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { AdminEntityDataTable } from "@/components/app/data/AdminEntityDataTable";
import { EntityRowActions } from "@/components/app/data/EntityRowActions";
import { EmptyState } from "@/components/app/feedback/EmptyState";
import { formatDateTime, formatRelative } from "@/lib/format";
import { cn } from "@/lib/utils";
import { resolveAlertSubjectHref } from "@/lib/alert-links";
import type { AlertRow, AlertsListFilters, AlertsListResponse } from "../types";
import type { AlertSeverity } from "@/schemas/alert";

const SEVERITY_META: Record<
  AlertSeverity,
  { label: string; icon: typeof AlertOctagon; className: string }
> = {
  CRITICAL: {
    label: "Critical",
    icon: AlertOctagon,
    className: "border-danger/30 bg-danger/10 text-danger",
  },
  HIGH: {
    label: "High",
    icon: AlertTriangle,
    className: "border-warning/30 bg-warning/10 text-warning",
  },
  MEDIUM: {
    label: "Medium",
    icon: Info,
    className: "border-info/30 bg-info/10 text-info",
  },
  LOW: {
    label: "Low",
    icon: Info,
    className: "border-border bg-surface-muted text-foreground-muted",
  },
};

export interface AlertsTableProps {
  data: AlertsListResponse | undefined;
  isLoading: boolean;
  filters: AlertsListFilters;
  onFiltersChange: (next: AlertsListFilters) => void;
  onAcknowledge: (row: AlertRow) => void;
  className?: string;
}

export function AlertsTable({
  data,
  isLoading,
  filters,
  onFiltersChange,
  onAcknowledge,
  className,
}: AlertsTableProps) {
  const rows = data?.items ?? [];
  const total = data?.total ?? 0;
  const pageSize = filters.pageSize ?? 25;
  const page = filters.page ?? 0;

  const columns = useMemo<ColumnDef<AlertRow>[]>(
    () => [
      {
        id: "severity",
        header: "Severity",
        meta: { label: "Severity", mobileCard: "primary" },
        cell: ({ row }) => {
          const meta = SEVERITY_META[row.original.severity];
          const Icon = meta.icon;
          return (
            <Badge
              data-slot="alerts-severity-badge"
              data-severity={row.original.severity}
              className={cn("gap-1", meta.className)}
            >
              <Icon aria-hidden="true" className="size-3" />
              <span>{meta.label}</span>
            </Badge>
          );
        },
      },
      {
        id: "title",
        header: "Alert",
        meta: { label: "Alert", mobileCard: "primary" },
        cell: ({ row }) => {
          const a = row.original;
          const href = resolveAlertSubjectHref(
            a.subjectType,
            a.subjectId,
            a.correlationId,
            a.contextJson,
          );
          return (
            <div className="flex flex-col gap-0.5">
              <Link
                to={href}
                data-slot="alerts-title-link"
                className="text-foreground focus-visible:ring-ring/50 rounded-sm text-sm font-medium outline-none hover:underline focus-visible:underline focus-visible:ring-2"
              >
                {a.title}
              </Link>
              <p className="text-foreground-muted line-clamp-2 max-w-md text-xs">{a.message}</p>
            </div>
          );
        },
      },
      {
        id: "subject",
        header: "Subject",
        cell: ({ row }) => (
          <span className="text-foreground-muted text-xs tracking-wide uppercase">
            {row.original.subjectType.replace(/_/g, " ").toLowerCase()}
          </span>
        ),
      },
      {
        id: "created",
        header: "Created",
        meta: { label: "Created", mobileCard: "secondary" },
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
        id: "acknowledgement",
        header: "Status",
        meta: { label: "Status", mobileCard: "secondary" },
        cell: ({ row }) => {
          const a = row.original;
          if (a.status === "ACKNOWLEDGED") {
            return (
              <div className="flex flex-col gap-0.5 text-xs">
                <span className="text-success flex items-center gap-1">
                  <CheckCircle2 aria-hidden="true" className="size-3" />
                  Acknowledged
                </span>
                {a.acknowledgedByName ? (
                  <span className="text-foreground-muted">by {a.acknowledgedByName}</span>
                ) : null}
                {a.acknowledgmentNote ? (
                  <span className="text-foreground-muted line-clamp-2" title={a.acknowledgmentNote}>
                    {a.acknowledgmentNote}
                  </span>
                ) : null}
              </div>
            );
          }
          return <span className="text-warning text-xs font-medium">Open</span>;
        },
      },
      {
        id: "actions",
        header: () => <span className="sr-only">Actions</span>,
        meta: { mobileCard: "actions" },
        cell: ({ row }) => {
          const a = row.original;
          return (
            <EntityRowActions
              mode="inline"
              items={
                a.status === "ACKNOWLEDGED"
                  ? []
                  : [
                      {
                        id: "acknowledge",
                        label: "Acknowledge",
                        dataSlot: "alerts-acknowledge-button",
                        onSelect: () => onAcknowledge(a),
                      },
                    ]
              }
              emptyFallback={<span className="text-foreground-muted text-xs">—</span>}
            />
          );
        },
      },
    ],
    [onAcknowledge],
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
      dataSlot="alerts-table"
      className={className}
      columns={columns}
      rows={rows}
      total={total}
      page={page}
      pageSize={pageSize}
      loading={isLoading}
      rowIdKey="id"
      ariaLabel="Operational alerts"
      onPaginationChange={handlePaginationChange}
      empty={
        <EmptyState
          variant="filtered-empty"
          title="No alerts"
          description="Loosen the filters or check back once a new alert fires."
        />
      }
    />
  );
}
