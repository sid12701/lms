import { useEffect, useId, useRef, useState } from "react";
import { Dialog as DialogPrimitive } from "radix-ui";
import { FileText, X as XIcon } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { cn } from "@/lib/utils";
import { formatBytes } from "./DocumentChecklistRow";
import { DocumentStatusPill } from "./DocumentStatusPill";
import { newIdempotencyKey } from "@/lib/idempotency";
import { formatDateTime } from "@/lib/format";
import type { LoanDocument } from "@/schemas/loan-application";

export interface DocumentPreviewSheetProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** When null, the sheet renders an empty-body fallback. */
  document: LoanDocument | null;
  /** Called once per open transition; parent records VIEW + PREVIEW audit. */
  onPreview: (args: { documentId: string; idempotencyKey: string }) => Promise<void> | void;
  /** Optional — fires when the user clicks Download from inside the sheet. */
  onDownload?: (args: { documentId: string; idempotencyKey: string }) => Promise<void> | void;
  /** When true the preview area renders a skeleton/spinner. */
  loading?: boolean;
}

/**
 * Read once at render. Used to suppress Radix's slide-in animation when the
 * user has opted into reduced motion. We toggle `data-reduced-motion` on the
 * content node and short-circuit the animate-in/out tailwind utilities via a
 * scoped class — this mirrors the Phase 4 pattern used for chart entrance.
 */
function prefersReducedMotion(): boolean {
  if (typeof window === "undefined" || typeof window.matchMedia !== "function") {
    return false;
  }
  try {
    return window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  } catch {
    return false;
  }
}

/**
 * Right-side sheet that previews a single LoanDocument. The preview surface is
 * intentionally a placeholder (`<div>` shaped like an iframe) — this repo does
 * not render real file content. The audit-bearing side-effect is `onPreview`,
 * fired exactly once per `open=true` transition (guarded by a ref) so the
 * parent can append a DocumentAccessEvent (action=VIEWED) per BR-7.
 */
export function DocumentPreviewSheet({
  open,
  onOpenChange,
  document,
  onPreview,
  onDownload,
  loading = false,
}: DocumentPreviewSheetProps) {
  const headerId = useId();
  const descriptionId = useId();
  const lastPreviewedRef = useRef<string | null>(null);
  const [downloading, setDownloading] = useState(false);
  const reducedMotion = prefersReducedMotion();

  // Fire onPreview exactly once per open transition with a non-null document.
  useEffect(() => {
    if (!open || !document) {
      // Reset the guard when the sheet closes so the next open re-fires.
      if (!open) lastPreviewedRef.current = null;
      return;
    }
    if (lastPreviewedRef.current === document.id) return;
    lastPreviewedRef.current = document.id;
    void onPreview({ documentId: document.id, idempotencyKey: newIdempotencyKey() });
  }, [open, document, onPreview]);

  const handleDownload = async () => {
    if (!document || !onDownload) return;
    setDownloading(true);
    try {
      await onDownload({ documentId: document.id, idempotencyKey: newIdempotencyKey() });
    } finally {
      setDownloading(false);
    }
  };

  const fileMeta = document?.fileMeta ?? null;
  const sizeBytes = fileMeta?.size ?? null;
  const mimeType = fileMeta?.mime ?? null;
  const fileName = document?.displayName ?? null;

  return (
    <DialogPrimitive.Root open={open} onOpenChange={onOpenChange}>
      <DialogPrimitive.Portal>
        <DialogPrimitive.Overlay
          data-slot="document-preview-overlay"
          data-reduced-motion={reducedMotion ? "true" : "false"}
          className={cn(
            "fixed inset-0 z-50 bg-black/50",
            reducedMotion
              ? ""
              : "data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:animate-in data-[state=open]:fade-in-0",
          )}
        />
        <DialogPrimitive.Content
          data-slot="document-preview-sheet"
          data-reduced-motion={reducedMotion ? "true" : "false"}
          aria-labelledby={headerId}
          aria-describedby={descriptionId}
          className={cn(
            "bg-background fixed top-0 right-0 z-50 flex h-full w-full max-w-[480px] flex-col border-l shadow-lg outline-none sm:w-[480px]",
            reducedMotion
              ? ""
              : "data-[state=closed]:animate-out data-[state=closed]:slide-out-to-right data-[state=open]:animate-in data-[state=open]:slide-in-from-right data-[state=closed]:duration-200 data-[state=open]:duration-200",
          )}
        >
          <div
            data-slot="document-preview-header"
            className="border-border flex items-start justify-between gap-2 border-b px-4 py-3"
          >
            <div className="flex min-w-0 flex-col gap-1">
              <DialogPrimitive.Title
                id={headerId}
                data-slot="document-preview-title"
                className="text-foreground truncate text-sm font-semibold"
              >
                {fileName ?? "Document preview"}
              </DialogPrimitive.Title>
              {document ? (
                <div className="flex items-center gap-2">
                  <DocumentStatusPill status={document.status} />
                </div>
              ) : null}
            </div>
            <DialogPrimitive.Close asChild>
              <Button
                type="button"
                variant="ghost"
                size="icon-sm"
                aria-label="Close preview"
                data-slot="document-preview-close-icon"
              >
                <XIcon aria-hidden="true" />
              </Button>
            </DialogPrimitive.Close>
          </div>

          <div
            data-slot="document-preview-body"
            className="flex flex-1 flex-col gap-4 overflow-y-auto px-4 py-4"
          >
            {document ? (
              <>
                <div
                  data-slot="document-preview-surface"
                  role="img"
                  aria-label="Preview placeholder"
                  className="border-border bg-muted/30 flex h-64 flex-col items-center justify-center gap-2 rounded-md border text-center"
                >
                  {loading ? (
                    <Skeleton
                      data-slot="document-preview-loading"
                      className="h-40 w-40 rounded-md"
                    />
                  ) : (
                    <>
                      <FileText aria-hidden="true" className="text-foreground-muted size-10" />
                      <p className="text-foreground-muted px-4 text-xs">
                        Preview not available — backend integration required.
                      </p>
                    </>
                  )}
                </div>

                <dl
                  id={descriptionId}
                  data-slot="document-preview-meta"
                  className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1.5 text-xs"
                >
                  <dt className="text-foreground-muted">Uploaded</dt>
                  <dd className="text-foreground">
                    {document.uploadedAt ? formatDateTime(document.uploadedAt) : "—"}
                  </dd>
                  <dt className="text-foreground-muted">Uploaded by</dt>
                  <dd className="text-foreground font-mono">{document.uploadedBy ?? "—"}</dd>
                  <dt className="text-foreground-muted">Size</dt>
                  <dd className="text-foreground">{formatBytes(sizeBytes)}</dd>
                  <dt className="text-foreground-muted">MIME type</dt>
                  <dd className="text-foreground font-mono">{mimeType ?? "—"}</dd>
                </dl>
              </>
            ) : (
              <p
                id={descriptionId}
                data-slot="document-preview-empty"
                className="text-foreground-muted py-12 text-center text-sm"
              >
                No document selected.
              </p>
            )}
          </div>

          <div
            data-slot="document-preview-footer"
            className="border-border flex items-center justify-end gap-2 border-t px-4 py-3"
          >
            <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>
              Close
            </Button>
            {onDownload && document ? (
              <Button
                type="button"
                variant="default"
                onClick={handleDownload}
                disabled={downloading}
                aria-busy={downloading ? "true" : undefined}
              >
                {downloading ? "Downloading…" : "Download"}
              </Button>
            ) : null}
          </div>
        </DialogPrimitive.Content>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  );
}
