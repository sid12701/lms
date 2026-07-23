import type { Document, DocumentKind } from "@/schemas/document";
import type { LoanDocument, LoanDocumentType } from "@/types";

const KIND_BY_LOAN_TYPE: Record<LoanDocumentType, DocumentKind> = {
  PAN: "PAN_CARD",
  AADHAAR: "AADHAAR_CARD",
  ADDRESS_PROOF: "ADDRESS_PROOF",
  INCOME_PROOF: "INCOME_PROOF",
  BANK_STATEMENT: "BANK_STATEMENT",
  PHOTOGRAPH: "KYC_PHOTO",
  KFS: "KFS",
  LOAN_AGREEMENT: "LOAN_AGREEMENT",
  OTHER: "OTHER",
};

export function adaptLoanDocumentToDocument(doc: LoanDocument): Document {
  return {
    id: doc.id,
    applicationId: doc.applicationId,
    kind: KIND_BY_LOAN_TYPE[doc.type],
    status: doc.status,
    requiredForDisbursement: doc.requiredForDisbursement,
    fileName: doc.fileMeta?.fileName ?? doc.fileMeta?.storageKey ?? doc.displayName ?? null,
    mimeType: doc.fileMeta?.mime ?? null,
    sizeBytes: doc.fileMeta?.size ?? null,
    uploadedAt: doc.uploadedAt,
    uploadedBy: doc.uploadedBy,
  };
}
