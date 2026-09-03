/**
 * Loan application + document schemas.
 *
 * Status enum is the union of:
 *   - blueprint §9 recommended statuses
 *   - frontend-implementation-plan.md §5.5 lifecycle groups
 *   - `@/lib/loan-application-status`, which mirrors the backend
 *     `LoanApplicationStatus` enum (e.g. APPROVED_PENDING_DISBURSAL)
 *
 * Document schema gates BR-2 (required-for-approval) and BR-3
 * (required-for-disbursement) flags.
 */
import { LOAN_APPLICATION_STATUSES } from "@/lib/loan-application-status";
import { z } from "zod";
import { Iso8601, MoneyINRPositive, Uuid } from "./common";

export const LoanStatus = z.enum(LOAN_APPLICATION_STATUSES);
export type LoanStatus = z.infer<typeof LoanStatus>;

export const SourceChannel = z.enum(["UI", "API", "WEBHOOK"]);
type SourceChannel = z.infer<typeof SourceChannel>;

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
  "KFS",
  "LOAN_AGREEMENT",
  "OTHER",
]);
export type LoanDocumentType = z.infer<typeof LoanDocumentType>;

// Gap #18 — the verify/reject document model was removed; statuses collapse
// to PENDING (no upload) or UPLOADED (file attached, maps to BE `SUBMITTED`).
export const LoanDocumentStatus = z.enum(["PENDING", "UPLOADED", "NOT_REQUIRED"]);
type LoanDocumentStatus = z.infer<typeof LoanDocumentStatus>;

export const LoanDocumentFileMeta = z.object({
  storageKey: z.string().min(1).max(240),
  fileName: z.string().min(1).max(240).optional(),
  mime: z.string().min(1).max(80),
  /** Bytes; capped at 100 MB. */
  size: z
    .number()
    .int()
    .nonnegative()
    .max(100 * 1024 * 1024),
  checksum: z.string().min(1).max(128),
});
type LoanDocumentFileMeta = z.infer<typeof LoanDocumentFileMeta>;

export const LoanDocument = z.object({
  id: Uuid,
  applicationId: Uuid,
  type: LoanDocumentType,
  displayName: z.string().min(1).max(160),
  /** BR-2: must be uploaded before approval (Gap #18 — no verify step). */
  requiredForApproval: z.boolean(),
  /** BR-3: must be uploaded before disbursement (Gap #18 — no verify step). */
  requiredForDisbursement: z.boolean(),
  status: LoanDocumentStatus,
  notes: z.string().max(500).nullable(),
  fileMeta: LoanDocumentFileMeta.nullable(),
  uploadedAt: Iso8601.nullable(),
  uploadedBy: Uuid.nullable(),
});
export type LoanDocument = z.infer<typeof LoanDocument>;
