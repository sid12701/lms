/**
 * Compact-density preview table of the Portfolio MIS dataset.
 *
 * Density locked to "compact" per D7 (MIS preview is a long-list surface).
 * Server-paged: the parent owns `{page, pageSize}` filter state; pagination
 * change fires `onFiltersChange`.
 *
 * Columns (left → right):
 *   - Loan id (middle-truncated)
 *   - Borrower (already masked on the wire)
 *   - LSP code
 *   - Product code
 *   - Amount (INR)
 *   - Status
 *   - Disbursal date
 *   - DPD
 *   - EMI (INR)
 *   - Overdue (INR)
 */
import { useMemo } from "react";
import {
  type ColumnDef,
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
import { Badge } from "@/components/ui/badge";
import { DataTablePagination } from "@/components/app/data/DataTablePagination";
import { TableSkeleton } from "@/components/app/feedback/Skeletons";
import { EmptyState } from "@/components/app/feedback/EmptyState";
import { TABULAR_ATTR } from "@/lib/tabular-nums";
import { formatINR } from "@/lib/format";
import { cn } from "@/lib/utils";
import type { MisPreviewFilters, MisPreviewResponseDto, MisPreviewRow } from "../types";

function truncateMiddle(value: string, head = 6, tail = 4): string {
  if (value.length <= head + tail + 1) return value;
  return `${value.slice(0, head)}…${value.slice(-tail)}`;
}

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

  const columns = useMemo<ColumnDef<MisPreviewRow>[]>(
    () => [
      {
        accessorKey: "loanId",
        meta: { label: "Loan id" },
        header: () => <span>Loan id</span>,
        cell: ({ row }) => (
          <span
            className="text-foreground font-mono text-[11px]"
            title={row.original.loanId}
          >
            {truncateMiddle(row.original.loanId)}
          </span>
        ),
      },
      {
        accessorKey: "borrowerName",
        meta: { label: "Borrower" },
        header: () => <span>Borrower</span>,
        cell: ({ row }) => (
          <span className="text-foreground text-xs">{row.original.borrowerName}</span>
        ),
      },
      {
        accessorKey: "lspCode",
        meta: { label: "LSP" },
        header: () => <span>LSP</span>,
        cell: ({ row }) => (
          <span className="text-foreground-muted text-[11px] uppercase tracking-wide">
            {row.original.lspCode}
          </span>
        ),
      },
      {
        accessorKey: "productCode",
        meta: { label: "Product" },
        header: () => <span>Product</span>,
        cell: ({ row }) => (
          <span className="text-foreground-muted text-[11px] uppercase tracking-wide">
            {row.original.productCode}
          </span>
        ),
      },
      {
        accessorKey: "amount",
        meta: { label: "Amount", numeric: true },
        header: () => <span>Amount</span>,
        cell: ({ row }) => (
          <span className="text-foreground tabular-nums">
            {formatINR(row.original.amount, { compact: true })}
          </span>
        ),
      },
      {
        accessorKey: "status",
        meta: { label: "Status" },
        header: () => <span>Status</span>,
        cell: ({ row }) => (
          <Badge
            variant="outline"
            data-status={row.original.status}
            className="text-[10px] font-medium"
          >
            {row.original.status.replace(/_/g, " ").toLowerCase()}
          </Badge>
        ),
      },
      {
        accessorKey: "disbursalDate",
        meta: { label: "Disbursed" },
        header: () => <span>Disbursed</span>,
        cell: ({ row }) => (
          <span className="text-foreground-muted text-[11px] tabular-nums">
            {row.original.disbursalDate ?? "—"}
          </span>
        ),
      },
      {
        accessorKey: "dpd",
        meta: { label: "DPD", numeric: true },
        header: () => <span>DPD</span>,
        cell: ({ row }) => (
          <span
            className={cn(
              "tabular-nums",
              row.original.dpd > 0 ? "text-warning font-medium" : "text-foreground-muted",
            )}
          >
            {row.original.dpd}
          </span>
        ),
      },
      {
        accessorKey: "emiAmount",
        meta: { label: "EMI", numeric: true },
        header: () => <span>EMI</span>,
        cell: ({ row }) => (
          <span className="text-foreground tabular-nums">
            {row.original.emiAmount > 0
              ? formatINR(row.original.emiAmount, { compact: true })
              : "—"}
          </span>
        ),
      },
      {
        accessorKey: "overdueAmount",
        meta: { label: "Overdue", numeric: true },
        header: () => <span>Overdue</span>,
        cell: ({ row }) => (
          <span
            className={cn(
              "tabular-nums",
              row.original.overdueAmount > 0
                ? "text-danger font-medium"
                : "text-foreground-muted",
            )}
          >
            {row.original.overdueAmount > 0
              ? formatINR(row.original.overdueAmount, { compact: true })
              : "—"}
          </span>
        ),
      },
    ],
    [],
  );

  const rows = data?.items ?? [];

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
        <TableSkeleton rows={8} cols={10} />
      </div>
    );
  }

  return (
    <div data-slot="mis-preview-table" className={cn("flex flex-col gap-3", className)}>
      <div
        className="border-border bg-surface shadow-e1 overflow-hidden rounded-md border"
        data-density="compact"
      >
        <Table aria-label="Portfolio MIS preview">
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
