import {
  flexRender,
  getCoreRowModel,
  getFilteredRowModel,
  getPaginationRowModel,
  getSortedRowModel,
  useReactTable,
  type ColumnDef,
  type ColumnFiltersState,
  type OnChangeFn,
  type PaginationState,
  type Row,
  type RowData,
  type SortingState,
  type Table as ReactTable,
  type TableOptions,
  type VisibilityState,
} from "@tanstack/react-table";
import { type KeyboardEvent, type ReactNode, useMemo } from "react";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { Skeleton } from "@/components/ui/skeleton";
import { useDensity } from "@/app/providers";
import { TABULAR_ATTR } from "@/lib/tabular-nums";
import { cn } from "@/lib/utils";

/** Augment ColumnMeta so consumers can flag numeric/sticky behaviour. */
declare module "@tanstack/react-table" {
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  interface ColumnMeta<TData extends RowData, TValue> {
    /** Right-aligns the cell + applies tabular-nums via `data-tabular`. */
    numeric?: boolean;
    /** Display label used by `DataTableViewOptions` toggle list. */
    label?: string;
  }
}

export interface DataTableState {
  sorting?: SortingState;
  columnFilters?: ColumnFiltersState;
  columnVisibility?: VisibilityState;
  pagination?: PaginationState;
}

export interface DataTableStateChange {
  sorting?: SortingState;
  columnFilters?: ColumnFiltersState;
  columnVisibility?: VisibilityState;
  pagination?: PaginationState;
}

export type DataTableDensity = "comfortable" | "compact";

export interface DataTablePaginationConfig {
  /** Set when paginating server-side. */
  manualPagination?: boolean;
  /** Total rows available across all pages (for manual pagination). */
  rowCount?: number;
  /** Default initial pagination state. */
  initialPageSize?: number;
}

export interface DataTableProps<TData, TValue> {
  /** Column definitions. */
  columns: ColumnDef<TData, TValue>[];
  /** Row data for the current page. */
  data: readonly TData[];
  /** Optional controlled state (sorting / filters / visibility / pagination). */
  state?: DataTableState;
  /** Callback for any controlled state change. */
  onStateChange?: (next: DataTableStateChange) => void;
  /** Pagination config — when omitted the table paginates client-side. */
  pagination?: DataTablePaginationConfig;
  /** Override the density token (default: from `useDensity()`). */
  density?: DataTableDensity;
  /** Show a skeleton overlay instead of the body. */
  loading?: boolean;
  /** Skeleton row count when `loading` is true. */
  skeletonRows?: number;
  /** Render-prop / node shown when `data` is empty. */
  empty?: ReactNode;
  /** Stable id for selection / aria attributes. */
  rowIdKey?: keyof TData;
  /** When provided, rows become focusable; Enter/Space fires the action. */
  getRowAction?: (row: Row<TData>) => void;
  /** Optional `data-testid` per row (e.g. triage tables). */
  getRowTestId?: (row: Row<TData>) => string | undefined;
  /** Optional accessible name when the row is interactive. */
  getRowAriaLabel?: (row: Row<TData>) => string | undefined;
  /** Server-driven sort; omit client `getSortedRowModel`. */
  manualSorting?: boolean;
  /** Optional caption rendered for screen readers. */
  ariaLabel?: string;
  /** Header className override. */
  headerClassName?: string;
  className?: string;
}

/**
 * Generic typed wrapper around TanStack Table 8 that renders shadcn `<Table>`
 * primitives. Supports controlled or uncontrolled state, manual or
 * client-side pagination, density-aware row padding, sticky header, tabular
 * numerics on numeric columns, and an optional row click/keyboard action.
 */
