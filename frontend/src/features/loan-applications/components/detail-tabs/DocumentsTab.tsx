/**
 * Documents tab — view-only checklist of required + optional documents for a
 * loan.
 *
 * Density: COMFORTABLE per D7. No `data-density` override.
 *
 * Gap #18 — the LMS does not verify document content; per-document
 * VERIFIED/REJECTED status is removed. Internal users see a view-only
 * checklist; LSP-owned upload affordances live on the my-loan detail page.
 */
import { useCallback, useMemo } from "react";
import { FileText } from "lucide-react";
import { toast } from "sonner";
import { EmptyState } from "@/components/app/feedback/EmptyState";
import { ErrorState } from "@/components/app/feedback/ErrorState";
import { TableSkeleton } from "@/components/app/feedback/Skeletons";
import { DocumentChecklistGroup } from "@/components/app/documents";
import { requestBlob } from "@/lib/api/http-client";
import type { Document, DocumentKind } from "@/schemas/document";
import type { LoanDocument, LoanDocumentType } from "@/types";
import { useLoanApplicationDocuments } from "../../hooks/useLoanApplicationDocuments";

const FE_TO_BE_DOCUMENT_TYPE: Record<LoanDocumentType, string> = {
  PAN: "PAN_CARD",
  AADHAAR: "AADHAAR_FILE",
  ADDRESS_PROOF: "ADDRESS_PROOF",
  INCOME_PROOF: "INCOME_PROOF",
  BANK_STATEMENT: "BANK_STATEMENT",
  PHOTOGRAPH: "SELFIE_PHOTOGRAPH",
  KFS: "KFS",
  LOAN_AGREEMENT: "LOAN_AGREEMENT",
  OTHER: "OTHER",
};

export interface DocumentsTabProps {
  applicationId: string;
  /** Reserved for future role gating; currently the tab is view-only for all internal roles. */
  canManage: boolean;
}

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

function filenameFromPathLike(value: string | null | undefined): string | null {
  const candidate = value?.replace(/\\/g, "/").split("/").pop()?.trim();
  return candidate && candidate.length > 0 ? candidate : null;
}

function extensionFromMime(mime: string | null | undefined): string {
  switch (mime) {
    case "application/pdf":
      return ".pdf";
    case "image/jpeg":
      return ".jpg";
    case "image/png":
      return ".png";
    default:
      return ".bin";
  }
}

function fallbackDownloadFilename(doc: LoanDocument, backendType: string): string {
  return (
    doc.fileMeta?.fileName?.trim() ||
    filenameFromPathLike(doc.fileMeta?.storageKey) ||
    `${backendType.toLowerCase()}${extensionFromMime(doc.fileMeta?.mime)}`
  );
}

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

export function DocumentsTab({ applicationId, canManage: _canManage }: DocumentsTabProps) {
  const query = useLoanApplicationDocuments(applicationId);

  const adapted = useMemo<Document[]>(
    () => (query.data?.documents ?? []).map(adaptLoanDocumentToDocument),
    [query.data],
  );

  const docsById = useMemo(() => {
    const map = new Map<string, LoanDocument>();
    for (const d of query.data?.documents ?? []) map.set(d.id, d);
    return map;
  }, [query.data]);

  const handleDownload = useCallback(
    async (doc: Document) => {
      const original = docsById.get(doc.id);
      if (!original || !original.fileMeta) return;
      const backendType = FE_TO_BE_DOCUMENT_TYPE[original.type];
      try {
        const { blob, filename } = await requestBlob(
          `/api/v1/internal/ops/loan-applications/${encodeURIComponent(applicationId)}/kyc-documents/${backendType}/content`,
        );
        const url = URL.createObjectURL(blob);
        const link = document.createElement("a");
        link.href = url;
        link.download = filename ?? fallbackDownloadFilename(original, backendType);
        document.body.appendChild(link);
        link.click();
        link.remove();
        URL.revokeObjectURL(url);
      } catch (err) {
        toast.error(err instanceof Error ? err.message : "Couldn't download the document.");
      }
    },
    [applicationId, docsById],
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
      <DocumentChecklistGroup docs={adapted} onDownload={handleDownload} />
    </div>
  );
}

export default DocumentsTab;
