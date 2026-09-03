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
import { useCallback, useMemo, useState } from "react";
import { FileText } from "lucide-react";
import { toast } from "sonner";
import { EmptyState } from "@/components/app/feedback/EmptyState";
import { ErrorState } from "@/components/app/feedback/ErrorState";
import { TableSkeleton } from "@/components/app/feedback/Skeletons";
import { DocumentChecklistGroup, DocumentPreviewModal } from "@/components/app/documents";
import { ApiError, requestBlob } from "@/lib/api/http-client";
import { mapApiErrorMessage } from "@/lib/api/user-messages";
import { DOCUMENT_KIND_LABELS, type Document } from "@/schemas/document";
import type { LoanStatus } from "@/types";
import type { LoanDocument, LoanDocumentType } from "@/types";
import { useLoanApplicationDocuments } from "../../hooks/useLoanApplicationDocuments";
import { adaptLoanDocumentToDocument } from "./document-adapter";

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
  /**
   * Loan lifecycle status, so the checklist can stop describing a gate the loan
   * has already passed. Optional for callers that do not have it to hand.
   */
  loanStatus?: LoanStatus;
}

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

/** Backend streaming path for a stored document; `inline` selects preview disposition. */
function documentContentPath(
  applicationId: string,
  backendType: string,
  opts?: { inline?: boolean },
): string {
  const base = `/api/v1/internal/ops/loan-applications/${encodeURIComponent(applicationId)}/kyc-documents/${backendType}/content`;
  return opts?.inline ? `${base}?disposition=inline` : base;
}

export function DocumentsTab({
  applicationId,
  canManage: _canManage,
  loanStatus,
}: DocumentsTabProps) {
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

  const [previewDoc, setPreviewDoc] = useState<Document | null>(null);
  const [previewOpen, setPreviewOpen] = useState(false);

  const handleView = useCallback(
    (doc: Document) => {
      const original = docsById.get(doc.id);
      if (!original?.fileMeta) return;
      setPreviewDoc(doc);
      setPreviewOpen(true);
    },
    [docsById],
  );

  const handleDownload = useCallback(
    async (doc: Document) => {
      const original = docsById.get(doc.id);
      if (!original || !original.fileMeta) return;
      const backendType = FE_TO_BE_DOCUMENT_TYPE[original.type];
      try {
        const { blob, filename } = await requestBlob(
          documentContentPath(applicationId, backendType),
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
        if (err instanceof ApiError && err.code === "DOCUMENT_STORAGE_UNAVAILABLE") {
          toast.error("Document storage is temporarily unavailable. Please try again in a moment.");
          return;
        }
        toast.error(mapApiErrorMessage(err, "Couldn't download the document."));
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
        description={mapApiErrorMessage(query.error, "Please try again.")}
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

  const previewOriginal = previewDoc ? (docsById.get(previewDoc.id) ?? null) : null;
  const previewBackendType = previewOriginal ? FE_TO_BE_DOCUMENT_TYPE[previewOriginal.type] : null;
  const previewContentPath =
    previewOriginal && previewBackendType
      ? documentContentPath(applicationId, previewBackendType, { inline: true })
      : null;
  const previewTitle =
    previewOriginal?.fileMeta?.fileName ??
    (previewDoc ? DOCUMENT_KIND_LABELS[previewDoc.kind] : "Document");

  return (
    <div data-slot="documents-tab" className="flex flex-col gap-4">
      <DocumentChecklistGroup
        docs={adapted}
        loanStatus={loanStatus}
        onView={handleView}
        onDownload={handleDownload}
      />
      <DocumentPreviewModal
        open={previewOpen}
        onOpenChange={setPreviewOpen}
        title={previewTitle}
        mimeType={previewOriginal?.fileMeta?.mime ?? null}
        contentPath={previewContentPath}
        onDownload={previewDoc ? () => handleDownload(previewDoc) : undefined}
      />
    </div>
  );
}
