/**
 * Compact-density audit log table.
 *
 * Renders the flattened `AuditRow` stream: timestamp · stream pill · actor
 * · headline · subject link · correlationId (truncated, with copy).
 *
 * Selecting a row fires `onSelect(row)` — the page wires this into the
 * detail sheet. Density is locked to "compact" per the locked decision D7
 * (long-list surfaces use compact density by default).
 *
 * Loading / empty / error states share the table's outer container so the
 * surrounding page does not jitter as data swaps in.
 */
import { useMemo } from "react";
import { Copy, ExternalLink, ScrollText } from "lucide-react";
import {
  type ColumnDef,
  type PaginationState,
  flexRender,
  getCoreRowModel,
  useReactTable,
} from "@tanstack/react-table";
import { formatDistanceToNow } from "date-fns";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { DataTablePagination } from "@/components/app/data/DataTablePagination";
import { TableSkeleton } from "@/components/app/feedback/Skeletons";
import { EmptyState } from "@/components/app/feedback/EmptyState";
import { TABULAR_ATTR } from "@/lib/tabular-nums";
import { cn } from "@/lib/utils";
import {
  AUDIT_STREAM_LABEL,
  type AuditEventsFilters,
  type AuditEventsResponse,
  type AuditRow,
  type AuditStream,
} from "../types";

function safeRelative(iso: string): string {
  try {
    return formatDistanceToNow(new Date(iso), { addSuffix: true });
  } catch {
    return iso;
  }
}

function truncateMiddle(value: string, head = 6, tail = 4): string {
  if (value.length <= head + tail + 1) return value;
  return `${value.slice(0, head)}…${value.slice(-tail)}`;
}

const STREAM_BADGE_TONE: Record<AuditStream, string> = {
  APPLICATION: "bg-info/15 text-info-foreground border-info/40",
  INTAKE: "bg-success/15 text-success-foreground border-success/40",
  PII_REVEAL: "bg-destructive/15 text-destructive border-destructive/40",
  DOCUMENT_ACCESS: "bg-warning/15 text-warning-foreground border-warning/40",
  PRODUCT: "bg-muted text-foreground-muted border-border",
};

function subjectHref(row: AuditRow): string | null {
  if (!row.subjectId) return null;
  switch (row.subjectType) {
    case "LOAN_APPLICATION":
      return `/loan-applications/${row.subjectId}`;
    case "BORROWER":
      return `/borrowers/${row.subjectId}`;
    case "LOAN_PRODUCT":
      return `/admin/products/${row.subjectId}`;
    case "LOAN_DOCUMENT":
      // Documents live under their application — there is no standalone
      // doc page yet; the sheet still exposes the raw id for cross-ref.
      return null;
    default:
      return null;
  }
}

function subjectLabel(row: AuditRow): string {
  if (!row.subjectId) return "—";
  switch (row.subjectType) {
    case "LOAN_APPLICATION":
      return `Application ${truncateMiddle(row.subjectId)}`;
    case "BORROWER":
      return `Borrower ${truncateMiddle(row.subjectId)}`;
    case "LOAN_PRODUCT":
      return `Product ${truncateMiddle(row.subjectId)}`;
    case "LOAN_DOCUMENT":
      return `Document ${truncateMiddle(row.subjectId)}`;
    default:
      return truncateMiddle(row.subjectId);
  }
}

async function copyToClipboard(value: string): Promise<void> {
  try {
    if (typeof navigator !== "undefined" && navigator.clipboard) {
      await navigator.clipboard.writeText(value);
    }
  } catch {
    // Clipboard rejection is non-fatal — the value remains in the cell.
  }
}

export interface AuditTableProps {
  data: AuditEventsResponse | undefined;
  isLoading: boolean;
  filters: AuditEventsFilters;
  onFiltersChange: (next: AuditEventsFilters) => void;
  onSelect: (row: AuditRow) => void;
  className?: string;
}

