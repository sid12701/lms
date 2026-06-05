import type { ColumnDef, PaginationState, Row, SortingState } from "@tanstack/react-table";
import type { ReactNode } from "react";
import { DataTable } from "@/components/app/data/DataTable";
import { DataTablePagination } from "@/components/app/data/DataTablePagination";
import { cn } from "@/lib/utils";

export interface AdminEntityDataTableProps<TData> {
  dataSlot: string;
  columns: ColumnDef<TData, unknown>[];
  rows: readonly TData[];
  total: number;
  page: number;
  pageSize: number;
  loading?: boolean;
  rowIdKey: keyof TData & string;
  ariaLabel: string;
  empty: ReactNode;
  onPaginationChange: (next: PaginationState) => void;
  sorting?: SortingState;
  manualSorting?: boolean;
  onSortingChange?: (next: SortingState) => void;
  getRowAction?: (row: Row<TData>) => void;
  getRowTestId?: (row: Row<TData>) => string | undefined;
  getRowAriaLabel?: (row: Row<TData>) => string | undefined;
  skeletonRows?: number;
  className?: string;
}

/**
 * Server-paged admin list table: one `DataTable` instance plus a controlled
 * footer pager (no second `useReactTable` solely for pagination).
 */
export function AdminEntityDataTable<TData>({
  dataSlot,
  columns,
  rows,
  total,
  page,
  pageSize,
  loading = false,
  rowIdKey,
  ariaLabel,
  empty,
  onPaginationChange,
  sorting,
  manualSorting = false,
  onSortingChange,
  getRowAction,
  getRowTestId,
  getRowAriaLabel,
  skeletonRows,
  className,
}: AdminEntityDataTableProps<TData>) {
  const pagination = { pageIndex: page, pageSize };

  return (
    <div data-slot={dataSlot} className={cn("flex flex-col gap-2", className)}>
      <DataTable<TData, unknown>
        columns={columns}
        data={rows}
        loading={loading}
        skeletonRows={skeletonRows}
        rowIdKey={rowIdKey}
        ariaLabel={ariaLabel}
        manualSorting={manualSorting}
        pagination={{
          manualPagination: true,
          rowCount: total,
          initialPageSize: pageSize,
        }}
        state={{
          pagination,
          ...(sorting !== undefined ? { sorting } : {}),
        }}
        onStateChange={(next) => {
          if (next.pagination !== undefined) onPaginationChange(next.pagination);
          if (next.sorting !== undefined && onSortingChange) onSortingChange(next.sorting);
        }}
        empty={empty}
        getRowAction={getRowAction}
        getRowTestId={getRowTestId}
        getRowAriaLabel={getRowAriaLabel}
      />
      <DataTablePagination
        pageIndex={page}
        pageSize={pageSize}
        totalRows={total}
        loading={loading}
        onPaginationChange={onPaginationChange}
      />
    </div>
  );
}
