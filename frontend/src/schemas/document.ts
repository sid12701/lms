/**
 * Document checklist schema for the documents-tab UI surface.
 *
 * Status lifecycle (Gap #18 — the verify/reject model was removed in favour of
 * an append-only attachment model with light auto-checks on upload):
 *   PENDING  → no file uploaded yet
 *   UPLOADED → a file is attached (maps to BE `SUBMITTED`)
 *
 * The UI vocabulary mirrors the backend-required KYC/disbursement checklist
 * while retaining legacy NACH/OTHER rows for seed data.
 */
import { z } from "zod";
import { Iso8601, Uuid } from "./common";

export const DocumentKind = z.enum([
  "PAN_CARD",
  "AADHAAR_CARD",
  "ADDRESS_PROOF",
  "BANK_STATEMENT",
  "INCOME_PROOF",
  "LOAN_AGREEMENT",
  "KYC_PHOTO",
  "KFS",
  "NACH_MANDATE",
  "OTHER",
]);
export type DocumentKind = z.infer<typeof DocumentKind>;

/**
 * `NOT_REQUIRED` mirrors the backend's third checklist state: the business has
 * recorded that this document does not apply to this loan. It used to be folded
 * onto `PENDING`, which reported a deliberate exemption as an outstanding
 * compliance gap and made "docs complete" unreachable for any loan carrying one.
 */
export const DocumentStatus = z.enum(["PENDING", "UPLOADED", "NOT_REQUIRED"]);
export type DocumentStatus = z.infer<typeof DocumentStatus>;

/** Gap #18 — BE `SUBMITTED` (+ legacy rows) count as uploaded for lifecycle gates. */
export function isUploadedBackendChecklistStatus(status: string): boolean {
  const value = status.toUpperCase();
  return (
    value === "SUBMITTED" || value === "UPLOADED" || value === "VERIFIED" || value === "RECEIVED"
  );
}

/**
 * Whether a checklist row no longer blocks disbursement.
 *
 * Distinct from `isUploadedBackendChecklistStatus`, which answers the narrower
 * "is there a file?". A document marked `NOT_REQUIRED` has no file and never
 * will, but it cannot gate a loan it does not apply to.
 */
export function isSatisfiedBackendChecklistStatus(status: string): boolean {
  return isUploadedBackendChecklistStatus(status) || status.toUpperCase() === "NOT_REQUIRED";
}

export const DOCUMENT_KIND_LABELS: Record<DocumentKind, string> = {
  PAN_CARD: "PAN card",
  AADHAAR_CARD: "Aadhaar card",
  ADDRESS_PROOF: "Address proof",
  BANK_STATEMENT: "Bank statement",
  INCOME_PROOF: "Income proof",
  LOAN_AGREEMENT: "Loan agreement",
  KYC_PHOTO: "KYC photo",
  KFS: "KFS",
  NACH_MANDATE: "NACH mandate",
  OTHER: "Other document",
};

export const Document = z.object({
  id: Uuid,
  applicationId: Uuid,
  kind: DocumentKind,
  status: DocumentStatus,
  requiredForDisbursement: z.boolean().default(false),
  fileName: z.string().min(1).max(240).nullable(),
  mimeType: z.string().min(1).max(120).nullable(),
  sizeBytes: z
    .number()
    .int()
    .nonnegative()
    .max(100 * 1024 * 1024)
    .nullable(),
  uploadedAt: Iso8601.nullable(),
  uploadedBy: Uuid.nullable(),
});
export type Document = z.infer<typeof Document>;
