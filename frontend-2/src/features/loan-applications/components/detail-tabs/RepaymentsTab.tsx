/**
 * Repayments tab — posted payment transactions for a loan application.
 *
 * Density: COMFORTABLE per D7.
 *
 * Columns: posted at (relative + tooltip with full timestamp), amount (INR,
 * right-aligned, tabular-nums), mode (PaymentChannel), payment reference,
 * transaction id (short uuid, mono). The Phase-5 contract
 * does not surface a payment status column — every posted row is settled
 * (the mock router rejects partials per BR-13). We render a static
 * "POSTED" badge so the column reads sensibly when the schema evolves.
 */
import { useMemo } from "react";
import { Receipt } from "lucide-react";
import type { ColumnDef } from "@tanstack/react-table";
import { Badge } from "@/components/ui/badge";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { DataTable } from "@/components/app/data/DataTable";
import { EmptyState } from "@/components/app/feedback/EmptyState";
import { ErrorState } from "@/components/app/feedback/ErrorState";
import { TableSkeleton } from "@/components/app/feedback/Skeletons";
import { formatDateTime, formatINR, formatRelative } from "@/lib/format";
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
    cell: ({ row }) => {
      const iso = row.original.postedAt;
      return (
        <Tooltip>
          <TooltipTrigger asChild>
            <span className="text-foreground text-sm">{formatRelative(iso)}</span>
          </TooltipTrigger>
          <TooltipContent>{formatDateTime(iso)}</TooltipContent>
        </Tooltip>
      );
    },
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
    cell: () => (
      <Badge variant="outline" data-tone="success">
        POSTED
      </Badge>
    ),
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
    return (
      <ErrorState
        title="Couldn't load repayments"
        description={query.error instanceof Error ? query.error.message : undefined}
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

export default RepaymentsTab;
