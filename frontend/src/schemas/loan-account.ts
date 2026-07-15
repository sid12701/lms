/**
 * Loan account + repayment schedule + installment.
 *
 * Per blueprint §13 + UI pages.md "Repayment installment".
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
import { Iso8601, IsoDate, MoneyINR, MoneyINRPositive, Uuid } from "./common";

/** Canonical loan-account statuses — keep in lockstep with the Java enum. */
export { LOAN_ACCOUNT_STATUSES };

export const LoanAccountStatus = z.enum(LOAN_ACCOUNT_STATUSES);
export type LoanAccountStatus = GeneratedLoanAccountStatus;

export function isLoanAccountStatus(value: string): value is LoanAccountStatus {
  return LoanAccountStatus.safeParse(value).success;
}

export const ClosureReason = z.enum(["FULLY_REPAID", "FORECLOSED", "CANCELLED"]);
export type ClosureReason = z.infer<typeof ClosureReason>;

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
export type ScheduleGenerator = z.infer<typeof ScheduleGenerator>;

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