export function AuditTable({
  data,
  isLoading,
  filters,
  onFiltersChange,
  onSelect,
  className,
}: AuditTableProps) {
  const pageSize = filters.pageSize ?? 50;
  const pageIndex = filters.page ?? 0;

  const pagination: PaginationState = useMemo(
    () => ({ pageIndex, pageSize }),
    [pageIndex, pageSize],
  );

  const columns = useMemo<ColumnDef<AuditRow>[]>(
    () => [
      {
        accessorKey: "createdAt",
        meta: { label: "When" },
        header: () => <span>When</span>,
        cell: ({ row }) => (
          <span
            className="text-foreground-muted text-xs tabular-nums"
            title={row.original.createdAt}
          >
            {safeRelative(row.original.createdAt)}
          </span>
        ),
      },
      {
        accessorKey: "stream",
        meta: { label: "Stream" },
        header: () => <span>Stream</span>,
        cell: ({ row }) => (
          <Badge
            variant="outline"
            className={cn("text-[10px] font-medium", STREAM_BADGE_TONE[row.original.stream])}
          >
            {AUDIT_STREAM_LABEL[row.original.stream]}
          </Badge>
        ),
      },
      {
        accessorKey: "actorName",
        meta: { label: "Actor" },
        header: () => <span>Actor</span>,
        cell: ({ row }) => (
          <div className="flex flex-col">
            <span className="text-foreground text-xs">{row.original.actorName}</span>
            {row.original.actorRole ? (
              <span className="text-foreground-muted text-[10px]">{row.original.actorRole}</span>
            ) : null}
          </div>
        ),
      },
      {
        accessorKey: "headline",
        meta: { label: "Event" },
        header: () => <span>Event</span>,
        cell: ({ row }) => <span className="text-foreground text-xs">{row.original.headline}</span>,
      },
      {
        id: "subject",
        meta: { label: "Subject" },
        header: () => <span>Subject</span>,
        cell: ({ row }) => {
          const href = subjectHref(row.original);
          const label = subjectLabel(row.original);
          if (!href) {
            return <span className="text-foreground-muted text-xs">{label}</span>;
          }
          return (
            <a
              href={href}
              onClick={(e) => e.stopPropagation()}
              className="text-foreground hover:text-primary inline-flex items-center gap-1 text-xs underline-offset-2 hover:underline"
            >
              <span>{label}</span>
              <ExternalLink aria-hidden="true" className="size-3" />
            </a>
          );
        },
      },
      {
        accessorKey: "correlationId",
        meta: { label: "Correlation" },
        header: () => <span>Correlation</span>,
        cell: ({ row }) => (
          <div className="flex items-center gap-1">
            <span
              className="text-foreground-muted font-mono text-[11px]"
              title={row.original.correlationId}
            >
              {truncateMiddle(row.original.correlationId)}
            </span>
            <Button
              type="button"
              variant="ghost"
              size="icon"
              data-slot="audit-copy-correlation"
              aria-label={`Copy correlation id ${row.original.correlationId}`}
              onClick={(event) => {
                event.stopPropagation();
                void copyToClipboard(row.original.correlationId);
              }}
              className="size-6 p-0"
            >
              <Copy aria-hidden="true" className="size-3" />
            </Button>
          </div>
        ),
      },
    ],
    [],
  );

  const rows = data?.items ?? [];

  const table = useReactTable({
    data: rows as AuditRow[],
    columns,
    state: { pagination },
    manualPagination: true,
    rowCount: data?.total ?? 0,
    getCoreRowModel: getCoreRowModel(),
    getRowId: (row) => row.id,
    onPaginationChange: (updater) => {
      const next = typeof updater === "function" ? updater(pagination) : updater;
      onFiltersChange({
        ...filters,
        page: next.pageIndex,
        pageSize: next.pageSize,
      });
    },
  });

  const visibleColumnCount = table.getVisibleLeafColumns().length;
  const tableRows = table.getRowModel().rows;
  const isEmpty = !isLoading && rows.length === 0;

  if (isLoading && rows.length === 0) {
    return (
      <div data-slot="audit-table" className={cn("flex flex-col gap-3", className)}>
        <TableSkeleton rows={8} cols={6} />
      </div>
    );
  }

  return (
    <div data-slot="audit-table" className={cn("flex flex-col gap-3", className)}>
      <div
        className="border-border bg-surface shadow-e1 overflow-hidden rounded-md border"
        data-density="compact"
      >
        <Table aria-label="Audit log">
          <TableHeader className="bg-surface-muted/60 sticky top-0 z-10 backdrop-blur">
            {table.getHeaderGroups().map((headerGroup) => (
              <TableRow key={headerGroup.id} className="border-border hover:bg-transparent">
                {headerGroup.headers.map((header) => (
                  <TableHead
                    key={header.id}
                    scope="col"
                    className="text-foreground-muted h-8 px-2.5 text-[11px] font-medium tracking-wide uppercase"
                  >
                    {header.isPlaceholder
                      ? null
                      : flexRender(header.column.columnDef.header, header.getContext())}
                  </TableHead>
                ))}
              </TableRow>
            ))}
          </TableHeader>
          <TableBody>
            {isEmpty ? (
              <TableRow className="border-border hover:bg-transparent">
                <TableCell colSpan={visibleColumnCount} className="p-0">
                  <EmptyState
                    variant="filtered-empty"
                    icon={ScrollText}
                    title="No audit events match these filters."
                    description="Widen the date range or clear filters to see more."
                  />
                </TableCell>
              </TableRow>
            ) : (
              tableRows.map((row) => (
                <TableRow
                  key={row.id}
                  tabIndex={0}
                  role="button"
                  aria-label={`Open audit event ${row.original.id}`}
                  data-testid={`audit-row-${row.original.id}`}
                  onClick={() => onSelect(row.original)}
                  onKeyDown={(event) => {
                    if (event.key === "Enter" || event.key === " ") {
                      event.preventDefault();
                      onSelect(row.original);
                    }
                  }}
                  className="border-border focus-visible:ring-ring/50 cursor-pointer outline-none focus-visible:ring-2"
                >
                  {row.getVisibleCells().map((cell) => (
                    <TableCell
                      key={cell.id}
                      {...TABULAR_ATTR}
                      className="text-foreground px-2.5 py-1.5 text-xs"
                    >
                      {flexRender(cell.column.columnDef.cell, cell.getContext())}
                    </TableCell>
                  ))}
                </TableRow>
              ))
            )}
          </TableBody>
        </Table>
      </div>

      <DataTablePagination table={table} totalRows={data?.total ?? 0} className="px-1" />
    </div>
  );
}
