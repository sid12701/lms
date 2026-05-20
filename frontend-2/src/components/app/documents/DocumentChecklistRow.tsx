import { useState } from "react";
import { Check, Download, Eye, ShieldCheck, X } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import { formatDateTime, formatRelative } from "@/lib/format";
import { newIdempotencyKey } from "@/lib/idempotency";
import { DOCUMENT_KIND_LABELS, type Document } from "@/schemas/document";
import { DocumentStatusPill } from "./DocumentStatusPill";
import { DocumentRejectDialog } from "./DocumentRejectDialog";

export interface DocumentChecklistRowPermissions {
  canVerify?: boolean;
  canReject?: boolean;
}

export interface DocumentChecklistRowProps {
  doc: Document;
  /** Compact density variant (D7 — used inside long lists / right-rail). */
  compact?: boolean;
  /** Read-only — view metadata. No idempotency. */
  onView?: () => void;
  /** Read-only — file download. No idempotency. */
  onDownload?: () => void;
  /** Mutation — verify the document. Receives a fresh idempotency key (BR-5). */
  onVerify?: (args: { idempotencyKey: string }) => Promise<void> | void;
  /**
   * Mutation — reject the document. The row owns the confirmation dialog;
   * the consumer is invoked only after the user submits a non-empty reason.
   */
  onReject?: (args: {
    rejectionReason: string;
    idempotencyKey: string;
  }) => Promise<void> | void;
  permissions?: DocumentChecklistRowPermissions;
  className?: string;
}

const KB = 1024;
const MB = KB * 1024;

/** Human-readable byte size. Defensive against null/negative input. */
export function formatBytes(bytes: number | null | undefined): string {
  if (bytes == null || !Number.isFinite(bytes) || bytes < 0) return "—";
  if (bytes < KB) return `${bytes} B`;
  if (bytes < MB) return `${(bytes / KB).toFixed(1)} KB`;
  return `${(bytes / MB).toFixed(1)} MB`;
}

/**
 * Read-only / action row for a single document on the loan-detail or
 * borrower-360 surfaces. Renders the kind label, "required for disbursement"
 * badge (BR-3), status pill, file metadata, and action buttons gated by
 * `permissions`. The Reject path opens `DocumentRejectDialog` — the
 * `onReject` consumer prop only fires after the user submits a reason.
 */
