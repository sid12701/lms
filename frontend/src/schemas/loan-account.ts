/**
 * Loan account + repayment schedule + installment.
 *
 * Per blueprint §13 (`docs/architecture/lms-blueprint.md`).
 * BR-12: schedule frozen flips to true at disbursement.
 *
 * LoanAccountStatus must match backend `com.bhawana.lms.domain.LoanAccountStatus`
 * (OpenAPI currently emits `accountStatus` as a bare string — Spec S8).
 */
import { z } from "zod";
import {
  LOAN_ACCOUNT_STATUSES,
  type LoanAccountStatus as GeneratedLoanAccountStatus,
} from "@/lib/api/generated/loan-account-status";
import { unknownLoanApplicationStatusLabel } from "@/lib/loan-application-status";
import { Iso8601, IsoDate, MoneyINR, MoneyINRPositive, Uuid } from "./common";

/** Canonical loan-account statuses — keep in lockstep with the Java enum. */
export { LOAN_ACCOUNT_STATUSES };

export const LoanAccountStatus = z.enum(LOAN_ACCOUNT_STATUSES);
export type LoanAccountStatus = GeneratedLoanAccountStatus;

export function isLoanAccountStatus(value: string): value is LoanAccountStatus {
  return LoanAccountStatus.safeParse(value).success;
}

export const ClosureReason = z.enum(["FULLY_REPAID", "FORECLOSED", "CANCELLED"]);
type ClosureReason = z.infer<typeof ClosureReason>;

/**
 * Two spellings of "closed by foreclosure" reach the UI: this schema's
 * `FORECLOSED` and the backend enum's `LoanAccountClosureReason.FORECLOSURE`.
 * Both are storage identifiers, so either rendered raw puts a database value in
 * front of a partner. Unmapped codes degrade to `Unknown (raw)` so a new
 * closure reason stays readable — and visibly unmapped — until it lands here.
 */
const CLOSURE_REASON_LABELS: Record<string, string> = {
  FULLY_REPAID: "Fully repaid",
  FORECLOSED: "Foreclosed",
  FORECLOSURE: "Foreclosed",
  CANCELLED: "Cancelled",
};

export function closureReasonLabel(raw: string): string {
  const trimmed = raw.trim();
  return CLOSURE_REASON_LABELS[trimmed] ?? unknownLoanApplicationStatusLabel(trimmed);
}

export const LoanAccount = z.object({
  id: Uuid,
  applicationId: Uuid,
  /** Bhawana-issued account number, opaque string (printed/branded). */
  accountNumber: z.string().min(4).max(32),
  accountStatus: LoanAccountStatus,
  principal: MoneyINRPositive,
  tenureMonths: z.number().int().min(1).max(360),
  approvedAt: Iso8601,
  createdAt: Iso8601,
  closedAt: Iso8601.nullable(),
  closureReason: ClosureReason.nullable(),
});
export type LoanAccount = z.infer<typeof LoanAccount>;

export const ScheduleGenerator = z.enum(["PLATFORM", "LSP"]);
type ScheduleGenerator = z.infer<typeof ScheduleGenerator>;

export const RepaymentSchedule = z.object({
  id: Uuid,
  accountId: Uuid,
  generatedBy: ScheduleGenerator,
  /** BR-12: once true, schedule cannot be replaced. */
  frozen: z.boolean(),
  createdAt: Iso8601,
});
export type RepaymentSchedule = z.infer<typeof RepaymentSchedule>;

export const DelinquencyBucket = z.enum(["B0", "B1_30", "B31_60", "B61_90", "B90_PLUS"]);
export type DelinquencyBucket = z.infer<typeof DelinquencyBucket>;

export const InstallmentStatus = z.enum(["DUE", "PARTIALLY_PAID", "PAID", "OVERDUE"]);
export type InstallmentStatus = z.infer<typeof InstallmentStatus>;

export const RepaymentInstallment = z.object({
  id: Uuid,
  accountId: Uuid,
  scheduleId: Uuid,
  number: z.number().int().min(1).max(360),
  dueDate: IsoDate,
  principalDue: MoneyINR,
  interestDue: MoneyINR,
  installmentAmount: MoneyINR,
  paidAmount: MoneyINR,
  outstandingAmount: MoneyINR,
  /** Days past due — non-negative; calculated daily. */
  dpd: z.number().int().nonnegative(),
  delinquencyBucket: DelinquencyBucket,
  status: InstallmentStatus,
});
export type RepaymentInstallment = z.infer<typeof RepaymentInstallment>;
