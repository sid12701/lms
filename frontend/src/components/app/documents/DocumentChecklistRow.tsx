import { Download, Eye, ShieldCheck } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import { formatDateTime, formatRelative } from "@/lib/format";
import { DOCUMENT_KIND_LABELS, type Document } from "@/schemas/document";
import { DocumentStatusPill } from "./DocumentStatusPill";
import { formatBytes } from "./document-format";

// Gap #18 — verify/reject affordances removed. Internal users render a
// view-only row; uploads are the only mutating action and live on the
// separate `DocumentUploadRow` component.
export interface DocumentChecklistRowPermissions {
  /** Reserved for future per-role surface tightening; currently unused. */
  canManage?: boolean;
}

export interface DocumentChecklistRowProps {
  doc: Document;
  compact?: boolean;
  onView?: () => void;
  onDownload?: () => void;
  permissions?: DocumentChecklistRowPermissions;
  /**
   * True once the loan is past disbursement, which turns the requirement from a
   * blocker into a record. See `isDisbursementGatePassed`.
   */
  gatePassed?: boolean;
  className?: string;
}

export function DocumentChecklistRow({
  doc,
  compact = false,
  onView,
  onDownload,
  gatePassed = false,
  className,
}: DocumentChecklistRowProps) {
  const kindLabel = DOCUMENT_KIND_LABELS[doc.kind];

  return (
    <div
      data-slot="document-checklist-row"
      data-status={doc.status}
      data-compact={compact ? "true" : "false"}
      className={cn(
        "border-border bg-surface rounded-container flex flex-col gap-3 border p-4 sm:flex-row sm:items-start sm:justify-between",
        compact && "gap-2 p-3",
        className,
      )}
    >
      <div className="flex min-w-0 flex-col gap-1.5">
        <div className="flex flex-wrap items-center gap-2">
          <span className="text-foreground text-sm font-semibold">{kindLabel}</span>
          <DocumentStatusPill status={doc.status} />
          {doc.requiredForDisbursement && doc.status !== "NOT_REQUIRED" ? (
            <Badge
              variant="outline"
              data-slot="required-for-disbursement-badge"
              className={cn(
                "gap-1",
                gatePassed
                  ? // `dark:` prefix required: the `outline` variant sets
                    // `dark:bg-input/30`, and tailwind-merge does not dedupe a
                    // prefixed utility against an unprefixed one — so in dark
                    // both applied and the pill rendered `input/30` *over*
                    // `surface-muted`, dropping its text to 4.32:1.
                    "border-border bg-surface-muted dark:bg-surface-muted text-foreground-muted"
                  : "border-warning/30 bg-warning/10 text-warning",
              )}
            >
              <ShieldCheck className="size-3" aria-hidden="true" />
              {/* Past the gate this is a record of what was required, not a
                  blocker — the warning tint would assert an action nobody can
                  take on a loan whose funds have already moved. */}
              <span>
                {gatePassed ? "Required before disbursement" : "Required for disbursement"}
              </span>
            </Badge>
          ) : null}
        </div>

        {doc.fileName ? (
          <p data-slot="document-file-meta" className="text-foreground-muted text-xs">
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
        ) : doc.status === "NOT_REQUIRED" ? (
          <p className="text-foreground-muted text-xs">
            Not required for this loan — no document is expected.
          </p>
        ) : gatePassed ? (
          // Factual and closed-ended: the gap is historical, and there is no
          // upload affordance on this surface that could resolve it.
          <p className="text-foreground-muted text-xs">Not provided before disbursement.</p>
        ) : (
          <p className="text-foreground-muted text-xs">No file uploaded yet.</p>
        )}

        {doc.uploadedAt ? (
          <p className="text-foreground-subtle text-xs">
            Uploaded <time dateTime={doc.uploadedAt}>{formatDateTime(doc.uploadedAt)}</time>{" "}
            <span className="text-foreground-subtle">({formatRelative(doc.uploadedAt)})</span>
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
      </div>
    </div>
  );
}
