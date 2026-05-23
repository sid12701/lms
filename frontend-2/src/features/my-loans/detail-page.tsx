import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Link, useParams } from "react-router-dom";
import {
  AlertTriangle,
  ArrowLeft,
  CheckCircle2,
  ChevronRight,
  Eye,
  EyeOff,
  FileText,
  Loader2,
  ShieldAlert,
  Upload,
} from "lucide-react";

import { PageHeader } from "@/components/app/layout/PageHeader";
import { EmptyState } from "@/components/app/feedback/EmptyState";
import { StatusBadge } from "@/components/app/status/StatusBadge";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Textarea } from "@/components/ui/textarea";
import { ApiError } from "@/lib/api/http-client";
import { formatDateTime, formatINR } from "@/lib/format";
import { newIdempotencyKey } from "@/lib/idempotency";
import {
  fetchInvalidReasons,
  fetchMyLoanDetail,
  LSP_DOCUMENT_TYPES,
  markLoanInvalid,
  revealBorrowerPii,
  uploadLspDocument,
  type BorrowerPiiReveal,
  type InvalidReasonOption,
  type LspDocumentType,
  type MyLoanDetail,
  type UploadedLspDocument,
} from "./api";
import type { LoanStatus } from "@/types";

const TERMINAL_STATUSES = new Set<LoanStatus>([
  "INVALIDATED",
  "REJECTED",
  "CANCELLED",
  "FULLY_REPAID",
  "CLOSED",
  "FORECLOSED",
]);

function fmt(value: string | null | undefined, fallback = "—"): string {
  if (value == null) return fallback;
  const trimmed = value.trim();
  return trimmed === "" ? fallback : trimmed;
}

function safeApiMessage(err: unknown, fallback: string): string {
  if (err instanceof ApiError) {
    if (err.status === 401 || err.status === 403) {
      return "Your role cannot perform this action.";
    }
    return err.message || fallback;
  }
  if (err instanceof Error) return err.message;
  return fallback;
}

interface MarkInvalidDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  applicationId: string;
  reasons: readonly InvalidReasonOption[];
  loadingReasons: boolean;
  onSuccess: (next: MyLoanDetail) => void;
}

