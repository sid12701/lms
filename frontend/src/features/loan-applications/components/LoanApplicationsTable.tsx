/**
 * Triage table for `/loan-applications`.
 *
 * Server-paged + server-sorted; render-only. Row click opens detail.
 */
import { useCallback, useMemo } from "react";
import { useNavigate } from "react-router-dom";
import { Inbox } from "lucide-react";
import type { ColumnDef, PaginationState, SortingState } from "@tanstack/react-table";
import { formatDistanceToNow } from "date-fns";
import { AdminEntityDataTable } from "@/components/app/data/AdminEntityDataTable";
import { DataTableColumnHeader } from "@/components/app/data/DataTableColumnHeader";
import { EmptyState } from "@/components/app/feedback/EmptyState";
import { StatusBadge } from "@/components/app/status/StatusBadge";
import { formatINR } from "@/lib/format";
import { cn } from "@/lib/utils";
import type {
  LoanApplicationListFilters,
  LoanApplicationListItem,
  LoanApplicationListResponse,
} from "../types";

const SORTABLE_COLUMNS = new Set(["createdAt", "updatedAt", "requestedAmount", "status"]);

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

export function LoanApplicationsTable({
  data,
  isLoading,
  filters,
  onFiltersChange,
  className,
}: LoanApplicationsTableProps) {
  const navigate = useNavigate();

  const rows = data?.items ?? [];
  const total = data?.total ?? 0;
  const pageSize = filters.pageSize ?? 25;
  const page = filters.page ?? 0;

  const sorting: SortingState = useMemo(() => {
    if (!filters.sortBy) return [];
    return [{ id: filters.sortBy, desc: filters.sortDir === "desc" }];
  }, [filters.sortBy, filters.sortDir]);

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
        meta: { label: "LSP Loan ID" },
        header: ({ column }) => <DataTableColumnHeader column={column} title="LSP Loan ID" />,
        cell: ({ row }) =>
          row.original.externalLoanId ?? <span className="text-foreground-muted">—</span>,
        enableSorting: false,
      },
      {
        accessorKey: "accountNumber",
        meta: { label: "Bhawana loan ID" },
        header: ({ column }) => <DataTableColumnHeader column={column} title="Bhawana loan ID" />,
        cell: ({ row }) =>
          row.original.accountNumber ?? <span className="text-foreground-muted">—</span>,
        enableSorting: false,
      },
      {
        accessorKey: "borrowerNameMasked",
        meta: { label: "Borrower", mobileCard: "primary" },
        header: ({ column }) => <DataTableColumnHeader column={column} title="Borrower" />,
        cell: ({ row }) => <span className="font-medium">{row.original.borrowerNameMasked}</span>,
        enableSorting: false,
      },
      {
        accessorKey: "lspName",
        meta: { label: "LSP", mobileCard: "secondary" },
        header: ({ column }) => <DataTableColumnHeader column={column} title="LSP" />,
        cell: ({ row }) => <span>{row.original.lspName}</span>,
        enableSorting: false,
      },
      {
        accessorKey: "productName",
        meta: { label: "Product", mobileCard: "secondary" },
        header: ({ column }) => <DataTableColumnHeader column={column} title="Product" />,
        cell: ({ row }) => <span>{row.original.productName}</span>,
        enableSorting: false,
      },
      {
        accessorKey: "requestedAmount",
        meta: { label: "Amount", numeric: true, mobileCard: "secondary" },
        header: ({ column }) => <DataTableColumnHeader column={column} title="Amount" numeric />,
        cell: ({ row }) => <span>{formatINR(row.original.requestedAmount)}</span>,
        enableSorting: true,
      },
      {
        accessorKey: "tenureMonths",
        meta: { label: "Tenure", numeric: true },
        header: ({ column }) => <DataTableColumnHeader column={column} title="Tenure" numeric />,
        cell: ({ row }) => <span>{row.original.tenureMonths} mo</span>,
        enableSorting: false,
      },
      {
        accessorKey: "status",
        meta: { label: "Status", mobileCard: "primary" },
        header: ({ column }) => <DataTableColumnHeader column={column} title="Status" />,
        cell: ({ row }) => <StatusBadge status={row.original.status} />,
        enableSorting: true,
      },
      {
        accessorKey: "updatedAt",
        meta: { label: "Updated", mobileCard: "secondary" },
        header: ({ column }) => <DataTableColumnHeader column={column} title="Updated" />,
        cell: ({ row }) => (
          <span className="text-foreground-muted text-sm" title={row.original.updatedAt}>
            {safeRelative(row.original.updatedAt)}
          </span>
        ),
        enableSorting: true,
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

  const handleSortingChange = useCallback(
    (next: SortingState) => {
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
    [filters, onFiltersChange],
  );

  return (
    <AdminEntityDataTable
      dataSlot="loan-applications-table"
      className={cn("gap-3", className)}
      columns={columns}
      rows={rows}
      total={total}
      page={page}
      pageSize={pageSize}
      loading={isLoading}
      skeletonRows={10}
      rowIdKey="id"
      ariaLabel="Loan applications"
      sorting={sorting}
      manualSorting
      onSortingChange={handleSortingChange}
      onPaginationChange={handlePaginationChange}
      getRowAction={(row) => navigate(`/loan-applications/${row.original.id}`)}
      getRowTestId={(row) => `loan-applications-row-${row.original.id}`}
      getRowAriaLabel={(row) => `Open application ${shortId(row.original.id)}`}
      empty={
        <EmptyState
          variant="filtered-empty"
          icon={Inbox}
          title="No applications match these filters."
          description="Try clearing some filters or widening the search."
        />
      }
    />
  );
}
