import type { RepaymentInstallment } from "@/schemas/loan-account";
import { BUSINESS_TIME_ZONE } from "@/lib/format";

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

const BUSINESS_DAY_PARTS_FORMATTER = new Intl.DateTimeFormat("en-US", {
  timeZone: BUSINESS_TIME_ZONE,
  year: "numeric",
  month: "2-digit",
  day: "2-digit",
});

/**
 * Today's *business* calendar date as `YYYY-MM-DD` (Asia/Kolkata — the same
 * zone the backend uses for DPD and schedule anchoring, M09).
 *
 * `toISOString()` would answer in UTC, which is the previous day for the first
 * 5.5 hours of every IST day — an operator opening the schedule at 02:00 IST
 * would see an installment due that morning excluded from "outstanding as of
 * today". The viewer's browser zone is equally wrong: a reviewer in London at
 * 23:00 (04:30 IST the next day) must see the same business day the backend
 * counts DPD against. Due dates are calendar dates, so they are compared
 * against the business calendar date.
 */
function todayIsoDate(): string {
  const parts = BUSINESS_DAY_PARTS_FORMATTER.formatToParts(new Date());
  const get = (type: string) => parts.find((part) => part.type === type)?.value ?? "";
  return `${get("year")}-${get("month")}-${get("day")}`;
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
