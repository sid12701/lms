import { useState } from "react";
import { ShieldCheck, Upload } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import { newIdempotencyKey } from "@/lib/idempotency";
import { DOCUMENT_KIND_LABELS, type Document } from "@/schemas/document";
import { DocumentStatusPill } from "./DocumentStatusPill";

export interface DocumentUploadRowProps {
  doc: Document;
  compact?: boolean;
  /**
   * Mutation — kicks off the upload flow. Phase 6 will wire in a real file
   * picker / drag-and-drop; for v1 the row exposes a Button that mints a
   * fresh idempotency key (BR-5) and forwards it to the consumer.
   */
  onUpload?: (args: { idempotencyKey: string }) => Promise<void> | void;
  /** True once the loan is past disbursement — see `isDisbursementGatePassed`. */
  gatePassed?: boolean;
  className?: string;
}

/**
 * Empty-state row for a document that has not been uploaded yet
 * (status === "PENDING"). Surfaces the kind label, the
 * "required for disbursement" badge if applicable, and a stub Upload button.
 */
export function DocumentUploadRow({
  doc,
  compact = false,
  onUpload,
  gatePassed = false,
  className,
}: DocumentUploadRowProps) {
  const [busy, setBusy] = useState(false);
  const kindLabel = DOCUMENT_KIND_LABELS[doc.kind];

  const handleClick = async () => {
    if (!onUpload) return;
    setBusy(true);
    try {
      await onUpload({ idempotencyKey: newIdempotencyKey() });
    } finally {
      setBusy(false);
    }
  };

  return (
    <div
      data-slot="document-upload-row"
      data-required={doc.requiredForDisbursement ? "true" : "false"}
      data-compact={compact ? "true" : "false"}
      className={cn(
        "border-border-strong bg-surface-muted rounded-container flex flex-col gap-3 border border-dashed p-4 sm:flex-row sm:items-center sm:justify-between",
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
              className={cn(
                "gap-1",
                gatePassed
                  ? // `dark:` prefix required — see the note in
                    // `DocumentChecklistRow`: the `outline` variant's
                    // `dark:bg-input/30` is not deduped by an unprefixed
                    // background, so both paint in dark.
                    "border-border bg-surface-muted dark:bg-surface-muted text-foreground-muted"
                  : "border-warning/30 bg-warning/10 text-warning",
              )}
            >
              <ShieldCheck className="size-3" aria-hidden="true" />
              <span>
                {gatePassed ? "Required before disbursement" : "Required for disbursement"}
              </span>
            </Badge>
          ) : null}
        </div>
        <p className="text-foreground-muted text-xs">
          {gatePassed ? "Not provided before disbursement." : "No file uploaded yet."}
        </p>
      </div>

      <div className="flex items-center">
        {onUpload ? (
          <Button
            type="button"
            variant="outline"
            size={compact ? "xs" : "sm"}
            onClick={handleClick}
            disabled={busy}
            aria-busy={busy ? "true" : undefined}
            aria-label={`Upload ${kindLabel}`}
          >
            <Upload aria-hidden="true" />
            <span>{busy ? "Uploading…" : "Upload"}</span>
          </Button>
        ) : null}
      </div>
    </div>
  );
}
