/**
 * Loan application + document schemas.
 *
 * Status enum is the union of:
 *   - blueprint §9 recommended statuses
 *   - frontend-implementation-plan.md §5.5 lifecycle groups
 *   - UI pages.md role-based-actions terminology (e.g. APPROVED_PENDING_DISBURSAL)
 *
 * Document schema gates BR-2 (required-for-approval) and BR-3
 * (required-for-disbursement) flags.
 */
import { z } from "zod";
import { Iso8601, MoneyINRPositive, Uuid } from "./common";

export const LoanStatus = z.enum([
  "INITIATED",
  "KYC_PENDING",
  "DOCS_PENDING",
  "UNDER_REVIEW",
  "AWAITING_APPROVAL",
  "APPROVED",
  "APPROVED_PENDING_DISBURSAL",
  "REJECTED",
  "DISBURSEMENT_IN_PROGRESS",
  "DISBURSED",
  "UNDER_REPAYMENT",
  "PARTIALLY_PAID",
  "DELINQUENT",
  "FORECLOSURE_REQUESTED",
  "FORECLOSURE_APPROVED",
  "FORECLOSED",
  "FULLY_REPAID",
  "CLOSED",
  "CANCELLED",
  "INVALIDATED",
]);
export type LoanStatus = z.infer<typeof LoanStatus>;

export const SourceChannel = z.enum(["UI", "API", "WEBHOOK"]);
export type SourceChannel = z.infer<typeof SourceChannel>;

export const LoanApplication = z.object({
  id: Uuid,
  /** External LSP-supplied id, opaque string. */
  externalLoanId: z.string().min(1).max(80).nullable(),
  borrowerId: Uuid,
  lspId: Uuid,
  productId: Uuid,
  requestedAmount: MoneyINRPositive,
  tenureMonths: z.number().int().min(1).max(360),
  status: LoanStatus,
  sourceChannel: SourceChannel,
  /** Internal user the application is currently assigned to. */
  assignedTo: Uuid.nullable(),
  createdAt: Iso8601,
  updatedAt: Iso8601,
  /** BR-driven invalidation metadata (LSP-side mark-as-invalid). */
  invalidatedAt: Iso8601.nullable(),
  invalidReason: z.string().max(500).nullable(),
});
export type LoanApplication = z.infer<typeof LoanApplication>;

export const LoanDocumentType = z.enum([
  "PAN",
  "AADHAAR",
  "ADDRESS_PROOF",
  "INCOME_PROOF",
  "BANK_STATEMENT",
  "PHOTOGRAPH",
  "LOAN_AGREEMENT",
  "OTHER",
]);
export type LoanDocumentType = z.infer<typeof LoanDocumentType>;

export const LoanDocumentStatus = z.enum(["PENDING", "UPLOADED", "VERIFIED", "REJECTED"]);
export type LoanDocumentStatus = z.infer<typeof LoanDocumentStatus>;

export const LoanDocumentFileMeta = z.object({
  storageKey: z.string().min(1).max(240),
  mime: z.string().min(1).max(80),
  /** Bytes; capped at 100 MB. */
  size: z
    .number()
    .int()
    .nonnegative()
    .max(100 * 1024 * 1024),
  checksum: z.string().min(1).max(128),
});
export type LoanDocumentFileMeta = z.infer<typeof LoanDocumentFileMeta>;

export const LoanDocument = z.object({
  id: Uuid,
  applicationId: Uuid,
  type: LoanDocumentType,
  displayName: z.string().min(1).max(160),
  /** BR-2: must be VERIFIED before approval. */
  requiredForApproval: z.boolean(),
  /** BR-3: must be VERIFIED before disbursement. */
  requiredForDisbursement: z.boolean(),
  status: LoanDocumentStatus,
  notes: z.string().max(500).nullable(),
  fileMeta: LoanDocumentFileMeta.nullable(),
  uploadedAt: Iso8601.nullable(),
  uploadedBy: Uuid.nullable(),
});
export type LoanDocument = z.infer<typeof LoanDocument>;
