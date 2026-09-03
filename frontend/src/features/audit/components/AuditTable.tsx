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
import { type ColumnDef, flexRender, getCoreRowModel, useReactTable } from "@tanstack/react-table";
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
import { AbsoluteRelativeTime } from "@/components/app/misc/AbsoluteRelativeTime";
import { TableSkeleton } from "@/components/app/feedback/Skeletons";
import { EmptyState } from "@/components/app/feedback/EmptyState";
import { TABULAR_ATTR } from "@/lib/tabular-nums";
import { cn } from "@/lib/utils";
import {
  AUDIT_STREAM_BADGE_TONE,
  AUDIT_STREAM_LABEL,
  type AuditEventsResponse,
  type AuditRow,
} from "../types";

function truncateMiddle(value: string, head = 6, tail = 4): string {
  if (value.length <= head + tail + 1) return value;
  return `${value.slice(0, head)}…${value.slice(-tail)}`;
}

function subjectHref(row: AuditRow): string | null {
  if (!row.subjectId) return null;
  switch (row.subjectType) {
    case "LOAN_APPLICATION":
      return `/loan-applications/${row.subjectId}`;
    case "BORROWER":
      return `/borrowers/${row.subjectId}`;
    case "LOAN_PRODUCT":
      return `/admin/products/${row.subjectId}`;
    case "APP_USER":
      return `/users`;
    case "API_CLIENT":
      return `/api-clients`;
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
    case "APP_USER":
      return `User ${truncateMiddle(row.subjectId)}`;
    case "API_CLIENT":
      return `Client ${truncateMiddle(row.subjectId)}`;
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
  onSelect: (row: AuditRow) => void;
  onLoadMore?: () => void;
  className?: string;
}

export function AuditTable({ data, isLoading, onSelect, onLoadMore, className }: AuditTableProps) {
  const columns = useMemo<ColumnDef<AuditRow>[]>(
    () => [
      {
        accessorKey: "createdAt",
        meta: { label: "When" },
        header: () => <span>When</span>,
        cell: ({ row }) => (
          // An audit log is forensic evidence: the instant is the datum, not
          // decoration, so this column carries the absolute reading rather
          // than hiding it in a `title` that held a raw ISO string.
          <AbsoluteRelativeTime iso={row.original.createdAt} compact />
        ),
      },
      {
        accessorKey: "stream",
        meta: { label: "Stream" },
        header: () => <span>Stream</span>,
        cell: ({ row }) => (
          <Badge
            variant="outline"
            className={cn("text-badge font-medium", AUDIT_STREAM_BADGE_TONE[row.original.stream])}
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
              <span className="text-foreground-muted text-badge">{row.original.actorRole}</span>
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
              // `min-h-6` keeps the link on the 24px target floor (WCAG 2.5.8);
              // at its natural 16px line box it failed both the size and the
              // spacing halves of that criterion.
              className="text-foreground hover:text-primary-tinted inline-flex min-h-6 items-center gap-1 text-xs underline-offset-2 hover:underline"
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
              className="text-foreground-muted font-mono text-xs"
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
    getCoreRowModel: getCoreRowModel(),
    getRowId: (row) => row.id,
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
        className="border-border bg-surface shadow-e1 rounded-container overflow-hidden border"
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
                    className="text-foreground-muted h-8 px-2.5 text-xs font-medium"
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
                  // Deliberately no `role="button"`. It made the row an
                  // interactive element wrapping a link and a copy button, which
                  // is a `nested-interactive` violation (WCAG 4.1.2) on all 25
                  // rows, and it also stripped the row of its `row` semantics so
                  // cells lost their column associations. This matches the
                  // shared `DataTable`, which keeps the implicit row role and is
                  // covered by a regression test asserting exactly that.
                  aria-label={`Open audit event ${row.original.headline}`}
                  data-testid={`audit-row-${row.original.id}`}
                  onClick={() => onSelect(row.original)}
                  onKeyDown={(event) => {
                    if (event.key === "Enter" || event.key === " ") {
                      event.preventDefault();
                      onSelect(row.original);
                    }
                  }}
                  className="border-border focus-visible:ring-ring cursor-pointer outline-none focus-visible:ring-2"
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

      {onLoadMore ? (
        <div className="flex justify-center px-1 pt-3">
          <Button
            type="button"
            variant="outline"
            size="sm"
            onClick={onLoadMore}
            disabled={isLoading}
          >
            Load more
          </Button>
        </div>
      ) : null}
    </div>
  );
}
