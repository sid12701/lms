import { useCallback, useEffect, useRef, useState } from "react";
import { CheckCircle2, FileText, Upload } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { formatDateTime } from "@/lib/format";
import {
  fetchLspDocumentRequirements,
  listLspSubmittedDocuments,
  uploadLspDocument,
  type LspDocumentType,
  type UploadedLspDocument,
} from "../api";
import { safeApiMessage } from "../utils";

const FALLBACK_REQUIRED_DOC_TYPES: readonly LspDocumentType[] = [
  "PAN",
  "AADHAAR",
  "ADDRESS_PROOF",
  "INCOME_PROOF",
  "BANK_STATEMENT",
  "PHOTOGRAPH",
  "KFS",
  "LOAN_AGREEMENT",
];

const LSP_DOC_LABELS: Record<LspDocumentType, string> = {
  PAN: "PAN",
  AADHAAR: "Aadhaar",
  ADDRESS_PROOF: "Address proof",
  INCOME_PROOF: "Income proof",
  BANK_STATEMENT: "Bank statement",
  PHOTOGRAPH: "Photograph",
  KFS: "Key Facts Statement",
  LOAN_AGREEMENT: "Loan agreement",
  OTHER: "Other",
};

const BE_TO_FE_SLOT: Record<string, LspDocumentType> = {
  PAN_CARD: "PAN",
  AADHAAR_FILE: "AADHAAR",
  ADDRESS_PROOF: "ADDRESS_PROOF",
  INCOME_PROOF: "INCOME_PROOF",
  BANK_STATEMENT: "BANK_STATEMENT",
  SELFIE_PHOTOGRAPH: "PHOTOGRAPH",
  KFS: "KFS",
  LOAN_AGREEMENT: "LOAN_AGREEMENT",
};

function backendCodeToSlot(code: string): LspDocumentType | null {
  return BE_TO_FE_SLOT[code] ?? null;
}

export type DocumentsReadOnlyReason = "terminal" | "role" | null;

interface DocumentRowProps {
  documentType: LspDocumentType;
  label: string;
  uploaded: UploadedLspDocument | null;
  onPickFile: (documentType: LspDocumentType, file: File) => Promise<void>;
  busy: boolean;
  canUpload: boolean;
}

function DocumentRow({
  documentType,
  label,
  uploaded,
  onPickFile,
  busy,
  canUpload,
}: DocumentRowProps) {
  const inputRef = useRef<HTMLInputElement | null>(null);
  const handleChange = async (event: React.ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    event.target.value = "";
    if (!file) return;
    await onPickFile(documentType, file);
  };
  const handleClick = () => {
    inputRef.current?.click();
  };
  return (
    <div
      data-slot="lsp-document-row"
      data-document-type={documentType}
      className="border-border bg-surface-muted flex flex-col gap-2 rounded-md border p-3 sm:flex-row sm:items-center sm:justify-between"
    >
      <div className="flex min-w-0 items-center gap-3">
        {uploaded ? (
          <CheckCircle2 className="text-success h-4 w-4 shrink-0" aria-hidden="true" />
        ) : (
          <FileText className="text-foreground-muted h-4 w-4 shrink-0" aria-hidden="true" />
        )}
        <div className="flex min-w-0 flex-col">
          <span className="text-sm font-medium">{label}</span>
          {uploaded ? (
            <span className="text-foreground-muted truncate text-xs">
              {uploaded.fileName ?? uploaded.documentDisplayName}
              {uploaded.uploadedAt ? ` · ${formatDateTime(uploaded.uploadedAt)}` : null}
            </span>
          ) : (
            <span className="text-foreground-muted text-xs">No file uploaded yet.</span>
          )}
        </div>
      </div>
      <div className="flex items-center gap-2">
        {uploaded ? (
          <Badge variant="outline" className="border-success/30 bg-success/5 text-success">
            {uploaded.status}
          </Badge>
        ) : null}
        {canUpload ? (
          <>
            <input
              ref={inputRef}
              type="file"
              className="sr-only"
              onChange={handleChange}
              aria-label={`Upload ${label}`}
            />
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={handleClick}
              disabled={busy}
              aria-busy={busy ? "true" : undefined}
            >
              <Upload aria-hidden="true" className="h-4 w-4" />
              <span>{busy ? "Uploading…" : uploaded ? "Replace" : "Upload"}</span>
            </Button>
          </>
        ) : null}
      </div>
    </div>
  );
}

export interface DocumentsSectionProps {
  applicationId: string;
  canUpload: boolean;
  readOnlyReason?: DocumentsReadOnlyReason;
}

