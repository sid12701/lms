import { useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import type { ColumnDef } from "@tanstack/react-table";
import { Folder } from "lucide-react";
import { PageHeader } from "@/components/app/layout/PageHeader";
import { EmptyState } from "@/components/app/feedback/EmptyState";
import { ErrorState } from "@/components/app/feedback/ErrorState";
import { AdminEntityDataTable } from "@/components/app/data/AdminEntityDataTable";
import { LspLinkCardGrid } from "@/features/home/components/LspLinkCardGrid";
import { StatusBadge } from "@/components/app/status/StatusBadge";
import { apiLoanStatus } from "@/lib/loan-application-status";
import { formatINR } from "@/lib/format";
import { fetchMyLoansPage, type MyLoanListRow } from "./api";
import { safeApiMessage } from "./utils";

const DEFAULT_PAGE_SIZE = 25;

const COLUMNS: ColumnDef<MyLoanListRow>[] = [
  {
    accessorKey: "externalLoanId",
    meta: { label: "Reference", mobileCard: "primary" },
    header: () => <span>Reference</span>,
    cell: ({ row }) => (
      <span className="text-primary font-mono text-xs">
        {row.original.externalLoanId ?? row.original.id.slice(0, 8)}
      </span>
    ),
  },
  {
    accessorKey: "borrowerFullName",
    meta: { label: "Borrower", mobileCard: "secondary" },
    header: () => <span>Borrower</span>,
    cell: ({ row }) => <span>{row.original.borrowerFullName}</span>,
  },
  {
    accessorKey: "productName",
    meta: { label: "Product" },
    header: () => <span>Product</span>,
    cell: ({ row }) => <span className="text-foreground-muted">{row.original.productName}</span>,
  },
  {
    accessorKey: "requestedAmount",
    meta: { label: "Amount", numeric: true, mobileCard: "secondary" },
    header: () => <span>Amount</span>,
    cell: ({ row }) => (
      <span className="tabular-nums">{formatINR(Number(row.original.requestedAmount ?? 0))}</span>
    ),
  },
  {
    accessorKey: "status",
    meta: { label: "Status", mobileCard: "actions" },
    header: () => <span>Status</span>,
    cell: ({ row }) => <StatusBadge status={apiLoanStatus(row.original.status)} variant="subtle" />,
  },
];

export function MyLoansPage() {
  const navigate = useNavigate();
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE);
  const [items, setItems] = useState<MyLoanListRow[]>([]);
  const [total, setTotal] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    async function run(): Promise<void> {
      setLoading(true);
      setError(null);
      try {
        const result = await fetchMyLoansPage({
          offset: page * pageSize,
          limit: pageSize,
        });
        if (cancelled) return;
        setItems(result.items);
        setTotal(result.totalCount);
      } catch (err) {
        if (cancelled) return;
        setError(safeApiMessage(err, "Failed to load loans."));
      } finally {
        if (!cancelled) setLoading(false);
      }
    }
    void run();
    return () => {
      cancelled = true;
    };
  }, [page, pageSize]);

  const columns = useMemo(() => COLUMNS, []);

  return (
    <div className="flex flex-col gap-6 p-6">
      <PageHeader
        eyebrow="LSP workspace"
        title="Loan applications"
        description="Loans and applications for your lending partner."
      />
      <LspLinkCardGrid />
      {error ? (
        <ErrorState
          title="Couldn't load loans"
          description={error}
          retry={{
            label: "Retry",
            onClick: () => window.location.reload(),
          }}
        />
      ) : (
        <AdminEntityDataTable
          dataSlot="my-loans-table"
          columns={columns}
          rows={items}
          total={total}
          page={page}
          pageSize={pageSize}
          loading={loading}
          rowIdKey="id"
          ariaLabel="Loan applications"
          onPaginationChange={(next) => {
            setPage(next.pageIndex);
            setPageSize(next.pageSize);
          }}
          getRowAction={(row) => navigate(`/my-loans/${row.original.id}`)}
          getRowAriaLabel={(row) => `Open loan for ${row.original.borrowerFullName}`}
          empty={
            <EmptyState
              icon={Folder}
              title="No loans yet"
              description="When your LSP originates loans through Bhawana, they will appear here."
            />
          }
        />
      )}
    </div>
  );
}

export default MyLoansPage;
export const Component = MyLoansPage;
