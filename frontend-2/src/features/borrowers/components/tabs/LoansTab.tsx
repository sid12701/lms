/**
 * Loans tab — every loan application this borrower has had with any LSP.
 *
 * Lighter than the triage `LoanApplicationsTable` (no borrower column,
 * client-side sort/page since the response is bounded by what the
 * borrower personally owns). Click a row to open the application's
 * detail surface.
 *
 * Per the Phase 6 plan (§7), density switches to `compact` when the row
 * count exceeds 12 — the table can otherwise dominate the viewport on
 * borrowers with many historical applications. The wrapper sets
 * `data-density` so the contained DataTable picks the right padding.
 */
import { useMemo } from "react";
import { useNavigate } from "react-router-dom";
import { Inbox } from "lucide-react";
import {
  type ColumnDef,
  type Row,
  type SortingState,
  flexRender,
  getCoreRowModel,
  getSortedRowModel,
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
import { TableSkeleton } from "@/components/app/feedback/Skeletons";
import { EmptyState } from "@/components/app/feedback/EmptyState";
import { ErrorState } from "@/components/app/feedback/ErrorState";
import { StatusBadge } from "@/components/app/status/StatusBadge";
import { TABULAR_ATTR } from "@/lib/tabular-nums";
import { formatINR } from "@/lib/format";
import { cn } from "@/lib/utils";
import { useBorrowerLoans } from "../../hooks/useBorrowerLoans";
import type { BorrowerLoanRow } from "../../types";

const SORTABLE_COLUMNS = new Set(["createdAt", "updatedAt", "requestedAmount", "status"]);

function shortId(id: string): string {
  return id.length > 8 ? `${id.slice(0, 8)}…` : id;
}

function safeRelative(iso: string): string {
  try {
    return formatDistanceToNow(new Date(iso), { addSuffix: true });
  } catch {
    return iso;
  }
}

export interface LoansTabProps {
  borrowerId: string;
}

/**
 * Loans tab — TanStack-Table-backed list. Sort is client-side because the
 * mock endpoint returns the full row set in one shot (no pagination on
 * the per-borrower view).
 */
export function LoansTab({ borrowerId }: LoansTabProps) {
  const navigate = useNavigate();
  const query = useBorrowerLoans(borrowerId);

  const loans = useMemo<BorrowerLoanRow[]>(
    () => (query.data?.loans ? [...query.data.loans] : []),
    [query.data],
  );

  const density = loans.length > 12 ? "compact" : "comfortable";

  const columns = useMemo<ColumnDef<BorrowerLoanRow>[]>(
    () => [
      {
        accessorKey: "applicationId",
        meta: { label: "ID" },
        header: ({ column }) => <DataTableColumnHeader column={column} title="ID" />,
        cell: ({ row }) => (
          <span className="font-mono text-xs" title={row.original.applicationId}>
            {shortId(row.original.applicationId)}
          </span>
        ),
        enableSorting: false,
      },
      {
        accessorKey: "externalLoanId",
        meta: { label: "External ID" },
        header: ({ column }) => <DataTableColumnHeader column={column} title="External ID" />,
        cell: ({ row }) =>
          row.original.externalLoanId ?? <span className="text-foreground-muted">—</span>,
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
        header: ({ column }) => <DataTableColumnHeader column={column} title="Product" />,
        cell: ({ row }) => <span>{row.original.productName}</span>,
        enableSorting: false,
      },
      {
        accessorKey: "requestedAmount",
        meta: { label: "Amount", numeric: true },
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
        meta: { label: "Status" },
        header: ({ column }) => <DataTableColumnHeader column={column} title="Status" />,
        cell: ({ row }) => <StatusBadge status={row.original.status} />,
        enableSorting: true,
      },
      {
        accessorKey: "updatedAt",
        meta: { label: "Updated" },
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

  const table = useReactTable({
    data: loans,
    columns,
    getCoreRowModel: getCoreRowModel(),
    getSortedRowModel: getSortedRowModel(),
    getRowId: (row) => row.applicationId,
    enableSortingRemoval: true,
    initialState: {
      sorting: [{ id: "updatedAt", desc: true }] satisfies SortingState,
    },
  });

  const openRow = (row: Row<BorrowerLoanRow>) => {
    navigate(`/loan-applications/${row.original.applicationId}`);
  };

  if (query.isPending) {
    return (
      <div data-slot="loans-tab-loading" data-density={density}>
        <TableSkeleton rows={8} cols={8} />
      </div>
    );
  }

  if (query.isError) {
    return (
      <ErrorState
        title="Couldn't load loans"
        description={query.error instanceof Error ? query.error.message : undefined}
        retry={{ label: "Retry", onClick: () => void query.refetch() }}
      />
    );
  }

  if (loans.length === 0) {
    return (
      <div data-slot="loans-tab" data-density="comfortable">
        <EmptyState
          icon={Inbox}
          title="No loan applications"
          description="This borrower has no loan applications."
        />
      </div>
    );
  }

  const cellPad = density === "compact" ? "px-2.5 py-1.5" : "px-3 py-3";
  const headPad = density === "compact" ? "h-8 px-2.5" : "h-10 px-3";

  return (
    <div data-slot="loans-tab" data-density={density} className="flex flex-col gap-3">
      <div
        className="border-border bg-surface shadow-e1 overflow-hidden rounded-md border"
        data-density={density}
      >
        <Table aria-label="Borrower loans">
          <TableHeader className="bg-surface-muted/60 sticky top-0 z-10 backdrop-blur">
            {table.getHeaderGroups().map((headerGroup) => (
              <TableRow key={headerGroup.id} className="border-border hover:bg-transparent">
                {headerGroup.headers.map((header) => {
                  const meta = header.column.columnDef.meta;
                  const numeric = meta?.numeric ?? false;
                  const sortable = SORTABLE_COLUMNS.has(header.column.id);
                  const sorted = header.column.getIsSorted();
                  const ariaSort: "ascending" | "descending" | "none" | undefined = sortable
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
                        : flexRender(header.column.columnDef.header, header.getContext())}
                    </TableHead>
                  );
                })}
              </TableRow>
            ))}
          </TableHeader>
          <TableBody>
            {table.getRowModel().rows.map((row) => (
              <TableRow
                key={row.id}
                tabIndex={0}
                role="button"
                aria-label={`Open application ${shortId(row.original.applicationId)}`}
                data-testid={`borrower-loan-row-${row.original.applicationId}`}
                data-interactive="true"
                onClick={() => openRow(row)}
                onKeyDown={(event) => {
                  if (event.key === "Enter" || event.key === " ") {
                    event.preventDefault();
                    openRow(row);
                  }
                }}
                className={cn(
                  "border-border focus-visible:ring-ring/50 cursor-pointer outline-none focus-visible:ring-2",
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
            ))}
          </TableBody>
        </Table>
      </div>
    </div>
  );
}

export default LoansTab;
