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
  type Table as TanStackTable,
  type TableOptions,
  type VisibilityState,
} from "@tanstack/react-table";
import { type KeyboardEvent, type ReactNode } from "react";
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
import { DataTableMobileCards } from "@/components/app/data/DataTableMobileCards";
import { useMediaQuery } from "@/lib/hooks/use-media-query";
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
    /** Mobile card layout slot (see `DataTableMobileCards`). */
    mobileCard?: "primary" | "secondary" | "actions";
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
  /** At mobile widths, render stacked cards instead of a wide table. */
  responsiveCards?: boolean;
  /** Header className override. */
  headerClassName?: string;
  className?: string;
}

interface DataTableConfiguration<TData, TValue> {
  columns: ColumnDef<TData, TValue>[];
  data: readonly TData[];
  state?: DataTableState;
  onStateChange?: (next: DataTableStateChange) => void;
  pagination?: DataTablePaginationConfig;
  rowIdKey?: keyof TData;
  manualSorting: boolean;
}

function controlledChange<T>(
  current: T,
  onStateChange: DataTableConfiguration<unknown, unknown>["onStateChange"],
  toChange: (next: T) => DataTableStateChange,
): OnChangeFn<T> | undefined {
  if (!onStateChange) return undefined;
  return (updater) => {
    const next = typeof updater === "function" ? (updater as (previous: T) => T)(current) : updater;
    onStateChange(toChange(next));
  };
}

function controlledTableState(state?: DataTableState): TableOptions<unknown>["state"] {
  if (!state) return {};
  return {
    ...(state.sorting ? { sorting: state.sorting } : {}),
    ...(state.columnFilters ? { columnFilters: state.columnFilters } : {}),
    ...(state.columnVisibility ? { columnVisibility: state.columnVisibility } : {}),
    ...(state.pagination ? { pagination: state.pagination } : {}),
  };
}

function rowIdGetter<TData>(rowIdKey?: keyof TData): TableOptions<TData>["getRowId"] | undefined {
  if (!rowIdKey) return undefined;
  return (row, index) => {
    const value = row[rowIdKey];
    return typeof value === "string" || typeof value === "number" ? String(value) : String(index);
  };
}

function currentPagination(
  state: DataTableState | undefined,
  pagination: DataTablePaginationConfig | undefined,
): PaginationState {
  return (
    state?.pagination ?? {
      pageIndex: 0,
      pageSize: pagination?.initialPageSize ?? 25,
    }
  );
}

function sortingOptions<TData>(manualSorting: boolean): Partial<TableOptions<TData>> {
  return {
    manualSorting,
    getSortedRowModel: manualSorting ? undefined : getSortedRowModel(),
  };
}

function paginationOptions<TData>(
  pagination: DataTablePaginationConfig | undefined,
): Partial<TableOptions<TData>> {
  const manualPagination = pagination?.manualPagination ?? false;
  return {
    manualPagination,
    getPaginationRowModel: manualPagination ? undefined : getPaginationRowModel(),
    rowCount: pagination?.rowCount,
  };
}

type TableChangeHandlers = Pick<
  TableOptions<unknown>,
  "onSortingChange" | "onColumnFiltersChange" | "onColumnVisibilityChange" | "onPaginationChange"
>;

function changeHandlers(
  state: DataTableState | undefined,
  paginationState: PaginationState,
  onStateChange: DataTableConfiguration<unknown, unknown>["onStateChange"],
): Partial<TableChangeHandlers> {
  const handlers: Partial<TableChangeHandlers> = {};
  const onSortingChange = controlledChange(state?.sorting ?? [], onStateChange, (next) => ({
    sorting: next,
  }));
  const onColumnFiltersChange = controlledChange(
    state?.columnFilters ?? [],
    onStateChange,
    (next) => ({ columnFilters: next }),
  );
  const onColumnVisibilityChange = controlledChange(
    state?.columnVisibility ?? {},
    onStateChange,
    (next) => ({ columnVisibility: next }),
  );
  const onPaginationChange = controlledChange(paginationState, onStateChange, (next) => ({
    pagination: next,
  }));

  if (onSortingChange) handlers.onSortingChange = onSortingChange;
  if (onColumnFiltersChange) handlers.onColumnFiltersChange = onColumnFiltersChange;
  if (onColumnVisibilityChange) handlers.onColumnVisibilityChange = onColumnVisibilityChange;
  if (onPaginationChange) handlers.onPaginationChange = onPaginationChange;
  return handlers;
}

function createTableOptions<TData, TValue>({
  columns,
  data,
  state,
  onStateChange,
  pagination,
  rowIdKey,
  manualSorting,
}: DataTableConfiguration<TData, TValue>): TableOptions<TData> {
  const paginationState = currentPagination(state, pagination);

  return {
    data: [...data],
    columns,
    state: controlledTableState(state),
    initialState: state?.pagination ? {} : { pagination: paginationState },
    getRowId: rowIdGetter(rowIdKey),
    getCoreRowModel: getCoreRowModel(),
    getFilteredRowModel: getFilteredRowModel(),
    ...sortingOptions<TData>(manualSorting),
    ...paginationOptions<TData>(pagination),
    ...changeHandlers(state, paginationState, onStateChange),
  };
}

function useConfiguredTable<TData, TValue>(configuration: DataTableConfiguration<TData, TValue>) {
  return useReactTable(createTableOptions(configuration));
}

