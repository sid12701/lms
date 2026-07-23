/**
 * MIS report request + summary + preview row.
 * Per UI pages.md "Portfolio MIS".
 */
import { z } from "zod";
import { Email, Iso8601, IsoDate, MoneyINR, Uuid } from "./common";
import { LoanStatus } from "./loan-application";
import { DelinquencyBucket } from "./loan-account";

const ReportType = z.enum(["PORTFOLIO_MIS"]);
type ReportType = z.infer<typeof ReportType>;

export const ReportStatus = z.enum(["QUEUED", "PROCESSING", "COMPLETED", "FAILED"]);
export type ReportStatus = z.infer<typeof ReportStatus>;

const ReportFileMeta = z.object({
  storageKey: z.string().min(1).max(240),
  size: z.number().int().nonnegative(),
  rowCount: z.number().int().nonnegative(),
  generatedAt: Iso8601,
});
type ReportFileMeta = z.infer<typeof ReportFileMeta>;

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

export const MisPreviewInstallment = z.object({
  installmentNumber: z.number().int().positive(),
  dueDate: IsoDate.nullable(),
  installmentAmount: MoneyINR,
  paidAmount: MoneyINR,
  received: z.boolean(),
});
export type MisPreviewInstallment = z.infer<typeof MisPreviewInstallment>;

/** One row in the on-page preview table; columns mirror UI pages.md §Reports. */
export const MisPreviewRow = z.object({
  loanId: Uuid,
  externalLoanId: z.string().nullable(),
  borrowerName: z.string(),
  borrowerId: Uuid.nullable(),
  lspCode: z.string(),
  lspName: z.string(),
  productCode: z.string(),
  productName: z.string(),
  accountNumber: z.string().nullable(),
  amount: MoneyINR,
  status: LoanStatus,
  loanStatusDisplay: z.string().nullable(),
  disbursalDate: IsoDate.nullable(),
  applicationCreatedAt: IsoDate.nullable(),
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
  closureReason: z.string().nullable(),
  foreclosureDate: IsoDate.nullable(),
  foreclosedAmount: MoneyINR.nullable(),
  address: z.string().nullable(),
  /** Masked PAN/Aadhaar — BE masks before send (Gap #1 + Gap #10). */
  pan: z.string().nullable(),
  aadhaar: z.string().nullable(),
  gender: z.string().nullable(),
  state: z.string().nullable(),
  zip: z.string().nullable(),
  ifsc: z.string().nullable(),
  bankAccount: z.string().nullable(),
  profession: z.string().nullable(),
  income: MoneyINR.nullable(),
  installments: z.array(MisPreviewInstallment).optional(),
});
export type MisPreviewRow = z.infer<typeof MisPreviewRow>;

export const MisPreviewResponseDto = z.object({
  items: z.array(MisPreviewRow),
  total: z.number().int().nonnegative(),
  page: z.number().int().nonnegative(),
  pageSize: z.number().int().positive(),
});
export type MisPreviewResponseDto = z.infer<typeof MisPreviewResponseDto>;

export const CreateReportRequestInput = z.object({
  type: ReportType.default("PORTFOLIO_MIS"),
  lspId: Uuid.nullable().optional(),
  dateFrom: IsoDate.nullable().optional(),
  dateTo: IsoDate.nullable().optional(),
  notificationEmail: Email.nullable().optional(),
});
export type CreateReportRequestInput = z.infer<typeof CreateReportRequestInput>;

/** Query params for MIS summary + preview endpoints. */
export type MisFilters = {
  lspId?: string | null;
  dateFrom?: string | null;
  dateTo?: string | null;
};

export type MisPreviewFilters = MisFilters & {
  page?: number;
  pageSize?: number;
};
