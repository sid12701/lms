import { useState } from "react";
import { toast } from "sonner";
import { Download, Loader2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { newIdempotencyKey } from "@/lib/idempotency";
import type { LoanDocument } from "@/schemas/loan-application";

export interface DownloadAllAsZipButtonProps {
  documents: readonly LoanDocument[];
  /**
   * Called once per document with its own idempotency key (BR-5 still applies
   * per individual file). This is the audit-bearing side-effect — parent
   * appends a DocumentAccessEvent (action=DOWNLOADED) per document.
   */
  onBeforeAccess: (args: { documentId: string; idempotencyKey: string }) => Promise<void> | void;
  /**
   * Receives the synthesised blob URL + filename once the in-memory "zip" is
   * ready. The parent is responsible for triggering the actual `<a>` click —
   * this primitive deliberately does not touch the DOM beyond Blob/URL.
   */
  onZipReady: (args: { url: string; filename: string }) => void;
  disabled?: boolean;
}

/**
 * Builds a single Blob containing a small text manifest (README.txt format)
 * that lists every document's id, filename, mime type, and size. The MIME type
 * is `application/zip` so consumers can treat the returned URL as if it were a
 * real zip download, but the body is plain text — this is a UI-only stub.
 *
 * Once real backend integration lands, replace this with a server-side zip
 * stream or swap to a client-side zip library (e.g. jszip). The interface
 * (`onZipReady({ url, filename })`) is designed to be drop-in compatible.
 */
function synthesizeZipBlob(documents: readonly LoanDocument[]): Blob {
  const header = [
    "Bhawana LMS — synthesised document bundle (UI stub)",
    "This file is not a real zip archive — it is an in-memory placeholder.",
    `Generated: ${new Date().toISOString()}`,
    `File count: ${documents.length}`,
    "",
    "Documents:",
  ];
  const rows = documents.map((doc, idx) => {
    const fileName = doc.displayName ?? "(unnamed)";
    const mime = doc.fileMeta?.mime ?? "—";
    const size = doc.fileMeta?.size != null ? String(doc.fileMeta.size) : "—";
    return `  ${idx + 1}. id=${doc.id} name=${fileName} mime=${mime} size=${size}`;
  });
  const body = [...header, ...rows, ""].join("\n");
  return new Blob([body], { type: "application/zip" });
}

function zipFilename(): string {
  // Compact ISO timestamp — strip ":" so the value is safe in file systems.
  const stamp = new Date().toISOString().replace(/[:.]/g, "-");
  return `borrower-documents-${stamp}.zip`;
}

/**
 * Bulk-download trigger for a borrower's document checklist. On click the
 * button (1) calls `onBeforeAccess` with a fresh idempotency key per document
 * — this is what writes the DOCUMENT_ACCESS audit row per file (BR-7) — and
 * then (2) synthesises an in-memory pseudo-zip blob and forwards the URL +
 * filename to the parent. The parent owns the actual `<a>` click.
 */
export function DownloadAllAsZipButton({
  documents,
  onBeforeAccess,
  onZipReady,
  disabled = false,
}: DownloadAllAsZipButtonProps) {
  const [busy, setBusy] = useState(false);
  const count = documents.length;
  const isDisabled = disabled || busy || count === 0;

  const handleClick = async () => {
    if (count === 0) return;
    setBusy(true);
    try {
      for (const doc of documents) {
        await onBeforeAccess({ documentId: doc.id, idempotencyKey: newIdempotencyKey() });
      }
      const blob = synthesizeZipBlob(documents);
      const url = URL.createObjectURL(blob);
      const filename = zipFilename();
      onZipReady({ url, filename });
    } catch (err) {
      // BR-7 audit is per-file — if any single onBeforeAccess fails, abort the
      // whole download so the user does not end up with a partial bundle that
      // misrepresents what was audited.
      const message = err instanceof Error ? err.message : "Failed to prepare download.";
      toast.error(`Download aborted: ${message}`);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Button
      type="button"
      variant="outline"
      onClick={handleClick}
      disabled={isDisabled}
      aria-busy={busy ? "true" : undefined}
      data-slot="download-all-as-zip"
    >
      {busy ? (
        <Loader2 aria-hidden="true" className="animate-spin" />
      ) : (
        <Download aria-hidden="true" />
      )}
      <span>{busy ? "Preparing…" : `Download all (${count})`}</span>
    </Button>
  );
}