export function DataTable<TData, TValue>({
  columns,
  data,
  state,
  onStateChange,
  pagination,
  density: densityProp,
  loading = false,
  skeletonRows = 5,
  empty,
  rowIdKey,
  getRowAction,
  getRowTestId,
  getRowAriaLabel,
  manualSorting = false,
  ariaLabel,
  headerClassName,
  className,
}: DataTableProps<TData, TValue>) {
  const densityCtx = useDensity();
  const density = densityProp ?? densityCtx.density;

  const sorting = state?.sorting;
  const columnFilters = state?.columnFilters;
  const columnVisibility = state?.columnVisibility;
  const paginationState = state?.pagination;

  const onSortingChange: OnChangeFn<SortingState> | undefined = onStateChange
    ? (updater) => {
        const next = typeof updater === "function" ? updater(sorting ?? []) : updater;
        onStateChange({ sorting: next });
      }
    : undefined;
  const onColumnFiltersChange: OnChangeFn<ColumnFiltersState> | undefined = onStateChange
    ? (updater) => {
        const next = typeof updater === "function" ? updater(columnFilters ?? []) : updater;
        onStateChange({ columnFilters: next });
      }
    : undefined;
  const onColumnVisibilityChange: OnChangeFn<VisibilityState> | undefined = onStateChange
    ? (updater) => {
        const next = typeof updater === "function" ? updater(columnVisibility ?? {}) : updater;
        onStateChange({ columnVisibility: next });
      }
    : undefined;
  const onPaginationChange: OnChangeFn<PaginationState> | undefined = onStateChange
    ? (updater) => {
        const current = paginationState ?? {
          pageIndex: 0,
          pageSize: pagination?.initialPageSize ?? 25,
        };
        const next = typeof updater === "function" ? updater(current) : updater;
        onStateChange({ pagination: next });
      }
    : undefined;

  const tableOptions: TableOptions<TData> = {
    data: [...data],
    columns,
    state: {
      ...(sorting ? { sorting } : {}),
      ...(columnFilters ? { columnFilters } : {}),
      ...(columnVisibility ? { columnVisibility } : {}),
      ...(paginationState ? { pagination: paginationState } : {}),
    },
    initialState: paginationState
      ? {}
      : { pagination: { pageIndex: 0, pageSize: pagination?.initialPageSize ?? 25 } },
    getRowId: rowIdKey
      ? (row, index) => {
          const v = row[rowIdKey];
          return typeof v === "string" || typeof v === "number" ? String(v) : String(index);
        }
      : undefined,
    getCoreRowModel: getCoreRowModel(),
    ...(manualSorting ? {} : { getSortedRowModel: getSortedRowModel() }),
    getFilteredRowModel: getFilteredRowModel(),
    manualSorting,
    getPaginationRowModel: pagination?.manualPagination ? undefined : getPaginationRowModel(),
    manualPagination: pagination?.manualPagination ?? false,
    rowCount: pagination?.rowCount,
    ...(onSortingChange ? { onSortingChange } : {}),
    ...(onColumnFiltersChange ? { onColumnFiltersChange } : {}),
    ...(onColumnVisibilityChange ? { onColumnVisibilityChange } : {}),
    ...(onPaginationChange ? { onPaginationChange } : {}),
  };

  const table = useReactTable(tableOptions);
  const rows = table.getRowModel().rows;

  const cellPad = density === "compact" ? "px-2.5 py-1.5" : "px-3 py-3";
  const headPad = density === "compact" ? "h-8 px-2.5" : "h-10 px-3";
  const visibleColumnCount = useMemo(() => table.getVisibleLeafColumns().length, [table]);

  return (
    <div
      data-slot="data-table"
      data-density={density}
      className={cn(
        "border-border bg-surface shadow-e1 overflow-x-auto rounded-md border",
        className,
      )}
    >
      <Table aria-label={ariaLabel}>
        <TableHeader
          data-slot="data-table-header"
          className={cn("bg-surface-muted/60 sticky top-0 z-10 backdrop-blur", headerClassName)}
        >
          {table.getHeaderGroups().map((headerGroup) => (
            <TableRow key={headerGroup.id} className="border-border hover:bg-transparent">
              {headerGroup.headers.map((header) => {
                const meta = header.column.columnDef.meta;
                const numeric = meta?.numeric ?? false;
                const sorted = header.column.getIsSorted();
                const ariaSort: "ascending" | "descending" | "none" | undefined =
                  header.column.getCanSort()
                    ? sorted === "asc"
                      ? "ascending"
                      : sorted === "desc"
                        ? "descending"
                        : "none"
                    : undefined;
                return (
                  <TableHead
                    key={header.id}
                    scope="col"
                    aria-sort={ariaSort}
                    style={{ width: header.getSize() === 150 ? undefined : header.getSize() }}
                    className={cn(
                      headPad,
                      numeric && "text-right",
                      header.column.getIsPinned() && "bg-surface-muted/60 sticky",
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
          {loading ? (
            Array.from({ length: skeletonRows }).map((_, i) => (
              <TableRow key={`skeleton-${i}`} className="border-border">
                {table.getVisibleLeafColumns().map((c) => (
                  <TableCell key={c.id} className={cellPad}>
                    <Skeleton className="h-4 w-3/5" />
                  </TableCell>
                ))}
              </TableRow>
            ))
          ) : rows.length === 0 ? (
            <TableRow className="border-border hover:bg-transparent">
              <TableCell colSpan={visibleColumnCount} className="p-0">
                {empty ?? (
                  <div className="text-foreground-muted py-12 text-center text-sm">No results</div>
                )}
              </TableCell>
            </TableRow>
          ) : (
            rows.map((row) => {
              const isInteractive = Boolean(getRowAction);
              const onKeyDown = (event: KeyboardEvent<HTMLTableRowElement>) => {
                if (!isInteractive) return;
                if (event.key === "Enter" || event.key === " ") {
                  event.preventDefault();
                  getRowAction?.(row);
                }
              };
              const rowTestId = getRowTestId?.(row);
              const rowAriaLabel = isInteractive ? getRowAriaLabel?.(row) : undefined;
              return (
                <TableRow
                  key={row.id}
                  data-state={row.getIsSelected() ? "selected" : undefined}
                  data-interactive={isInteractive || undefined}
                  data-testid={rowTestId}
                  tabIndex={isInteractive ? 0 : undefined}
                  role={isInteractive ? "button" : undefined}
                  aria-label={rowAriaLabel}
                  onClick={isInteractive ? () => getRowAction?.(row) : undefined}
                  onKeyDown={onKeyDown}
                  className={cn(
                    "border-border hover:bg-surface-muted/40 transition-colors duration-150",
                    isInteractive &&
                      "focus-visible:ring-ring/50 cursor-pointer outline-none focus-visible:ring-2",
                  )}
                >
                  {row.getVisibleCells().map((cell) => {
                    const meta = cell.column.columnDef.meta;
                    const numeric = meta?.numeric ?? false;
                    return (
                      <TableCell
                        key={cell.id}
                        {...(numeric ? TABULAR_ATTR : {})}
                        className={cn(
                          cellPad,
                          "text-foreground text-sm",
                          numeric && "text-right font-mono",
                        )}
                      >
                        {flexRender(cell.column.columnDef.cell, cell.getContext())}
                      </TableCell>
                    );
                  })}
                </TableRow>
              );
            })
          )}
        </TableBody>
      </Table>
    </div>
  );
}

export type { ReactTable };