export function DocumentsSection({
  applicationId,
  canUpload,
  readOnlyReason = null,
}: DocumentsSectionProps) {
  const [requiredDocTypes, setRequiredDocTypes] = useState<readonly LspDocumentType[]>(
    FALLBACK_REQUIRED_DOC_TYPES,
  );
  const [docLabels, setDocLabels] = useState<Record<LspDocumentType, string>>(LSP_DOC_LABELS);
  const [uploads, setUploads] = useState<Record<string, UploadedLspDocument>>({});
  const [busyType, setBusyType] = useState<LspDocumentType | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    void fetchLspDocumentRequirements()
      .then((rows) => {
        if (cancelled) return;
        const intakeSlots: LspDocumentType[] = [];
        const labels = { ...LSP_DOC_LABELS };
        for (const row of rows) {
          const slot = backendCodeToSlot(row.code);
          if (!slot) continue;
          if (row.requiredForDisbursement) intakeSlots.push(slot);
          labels[slot] = row.displayName;
        }
        if (intakeSlots.length > 0) {
          setRequiredDocTypes(intakeSlots);
        }
        setDocLabels(labels);
      })
      .catch(() => {
        // Fall back to the static taxonomy when metadata is unavailable.
      });
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    let cancelled = false;
    void listLspSubmittedDocuments(applicationId)
      .then((rows) => {
        if (cancelled) return;
        const seeded: Record<string, UploadedLspDocument> = {};
        for (const row of rows) {
          seeded[row.documentType] = {
            id: `${row.documentType}-${row.uploadedAt ?? "seed"}`,
            documentType: row.documentType,
            documentDisplayName: docLabels[row.documentType as LspDocumentType] ?? row.documentType,
            status: row.status,
            fileName: row.fileName,
            contentType: row.contentType,
            fileSizeBytes: null,
            uploadedAt: row.uploadedAt,
            uploadedByUsername: row.uploadedByUsername,
          };
        }
        setUploads(seeded);
      })
      .catch(() => {
        // Non-fatal: the section continues to work with upload-only state.
      });
    return () => {
      cancelled = true;
    };
  }, [applicationId, docLabels]);

  const handlePickFile = useCallback(
    async (documentType: LspDocumentType, file: File) => {
      setBusyType(documentType);
      setError(null);
      try {
        const uploaded = await uploadLspDocument({ applicationId, documentType, file });
        setUploads((prev) => ({ ...prev, [documentType]: uploaded }));
      } catch (err) {
        setError(safeApiMessage(err, `Failed to upload ${docLabels[documentType]}.`));
      } finally {
        setBusyType(null);
      }
    },
    [applicationId, docLabels],
  );

  const otherUploads = Object.values(uploads).filter(
    (row) => row.documentType.toUpperCase() === "OTHER",
  );

  const readOnlyMessage =
    readOnlyReason === "terminal"
      ? "Documents are read-only because this loan has reached a final status."
      : readOnlyReason === "role"
        ? "Documents are read-only for your access level."
        : canUpload
          ? "Required documents are validated on upload for type and file size. Replace a file any time before the loan reaches a terminal status."
          : "Documents are read-only for your account.";

  return (
    <section
      data-slot="lsp-documents-card"
      className="border-border bg-background flex flex-col gap-4 rounded-md border p-5"
    >
      <header className="flex flex-col gap-1">
        <h2 className="text-base font-semibold">Documents</h2>
        <p className="text-foreground-muted text-xs">
          {canUpload
            ? "Upload borrower KYC, agreement, and supporting documents for this application."
            : "Review borrower KYC, agreement, and supporting documents for this application."}
        </p>
      </header>

      {error ? (
        <div
          role="alert"
          className="border-danger/30 bg-danger/5 text-danger rounded-md border px-3 py-2 text-sm"
        >
          {error}
        </div>
      ) : null}

      <div className="flex flex-col gap-2">
        {requiredDocTypes.map((documentType) => (
          <DocumentRow
            key={documentType}
            documentType={documentType}
            label={docLabels[documentType]}
            uploaded={uploads[documentType] ?? null}
            onPickFile={handlePickFile}
            busy={busyType === documentType}
            canUpload={canUpload}
          />
        ))}
      </div>

      <div className="border-border flex flex-col gap-2 border-t pt-4">
        <div className="flex items-center justify-between">
          <div>
            <h3 className="text-sm font-semibold">Other documents</h3>
            <p className="text-foreground-muted text-xs">
              Additional files uploaded during this session.
            </p>
          </div>
          {canUpload ? (
            <DocumentRow
              documentType="OTHER"
              label={docLabels.OTHER}
              uploaded={null}
              onPickFile={handlePickFile}
              busy={busyType === "OTHER"}
              canUpload
            />
          ) : null}
        </div>
        {otherUploads.length > 0 ? (
          <ul className="flex flex-col gap-1 text-xs">
            {otherUploads.map((row) => (
              <li key={row.id} className="text-foreground-muted">
                {row.fileName ?? row.documentDisplayName} ·{" "}
                <span className="text-foreground">{row.status}</span>
              </li>
            ))}
          </ul>
        ) : null}
      </div>

      <p className="text-foreground-muted text-xs">{readOnlyMessage}</p>
    </section>
  );
}
