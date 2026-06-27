/**
 * Shared document-preview helpers. Kept separate from the modal component so
 * the component module only exports components (react-refresh friendly).
 */

/** Content types the UI can render inline. Mirrors the backend preview allowlist. */
const INLINE_PREVIEWABLE_MIME_TYPES = new Set(["application/pdf", "image/jpeg", "image/png"]);

/** True when `mimeType` (charset params ignored) is safe to render inline in the browser. */
export function isInlinePreviewable(mimeType: string | null | undefined): boolean {
  if (!mimeType) return false;
  return INLINE_PREVIEWABLE_MIME_TYPES.has(mimeType.split(";")[0]!.trim().toLowerCase());
}
