import { flexRender, type Column, type Row, type Table as ReactTable } from "@tanstack/react-table";
import type { KeyboardEvent, ReactNode } from "react";
import { Skeleton } from "@/components/ui/skeleton";
import { TABULAR_ATTR } from "@/lib/tabular-nums";
import { cn } from "@/lib/utils";

export type MobileCardSlot = "primary" | "secondary" | "actions";

function columnLabel<TData>(column: Column<TData, unknown>): string {
  const meta = column.columnDef.meta;
  if (meta?.label) return meta.label;
  const header = column.columnDef.header;
  if (typeof header === "string") return header;
  return column.id;
}

function columnsForSlot<TData>(
  columns: Column<TData, unknown>[],
  slot: MobileCardSlot,
): Column<TData, unknown>[] {
  return columns.filter((column) => column.columnDef.meta?.mobileCard === slot);
}

function fallbackMobileColumns<TData>(columns: Column<TData, unknown>[]): {
  primary: Column<TData, unknown>[];
  secondary: Column<TData, unknown>[];
} {
  const dataColumns = columns.filter((c) => c.columnDef.meta?.mobileCard !== "actions");
  return {
    primary: dataColumns.slice(0, 1),
    secondary: dataColumns.slice(1, 5),
  };
}

export interface DataTableMobileCardsProps<TData> {
  table: ReactTable<TData>;
  density: "comfortable" | "compact";
  loading?: boolean;
  skeletonRows?: number;
  empty?: ReactNode;
  getRowAction?: (row: Row<TData>) => void;
  getRowTestId?: (row: Row<TData>) => string | undefined;
  getRowAriaLabel?: (row: Row<TData>) => string | undefined;
  className?: string;
}

/**
 * Stacked card list for narrow viewports. Columns opt in via
 * `columnDef.meta.mobileCard` (`primary` | `secondary` | `actions`).
 */
export function DataTableMobileCards<TData>({
  table,
  density,
  loading = false,
  skeletonRows = 5,
  empty,
  getRowAction,
  getRowTestId,
  getRowAriaLabel,
  className,
}: DataTableMobileCardsProps<TData>) {
  const columns = table.getVisibleLeafColumns();
  let primaryCols = columnsForSlot(columns, "primary");
  let secondaryCols = columnsForSlot(columns, "secondary");
  const actionCols = columnsForSlot(columns, "actions");

  if (primaryCols.length === 0 && secondaryCols.length === 0) {
    const fallback = fallbackMobileColumns(columns);
    primaryCols = fallback.primary;
    secondaryCols = fallback.secondary;
  }

  const rows = table.getRowModel().rows;
  const pad = density === "compact" ? "p-3" : "p-4";

  if (loading) {
    return (
      <ul
        data-slot="data-table-mobile-cards"
        data-density={density}
        className={cn("flex flex-col gap-2", className)}
      >
        {Array.from({ length: skeletonRows }).map((_, i) => (
          <li
            key={`mobile-skeleton-${i}`}
            className="border-border bg-surface shadow-e1 rounded-md border p-4"
          >
            <Skeleton className="mb-2 h-4 w-2/3" />
            <Skeleton className="mb-1 h-3 w-full" />
            <Skeleton className="h-3 w-4/5" />
          </li>
        ))}
      </ul>
    );
  }

  if (rows.length === 0) {
    return (
      <div
        data-slot="data-table-mobile-empty"
        className={cn(
          "border-border bg-surface shadow-e1 flex min-h-[180px] items-center justify-center rounded-md border p-4",
          className,
        )}
      >
        {empty ?? <div className="text-foreground-muted py-8 text-center text-sm">No results</div>}
      </div>
    );
  }

  return (
    <ul
      data-slot="data-table-mobile-cards"
      data-density={density}
      className={cn("flex flex-col gap-2", className)}
    >
      {rows.map((row) => {
        const isInteractive = Boolean(getRowAction);
        const rowTestId = getRowTestId?.(row);
        const rowAriaLabel = isInteractive ? getRowAriaLabel?.(row) : undefined;

        const cellByColumnId = new Map(row.getVisibleCells().map((cell) => [cell.column.id, cell]));

        const onRowActivate = () => getRowAction?.(row);
        const onRowKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
          if (event.key === "Enter" || event.key === " ") {
            event.preventDefault();
            onRowActivate();
          }
        };

        const primaryBlock =
          primaryCols.length > 0 ? (
            <div className="mb-2 flex flex-wrap items-start justify-between gap-2">
              {primaryCols.map((column) => {
                const cell = cellByColumnId.get(column.id);
                if (!cell) return null;
                return (
                  <div key={cell.id} className="text-foreground min-w-0 text-sm font-medium">
                    {flexRender(cell.column.columnDef.cell, cell.getContext())}
                  </div>
                );
              })}
            </div>
          ) : null;

        const secondaryBlock =
          secondaryCols.length > 0 ? (
            <dl className="grid grid-cols-1 gap-x-4 gap-y-2 sm:grid-cols-2">
              {secondaryCols.map((column) => {
                const cell = cellByColumnId.get(column.id);
                if (!cell) return null;
                const numeric = cell.column.columnDef.meta?.numeric ?? false;
                return (
                  <div key={cell.id} className="flex min-w-0 flex-col gap-0.5">
                    <dt className="text-foreground-muted text-[11px] font-medium tracking-wide uppercase">
                      {columnLabel(column)}
                    </dt>
                    <dd
                      {...(numeric ? TABULAR_ATTR : {})}
                      className={cn("text-foreground text-sm", numeric && "font-mono tabular-nums")}
                    >
                      {flexRender(cell.column.columnDef.cell, cell.getContext())}
                    </dd>
                  </div>
                );
              })}
            </dl>
          ) : null;

        const actionBlock =
          actionCols.length > 0 ? (
            <div className="border-border mt-3 flex flex-wrap items-center gap-2 border-t pt-3">
              {actionCols.map((column) => {
                const cell = cellByColumnId.get(column.id);
                if (!cell) return null;
                return (
                  <div key={cell.id} className="flex flex-wrap gap-2">
                    {flexRender(cell.column.columnDef.cell, cell.getContext())}
                  </div>
                );
              })}
            </div>
          ) : null;

        return (
          <li
            key={row.id}
            data-testid={rowTestId}
            className={cn("border-border bg-surface shadow-e1 rounded-md border", pad)}
          >
            {isInteractive ? (
              <div
                role="button"
                tabIndex={0}
                aria-label={rowAriaLabel}
                onClick={onRowActivate}
                onKeyDown={onRowKeyDown}
                className={cn(
                  "focus-visible:ring-ring/50 -m-px cursor-pointer rounded-[inherit] p-px outline-none focus-visible:ring-2",
                  actionBlock ? "pb-0" : undefined,
                )}
              >
                {primaryBlock}
                {secondaryBlock}
              </div>
            ) : (
              <>
                {primaryBlock}
                {secondaryBlock}
              </>
            )}
            {actionBlock}
          </li>
        );
      })}
    </ul>
  );
}