export function DocumentChecklistRow({
  doc,
  compact = false,
  onView,
  onDownload,
  onVerify,
  onReject,
  permissions,
  className,
}: DocumentChecklistRowProps) {
  const [rejectOpen, setRejectOpen] = useState(false);
  const [rejectBusy, setRejectBusy] = useState(false);
  const [verifyBusy, setVerifyBusy] = useState(false);

  const kindLabel = DOCUMENT_KIND_LABELS[doc.kind];
  const canVerify = permissions?.canVerify === true;
  const canReject = permissions?.canReject === true;
  const showVerifyButton = onVerify && canVerify && doc.status !== "VERIFIED";
  const showRejectButton = onReject && canReject && doc.status !== "REJECTED";

  const handleVerify = async () => {
    if (!onVerify) return;
    setVerifyBusy(true);
    try {
      await onVerify({ idempotencyKey: newIdempotencyKey() });
    } finally {
      setVerifyBusy(false);
    }
  };

  const handleRejectConfirm = async ({
    rejectionReason,
    idempotencyKey,
  }: {
    rejectionReason: string;
    idempotencyKey: string;
  }) => {
    if (!onReject) return;
    setRejectBusy(true);
    try {
      await onReject({ rejectionReason, idempotencyKey });
      setRejectOpen(false);
    } finally {
      setRejectBusy(false);
    }
  };

  return (
    <div
      data-slot="document-checklist-row"
      data-status={doc.status}
      data-compact={compact ? "true" : "false"}
      className={cn(
        "border-border bg-surface flex flex-col gap-3 rounded-md border p-4 sm:flex-row sm:items-start sm:justify-between",
        compact && "gap-2 p-3",
        className,
      )}
    >
      <div className="flex min-w-0 flex-col gap-1.5">
        <div className="flex flex-wrap items-center gap-2">
          <span className="text-foreground text-sm font-semibold">{kindLabel}</span>
          <DocumentStatusPill status={doc.status} />
          {doc.requiredForDisbursement ? (
            <Badge
              variant="outline"
              data-slot="required-for-disbursement-badge"
              className="border-warning/30 bg-warning/10 text-warning gap-1"
            >
              <ShieldCheck className="size-3" aria-hidden="true" />
              <span>Required for disbursement</span>
            </Badge>
          ) : null}
        </div>

        {doc.fileName ? (
          <p
            data-slot="document-file-meta"
            className="text-foreground-muted text-xs"
          >
            <span className="text-foreground font-mono">{doc.fileName}</span>
            <span aria-hidden="true"> · </span>
            <span>{formatBytes(doc.sizeBytes)}</span>
            {doc.mimeType ? (
              <>
                <span aria-hidden="true"> · </span>
                <span>{doc.mimeType}</span>
              </>
            ) : null}
          </p>
        ) : (
          <p className="text-foreground-muted text-xs">No file uploaded yet.</p>
        )}

        {doc.uploadedAt ? (
          <p className="text-foreground-subtle text-xs">
            Uploaded{" "}
            <time dateTime={doc.uploadedAt}>{formatDateTime(doc.uploadedAt)}</time>{" "}
            <span className="text-foreground-subtle">({formatRelative(doc.uploadedAt)})</span>
          </p>
        ) : null}

        {doc.status === "REJECTED" && doc.rejectionReason ? (
          <p
            data-slot="document-rejection-reason"
            className="text-danger text-xs"
          >
            Rejected: {doc.rejectionReason}
          </p>
        ) : null}
      </div>

      <div className="flex flex-wrap items-center gap-2">
        {onView ? (
          <Button
            type="button"
            variant="ghost"
            size={compact ? "xs" : "sm"}
            onClick={onView}
            aria-label={`View ${kindLabel}`}
          >
            <Eye aria-hidden="true" />
            <span>View</span>
          </Button>
        ) : null}
        {onDownload && doc.fileName ? (
          <Button
            type="button"
            variant="ghost"
            size={compact ? "xs" : "sm"}
            onClick={onDownload}
            aria-label={`Download ${kindLabel}`}
          >
            <Download aria-hidden="true" />
            <span>Download</span>
          </Button>
        ) : null}
        {showVerifyButton ? (
          <Button
            type="button"
            variant="outline"
            size={compact ? "xs" : "sm"}
            onClick={handleVerify}
            disabled={verifyBusy}
            aria-busy={verifyBusy ? "true" : undefined}
            aria-label={`Verify ${kindLabel}`}
          >
            <Check aria-hidden="true" />
            <span>{verifyBusy ? "Verifying…" : "Verify"}</span>
          </Button>
        ) : null}
        {showRejectButton ? (
          <Button
            type="button"
            variant="outline"
            size={compact ? "xs" : "sm"}
            onClick={() => setRejectOpen(true)}
            aria-label={`Reject ${kindLabel}`}
            className="border-danger/30 text-danger hover:bg-danger/10 hover:text-danger"
          >
            <X aria-hidden="true" />
            <span>Reject</span>
          </Button>
        ) : null}
      </div>

      {onReject && canReject ? (
        <DocumentRejectDialog
          open={rejectOpen}
          onOpenChange={(next) => {
            if (!rejectBusy) setRejectOpen(next);
          }}
          kind={doc.kind}
          fileName={doc.fileName}
          loading={rejectBusy}
          onConfirm={handleRejectConfirm}
        />
      ) : null}
    </div>
  );
}
