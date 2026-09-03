import type { ReactNode } from "react";
import { MetricHint } from "@/components/app/misc/MetricHint";
import { formatINR } from "@/lib/format";
import { cn } from "@/lib/utils";
import type { RepaymentInstallment } from "@/schemas/loan-account";
import { computeScheduleTotals } from "./scheduleTotals";

export interface ScheduleSummaryStripProps {
  installments: readonly RepaymentInstallment[];
  className?: string;
}

function SummaryItem({ label, value, hint }: { label: string; value: string; hint: ReactNode }) {
  return (
    <div className="flex flex-col gap-0.5">
      <dt className="text-foreground-muted text-eyebrow flex items-center gap-0.5 uppercase">
        <span>{label}</span>
        <MetricHint label={label} className="-my-1">
          {hint}
        </MetricHint>
      </dt>
      <dd className="text-foreground text-sm font-medium tabular-nums">{value}</dd>
    </div>
  );
}

/**
 * Compact totals row for repayment schedules — total installment amount,
 * remaining book outstanding, and amount due on or before today.
 */
export function ScheduleSummaryStrip({ installments, className }: ScheduleSummaryStripProps) {
  if (installments.length === 0) return null;

  const { totalInstallmentAmount, totalOutstanding, outstandingAsOfToday } =
    computeScheduleTotals(installments);

  return (
    <dl
      data-slot="schedule-summary-strip"
      className={cn(
        "border-border bg-surface-muted/40 rounded-container grid grid-cols-1 gap-3 border px-3 py-2.5 text-sm sm:grid-cols-3",
        className,
      )}
    >
      <SummaryItem
        label="Total installment amount"
        value={formatINR(totalInstallmentAmount, { decimals: 2 })}
        hint="Every scheduled installment across the full tenure, added up — principal plus interest, including installments already paid."
      />
      <SummaryItem
        label="Total outstanding"
        value={formatINR(totalOutstanding, { decimals: 2 })}
        hint="Unpaid balance across the whole schedule, including installments that are not due yet."
      />
      <SummaryItem
        label="Outstanding as of today"
        value={formatINR(outstandingAsOfToday, { decimals: 2 })}
        hint="Unpaid balance on installments due on or before today. Future installments are excluded, so this is what the borrower owes right now."
      />
    </dl>
  );
}
