/**
 * Triage table for `/loan-applications`.
 *
 * Renders a TanStack-Table-backed list of `LoanApplicationListItem` rows
 * with sticky-header pagination, server-side sort wiring, and click-to-
 * open navigation. The component is purely presentational — list data and
 * filter state are owned by the page (`page.tsx`); it just reads from
 * props and emits filter patches via `onFiltersChange`.
 *
 * Loading shows a `TableSkeleton`; an empty (post-fetch) result shows an
 * `EmptyState`. Both share the table's outer container so the surrounding
 * page does not jitter as data swaps in.
 */
import { useMemo } from "react";
import { useNavigate } from "react-router-dom";
import { Inbox } from "lucide-react";
import {
  type ColumnDef,
  type PaginationState,
  type Row,
  type SortingState,
} from "@tanstack/react-table";
import {
  flexRender,
  getCoreRowModel,
  useReactTable,
} from "@tanstack/react-table";
import { formatDistanceToNow } from "date-fns";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { DataTableColumnHeader } from "@/components/app/data/DataTableColumnHeader";
import { DataTablePagination } from "@/components/app/data/DataTablePagination";
import { TableSkeleton } from "@/components/app/feedback/Skeletons";
import { EmptyState } from "@/components/app/feedback/EmptyState";
import { StatusBadge } from "@/components/app/status/StatusBadge";
import { useDensity } from "@/app/providers";
import { TABULAR_ATTR } from "@/lib/tabular-nums";
import { cn } from "@/lib/utils";
import { formatINR } from "@/lib/format";
import type {
  LoanApplicationListFilters,
  LoanApplicationListItem,
  LoanApplicationListResponse,
} from "../types";

const SORTABLE_COLUMNS = new Set([
  "createdAt",
  "updatedAt",
  "requestedAmount",
  "status",
]);

type SortKey = NonNullable<LoanApplicationListFilters["sortBy"]>;

function shortId(id: string): string {
  return `${id.slice(0, 8)}…`;
}

function safeRelative(iso: string): string {
  try {
    return formatDistanceToNow(new Date(iso), { addSuffix: true });
  } catch {
    return iso;
  }
}

export interface LoanApplicationsTableProps {
  data: LoanApplicationListResponse | undefined;
  isLoading: boolean;
  filters: LoanApplicationListFilters;
  onFiltersChange: (next: LoanApplicationListFilters) => void;
  className?: string;
}

/**
 * Triage list table. Server-paged + server-sorted; render-only.
 */
