/**
 * Documents tab — checklist of required + optional documents for a loan.
 *
 * Density: COMFORTABLE per D7. No `data-density` override.
 *
 * The Phase 5 mock API returns `LoanDocument` rows (schemas/loan-application),
 * while `DocumentChecklistGroup` consumes the Phase-3 `Document` shape
 * (schemas/document) — the two are not unified yet. We adapt inline:
 *
 *   LoanDocument.type        → Document.kind (closest mapping)
 *   LoanDocument.fileMeta    → Document.{fileName, mimeType, sizeBytes}
 *
 * TODO(phase-6): unify the two document schemas. When that lands, drop the
 * adapter + pass the response straight through.
 *
 * TODO(phase-6): wire `onVerify` / `onReject` / `onUpload` to mutations.
 * For Phase 5 those handlers are no-ops by design — the contract is
 * read-only on the tab. Manage callbacks land with the action bar.
 */
import { useMemo } from "react";
import { FileText } from "lucide-react";
import { EmptyState } from "@/components/app/feedback/EmptyState";
import { ErrorState } from "@/components/app/feedback/ErrorState";
import { TableSkeleton } from "@/components/app/feedback/Skeletons";
import { DocumentChecklistGroup } from "@/components/app/documents";
import type { Document, DocumentKind } from "@/schemas/document";
import type { LoanDocument, LoanDocumentType } from "@/types";
import { useLoanApplicationDocuments } from "../../hooks/useLoanApplicationDocuments";

export interface DocumentsTabProps {
  applicationId: string;
  /** Reserved for Phase 6 — when true, manage actions surface on each row. */
  canManage: boolean;
}

const KIND_BY_LOAN_TYPE: Record<LoanDocumentType, DocumentKind> = {
  PAN: "PAN_CARD",
  AADHAAR: "AADHAAR_CARD",
  ADDRESS_PROOF: "OTHER",
  INCOME_PROOF: "INCOME_PROOF",
  BANK_STATEMENT: "BANK_STATEMENT",
  PHOTOGRAPH: "KYC_PHOTO",
  LOAN_AGREEMENT: "LOAN_AGREEMENT",
  OTHER: "OTHER",
};

/** Adapt a LoanDocument → Document shape consumed by `DocumentChecklistGroup`. */
export function adaptLoanDocumentToDocument(doc: LoanDocument): Document {
  return {
    id: doc.id,
    applicationId: doc.applicationId,
    kind: KIND_BY_LOAN_TYPE[doc.type],
    status: doc.status,
    requiredForDisbursement: doc.requiredForDisbursement,
    fileName: doc.fileMeta?.storageKey ?? doc.displayName ?? null,
    mimeType: doc.fileMeta?.mime ?? null,
    sizeBytes: doc.fileMeta?.size ?? null,
    uploadedAt: doc.uploadedAt,
    uploadedBy: doc.uploadedBy,
    verifiedAt: null,
    verifiedBy: null,
    rejectionReason: null,
  };
}

export function DocumentsTab({ applicationId, canManage: _canManage }: DocumentsTabProps) {
  const query = useLoanApplicationDocuments(applicationId);

  const adapted = useMemo<Document[]>(
    () => (query.data?.documents ?? []).map(adaptLoanDocumentToDocument),
    [query.data],
  );

  if (query.isPending) {
    return (
      <TableSkeleton
        rows={5}
        cols={4}
        className="opacity-100 transition-opacity duration-200 motion-reduce:transition-none"
      />
    );
  }
  if (query.isError) {
    return (
      <ErrorState
        title="Couldn't load documents"
        description={query.error instanceof Error ? query.error.message : undefined}
        retry={{ onClick: () => void query.refetch() }}
      />
    );
  }
  if (adapted.length === 0) {
    return (
      <EmptyState
        icon={FileText}
        title="No documents attached"
        description="Documents will appear here once the LSP attaches them to this application."
      />
    );
  }

  return (
    <div data-slot="documents-tab" className="flex flex-col gap-4">
      <DocumentChecklistGroup docs={adapted} />
    </div>
  );
}

export default DocumentsTab;
