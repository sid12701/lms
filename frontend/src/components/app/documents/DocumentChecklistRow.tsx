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
  className?: string;
}

export function DocumentChecklistRow({
  doc,
  compact = false,
  onView,
  onDownload,
  className,
}: DocumentChecklistRowProps) {
  const kindLabel = DOCUMENT_KIND_LABELS[doc.kind];

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
