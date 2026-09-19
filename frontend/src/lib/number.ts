export function finiteNumberOrZero(value: number | string | null | undefined): number {
  if (value == null) return 0;
  const parsed = typeof value === "string" ? Number(value) : value;
  return Number.isFinite(parsed) ? parsed : 0;
}

/**
 * H28 — nullable counterpart for externally supplied amounts. A missing or
 * non-finite figure means "not available" and must stay null so the UI can
 * render it as unknown; mapping it to zero would understate debt.
 */
export function finiteNumberOrNull(value: number | string | null | undefined): number | null {
  if (value == null) return null;
  const parsed = typeof value === "string" ? Number(value) : value;
  return Number.isFinite(parsed) ? parsed : null;
}
