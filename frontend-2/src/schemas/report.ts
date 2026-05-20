/**
 * MIS report request + summary + preview row.
 * Per UI pages.md "Portfolio MIS".
 */
import { z } from "zod";
import { Email, Iso8601, IsoDate, MoneyINR, Uuid } from "./common";
import { LoanStatus } from "./loan-application";
import { DelinquencyBucket } from "./loan-account";

export const ReportType = z.enum(["PORTFOLIO_MIS"]);
export type ReportType = z.infer<typeof ReportType>;

export const ReportStatus = z.enum(["QUEUED", "PROCESSING", "COMPLETED", "FAILED"]);
export type ReportStatus = z.infer<typeof ReportStatus>;

export const ReportFileMeta = z.object({
  storageKey: z.string().min(1).max(240),
  size: z.number().int().nonnegative(),
  rowCount: z.number().int().nonnegative(),
  generatedAt: Iso8601,
});
export type ReportFileMeta = z.infer<typeof ReportFileMeta>;

export const ReportRequest = z.object({
  id: Uuid,
  type: ReportType,
  status: ReportStatus,
  requestedBy: Uuid,
  lspId: Uuid.nullable(),
  dateFrom: IsoDate.nullable(),
  dateTo: IsoDate.nullable(),
  notificationEmail: Email.nullable(),
  fileMeta: ReportFileMeta.nullable(),
  errorMessage: z.string().max(1000).nullable(),
  queuedAt: Iso8601,
  completedAt: Iso8601.nullable(),
});
export type ReportRequest = z.infer<typeof ReportRequest>;

/** Summary KPIs above the preview table. */
export const MisSummary = z.object({
  totalDisbursedMtd: MoneyINR,
  activeLoanCount: z.number().int().nonnegative(),
  weightedAvgYieldPct: z.number().nonnegative().max(100),
  portfolioAtRisk30Pct: z.number().nonnegative().max(100),
  totalLoanCount: z.number().int().nonnegative(),
});
export type MisSummary = z.infer<typeof MisSummary>;

/** One row in the on-page preview table; columns mirror UI pages.md §Reports. */
export const MisPreviewRow = z.object({
  loanId: Uuid,
  externalLoanId: z.string().nullable(),
  borrowerName: z.string(),
  lspCode: z.string(),
  productCode: z.string(),
  amount: MoneyINR,
  status: LoanStatus,
  disbursalDate: IsoDate.nullable(),
  dpd: z.number().int().nonnegative(),
  delinquencyBucket: DelinquencyBucket.nullable(),
  year: z.number().int().min(2000).max(2100).nullable(),
  processingFee: MoneyINR,
  disbursalAmount: MoneyINR,
  interestPct: z.number().nonnegative().max(100),
  tenureMonths: z.number().int().min(1).max(360),
  emiAmount: MoneyINR,
  overdueAmount: MoneyINR,
  closureDate: IsoDate.nullable(),
  foreclosureDate: IsoDate.nullable(),
  foreclosedAmount: MoneyINR.nullable(),
  /** Masked PAN/Aadhaar carried only when the requester has reveal permission. */
  pan: z.string().nullable(),
  aadhaar: z.string().nullable(),
  gender: z.string().nullable(),
  state: z.string().nullable(),
  zip: z.string().nullable(),
  ifsc: z.string().nullable(),
  bankAccount: z.string().nullable(),
  profession: z.string().nullable(),
  income: MoneyINR.nullable(),
  /** Dynamic per-installment EMI columns keyed by installment number. */
  emis: z.record(z.string(), MoneyINR).optional(),
});
export type MisPreviewRow = z.infer<typeof MisPreviewRow>;
