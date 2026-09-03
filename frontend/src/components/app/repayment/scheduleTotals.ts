import type { RepaymentInstallment } from "@/schemas/loan-account";

export interface ScheduleTotals {
  /** Sum of scheduled installment amounts across the full schedule. */
  totalInstallmentAmount: number;
  /** Sum of per-installment outstanding balances (remaining book). */
  totalOutstanding: number;
  /**
   * Outstanding on installments due on or before `asOf` (default: today).
   * Answers "what do I owe right now" without counting future EMIs.
   */
  outstandingAsOfToday: number;
}

/**
 * Today's *local* calendar date as `YYYY-MM-DD`.
 *
 * `toISOString()` would answer in UTC, which is the previous day for the first
 * 5.5 hours of every IST day — an operator opening the schedule at 02:00 IST
 * would see an installment due that morning excluded from "outstanding as of
 * today". Due dates are calendar dates, so they must be compared against the
 * viewer's calendar date.
 */
function todayIsoDate(): string {
  const now = new Date();
  const month = String(now.getMonth() + 1).padStart(2, "0");
  const day = String(now.getDate()).padStart(2, "0");
  return `${now.getFullYear()}-${month}-${day}`;
}

/**
 * Derive schedule-level totals from installment rows. Both ops and LSP
 * surfaces share this helper so the summary strip stays consistent.
 */
export function computeScheduleTotals(
  installments: readonly RepaymentInstallment[],
  asOf: string = todayIsoDate(),
): ScheduleTotals {
  return installments.reduce<ScheduleTotals>(
    (acc, installment) => {
      const outstanding = installment.outstandingAmount;
      return {
        totalInstallmentAmount: acc.totalInstallmentAmount + installment.installmentAmount,
        totalOutstanding: acc.totalOutstanding + outstanding,
        outstandingAsOfToday:
          acc.outstandingAsOfToday + (installment.dueDate <= asOf ? outstanding : 0),
      };
    },
    { totalInstallmentAmount: 0, totalOutstanding: 0, outstandingAsOfToday: 0 },
  );
}
