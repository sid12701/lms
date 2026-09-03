/**
 * Repayments tab — posted payment transactions for a loan application.
 *
 * Density: COMFORTABLE per D7.
 *
 * Columns: posted at (absolute + relative), amount (INR, right-aligned,
 * tabular-nums), mode (PaymentChannel), payment reference, transaction id
 * (short uuid, mono). The Phase-5 contract does not surface a payment status
 * column — every posted row is settled (the backend rejects partials per
 * BR-13). We render a human "Posted" badge so the column reads sensibly
 * when the schema evolves.
 */
import { useMemo } from "react";
import { Receipt } from "lucide-react";
import type { ColumnDef } from "@tanstack/react-table";
import { AbsoluteRelativeTime } from "@/components/app/misc/AbsoluteRelativeTime";
import { PaymentStatusBadge } from "@/components/app/repayment/PaymentStatusBadge";
import { DataTable } from "@/components/app/data/DataTable";
import { EmptyState } from "@/components/app/feedback/EmptyState";
import { ErrorState } from "@/components/app/feedback/ErrorState";
import { TableSkeleton } from "@/components/app/feedback/Skeletons";
import { isNotFoundApiError } from "@/lib/api/api-errors";
import { mapApiErrorMessage } from "@/lib/api/user-messages";
import { formatINR } from "@/lib/format";
import type { PaymentTransaction } from "@/types";
import { useLoanApplicationRepayments } from "../../hooks/useLoanApplicationRepayments";

export interface RepaymentsTabProps {
  applicationId: string;
}

/** Short prefix of UUID-like values while preserving human references. */
function shortId(value: string): string {
  return /^[0-9a-f]{8}-[0-9a-f-]{27}$/i.test(value) ? value.slice(0, 8) : value;
}

const COLUMNS: ColumnDef<PaymentTransaction>[] = [
  {
    id: "postedAt",
    header: "Posted at",
    accessorKey: "postedAt",
    cell: ({ row }) => <AbsoluteRelativeTime iso={row.original.postedAt} />,
  },
  {
    id: "amount",
    header: "Amount",
    accessorKey: "amount",
    meta: { numeric: true, label: "Amount" },
    cell: ({ row }) => formatINR(row.original.amount, { decimals: 2 }),
  },
  {
    id: "mode",
    header: "Mode",
    accessorKey: "channel",
    cell: ({ row }) => <span className="text-foreground text-sm">{row.original.channel}</span>,
  },
  {
    id: "status",
    header: "Status",
    cell: () => <PaymentStatusBadge status="POSTED" />,
  },
  {
    id: "reference",
    header: "Reference",
    cell: ({ row }) => {
      const ref = row.original.reference?.trim() || row.original.installmentId;
      return (
        <code className="text-foreground-muted font-mono text-xs">{ref ? shortId(ref) : "—"}</code>
      );
    },
  },
  {
    id: "transactionId",
    header: "Transaction",
    cell: ({ row }) => (
      <code className="text-foreground-muted font-mono text-xs">{shortId(row.original.id)}</code>
    ),
  },
];

export function RepaymentsTab({ applicationId }: RepaymentsTabProps) {
  const query = useLoanApplicationRepayments(applicationId);

  const data = useMemo<PaymentTransaction[]>(
    () => (query.data?.payments ? [...query.data.payments] : []),
    [query.data],
  );

  if (query.isPending) {
    return <TableSkeleton rows={5} cols={6} />;
  }
  if (query.isError) {
    if (isNotFoundApiError(query.error)) {
      // No loan account exists yet (e.g. still awaiting approval) — an
      // expected pre-disbursement state, not a failure.
      return (
        <EmptyState
          icon={Receipt}
          title="No repayments yet"
          description="Repayments become available once the loan is disbursed."
        />
      );
    }
    return (
      <ErrorState
        title="Couldn't load repayments"
        description={mapApiErrorMessage(query.error, "Please try again.")}
        retry={{ onClick: () => void query.refetch() }}
      />
    );
  }

  return (
    <div data-slot="repayments-tab" className="flex flex-col gap-4">
      <DataTable
        columns={COLUMNS}
        data={data}
        ariaLabel="Posted repayments"
        density="comfortable"
        rowIdKey="id"
        empty={
          <EmptyState
            icon={Receipt}
            title="No payments posted yet"
            description="Repayments will appear here once the borrower posts the first installment."
          />
        }
      />
    </div>
  );
}
