/**
 * `AlertsTable` — TanStack-table view of the operational alerts inbox.
 *
 * Severity is colour-coded via a small intent-mapped `Badge`. The title
 * cell links to the alert's subject (loan application, borrower, audit
 * explorer, etc.). Each OPEN row exposes an "Acknowledge" action that
 * delegates to the parent via `onAcknowledge`.
 */
import { useMemo } from "react";
import { Link } from "react-router-dom";
import {
  getCoreRowModel,
  useReactTable,
  type ColumnDef,
  type PaginationState,
} from "@tanstack/react-table";
import {
  AlertOctagon,
  AlertTriangle,
  CheckCircle2,
  Info,
} from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { DataTable } from "@/components/app/data/DataTable";
import { DataTablePagination } from "@/components/app/data/DataTablePagination";
import { EmptyState } from "@/components/app/feedback/EmptyState";
import { formatDateTime, formatRelative } from "@/lib/format";
import { cn } from "@/lib/utils";
import type { AlertRow, AlertsListFilters, AlertsListResponse } from "../types";
import type { AlertSeverity, AlertSubjectType } from "@/schemas/alert";

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

export function resolveAlertSubjectHref(
  subjectType: AlertSubjectType,
  subjectId: string,
  correlationId: string,
): string {
  switch (subjectType) {
    case "LOAN_APPLICATION":
      return `/loan-applications/${subjectId}`;
    case "LOAN_ACCOUNT":
      // No dedicated route yet — fall back to the audit explorer.
      return `/audit?correlationId=${encodeURIComponent(correlationId)}`;
    case "BORROWER":
      return `/borrowers/${subjectId}`;
    case "WEBHOOK_DELIVERY":
    case "REPORT_REQUEST":
    case "SYSTEM":
    default:
      return `/audit?correlationId=${encodeURIComponent(correlationId)}`;
  }
}

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
        cell: ({ row }) => {
          const a = row.original;
          const href = resolveAlertSubjectHref(
            a.subjectType,
            a.subjectId,
            a.correlationId,
          );
          return (
            <div className="flex flex-col gap-0.5">
              <Link
                to={href}
                data-slot="alerts-title-link"
                className="text-foreground hover:underline focus-visible:underline outline-none focus-visible:ring-2 focus-visible:ring-ring/50 rounded-sm text-sm font-medium"
              >
                {a.title}
              </Link>
              <p className="text-foreground-muted line-clamp-2 max-w-md text-xs">
                {a.message}
              </p>
            </div>
          );
        },
      },
      {
        id: "subject",
        header: "Subject",
        cell: ({ row }) => (
          <span className="text-foreground-muted text-xs uppercase tracking-wide">
            {row.original.subjectType.replace(/_/g, " ").toLowerCase()}
          </span>
        ),
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
        id: "acknowledgement",
        header: "Status",
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
                  <span className="text-foreground-muted">
                    by {a.acknowledgedByName}
                  </span>
                ) : null}
              </div>
            );
          }
          return (
            <span className="text-warning text-xs font-medium">Open</span>
          );
        },
      },
      {
        id: "actions",
        header: () => <span className="sr-only">Actions</span>,
        cell: ({ row }) => {
          const a = row.original;
          if (a.status === "ACKNOWLEDGED") {
            return (
              <span className="text-foreground-muted text-xs">—</span>
            );
          }
          return (
            <Button
              type="button"
              variant="outline"
              size="sm"
              data-slot="alerts-acknowledge-button"
              onClick={() => onAcknowledge(a)}
            >
              Acknowledge
            </Button>
          );
        },
      },
    ],
    [onAcknowledge],
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
    <div data-slot="alerts-table" className={cn("flex flex-col gap-2", className)}>
      <DataTable<AlertRow, unknown>
        columns={columns}
        data={rows}
        loading={isLoading}
        rowIdKey="id"
        ariaLabel="Operational alerts"
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
            title="No alerts"
            description="Loosen the filters or check back once a new alert fires."
          />
        }
      />
      <DataTablePagination table={table} totalRows={total} />
    </div>
  );
}