function MarkInvalidDialog({
  open,
  onOpenChange,
  applicationId,
  reasons,
  loadingReasons,
  onSuccess,
}: MarkInvalidDialogProps) {
  const [reasonCode, setReasonCode] = useState<string>("");
  const [reasonText, setReasonText] = useState<string>("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!open) {
      setReasonCode("");
      setReasonText("");
      setError(null);
      setBusy(false);
    }
  }, [open]);

  const selected = useMemo(
    () => reasons.find((row) => row.code === reasonCode) ?? null,
    [reasons, reasonCode],
  );
  const requiresText = selected?.requiresText ?? false;
  const canSubmit =
    !!reasonCode && !busy && (!requiresText || reasonText.trim().length > 0);

  const handleSubmit = async () => {
    if (!canSubmit) return;
    setBusy(true);
    setError(null);
    try {
      const next = await markLoanInvalid({
        applicationId,
        reasonCode,
        reasonText: requiresText ? reasonText.trim() : undefined,
        idempotencyKey: newIdempotencyKey(),
      });
      onSuccess(next);
      onOpenChange(false);
    } catch (err) {
      setError(safeApiMessage(err, "Failed to mark loan as invalid."));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={(next) => (!busy ? onOpenChange(next) : undefined)}>
      <DialogContent>
        <DialogHeader>
          <div className="flex items-center gap-2">
            <AlertTriangle className="text-warning h-5 w-5" aria-hidden="true" />
            <DialogTitle>Mark loan invalid</DialogTitle>
          </div>
          <DialogDescription>
            This stops further processing of the application. The reason is recorded in
            the audit log against your account.
          </DialogDescription>
        </DialogHeader>

        <div className="flex flex-col gap-4">
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="invalid-reason">Reason</Label>
            <Select
              value={reasonCode}
              onValueChange={(value) => setReasonCode(value)}
              disabled={loadingReasons || busy}
            >
              <SelectTrigger id="invalid-reason" aria-label="Reason">
                <SelectValue
                  placeholder={loadingReasons ? "Loading reasons…" : "Select a reason"}
                />
              </SelectTrigger>
              <SelectContent>
                {reasons.map((row) => (
                  <SelectItem key={row.code} value={row.code}>
                    {row.label}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>

          {requiresText ? (
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="invalid-reason-text">Reason details</Label>
              <Textarea
                id="invalid-reason-text"
                rows={3}
                maxLength={500}
                placeholder="Describe why this loan is being marked invalid."
                value={reasonText}
                onChange={(event) => setReasonText(event.target.value)}
                disabled={busy}
              />
            </div>
          ) : null}

          {error ? (
            <div
              role="alert"
              className="border-destructive/30 bg-destructive/5 text-destructive rounded-md border px-3 py-2 text-sm"
            >
              {error}
            </div>
          ) : null}
        </div>

        <DialogFooter>
          <Button
            type="button"
            variant="outline"
            onClick={() => onOpenChange(false)}
            disabled={busy}
          >
            Cancel
          </Button>
          <Button type="button" onClick={handleSubmit} disabled={!canSubmit}>
            {busy ? "Submitting…" : "Mark invalid"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

interface PiiRevealCardProps {
  applicationId: string;
  detail: MyLoanDetail;
}

function PiiRevealCard({ applicationId, detail }: PiiRevealCardProps) {
  const [revealed, setRevealed] = useState<BorrowerPiiReveal | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleReveal = async () => {
    setBusy(true);
    setError(null);
    try {
      const result = await revealBorrowerPii(applicationId);
      setRevealed(result);
    } catch (err) {
      setError(safeApiMessage(err, "Failed to reveal borrower PII."));
    } finally {
      setBusy(false);
    }
  };

  const handleHide = () => {
    setRevealed(null);
  };

  return (
    <section
      data-slot="pii-reveal-card"
      className="border-border bg-background flex flex-col gap-4 rounded-md border p-5"
    >
      <header className="flex flex-wrap items-start justify-between gap-3">
        <div className="flex flex-col gap-1">
          <div className="flex items-center gap-2">
            <ShieldAlert className="text-warning h-4 w-4" aria-hidden="true" />
            <h2 className="text-base font-semibold">Verified borrower PII</h2>
          </div>
          <p className="text-foreground-muted text-xs">
            Revealing PII is logged in the audit trail with your username and timestamp.
          </p>
        </div>
        {revealed ? (
          <Button type="button" variant="outline" size="sm" onClick={handleHide}>
            <EyeOff className="h-4 w-4" aria-hidden="true" />
            <span>Hide</span>
          </Button>
        ) : (
          <Button
            type="button"
            variant="outline"
            size="sm"
            onClick={handleReveal}
            disabled={busy}
            aria-busy={busy ? "true" : undefined}
          >
            <Eye className="h-4 w-4" aria-hidden="true" />
            <span>{busy ? "Revealing…" : "Reveal PII"}</span>
          </Button>
        )}
      </header>

      {error ? (
        <div
          role="alert"
          className="border-destructive/30 bg-destructive/5 text-destructive rounded-md border px-3 py-2 text-sm"
        >
          {error}
        </div>
      ) : null}

      <dl className="grid grid-cols-1 gap-x-6 gap-y-3 text-sm sm:grid-cols-2">
        <Field label="Aadhaar" value={revealed?.aadhaarNumber ?? detail.borrowerAadhaarMasked} mono />
        <Field label="PAN" value={revealed?.panNumber ?? detail.borrowerPanMasked} mono />
        <Field
          label="Bank account"
          value={revealed?.bankAccountNumber ?? (revealed ? null : "•••• (revealed on demand)")}
          mono
        />
        <Field label="IFSC" value={revealed?.ifscCode ?? null} mono />
        <Field label="Account holder" value={revealed?.accountHolderName ?? null} />
        <Field label="Employee ID" value={revealed?.employeeId ?? null} mono />
        <Field label="Reference name" value={revealed?.referencePersonName ?? null} />
        <Field label="Reference contact" value={revealed?.referencePersonNumber ?? null} mono />
      </dl>
    </section>
  );
}

interface FieldProps {
  label: string;
  value: string | null | undefined;
  mono?: boolean;
}

function Field({ label, value, mono = false }: FieldProps) {
  return (
    <div className="flex flex-col gap-0.5">
      <dt className="text-foreground-muted text-xs uppercase tracking-wide">{label}</dt>
      <dd className={mono ? "font-mono text-sm tabular-nums" : "text-sm"}>{fmt(value)}</dd>
    </div>
  );
}

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

interface DocumentsSectionProps {
  applicationId: string;
  disabled: boolean;
}

function DocumentsSection({ applicationId, disabled }: DocumentsSectionProps) {
  const [uploads, setUploads] = useState<Record<string, UploadedLspDocument>>({});
  const [busyType, setBusyType] = useState<LspDocumentType | null>(null);
  const [error, setError] = useState<string | null>(null);

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

  // Track ad-hoc OTHER uploads as an ordered list since the same type can be
  // submitted multiple times (e.g. multiple addenda).
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
          Upload borrower KYC, agreement, and supporting documents. The LSP API
          accepts uploads but does not yet expose a GET endpoint — this checklist
          is derived locally from the standard document types and recent uploads
          in this session.
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
                <span className="font-mono">{row.id.slice(0, 8)}</span>{" "}
                · {row.fileName ?? row.documentDisplayName}{" "}
                · <span className="text-foreground">{row.status}</span>
              </li>
            ))}
          </ul>
        ) : null}
      </div>

      <p className="text-foreground-muted text-xs">
        Required document types: {LSP_REQUIRED_DOC_TYPES.length}. The backend
        validates type + file size on every upload; the UI surfaces failures
        inline. Tracked via {LSP_DOCUMENT_TYPES.length} known document types.
      </p>
    </section>
  );
}

