import { Loader2 } from "lucide-react";

import { ErrorState } from "@/components/app/feedback/ErrorState";
import { formatINR } from "@/lib/format";
import { safeApiMessage } from "../utils";
import { useMyLoanServicing } from "../hooks/useMyLoanServicing";

export interface LoanServicingPanelProps {
  loanAccountId: string;
}

export function LoanServicingPanel({ loanAccountId }: LoanServicingPanelProps) {
  const { schedule, payments, loading, error, refetch } = useMyLoanServicing(loanAccountId);

  return (
    <section
      data-slot="loan-servicing-card"
      className="border-border bg-background flex flex-col gap-4 rounded-md border p-5"
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
            {schedule && schedule.length > 0 ? (
              <div className="overflow-x-auto">
                <table className="w-full min-w-160 text-left text-xs">
                  <thead className="text-foreground-muted border-border border-b">
                    <tr>
                      <th className="px-2 py-2 font-medium">#</th>
                      <th className="px-2 py-2 font-medium">Due date</th>
                      <th className="px-2 py-2 font-medium">Amount</th>
                      <th className="px-2 py-2 font-medium">Paid</th>
                      <th className="px-2 py-2 font-medium">Outstanding</th>
                      <th className="px-2 py-2 font-medium">Status</th>
                      <th className="px-2 py-2 font-medium">DPD</th>
                    </tr>
                  </thead>
                  <tbody>
                    {schedule.map((row) => (
                      <tr key={row.id} className="border-border border-b last:border-b-0">
                        <td className="px-2 py-2 font-mono">{row.installmentNumber}</td>
                        <td className="px-2 py-2 font-mono">{row.dueDate}</td>
                        <td className="px-2 py-2 font-mono">{formatINR(row.installmentAmount)}</td>
                        <td className="px-2 py-2 font-mono">{formatINR(row.paidAmount)}</td>
                        <td className="px-2 py-2 font-mono">{formatINR(row.outstandingAmount)}</td>
                        <td className="px-2 py-2">{row.status}</td>
                        <td className="px-2 py-2 font-mono">{row.daysPastDue ?? "—"}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
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
                        <td className="px-2 py-2">{row.status}</td>
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
