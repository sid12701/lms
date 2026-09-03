import { useQuery } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import type { ColumnDef } from "@tanstack/react-table";
import { Folder, SearchX } from "lucide-react";
import { PageHeader } from "@/components/app/layout/PageHeader";
import { EmptyState } from "@/components/app/feedback/EmptyState";
import { ErrorState } from "@/components/app/feedback/ErrorState";
import { AdminEntityDataTable } from "@/components/app/data/AdminEntityDataTable";
import { StatusBadge } from "@/components/app/status/StatusBadge";
import { apiLoanStatus } from "@/lib/loan-application-status";
import { formatINR } from "@/lib/format";
import { useUrlFilters } from "@/lib/url-state";
import { fetchMyLoansPage, type MyLoanListRow } from "./api";
import { MyLoansFilterBar } from "./components/MyLoansFilterBar";
import { MyLoanListFilters } from "./types";
import { safeApiMessage } from "./utils";

const DEFAULT_PAGE_SIZE = 25;

const COLUMNS: ColumnDef<MyLoanListRow>[] = [
  {
    accessorKey: "externalLoanId",
    meta: { label: "Reference", mobileCard: "primary" },
    header: () => <span>Reference</span>,
    cell: ({ row }) => (
      <span className="text-primary-tinted font-mono text-xs">
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
  // Pagination and filters share the URL so a link to "this borrower's loan"
  // reopens the same view — the support workflow this surface exists for.
  const [filters, setFilters] = useUrlFilters(MyLoanListFilters);
  const page = filters.page ?? 0;
  const pageSize = filters.pageSize ?? DEFAULT_PAGE_SIZE;
  const query = useQuery({
    queryKey: ["my-loans", page, pageSize, filters.q ?? null, filters.status ?? null],
    queryFn: () =>
      fetchMyLoansPage({
        offset: page * pageSize,
        limit: pageSize,
        q: filters.q,
        status: filters.status,
      }),
    placeholderData: (previousData) => previousData,
  });

  const items: MyLoanListRow[] = query.data?.items ?? [];
  const total = query.data?.totalCount ?? 0;
  // An empty book and an over-filtered one need different guidance.
  const hasActiveFilters = Boolean(filters.q?.trim() || filters.status);

  return (
    <div className="flex flex-col gap-6 p-6">
      <PageHeader title="My loans" description="Loans and applications for your lending partner." />
      <MyLoansFilterBar resultCount={query.data?.totalCount} />
      {query.isError ? (
        <ErrorState
          title="Couldn't load loans"
          description={safeApiMessage(query.error, "Failed to load loans.")}
          retry={{
            label: "Retry",
            onClick: () => {
              void query.refetch();
            },
          }}
        />
      ) : (
        <AdminEntityDataTable
          dataSlot="my-loans-table"
          columns={COLUMNS}
          rows={items}
          total={total}
          page={page}
          pageSize={pageSize}
          loading={query.isFetching}
          rowIdKey="id"
          ariaLabel="My loans"
          onPaginationChange={(next) => {
            setFilters({ page: next.pageIndex, pageSize: next.pageSize });
          }}
          getRowAction={(row) => navigate(`/my-loans/${row.original.id}`)}
          getRowAriaLabel={(row) => `Open loan for ${row.original.borrowerFullName}`}
          empty={
            hasActiveFilters ? (
              <EmptyState
                icon={SearchX}
                title="No loans match these filters"
                description="Try a different search term or status, or clear the filters to see the full book."
              />
            ) : (
              <EmptyState
                icon={Folder}
                title="No loans yet"
                description="When your LSP originates loans through Bhawana, they will appear here."
              />
            )
          }
        />
      )}
    </div>
  );
}

export default MyLoansPage;
export const Component = MyLoansPage;