function DataTableHeader<TData>({
  table,
  headPad,
  className,
}: {
  table: TanStackTable<TData>;
  headPad: string;
  className?: string;
}) {
  return (
    <TableHeader
      data-slot="data-table-header"
      className={cn("bg-surface-muted/60 sticky top-0 z-10 backdrop-blur", className)}
    >
      {table.getHeaderGroups().map((headerGroup) => (
        <TableRow key={headerGroup.id} className="border-border hover:bg-transparent">
          {headerGroup.headers.map((header) => {
            const numeric = header.column.columnDef.meta?.numeric ?? false;
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
  );
}

interface DataTableBodyProps<TData> {
  table: TanStackTable<TData>;
  loading: boolean;
  skeletonRows: number;
  cellPad: string;
  empty?: ReactNode;
  getRowAction?: (row: Row<TData>) => void;
  getRowTestId?: (row: Row<TData>) => string | undefined;
  getRowAriaLabel?: (row: Row<TData>) => string | undefined;
}

function DataTableBody<TData>({
  table,
  loading,
  skeletonRows,
  cellPad,
  empty,
  getRowAction,
  getRowTestId,
  getRowAriaLabel,
}: DataTableBodyProps<TData>) {
  if (loading) {
    return (
      <TableBody>
        {Array.from({ length: skeletonRows }, (_, index) => (
          <TableRow key={`skeleton-${index}`} className="border-border">
            {table.getVisibleLeafColumns().map((column) => (
              <TableCell key={column.id} className={cellPad}>
                <Skeleton className="h-4 w-3/5" />
              </TableCell>
            ))}
          </TableRow>
        ))}
      </TableBody>
    );
  }

  const rows = table.getRowModel().rows;
  if (rows.length === 0) {
    return (
      <TableBody>
        <TableRow className="border-border hover:bg-transparent">
          <TableCell colSpan={table.getVisibleLeafColumns().length} className="p-0">
            {empty ?? (
              <div className="text-foreground-muted py-12 text-center text-sm">No results</div>
            )}
          </TableCell>
        </TableRow>
      </TableBody>
    );
  }

  return (
    <TableBody>
      {rows.map((row) => (
        <DataTableRow
          key={row.id}
          row={row}
          cellPad={cellPad}
          getRowAction={getRowAction}
          getRowTestId={getRowTestId}
          getRowAriaLabel={getRowAriaLabel}
        />
      ))}
    </TableBody>
  );
}

function DataTableRow<TData>({
  row,
  cellPad,
  getRowAction,
  getRowTestId,
  getRowAriaLabel,
}: {
  row: Row<TData>;
  cellPad: string;
  getRowAction?: (row: Row<TData>) => void;
  getRowTestId?: (row: Row<TData>) => string | undefined;
  getRowAriaLabel?: (row: Row<TData>) => string | undefined;
}) {
  const interactive = getRowAction !== undefined;
  const activate = () => getRowAction?.(row);
  const onKeyDown = (event: KeyboardEvent<HTMLTableRowElement>) => {
    if (!interactive || (event.key !== "Enter" && event.key !== " ")) return;
    event.preventDefault();
    activate();
  };

  return (
    <TableRow
      data-state={row.getIsSelected() ? "selected" : undefined}
      data-interactive={interactive || undefined}
      data-testid={getRowTestId?.(row)}
      tabIndex={interactive ? 0 : undefined}
      aria-label={interactive ? getRowAriaLabel?.(row) : undefined}
      onClick={interactive ? activate : undefined}
      onKeyDown={onKeyDown}
      className={cn(
        "border-border hover:bg-surface-muted/40 transition-colors duration-150",
        interactive &&
          "focus-visible:ring-ring/50 cursor-pointer outline-none focus-visible:ring-2",
      )}
    >
      {row.getVisibleCells().map((cell) => {
        const numeric = cell.column.columnDef.meta?.numeric ?? false;
        return (
          <TableCell
            key={cell.id}
            {...(numeric ? TABULAR_ATTR : {})}
            className={cn(cellPad, "text-foreground text-sm", numeric && "text-right font-mono")}
          >
            {flexRender(cell.column.columnDef.cell, cell.getContext())}
          </TableCell>
        );
      })}
    </TableRow>
  );
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
  responsiveCards = true,
  headerClassName,
  className,
}: DataTableProps<TData, TValue>) {
  const densityCtx = useDensity();
  const density = densityProp ?? densityCtx.density;
  const isMobile = useMediaQuery("(max-width: 767px)");
  const showMobileCards = responsiveCards && isMobile;
  const table = useConfiguredTable({
    columns,
    data,
    state,
    onStateChange,
    pagination,
    rowIdKey,
    manualSorting,
  });
  const cellPad = density === "compact" ? "px-2.5 py-1.5" : "px-3 py-3";
  const headPad = density === "compact" ? "h-8 px-2.5" : "h-10 px-3";

  if (showMobileCards) {
    return (
      <DataTableMobileCards
        table={table}
        density={density}
        loading={loading}
        skeletonRows={skeletonRows}
        empty={empty}
        getRowAction={getRowAction}
        getRowTestId={getRowTestId}
        getRowAriaLabel={getRowAriaLabel}
        className={className}
      />
    );
  }

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
        <DataTableHeader table={table} headPad={headPad} className={headerClassName} />
        <DataTableBody
          table={table}
          loading={loading}
          skeletonRows={skeletonRows}
          cellPad={cellPad}
          empty={empty}
          getRowAction={getRowAction}
          getRowTestId={getRowTestId}
          getRowAriaLabel={getRowAriaLabel}
        />
      </Table>
    </div>
  );
}
