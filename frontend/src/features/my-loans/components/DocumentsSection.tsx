import { useCallback, useEffect, useRef, useState } from "react";
import { CheckCircle2, FileText, Upload } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { formatDateTime } from "@/lib/format";
import {
  listLspSubmittedDocuments,
  LSP_DOCUMENT_TYPES,
  uploadLspDocument,
  type LspDocumentType,
  type UploadedLspDocument,
} from "../api";
import { safeApiMessage } from "../utils";

const LSP_REQUIRED_DOC_TYPES: readonly LspDocumentType[] = [
  "PAN",
  "AADHAAR",
  "ADDRESS_PROOF",
  "INCOME_PROOF",
  "BANK_STATEMENT",
  "PHOTOGRAPH",
  "LOAN_AGREEMENT",
];

const LSP_DOC_LABELS: Record<LspDocumentType, string> = {
  PAN: "PAN",
  AADHAAR: "Aadhaar",
  ADDRESS_PROOF: "Address proof",
  INCOME_PROOF: "Income proof",
  BANK_STATEMENT: "Bank statement",
  PHOTOGRAPH: "Photograph",
  LOAN_AGREEMENT: "Loan agreement",
  OTHER: "Other",
};

interface DocumentRowProps {
  documentType: LspDocumentType;
  uploaded: UploadedLspDocument | null;
  onPickFile: (documentType: LspDocumentType, file: File) => Promise<void>;
  busy: boolean;
  disabled: boolean;
}

function DocumentRow({ documentType, uploaded, onPickFile, busy, disabled }: DocumentRowProps) {
  const inputRef = useRef<HTMLInputElement | null>(null);
  const label = LSP_DOC_LABELS[documentType];
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
          disabled={disabled || busy}
          aria-busy={busy ? "true" : undefined}
        >
          <Upload aria-hidden="true" className="h-4 w-4" />
          <span>{busy ? "Uploading…" : uploaded ? "Replace" : "Upload"}</span>
        </Button>
      </div>
    </div>
  );
}

export interface DocumentsSectionProps {
  applicationId: string;
  disabled: boolean;
}

export function DocumentsSection({ applicationId, disabled }: DocumentsSectionProps) {
  const [uploads, setUploads] = useState<Record<string, UploadedLspDocument>>({});
  const [busyType, setBusyType] = useState<LspDocumentType | null>(null);
  const [error, setError] = useState<string | null>(null);

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
            documentDisplayName:
              LSP_DOC_LABELS[row.documentType as LspDocumentType] ?? row.documentType,
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
  }, [applicationId]);

  const handlePickFile = useCallback(
    async (documentType: LspDocumentType, file: File) => {
      setBusyType(documentType);
      setError(null);
      try {
        const uploaded = await uploadLspDocument({ applicationId, documentType, file });
        setUploads((prev) => ({ ...prev, [documentType]: uploaded }));
      } catch (err) {
        setError(safeApiMessage(err, `Failed to upload ${LSP_DOC_LABELS[documentType]}.`));
      } finally {
        setBusyType(null);
      }
    },
    [applicationId],
  );

  const otherUploads = Object.values(uploads).filter(
    (row) => row.documentType.toUpperCase() === "OTHER",
  );

  return (
    <section
      data-slot="lsp-documents-card"
      className="border-border bg-background flex flex-col gap-4 rounded-md border p-5"
    >
      <header className="flex flex-col gap-1">
        <h2 className="text-base font-semibold">Documents</h2>
        <p className="text-foreground-muted text-xs">
          Upload borrower KYC, agreement, and supporting documents. The checklist below is seeded
          from the LSP-scoped server read on mount and updated live as new files are uploaded.
        </p>
      </header>

      {error ? (
        <div
          role="alert"
          className="border-destructive/30 bg-destructive/5 text-destructive rounded-md border px-3 py-2 text-sm"
        >
          {error}
        </div>
      ) : null}

      <div className="flex flex-col gap-2">
        {LSP_REQUIRED_DOC_TYPES.map((documentType) => (
          <DocumentRow
            key={documentType}
            documentType={documentType}
            uploaded={uploads[documentType] ?? null}
            onPickFile={handlePickFile}
            busy={busyType === documentType}
            disabled={disabled}
          />
        ))}
      </div>

      <div className="border-border flex flex-col gap-2 border-t pt-4">
        <div className="flex items-center justify-between">
          <div>
            <h3 className="text-sm font-semibold">Other documents</h3>
            <p className="text-foreground-muted text-xs">
              Ad-hoc uploads recorded in this session.
            </p>
          </div>
          <DocumentRow
            documentType="OTHER"
            uploaded={null}
            onPickFile={handlePickFile}
            busy={busyType === "OTHER"}
            disabled={disabled}
          />
        </div>
        {otherUploads.length > 0 ? (
          <ul className="flex flex-col gap-1 text-xs">
            {otherUploads.map((row) => (
              <li key={row.id} className="text-foreground-muted">
                <span className="font-mono">{row.id.slice(0, 8)}</span> ·{" "}
                {row.fileName ?? row.documentDisplayName} ·{" "}
                <span className="text-foreground">{row.status}</span>
              </li>
            ))}
          </ul>
        ) : null}
      </div>

      <p className="text-foreground-muted text-xs">
        Required document types: {LSP_REQUIRED_DOC_TYPES.length}. The backend validates type + file
        size on every upload; the UI surfaces failures inline. Tracked via{" "}
        {LSP_DOCUMENT_TYPES.length} known document types.
      </p>
    </section>
  );
}
