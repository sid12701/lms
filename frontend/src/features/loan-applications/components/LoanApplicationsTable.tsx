/**
 * Triage table for `/loan-applications`.
 *
 * Server-paged + server-sorted; render-only. Row click opens detail.
 */
import { useCallback, useMemo } from "react";
import { useNavigate } from "react-router-dom";
import { Inbox } from "lucide-react";
import type { ColumnDef, PaginationState, SortingState } from "@tanstack/react-table";
import { AdminEntityDataTable } from "@/components/app/data/AdminEntityDataTable";
import { DataTableColumnHeader } from "@/components/app/data/DataTableColumnHeader";
import { EmptyState } from "@/components/app/feedback/EmptyState";
import { AbsoluteRelativeTime } from "@/components/app/misc/AbsoluteRelativeTime";
import { StatusBadge } from "@/components/app/status/StatusBadge";
import { resolveStatusMeta } from "@/components/app/status/statusBadgeMeta";
import { formatINR } from "@/lib/format";
import { useColumnVisibility } from "@/lib/use-column-visibility";
import { cn } from "@/lib/utils";
import type {
  LoanApplicationListFilters,
  LoanApplicationListItem,
  LoanApplicationListResponse,
} from "../types";

const SORTABLE_COLUMNS = new Set(["createdAt", "updatedAt", "requestedAmount", "status"]);

/**
 * Identifier columns ship hidden. They are lookup keys — reached from the
 * detail page or by filtering — not scanning keys. Operators who want them back
 * restore them under "Columns" and the choice persists.
 */
const COLUMN_VISIBILITY_STORAGE_KEY = "bhawana.loan-applications.columns";
const DEFAULT_COLUMN_VISIBILITY = {
  externalLoanId: false,
  accountNumber: false,
  id: false,
} as const;

type SortKey = NonNullable<LoanApplicationListFilters["sortBy"]>;

function shortId(id: string): string {
  return `${id.slice(0, 8)}…`;
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

  const [columnVisibility, setColumnVisibility] = useColumnVisibility(
    COLUMN_VISIBILITY_STORAGE_KEY,
    DEFAULT_COLUMN_VISIBILITY,
  );

  /*
   * Column order follows what triage actually reads: who → what state → how
   * much → whose book → when. The three identifier columns come last and ship
   * hidden (see DEFAULT_COLUMN_VISIBILITY): they are lookup keys, not scanning
   * keys, and together they previously consumed 44% of the table width — which
   * pushed Status, the one column triage turns on, off-screen entirely.
   * They remain one click away under "Columns", and that choice persists.
   */
  const columns = useMemo<ColumnDef<LoanApplicationListItem>[]>(
    () => [
      {
        accessorKey: "borrowerNameMasked",
        meta: { label: "Borrower", mobileCard: "primary" },
        header: ({ column }) => <DataTableColumnHeader column={column} title="Borrower" />,
        cell: ({ row }) => <span className="font-medium">{row.original.borrowerNameMasked}</span>,
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
        accessorKey: "requestedAmount",
        meta: { label: "Amount", numeric: true, mobileCard: "secondary" },
        header: ({ column }) => <DataTableColumnHeader column={column} title="Amount" numeric />,
        cell: ({ row }) => <span>{formatINR(row.original.requestedAmount)}</span>,
        enableSorting: true,
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
        accessorKey: "tenureMonths",
        meta: { label: "Tenure", numeric: true },
        header: ({ column }) => <DataTableColumnHeader column={column} title="Tenure" numeric />,
        cell: ({ row }) => <span>{row.original.tenureMonths} mo</span>,
        enableSorting: false,
      },
      {
        accessorKey: "updatedAt",
        meta: { label: "Updated", mobileCard: "secondary" },
        header: ({ column }) => <DataTableColumnHeader column={column} title="Updated" />,
        cell: ({ row }) => <AbsoluteRelativeTime iso={row.original.updatedAt} variant="relative" />,
        enableSorting: true,
      },
      {
        accessorKey: "externalLoanId",
        meta: { label: "LSP loan ID" },
        header: ({ column }) => <DataTableColumnHeader column={column} title="LSP loan ID" />,
        cell: ({ row }) =>
          row.original.externalLoanId ?? <span className="text-foreground-muted">—</span>,
        enableSorting: false,
      },
      {
        accessorKey: "accountNumber",
        meta: { label: "Bhawana loan ID" },
        header: ({ column }) => <DataTableColumnHeader column={column} title="Bhawana loan ID" />,
        cell: ({ row }) => (
          <span className="font-mono text-xs break-all">
            {row.original.accountNumber ?? <span className="text-foreground-muted">—</span>}
          </span>
        ),
        enableSorting: false,
      },
      {
        accessorKey: "id",
        meta: { label: "Internal ID" },
        header: ({ column }) => <DataTableColumnHeader column={column} title="Internal ID" />,
        cell: ({ row }) => (
          <span className="font-mono text-xs" title={row.original.id}>
            {shortId(row.original.id)}
          </span>
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
      columnVisibility={columnVisibility}
      onColumnVisibilityChange={setColumnVisibility}
      getRowAction={(row) => navigate(`/loan-applications/${row.original.id}`)}
      getRowTestId={(row) => `loan-applications-row-${row.original.id}`}
      /*
       * Named by what the row *is*, not by its uuid.
       *
       * This used to read `Open application 845b299f…`, so a screen-reader user
       * arrowing the list heard 25 opaque identifiers and could not tell one
       * row from another without entering each. Borrower, status and amount are
       * the three facts the sighted columns lead with.
       */
      getRowAriaLabel={(row) =>
        [
          `Open application for ${row.original.borrowerNameMasked}`,
          resolveStatusMeta(row.original.status).label,
          formatINR(row.original.requestedAmount),
          row.original.externalLoanId ? `LSP ref ${row.original.externalLoanId}` : null,
        ]
          .filter(Boolean)
          .join(", ")
      }
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