export function LoanApplicationsTable({
  data,
  isLoading,
  filters,
  onFiltersChange,
  className,
}: LoanApplicationsTableProps) {
  const navigate = useNavigate();
  const { density } = useDensity();

  const pageSize = filters.pageSize ?? 25;
  const pageIndex = filters.page ?? 0;

  const sorting: SortingState = useMemo(() => {
    if (!filters.sortBy) return [];
    return [{ id: filters.sortBy, desc: filters.sortDir === "desc" }];
  }, [filters.sortBy, filters.sortDir]);

  const pagination: PaginationState = useMemo(
    () => ({ pageIndex, pageSize }),
    [pageIndex, pageSize],
  );

  const columns = useMemo<ColumnDef<LoanApplicationListItem>[]>(
    () => [
      {
        accessorKey: "id",
        meta: { label: "ID" },
        header: ({ column }) => <DataTableColumnHeader column={column} title="ID" />,
        cell: ({ row }) => (
          <span className="font-mono text-xs" title={row.original.id}>
            {shortId(row.original.id)}
          </span>
        ),
        enableSorting: false,
      },
      {
        accessorKey: "externalLoanId",
        meta: { label: "External ID" },
        header: ({ column }) => (
          <DataTableColumnHeader column={column} title="External ID" />
        ),
        cell: ({ row }) =>
          row.original.externalLoanId ?? (
            <span className="text-foreground-muted">—</span>
          ),
        enableSorting: false,
      },
      {
        accessorKey: "borrowerNameMasked",
        meta: { label: "Borrower" },
        header: ({ column }) => (
          <DataTableColumnHeader column={column} title="Borrower" />
        ),
        cell: ({ row }) => (
          <span className="font-medium">{row.original.borrowerNameMasked}</span>
        ),
        enableSorting: false,
      },
      {
        accessorKey: "lspName",
        meta: { label: "LSP" },
        header: ({ column }) => <DataTableColumnHeader column={column} title="LSP" />,
        cell: ({ row }) => <span>{row.original.lspName}</span>,
        enableSorting: false,
      },
      {
        accessorKey: "productName",
        meta: { label: "Product" },
        header: ({ column }) => (
          <DataTableColumnHeader column={column} title="Product" />
        ),
        cell: ({ row }) => <span>{row.original.productName}</span>,
        enableSorting: false,
      },
      {
        accessorKey: "requestedAmount",
        meta: { label: "Amount", numeric: true },
        header: ({ column }) => (
          <DataTableColumnHeader column={column} title="Amount" numeric />
        ),
        cell: ({ row }) => <span>{formatINR(row.original.requestedAmount)}</span>,
        enableSorting: true,
      },
      {
        accessorKey: "tenureMonths",
        meta: { label: "Tenure", numeric: true },
        header: ({ column }) => (
          <DataTableColumnHeader column={column} title="Tenure" numeric />
        ),
        cell: ({ row }) => <span>{row.original.tenureMonths} mo</span>,
        enableSorting: false,
      },
      {
        accessorKey: "status",
        meta: { label: "Status" },
        header: ({ column }) => (
          <DataTableColumnHeader column={column} title="Status" />
        ),
        cell: ({ row }) => <StatusBadge status={row.original.status} />,
        enableSorting: true,
      },
      {
        accessorKey: "updatedAt",
        meta: { label: "Updated" },
        header: ({ column }) => (
          <DataTableColumnHeader column={column} title="Updated" />
        ),
        cell: ({ row }) => (
          <span
            className="text-foreground-muted text-sm"
            title={row.original.updatedAt}
          >
            {safeRelative(row.original.updatedAt)}
          </span>
        ),
        enableSorting: true,
      },
    ],
    [],
  );

  const rows = data?.items ?? [];

  const table = useReactTable({
    data: rows as LoanApplicationListItem[],
    columns,
    state: { sorting, pagination },
    manualSorting: true,
    manualPagination: true,
    rowCount: data?.total ?? 0,
    getCoreRowModel: getCoreRowModel(),
    getRowId: (row) => row.id,
    onSortingChange: (updater) => {
      const next =
        typeof updater === "function" ? updater(sorting) : updater;
      if (next.length === 0) {
        onFiltersChange({
          ...filters,
          sortBy: undefined,
          sortDir: undefined,
          page: 0,
        });
        return;
      }
      const first = next[0]!;
      if (!SORTABLE_COLUMNS.has(first.id)) return;
      onFiltersChange({
        ...filters,
        sortBy: first.id as SortKey,
        sortDir: first.desc ? "desc" : "asc",
        page: 0,
      });
    },
    onPaginationChange: (updater) => {
      const next =
        typeof updater === "function" ? updater(pagination) : updater;
      onFiltersChange({
        ...filters,
        page: next.pageIndex,
        pageSize: next.pageSize,
      });
    },
  });

  const cellPad = density === "compact" ? "px-2.5 py-1.5" : "px-3 py-3";
  const headPad = density === "compact" ? "h-8 px-2.5" : "h-10 px-3";
  const visibleColumnCount = table.getVisibleLeafColumns().length;

  const tableRows = table.getRowModel().rows;
  const isEmpty = !isLoading && rows.length === 0;

  const openRow = (row: Row<LoanApplicationListItem>) => {
    navigate(`/loan-applications/${row.original.id}`);
  };

  if (isLoading && rows.length === 0) {
    return (
      <div
        data-slot="loan-applications-table"
        data-testid="loan-applications-table"
        className={cn("flex flex-col gap-3", className)}
      >
        <TableSkeleton rows={10} cols={10} />
      </div>
    );
  }

  return (
    <div
      data-slot="loan-applications-table"
      data-testid="loan-applications-table"
      className={cn("flex flex-col gap-3", className)}
    >
      <div
        className="border-border bg-surface shadow-e1 overflow-hidden rounded-md border"
        data-density={density}
      >
        <Table aria-label="Loan applications">
          <TableHeader className="bg-surface-muted/60 sticky top-0 z-10 backdrop-blur">
            {table.getHeaderGroups().map((headerGroup) => (
              <TableRow
                key={headerGroup.id}
                className="border-border hover:bg-transparent"
              >
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
                      className={cn(headPad, numeric && "text-right")}
                    >
                      {header.isPlaceholder
                        ? null
                        : flexRender(
                            header.column.columnDef.header,
                            header.getContext(),
                          )}
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
                    icon={Inbox}
                    title="No applications match these filters."
                    description="Try clearing some filters or widening the search."
                  />
                </TableCell>
              </TableRow>
            ) : (
              tableRows.map((row) => (
                <TableRow
                  key={row.id}
                  tabIndex={0}
                  aria-label={`Open application ${shortId(row.original.id)}`}
                  data-testid={`loan-applications-row-${row.original.id}`}
                  data-interactive="true"
                  onClick={() => openRow(row)}
                  onKeyDown={(event) => {
                    if (event.key === "Enter" || event.key === " ") {
                      event.preventDefault();
                      openRow(row);
                    }
                  }}
                  className={cn(
                    "border-border hover:bg-surface-muted/40 focus-visible:ring-ring/50 cursor-pointer outline-none transition-colors duration-150 focus-visible:ring-2",
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
                          numeric && "text-right tabular-nums",
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

      <DataTablePagination
        table={table}
        totalRows={data?.total ?? 0}
        className="px-1"
      />
    </div>
  );
}
