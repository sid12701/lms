/**
 * Compact-density preview table of the Portfolio MIS dataset.
 *
 * Density locked to "compact" per D7 (MIS preview is a long-list surface).
 * Server-paged: the parent owns `{page, pageSize}` filter state; pagination
 * change fires `onFiltersChange`.
 *
 * Gap #10 — the preview now exposes every column the BE returns. Identity +
 * status columns lead; financial + lifecycle dates follow; borrower KYC +
 * banking columns close out the row. Aadhaar values are pre-masked by the
 * BE (Gap #1 + Gap #10) and the column renders a defensive masker as a
 * second line of defence against a misconfigured upstream. The table
 * scrolls horizontally inside the preview card to keep the page chrome
 * stable.
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
import { buildMisPreviewColumns } from "./mis-preview-columns";

export interface MisPreviewTableProps {
  data: MisPreviewResponseDto | undefined;
  isLoading: boolean;
  filters: MisPreviewFilters;
  onFiltersChange: (next: MisPreviewFilters) => void;
  className?: string;
}

export function MisPreviewTable({
  data,
  isLoading,
  filters,
  onFiltersChange,
  className,
}: MisPreviewTableProps) {
  const pageSize = filters.pageSize ?? 25;
  const pageIndex = filters.page ?? 0;

  const pagination: PaginationState = useMemo(
    () => ({ pageIndex, pageSize }),
    [pageIndex, pageSize],
  );

  const rows = useMemo(() => data?.items ?? [], [data?.items]);

  const maxInstallments = useMemo(() => {
    let max = 0;
    for (const row of rows) {
      const count = row.installments?.length ?? 0;
      if (count > max) max = count;
    }
    return max;
  }, [rows]);

  const columns = useMemo(() => buildMisPreviewColumns(maxInstallments), [maxInstallments]);

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

  const visibleColumnCount = table.getVisibleLeafColumns().length;
  const tableRows = table.getRowModel().rows;
  const isEmpty = !isLoading && rows.length === 0;

  if (isLoading && rows.length === 0) {
    return (
      <div data-slot="mis-preview-table" className={cn("flex flex-col gap-3", className)}>
        <TableSkeleton rows={8} cols={12} />
      </div>
    );
  }

  return (
    <div data-slot="mis-preview-table" className={cn("flex flex-col gap-3", className)}>
      <div
        className="border-border bg-surface shadow-e1 overflow-x-auto rounded-md border"
        data-density="compact"
        data-slot="mis-preview-scroll"
      >
        <Table aria-label="Portfolio MIS preview" className="min-w-max">
          <TableHeader className="bg-surface-muted/60 sticky top-0 z-10 backdrop-blur">
            {table.getHeaderGroups().map((headerGroup) => (
              <TableRow key={headerGroup.id} className="border-border hover:bg-transparent">
                {headerGroup.headers.map((header) => {
                  const numeric = header.column.columnDef.meta?.numeric ?? false;
                  return (
                    <TableHead
                      key={header.id}
                      scope="col"
                      className={cn(
                        "text-foreground-muted h-8 px-2.5 text-[11px] font-medium tracking-wide uppercase",
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
            {isEmpty ? (
              <TableRow className="border-border hover:bg-transparent">
                <TableCell colSpan={visibleColumnCount} className="p-0">
                  <EmptyState
                    variant="filtered-empty"
                    title="No portfolio rows match these filters."
                    description="Widen the date range or clear filters to see more."
                  />
                </TableCell>
              </TableRow>
            ) : (
              tableRows.map((row) => (
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
              ))
            )}
          </TableBody>
        </Table>
      </div>

      <DataTablePagination table={table} totalRows={data?.total ?? 0} className="px-1" />
    </div>
  );
}
