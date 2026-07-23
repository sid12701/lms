/**
 * DocumentPreviewModal — safe in-browser preview of a single stored document.
 *
 * Security model:
 * - Bytes are fetched with the session JWT in the `Authorization` header via
 *   `requestBlob` and rendered from an ephemeral `blob:` object URL. We never
 *   point an `<iframe>`/`<img>` at the API URL directly (that would be blocked
 *   by the backend's `X-Frame-Options: DENY` and could not carry the auth
 *   header), so the storage URL is never exposed and no credentials leak.
 * - The backend only serves `disposition=inline` for an allowlist of safe types
 *   (PDF/JPEG/PNG) and responds 415 otherwise, so scriptable formats (SVG/HTML)
 *   can never reach the renderer. We mirror that allowlist here to decide which
 *   renderer to use, and fall back to a download-only card for anything else.
 * - The object URL is derived from the fetched blob and revoked when the blob
 *   changes or the modal unmounts.
 *
 * Each open triggers a fresh inline fetch, which the backend records as a
 * SINGLE_DOCUMENT_PREVIEWED access-audit row.
 */
import { useEffect, useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Download, FileWarning, Loader2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { ApiError, requestBlob } from "@/lib/api/http-client";
import { mapApiErrorMessage } from "@/lib/api/user-messages";
import { isInlinePreviewable } from "./documentPreview";

export interface DocumentPreviewModalProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** Human title for the dialog (document kind label or filename). */
  title: string;
  /** MIME type used to pick the renderer; non-allowlisted → download-only fallback. */
  mimeType: string | null;
  /**
   * Relative backend path to fetch the inline blob from
   * (e.g. `/api/v1/internal/ops/.../content?disposition=inline`). Null when no
   * document is selected.
   */
  contentPath: string | null;
  /** Fires when the user clicks Download from inside the modal. */
  onDownload?: () => void | Promise<void>;
}

export function DocumentPreviewModal({
  open,
  onOpenChange,
  title,
  mimeType,
  contentPath,
  onDownload,
}: DocumentPreviewModalProps) {
  const [downloading, setDownloading] = useState(false);

  const previewable = isInlinePreviewable(mimeType);
  const enabled = open && Boolean(contentPath) && previewable;

  const blobQuery = useQuery({
    queryKey: ["document-preview", contentPath],
    queryFn: async () => {
      const { blob } = await requestBlob(contentPath as string);
      return blob;
    },
    enabled,
    // Sensitive PII — never cache the bytes; refetch (and re-audit) on each open.
    staleTime: 0,
    gcTime: 0,
    retry: false,
  });

  const objectUrl = useMemo(() => {
    if (!blobQuery.data) return null;
    return URL.createObjectURL(blobQuery.data);
  }, [blobQuery.data]);

  useEffect(() => {
    if (!objectUrl) return;
    return () => URL.revokeObjectURL(objectUrl);
  }, [objectUrl]);

  const renderKind = (mimeType ?? "").toLowerCase().startsWith("image/") ? "image" : "pdf";

  const handleDownload = async () => {
    if (!onDownload) return;
    setDownloading(true);
    try {
      await onDownload();
    } finally {
      setDownloading(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="flex max-h-[90vh] w-full flex-col gap-4 sm:max-w-3xl">
        <DialogHeader>
          <DialogTitle className="truncate pr-8">{title}</DialogTitle>
          <DialogDescription>{mimeType ?? "Unknown file type"}</DialogDescription>
        </DialogHeader>

        <div
          data-slot="document-preview-surface"
          className="bg-muted/30 border-border relative flex h-[70vh] min-h-64 items-center justify-center overflow-hidden rounded-md border"
        >
          {!previewable ? (
            <PreviewFallback
              message="This file type can't be previewed here."
              hint="Download the file to view it in a native application."
            />
          ) : blobQuery.isError ? (
            <PreviewFallback
              message="Couldn't load the preview."
              hint={messageForError(blobQuery.error)}
            />
          ) : !objectUrl ? (
            <div
              data-slot="document-preview-loading"
              role="status"
              aria-live="polite"
              className="text-foreground-muted flex flex-col items-center gap-2 text-xs"
            >
              <Loader2
                className="size-6 animate-spin motion-reduce:animate-none"
                aria-hidden="true"
              />
              <span>Loading preview…</span>
            </div>
          ) : renderKind === "image" ? (
            <img
              data-slot="document-preview-image"
              src={objectUrl}
              alt={`Preview of ${title}`}
              className="max-h-full max-w-full object-contain"
            />
          ) : (
            <iframe
              data-slot="document-preview-pdf"
              title={`Preview of ${title}`}
              src={objectUrl}
              sandbox="allow-same-origin"
              className="h-full w-full border-0"
            />
          )}
        </div>

        <DialogFooter>
          <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>
            Close
          </Button>
          {onDownload ? (
            <Button
              type="button"
              variant="default"
              onClick={handleDownload}
              disabled={downloading}
              aria-busy={downloading ? "true" : undefined}
            >
              <Download aria-hidden="true" />
              <span>{downloading ? "Downloading…" : "Download"}</span>
            </Button>
          ) : null}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function PreviewFallback({ message, hint }: { message: string; hint?: string }) {
  return (
    <div
      data-slot="document-preview-fallback"
      className="text-foreground-muted flex max-w-sm flex-col items-center gap-2 px-6 text-center"
    >
      <FileWarning className="size-8" aria-hidden="true" />
      <p className="text-foreground text-sm font-medium">{message}</p>
      {hint ? <p className="text-xs">{hint}</p> : null}
    </div>
  );
}

function messageForError(err: unknown): string {
  if (err instanceof ApiError && err.code === "DOCUMENT_PREVIEW_UNSUPPORTED") {
    return "This file type can't be previewed. Download it instead.";
  }
  return mapApiErrorMessage(err, "Couldn't load the document preview.");
}
