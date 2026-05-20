/**
 * Document checklist schema for the documents-tab UI surface.
 *
 * Source notes for `DocumentKind`:
 * The BRD §5.1, §5.5, §5.11 and BR-3 mention "KYC documents", "income proof",
 * "loan agreement", "bank statement", "photograph", and "agreements" as the
 * categories that gate approval/disbursement. The blueprint §10
 * (`POST /api/v1/lsp/loan-applications/{loanId}/documents`) does not enumerate
 * a closed kind list — it leaves the type vocabulary to the platform. The
 * Phase 3 wave-2 spec pins the closed UI vocabulary to:
 *
 *   PAN_CARD | AADHAAR_CARD | BANK_STATEMENT | INCOME_PROOF |
 *   LOAN_AGREEMENT | KYC_PHOTO | NACH_MANDATE | OTHER
 *
 * NACH_MANDATE is included because BR-3 / §5.5 disbursement gating implicitly
 * requires a debit-mandate artefact for autopay-driven repayment products,
 * which the production system will need on day one. If the backend lands a
 * different closed list, this enum is the single edit point — the UI reads
 * labels through `DOCUMENT_KIND_LABELS` only.
 *
 * `DocumentStatus` mirrors the existing `LoanDocumentStatus` lifecycle in
 * `loan-application.ts` (`PENDING | UPLOADED | VERIFIED | REJECTED`) and the
 * `DocumentAccessAction` audit enum in `audit.ts`.
 */
import { z } from "zod";
import { Iso8601, Uuid } from "./common";

export const DocumentKind = z.enum([
  "PAN_CARD",
  "AADHAAR_CARD",
  "BANK_STATEMENT",
  "INCOME_PROOF",
  "LOAN_AGREEMENT",
  "KYC_PHOTO",
  "NACH_MANDATE",
  "OTHER",
]);
export type DocumentKind = z.infer<typeof DocumentKind>;

export const DocumentStatus = z.enum(["PENDING", "UPLOADED", "VERIFIED", "REJECTED"]);
export type DocumentStatus = z.infer<typeof DocumentStatus>;

/** Human-readable labels rendered in the checklist UI. */
export const DOCUMENT_KIND_LABELS: Record<DocumentKind, string> = {
  PAN_CARD: "PAN card",
  AADHAAR_CARD: "Aadhaar card",
  BANK_STATEMENT: "Bank statement",
  INCOME_PROOF: "Income proof",
  LOAN_AGREEMENT: "Loan agreement",
  KYC_PHOTO: "KYC photo",
  NACH_MANDATE: "NACH mandate",
  OTHER: "Other document",
};

export const Document = z.object({
  id: Uuid,
  applicationId: Uuid,
  kind: DocumentKind,
  status: DocumentStatus,
  /** BR-3: must be VERIFIED before disbursement. Defaults to false. */
  requiredForDisbursement: z.boolean().default(false),
  fileName: z.string().min(1).max(240).nullable(),
  mimeType: z.string().min(1).max(120).nullable(),
  /** Bytes; capped at 100 MB. */
  sizeBytes: z
    .number()
    .int()
    .nonnegative()
    .max(100 * 1024 * 1024)
    .nullable(),
  uploadedAt: Iso8601.nullable(),
  uploadedBy: Uuid.nullable(),
  verifiedAt: Iso8601.nullable(),
  verifiedBy: Uuid.nullable(),
  /** Required when status === REJECTED. Min 1 char ensures auditors get a reason. */
  rejectionReason: z.string().min(1).max(500).nullable(),
});
export type Document = z.infer<typeof Document>;
