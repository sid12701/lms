import { ChevronLeft, ChevronRight, ChevronsLeft, ChevronsRight } from "lucide-react";
import type { PaginationState, Table } from "@tanstack/react-table";
import { Button } from "@/components/ui/button";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { cn } from "@/lib/utils";

type DataTablePaginationBaseProps = {
  /** Override the default `[10, 25, 50, 100]` page-size choices. */
  pageSizeOptions?: number[];
  /** When true, the range label shows a loading state instead of "No rows". */
  loading?: boolean;
  className?: string;
};

export type DataTablePaginationProps<TData> = DataTablePaginationBaseProps &
  (
    | {
        table: Table<TData>;
        /** Total row count when the table uses `manualPagination`. */
        totalRows?: number;
        pageIndex?: never;
        pageSize?: never;
        onPaginationChange?: never;
      }
    | {
        table?: never;
        pageIndex: number;
        pageSize: number;
        totalRows: number;
        onPaginationChange: (next: PaginationState) => void;
      }
  );

const DEFAULT_PAGE_SIZES = [10, 25, 50, 100] as const;

/**
 * Footer pagination control: page-size select, "Showing N–M of T" label,
 * first/prev/next/last buttons. Works with both client-side pagination
 * (default) and `manualPagination` (pass `totalRows` for the label).
 */
export function DataTablePagination<TData>(props: DataTablePaginationProps<TData>) {
  const { pageSizeOptions = [...DEFAULT_PAGE_SIZES], loading = false, className } = props;

  const pageIndex = props.table ? props.table.getState().pagination.pageIndex : props.pageIndex;
  const pageSize = props.table ? props.table.getState().pagination.pageSize : props.pageSize;
  const total =
    typeof props.totalRows === "number"
      ? props.totalRows
      : props.table
        ? props.table.getFilteredRowModel().rows.length
        : 0;
  const pageCount = Math.max(
    1,
    props.table && typeof props.totalRows !== "number"
      ? props.table.getPageCount()
      : Math.ceil(total / pageSize) || 1,
  );
  const canPreviousPage = pageIndex > 0;
  const canNextPage = pageIndex < pageCount - 1;

  const setPageIndex = (nextIndex: number) => {
    if (props.table) {
      props.table.setPageIndex(nextIndex);
      return;
    }
    props.onPaginationChange({ pageIndex: nextIndex, pageSize });
  };

  const setPageSize = (nextSize: number) => {
    if (props.table) {
      props.table.setPageSize(nextSize);
      return;
    }
    props.onPaginationChange({ pageIndex: 0, pageSize: nextSize });
  };

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
        <Select value={String(pageSize)} onValueChange={(value) => setPageSize(Number(value))}>
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
        {loading
          ? "Loading…"
          : total === 0
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
          onClick={() => setPageIndex(0)}
          disabled={!canPreviousPage}
          aria-label="Go to first page"
          data-slot="page-first"
        >
          <ChevronsLeft aria-hidden="true" />
        </Button>
        <Button
          type="button"
          variant="outline"
          size="icon-sm"
          onClick={() => setPageIndex(pageIndex - 1)}
          disabled={!canPreviousPage}
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
          onClick={() => setPageIndex(pageIndex + 1)}
          disabled={!canNextPage}
          aria-label="Go to next page"
          data-slot="page-next"
        >
          <ChevronRight aria-hidden="true" />
        </Button>
        <Button
          type="button"
          variant="outline"
          size="icon-sm"
          onClick={() => setPageIndex(pageCount - 1)}
          disabled={!canNextPage}
          aria-label="Go to last page"
          data-slot="page-last"
        >
          <ChevronsRight aria-hidden="true" />
        </Button>
      </div>
    </div>
  );
}
