/**
 * Directory table for `/borrowers`.
 *
 * Render-only: list data + filter state are owned by the page. Click /
 * Enter on a row opens `/borrowers/:id`.
 */
import { useCallback, useMemo } from "react";
import { useNavigate } from "react-router-dom";
import { Users } from "lucide-react";
import type { ColumnDef, PaginationState } from "@tanstack/react-table";
import { AdminEntityDataTable } from "@/components/app/data/AdminEntityDataTable";
import { DataTableColumnHeader } from "@/components/app/data/DataTableColumnHeader";
import { EmptyState } from "@/components/app/feedback/EmptyState";
import { cn } from "@/lib/utils";
import type { BorrowerListFilters, BorrowerListResponse, BorrowerSummary } from "../list-types";

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

  const rows = data?.items ?? [];
  const total = data?.total ?? 0;
  const pageSize = filters.pageSize ?? 25;
  const page = filters.page ?? 0;

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

  const handlePaginationChange = useCallback(
    (next: PaginationState) => {
      onFiltersChange({
        ...filters,
        page: next.pageIndex,
        pageSize: next.pageSize,
      });
    },
    [filters, onFiltersChange],
  );

  return (
    <AdminEntityDataTable
      dataSlot="borrowers-table"
      className={cn("gap-3", className)}
      columns={columns}
      rows={rows}
      total={total}
      page={page}
      pageSize={pageSize}
      loading={isLoading}
      skeletonRows={10}
      rowIdKey="id"
      ariaLabel="Borrowers"
      onPaginationChange={handlePaginationChange}
      getRowAction={(row) => navigate(`/borrowers/${row.original.id}`)}
      getRowTestId={(row) => `borrowers-row-${row.original.id}`}
      getRowAriaLabel={(row) => `Open borrower ${row.original.fullName}`}
      empty={
        <EmptyState
          variant="filtered-empty"
          icon={Users}
          title="No borrowers match these filters."
          description="Clear the search or widen the criteria."
        />
      }
    />
  );
}
