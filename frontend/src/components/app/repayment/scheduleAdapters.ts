import type { RepaymentInstallment } from "@/schemas/loan-account";

const INSTALLMENT_STATUSES = new Set(["DUE", "PARTIALLY_PAID", "PAID", "OVERDUE"]);
const DELINQUENCY_BUCKETS = new Set(["B0", "B1_30", "B31_60", "B61_90", "B90_PLUS"]);

function safeInstallmentStatus(value: string): RepaymentInstallment["status"] {
  if (INSTALLMENT_STATUSES.has(value)) return value as RepaymentInstallment["status"];
  if (value === "SCHEDULED" || value === "PENDING") return "DUE";
  if (value === "SETTLED") return "PAID";
  return "DUE";
}

function safeDelinquencyBucket(
  value: string | null | undefined,
): RepaymentInstallment["delinquencyBucket"] {
  if (value && DELINQUENCY_BUCKETS.has(value)) {
    return value as RepaymentInstallment["delinquencyBucket"];
  }
  return "B0";
}

/** Minimal LSP schedule row shape before adaptation to `RepaymentInstallment`. */
export interface LspScheduleInstallmentRow {
  id: string;
  installmentNumber: number;
  dueDate: string;
  principalDue?: number;
  interestDue?: number;
  installmentAmount: number;
  paidAmount: number;
  outstandingAmount: number;
  status: string;
  daysPastDue: number | null;
  delinquencyBucket?: string | null;
}

/**
 * Map LSP servicing schedule rows into the shared `RepaymentInstallment`
 * projection consumed by `ScheduleTable`.
 */
export function lspScheduleToRepaymentInstallments(
  rows: readonly LspScheduleInstallmentRow[],
  accountId: string,
): RepaymentInstallment[] {
  return rows.map((row) => ({
    id: row.id,
    accountId,
    scheduleId: accountId,
    number: row.installmentNumber,
    dueDate: row.dueDate,
    principalDue: row.principalDue ?? 0,
    interestDue: row.interestDue ?? 0,
    installmentAmount: row.installmentAmount,
    paidAmount: row.paidAmount,
    outstandingAmount: row.outstandingAmount,
    dpd: row.daysPastDue ?? 0,
    delinquencyBucket: safeDelinquencyBucket(row.delinquencyBucket),
    status: safeInstallmentStatus(row.status),
  }));
}
