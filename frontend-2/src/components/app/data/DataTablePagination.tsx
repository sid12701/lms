import { ChevronLeft, ChevronRight, ChevronsLeft, ChevronsRight } from "lucide-react";
import type { Table } from "@tanstack/react-table";
import { Button } from "@/components/ui/button";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { cn } from "@/lib/utils";

export interface DataTablePaginationProps<TData> {
  table: Table<TData>;
  /** Override the default `[10, 25, 50, 100]` page-size choices. */
  pageSizeOptions?: number[];
  /** Total row count if the table is in `manualPagination` mode. */
  totalRows?: number;
  className?: string;
}

const DEFAULT_PAGE_SIZES = [10, 25, 50, 100] as const;

/**
 * Footer pagination control: page-size select, "Showing N–M of T" label,
 * first/prev/next/last buttons. Works with both client-side pagination
 * (default) and `manualPagination` (pass `totalRows` for the label).
 */
export function DataTablePagination<TData>({
  table,
  pageSizeOptions = [...DEFAULT_PAGE_SIZES],
  totalRows,
  className,
}: DataTablePaginationProps<TData>) {
  const pageIndex = table.getState().pagination.pageIndex;
  const pageSize = table.getState().pagination.pageSize;
  const total = typeof totalRows === "number" ? totalRows : table.getFilteredRowModel().rows.length;
  const pageCount = Math.max(
    1,
    typeof totalRows === "number" ? Math.ceil(totalRows / pageSize) : table.getPageCount(),
  );

  const startRow = total === 0 ? 0 : pageIndex * pageSize + 1;
  const endRow = Math.min(total, (pageIndex + 1) * pageSize);

  return (
    <div
      data-slot="data-table-pagination"
      className={cn(
        "flex flex-col items-stretch justify-between gap-3 py-2 sm:flex-row sm:items-center",
        className,
      )}
    >
      <div className="flex items-center gap-2 text-sm">
        <label htmlFor="data-table-page-size" className="text-foreground-muted hidden sm:inline">
          Rows per page
        </label>
        <Select
          value={String(pageSize)}
          onValueChange={(value) => table.setPageSize(Number(value))}
        >
          <SelectTrigger
            id="data-table-page-size"
            size="sm"
            data-slot="page-size-trigger"
            className="w-20"
            aria-label="Rows per page"
          >
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {pageSizeOptions.map((size) => (
              <SelectItem key={size} value={String(size)}>
                {size}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      <p
        data-slot="page-range-label"
        className="text-foreground-muted text-center text-sm tabular-nums sm:text-left"
      >
        {total === 0
          ? "No rows"
          : `Showing ${startRow.toLocaleString("en-IN")}–${endRow.toLocaleString(
              "en-IN",
            )} of ${total.toLocaleString("en-IN")}`}
      </p>

      <div className="flex items-center justify-end gap-1">
        <Button
          type="button"
          variant="outline"
          size="icon-sm"
          onClick={() => table.setPageIndex(0)}
          disabled={!table.getCanPreviousPage()}
          aria-label="Go to first page"
          data-slot="page-first"
        >
          <ChevronsLeft aria-hidden="true" />
        </Button>
        <Button
          type="button"
          variant="outline"
          size="icon-sm"
          onClick={() => table.previousPage()}
          disabled={!table.getCanPreviousPage()}
          aria-label="Go to previous page"
          data-slot="page-prev"
        >
          <ChevronLeft aria-hidden="true" />
        </Button>
        <span
          data-slot="page-counter"
          aria-live="polite"
          className="text-foreground-muted px-2 text-sm tabular-nums"
        >
          {pageIndex + 1} / {pageCount}
        </span>
        <Button
          type="button"
          variant="outline"
          size="icon-sm"
          onClick={() => table.nextPage()}
          disabled={!table.getCanNextPage()}
          aria-label="Go to next page"
          data-slot="page-next"
        >
          <ChevronRight aria-hidden="true" />
        </Button>
        <Button
          type="button"
          variant="outline"
          size="icon-sm"
          onClick={() => table.setPageIndex(pageCount - 1)}
          disabled={!table.getCanNextPage()}
          aria-label="Go to last page"
          data-slot="page-last"
        >
          <ChevronsRight aria-hidden="true" />
        </Button>
      </div>
    </div>
  );
}
