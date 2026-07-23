const KIBIBYTE = 1024;
const MEBIBYTE = KIBIBYTE * 1024;

export function formatBytes(bytes: number | null | undefined): string {
  if (bytes == null || !Number.isFinite(bytes) || bytes < 0) return "—";
  if (bytes < KIBIBYTE) return `${bytes} B`;
  if (bytes < MEBIBYTE) return `${(bytes / KIBIBYTE).toFixed(1)} KB`;
  return `${(bytes / MEBIBYTE).toFixed(1)} MB`;
}
