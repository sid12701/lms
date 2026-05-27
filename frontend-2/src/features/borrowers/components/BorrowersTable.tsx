/**
 * Directory table for `/borrowers`.
 *
 * Render-only: list data + filter state are owned by the page. Click /
 * Enter on a row opens `/borrowers/:id`. Empty + skeleton states share
 * the table's outer container so the surrounding page does not jitter
 * as data swaps in.
 */
import { useMemo } from "react";
import { useNavigate } from "react-router-dom";
import { Users } from "lucide-react";
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
import { DataTableColumnHeader } from "@/components/app/data/DataTableColumnHeader";
import { DataTablePagination } from "@/components/app/data/DataTablePagination";
import { TableSkeleton } from "@/components/app/feedback/Skeletons";
import { EmptyState } from "@/components/app/feedback/EmptyState";
import { useDensity } from "@/app/providers";
import { cn } from "@/lib/utils";
import type {
  BorrowerListFilters,
  BorrowerListResponse,
  BorrowerSummary,
} from "../list-types";

const DASH = "—";

function shortId(id: string): string {
  return `${id.slice(0, 8)}…`;
}

function formatLocation(row: BorrowerSummary): string {
  const parts = [row.city, row.state].filter(Boolean);
  return parts.length > 0 ? parts.join(", ") : DASH;
}

export interface BorrowersTableProps {
  data: BorrowerListResponse | undefined;
  isLoading: boolean;
  filters: BorrowerListFilters;
  onFiltersChange: (next: BorrowerListFilters) => void;
  className?: string;
}

export function BorrowersTable({
  data,
  isLoading,
  filters,
  onFiltersChange,
  className,
}: BorrowersTableProps) {
  const navigate = useNavigate();
  const { density } = useDensity();

  const pageSize = filters.pageSize ?? 25;
  const pageIndex = filters.page ?? 0;

  const pagination: PaginationState = useMemo(
    () => ({ pageIndex, pageSize }),
    [pageIndex, pageSize],
  );

  const columns = useMemo<ColumnDef<BorrowerSummary>[]>(
    () => [
      {
        accessorKey: "fullName",
        meta: { label: "Borrower" },
        header: ({ column }) => <DataTableColumnHeader column={column} title="Borrower" />,
        cell: ({ row }) => (
          <div className="flex flex-col">
            <span className="font-medium">{row.original.fullName}</span>
            <span className="text-foreground-muted font-mono text-xs" title={row.original.id}>
              {shortId(row.original.id)}
            </span>
          </div>
        ),
        enableSorting: false,
      },
      {
        accessorKey: "pan",
        meta: { label: "PAN" },
        header: ({ column }) => <DataTableColumnHeader column={column} title="PAN" />,
        cell: ({ row }) => <span className="font-mono text-xs">{row.original.pan}</span>,
        enableSorting: false,
      },
      {
        accessorKey: "mobile",
        meta: { label: "Mobile" },
        header: ({ column }) => <DataTableColumnHeader column={column} title="Mobile" />,
        cell: ({ row }) => <span className="tabular-nums">{row.original.mobile}</span>,
        enableSorting: false,
      },
      {
        accessorKey: "email",
        meta: { label: "Email" },
        header: ({ column }) => <DataTableColumnHeader column={column} title="Email" />,
        cell: ({ row }) =>
          row.original.email ? (
            <span>{row.original.email}</span>
          ) : (
            <span className="text-foreground-muted">{DASH}</span>
          ),
        enableSorting: false,
      },
      {
        id: "location",
        meta: { label: "Location" },
        header: ({ column }) => <DataTableColumnHeader column={column} title="Location" />,
        cell: ({ row }) => {
          const value = formatLocation(row.original);
          return value === DASH ? (
            <span className="text-foreground-muted">{DASH}</span>
          ) : (
            <span>{value}</span>
          );
        },
        enableSorting: false,
      },
      {
        accessorKey: "aadharNumberMasked",
        meta: { label: "Aadhaar" },
        header: ({ column }) => <DataTableColumnHeader column={column} title="Aadhaar" />,
        cell: ({ row }) =>
          row.original.aadharNumberMasked ? (
            <span className="font-mono text-xs">{row.original.aadharNumberMasked}</span>
          ) : (
            <span className="text-foreground-muted">{DASH}</span>
          ),
        enableSorting: false,
      },
    ],
    [],
  );

  const rows = data?.items ?? [];

  const table = useReactTable({
    data: rows as BorrowerSummary[],
    columns,
    state: { pagination },
    manualPagination: true,
    rowCount: data?.total ?? 0,
    getCoreRowModel: getCoreRowModel(),
    getRowId: (row) => row.id,
    onPaginationChange: (updater) => {
      const next = typeof updater === "function" ? updater(pagination) : updater;
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

  const openRow = (id: string) => navigate(`/borrowers/${id}`);

  if (isLoading && rows.length === 0) {
    return (
      <div
        data-slot="borrowers-table"
        data-testid="borrowers-table"
        className={cn("flex flex-col gap-3", className)}
      >
        <TableSkeleton rows={10} cols={6} />
      </div>
    );
  }

  return (
    <div
      data-slot="borrowers-table"
      data-testid="borrowers-table"
      className={cn("flex flex-col gap-3", className)}
    >
      <div
        className="border-border bg-surface shadow-e1 overflow-hidden rounded-md border"
        data-density={density}
      >
        <Table aria-label="Borrowers">
          <TableHeader className="bg-surface-muted/60 sticky top-0 z-10 backdrop-blur">
            {table.getHeaderGroups().map((headerGroup) => (
              <TableRow key={headerGroup.id} className="border-border hover:bg-transparent">
                {headerGroup.headers.map((header) => (
                  <TableHead
                    key={header.id}
                    scope="col"
                    className={cn(headPad)}
                  >
                    {header.isPlaceholder
                      ? null
                      : flexRender(header.column.columnDef.header, header.getContext())}
                  </TableHead>
                ))}
              </TableRow>
            ))}
          </TableHeader>
          <TableBody>
            {isEmpty ? (
              <TableRow className="border-border hover:bg-transparent">
                <TableCell colSpan={visibleColumnCount} className="p-0">
                  <EmptyState
                    variant="filtered-empty"
                    icon={Users}
                    title="No borrowers match these filters."
                    description="Clear the search or widen the criteria."
                  />
                </TableCell>
              </TableRow>
            ) : (
              tableRows.map((row) => (
                <TableRow
                  key={row.id}
                  tabIndex={0}
                  aria-label={`Open borrower ${row.original.fullName}`}
                  data-testid={`borrowers-row-${row.original.id}`}
                  data-interactive="true"
                  onClick={() => openRow(row.original.id)}
                  onKeyDown={(event) => {
                    if (event.key === "Enter" || event.key === " ") {
                      event.preventDefault();
                      openRow(row.original.id);
                    }
                  }}
                  className={cn(
                    "border-border hover:bg-surface-muted/40 focus-visible:ring-ring/50 cursor-pointer outline-none transition-colors duration-150 focus-visible:ring-2",
                  )}
                >
                  {row.getVisibleCells().map((cell) => (
                    <TableCell key={cell.id} className={cn(cellPad, "text-foreground text-sm")}>
                      {flexRender(cell.column.columnDef.cell, cell.getContext())}
                    </TableCell>
                  ))}
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
