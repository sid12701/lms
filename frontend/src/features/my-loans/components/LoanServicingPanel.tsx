import { Loader2 } from "lucide-react";

import { ErrorState } from "@/components/app/feedback/ErrorState";
import { ScheduleTable, lspScheduleToRepaymentInstallments } from "@/components/app/repayment";
import { PaymentStatusBadge } from "@/components/app/repayment/PaymentStatusBadge";
import { formatINR } from "@/lib/format";
import { useMemo } from "react";
import { safeApiMessage } from "../utils";
import { useMyLoanServicing } from "../hooks/useMyLoanServicing";

export interface LoanServicingPanelProps {
  loanAccountId: string;
}

export function LoanServicingPanel({ loanAccountId }: LoanServicingPanelProps) {
  const { schedule, payments, loading, error, refetch } = useMyLoanServicing(loanAccountId);

  const installments = useMemo(
    () => (schedule ? lspScheduleToRepaymentInstallments(schedule, loanAccountId) : []),
    [loanAccountId, schedule],
  );

  return (
    <section
      data-slot="loan-servicing-card"
      className="border-border bg-background rounded-container flex flex-col gap-4 border p-5"
    >
      <h2 className="text-base font-semibold">Servicing</h2>
      {loading ? (
        <div role="status" className="text-foreground-muted flex items-center gap-2 text-sm">
          <Loader2 aria-hidden="true" className="h-4 w-4 animate-spin" />
          Loading repayment schedule and payments…
        </div>
      ) : error ? (
        <ErrorState
          title="Couldn't load servicing details"
          description={safeApiMessage(error, "Failed to load servicing details.")}
          retry={{
            label: "Retry",
            onClick: () => {
              void refetch();
            },
          }}
        />
      ) : (
        <>
          <div className="flex flex-col gap-2">
            <h3 className="text-sm font-semibold">Repayment schedule</h3>
            {installments.length > 0 ? (
              <ScheduleTable installments={installments} ariaLabel="LSP repayment schedule" />
            ) : (
              <p className="text-foreground-muted text-xs">
                No repayment schedule is available yet.
              </p>
            )}
          </div>

          <div className="flex flex-col gap-2">
            <h3 className="text-sm font-semibold">Payments</h3>
            {payments && payments.length > 0 ? (
              <div className="overflow-x-auto">
                <table className="w-full min-w-xl text-left text-xs">
                  <thead className="text-foreground-muted border-border border-b">
                    <tr>
                      <th className="px-2 py-2 font-medium">Date</th>
                      <th className="px-2 py-2 font-medium">Amount</th>
                      <th className="px-2 py-2 font-medium">Channel</th>
                      <th className="px-2 py-2 font-medium">Reference</th>
                      <th className="px-2 py-2 font-medium">Status</th>
                    </tr>
                  </thead>
                  <tbody>
                    {payments.map((row) => (
                      <tr key={row.id} className="border-border border-b last:border-b-0">
                        <td className="px-2 py-2 font-mono">{row.paymentDate}</td>
                        <td className="px-2 py-2 font-mono">{formatINR(row.amount)}</td>
                        <td className="px-2 py-2">{row.channel}</td>
                        <td className="px-2 py-2 font-mono">{row.reference ?? "—"}</td>
                        {/* Same badge the ops repayments ledger uses, so an
                            LSP agent and the Bhawana operator they call read
                            one vocabulary for one payment. */}
                        <td className="px-2 py-2">
                          <PaymentStatusBadge status={row.status} />
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            ) : (
              <p className="text-foreground-muted text-xs">No payments recorded yet.</p>
            )}
          </div>
        </>
      )}
    </section>
  );
}