export function MyLoanDetailPage() {
  const { id } = useParams<{ id: string }>();
  const [detail, setDetail] = useState<MyLoanDetail | null>(null);
  const [reasons, setReasons] = useState<readonly InvalidReasonOption[]>([]);
  const [loadingReasons, setLoadingReasons] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [markInvalidOpen, setMarkInvalidOpen] = useState(false);

  useEffect(() => {
    if (!id) return;
    let cancelled = false;
    setLoading(true);
    setError(null);
    void fetchMyLoanDetail(id)
      .then((payload) => {
        if (!cancelled) setDetail(payload);
      })
      .catch((err) => {
        if (cancelled) return;
        setError(safeApiMessage(err, "Failed to load loan."));
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [id]);

  const openMarkInvalid = useCallback(() => {
    setMarkInvalidOpen(true);
    if (reasons.length === 0 && !loadingReasons) {
      setLoadingReasons(true);
      void fetchInvalidReasons()
        .then((rows) => setReasons(rows))
        .catch(() => {
          // Surface the failure inside the dialog (it shows an empty list with
          // a disabled trigger) — non-fatal for the parent page.
        })
        .finally(() => setLoadingReasons(false));
    }
  }, [reasons.length, loadingReasons]);

  if (!id) {
    return (
      <div className="flex flex-col gap-6 p-6">
        <PageHeader eyebrow="LSP workspace" title="Loan" />
        <EmptyState
          icon={AlertTriangle}
          title="Missing loan id"
          description="The URL did not include a loan identifier."
        />
      </div>
    );
  }

  if (loading) {
    return (
      <div className="flex flex-col gap-6 p-6">
        <PageHeader eyebrow="LSP workspace" title="Loan" />
        <div
          role="status"
          className="text-foreground-muted flex items-center gap-2 text-sm"
        >
          <Loader2 aria-hidden="true" className="h-4 w-4 animate-spin" />
          Loading loan details…
        </div>
      </div>
    );
  }

  if (error || !detail) {
    return (
      <div className="flex flex-col gap-6 p-6">
        <PageHeader eyebrow="LSP workspace" title="Loan" />
        <div
          role="alert"
          className="border-destructive/30 bg-destructive/5 text-destructive rounded-md border px-4 py-3 text-sm"
        >
          {error ?? "Loan not found."}
        </div>
      </div>
    );
  }

  const isTerminal = TERMINAL_STATUSES.has(detail.status);
  const eyebrow = detail.externalLoanId
    ? `LSP workspace · ${detail.externalLoanId}`
    : "LSP workspace";

  return (
    <div className="flex flex-col gap-6 p-6">
      <nav aria-label="Breadcrumb" className="text-foreground-muted text-xs">
        <Link to="/my-loans" className="hover:text-foreground inline-flex items-center gap-1">
          <ArrowLeft aria-hidden="true" className="h-3.5 w-3.5" />
          <span>My loans</span>
        </Link>
        <ChevronRight aria-hidden="true" className="mx-1 inline h-3.5 w-3.5 align-text-bottom" />
        <span className="text-foreground">{detail.borrowerFullName}</span>
      </nav>

      <PageHeader
        eyebrow={eyebrow}
        title={detail.borrowerFullName}
        description={`${detail.productName} · ${detail.lspName}`}
        actions={
          <div className="flex items-center gap-2">
            <StatusBadge status={detail.status} />
            {!isTerminal ? (
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={openMarkInvalid}
              >
                <AlertTriangle className="h-4 w-4" aria-hidden="true" />
                <span>Mark invalid</span>
              </Button>
            ) : null}
          </div>
        }
      />

      <section
        data-slot="loan-terms-card"
        className="border-border bg-background flex flex-col gap-4 rounded-md border p-5"
      >
        <h2 className="text-base font-semibold">Loan terms</h2>
        <dl className="grid grid-cols-1 gap-x-6 gap-y-3 text-sm sm:grid-cols-3">
          <Field label="Requested amount" value={formatINR(detail.requestedAmount)} mono />
          <Field
            label="Interest rate"
            value={detail.interestRate != null ? `${detail.interestRate}%` : null}
            mono
          />
          <Field
            label="Tenure"
            value={detail.tenureMonths ? `${detail.tenureMonths} months` : null}
          />
          <Field label="Product" value={detail.productName} />
          <Field label="Product code" value={detail.productCode} mono />
          <Field label="LSP" value={detail.lspName} />
          <Field label="Created" value={detail.createdAt ? formatDateTime(detail.createdAt) : null} />
          <Field label="Updated" value={detail.updatedAt ? formatDateTime(detail.updatedAt) : null} />
          {detail.invalidatedAt ? (
            <Field label="Invalidated at" value={formatDateTime(detail.invalidatedAt)} />
          ) : null}
        </dl>
        {detail.invalidReasonCode ? (
          <div className="border-warning/30 bg-warning/5 text-warning rounded-md border px-3 py-2 text-xs">
            <span className="font-semibold">Invalid reason:</span>{" "}
            <span className="font-mono">{detail.invalidReasonCode}</span>
            {detail.invalidReasonText ? <> — {detail.invalidReasonText}</> : null}
          </div>
        ) : null}
      </section>

      <section
        data-slot="borrower-contact-card"
        className="border-border bg-background flex flex-col gap-4 rounded-md border p-5"
      >
        <h2 className="text-base font-semibold">Borrower contact</h2>
        <dl className="grid grid-cols-1 gap-x-6 gap-y-3 text-sm sm:grid-cols-2">
          <Field label="Mobile" value={detail.borrowerMobile} mono />
          <Field label="Email" value={detail.borrowerEmail} />
          <Field label="Date of birth" value={detail.borrowerDob} mono />
          <Field
            label="Location"
            value={[detail.borrowerCity, detail.borrowerState].filter(Boolean).join(", ") || null}
          />
        </dl>
      </section>

      <PiiRevealCard applicationId={id} detail={detail} />

      <DocumentsSection applicationId={id} disabled={isTerminal} />

      {detail.loanAccount ? (
        <section
          data-slot="loan-account-card"
          className="border-border bg-background flex flex-col gap-4 rounded-md border p-5"
        >
          <h2 className="text-base font-semibold">Loan account</h2>
          <dl className="grid grid-cols-1 gap-x-6 gap-y-3 text-sm sm:grid-cols-3">
            <Field label="Account number" value={detail.loanAccount.accountNumber} mono />
            <Field label="Status" value={detail.loanAccount.status} />
            <Field
              label="Principal"
              value={formatINR(detail.loanAccount.principalAmount)}
              mono
            />
            <Field
              label="Tenure"
              value={
                detail.loanAccount.tenureMonths
                  ? `${detail.loanAccount.tenureMonths} months`
                  : null
              }
            />
            <Field
              label="Approved at"
              value={
                detail.loanAccount.approvedAt
                  ? formatDateTime(detail.loanAccount.approvedAt)
                  : null
              }
            />
            <Field
              label="Closed at"
              value={
                detail.loanAccount.closedAt
                  ? formatDateTime(detail.loanAccount.closedAt)
                  : null
              }
            />
          </dl>
          {detail.loanAccount.repaymentSchedule ? (
            <p className="text-foreground-muted text-xs">
              Schedule: {detail.loanAccount.repaymentSchedule.installmentCount ?? 0}{" "}
              installments of{" "}
              <span className="font-mono">
                {formatINR(detail.loanAccount.repaymentSchedule.installmentAmount)}
              </span>
              {detail.loanAccount.repaymentSchedule.firstDueDate
                ? `, first due ${detail.loanAccount.repaymentSchedule.firstDueDate}`
                : null}
            </p>
          ) : null}
        </section>
      ) : null}

      <MarkInvalidDialog
        open={markInvalidOpen}
        onOpenChange={setMarkInvalidOpen}
        applicationId={id}
        reasons={reasons}
        loadingReasons={loadingReasons}
        onSuccess={(next) => setDetail(next)}
      />
    </div>
  );
}

export default MyLoanDetailPage;
export const Component = MyLoanDetailPage;
