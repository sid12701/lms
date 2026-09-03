/**
 * Compact-density preview table of the Portfolio MIS dataset.
 *
 * Density locked to "compact" per D7 (MIS preview is a long-list surface).
 * Server-paged: the parent owns `{page, pageSize}` filter state; pagination
 * change fires `onFiltersChange`.
 *
 * Gap #10 — the in-app preview shows a curated set of key portfolio columns.
 * Operators can generate a report request to download the full CSV with every
 * field the backend exports.
 */
import { useMemo } from "react";
import {
  type PaginationState,
  flexRender,
  getCoreRowModel,
  useReactTable,
} from "@tanstack/react-table";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { DataTablePagination } from "@/components/app/data/DataTablePagination";
import { TableSkeleton } from "@/components/app/feedback/Skeletons";
import { EmptyState } from "@/components/app/feedback/EmptyState";
import { TABULAR_ATTR } from "@/lib/tabular-nums";
import { cn } from "@/lib/utils";
import type { MisPreviewFilters, MisPreviewResponseDto, MisPreviewRow } from "../types";
import { buildMisPreviewSummaryColumns } from "./mis-preview-columns";

export interface MisPreviewTableProps {
  data: MisPreviewResponseDto | undefined;
  isLoading: boolean;
  filters: MisPreviewFilters;
  onFiltersChange: (next: MisPreviewFilters) => void;
  /** Opens the generate-report dialog for a full CSV export. */
  onRequestFullExport?: () => void;
  className?: string;
}

export function MisPreviewTable({
  data,
  isLoading,
  filters,
  onFiltersChange,
  onRequestFullExport,
  className,
}: MisPreviewTableProps) {
  const pageSize = filters.pageSize ?? 25;
  const pageIndex = filters.page ?? 0;

  const pagination: PaginationState = useMemo(
    () => ({ pageIndex, pageSize }),
    [pageIndex, pageSize],
  );

  const rows = useMemo(() => data?.items ?? [], [data?.items]);

  const columns = useMemo(() => buildMisPreviewSummaryColumns(), []);

  const table = useReactTable({
    data: rows as MisPreviewRow[],
    columns,
    state: { pagination },
    manualPagination: true,
    rowCount: data?.total ?? 0,
    getCoreRowModel: getCoreRowModel(),
    getRowId: (row) => row.loanId,
    onPaginationChange: (updater) => {
      const next = typeof updater === "function" ? updater(pagination) : updater;
      onFiltersChange({
        ...filters,
        page: next.pageIndex,
        pageSize: next.pageSize,
      });
    },
  });

  const tableRows = table.getRowModel().rows;
  const isEmpty = !isLoading && rows.length === 0;

  if (isLoading && rows.length === 0) {
    return (
      <div data-slot="mis-preview-table" className={cn("flex flex-col gap-3", className)}>
        <TableSkeleton rows={8} cols={6} />
      </div>
    );
  }

  if (isEmpty) {
    return (
      <div data-slot="mis-preview-table" className={cn("flex flex-col gap-3", className)}>
        <div
          data-slot="mis-preview-empty"
          className="border-border bg-surface rounded-container flex min-h-[220px] items-center justify-center border p-6"
        >
          <EmptyState
            variant="filtered-empty"
            title="No portfolio rows match these filters."
            description="Widen the date range or clear filters to see more."
          />
        </div>
        <DataTablePagination table={table} totalRows={0} className="px-1" />
      </div>
    );
  }

  return (
    <div data-slot="mis-preview-table" className={cn("flex flex-col gap-3", className)}>
      <p className="text-foreground-muted text-xs leading-5">
        Key portfolio columns only.{" "}
        {onRequestFullExport ? (
          <button
            type="button"
            onClick={onRequestFullExport}
            className="text-primary-tinted hover:text-primary-tinted/80 font-medium underline underline-offset-2"
          >
            Generate a report
          </button>
        ) : (
          "Generate a report"
        )}{" "}
        to download the full CSV with all fields.
      </p>
      {/* One bounded scroller, so the sticky header can resolve against it —
          same fix as `DataTable`. */}
      <div
        className="border-border bg-surface shadow-e1 rounded-container border"
        data-density="compact"
        data-slot="mis-preview-scroll"
      >
        <Table
          aria-label="Portfolio MIS preview"
          className="min-w-max"
          containerClassName="max-h-[calc(100dvh-var(--data-table-chrome,17rem))] overflow-y-auto"
        >
          <TableHeader className="bg-surface-muted sticky top-0 z-10">
            {table.getHeaderGroups().map((headerGroup) => (
              <TableRow key={headerGroup.id} className="border-border hover:bg-transparent">
                {headerGroup.headers.map((header) => {
                  const numeric = header.column.columnDef.meta?.numeric ?? false;
                  return (
                    <TableHead
                      key={header.id}
                      scope="col"
                      className={cn(
                        "text-foreground-muted h-8 px-2.5 text-xs font-medium",
                        numeric && "text-right",
                      )}
                    >
                      {header.isPlaceholder
                        ? null
                        : flexRender(header.column.columnDef.header, header.getContext())}
                    </TableHead>
                  );
                })}
              </TableRow>
            ))}
          </TableHeader>
          <TableBody>
            {tableRows.map((row) => (
              <TableRow
                key={row.id}
                data-testid={`mis-preview-row-${row.original.loanId}`}
                className="border-border"
              >
                {row.getVisibleCells().map((cell) => {
                  const numeric = cell.column.columnDef.meta?.numeric ?? false;
                  return (
                    <TableCell
                      key={cell.id}
                      {...(numeric ? TABULAR_ATTR : {})}
                      className={cn(
                        "text-foreground px-2.5 py-1.5 text-xs",
                        numeric && "text-right font-mono",
                      )}
                    >
                      {flexRender(cell.column.columnDef.cell, cell.getContext())}
                    </TableCell>
                  );
                })}
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>

      <DataTablePagination table={table} totalRows={data?.total ?? 0} className="px-1" />
    </div>
  );
}
