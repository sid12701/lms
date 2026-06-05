/** Cell formatters for MIS preview columns. */
import { formatINR, maskAadhaar } from "@/lib/format";
import type { MisPreviewInstallment } from "../types";

export function truncateMiddle(value: string, head = 6, tail = 4): string {
  if (value.length <= head + tail + 1) return value;
  return `${value.slice(0, head)}…${value.slice(-tail)}`;
}

export function plainText(value: string | null | undefined): string {
  return value && value.trim() !== "" ? value : "—";
}

export function nullableNumber(value: number | null | undefined): string {
  if (value === null || value === undefined) return "—";
  if (!Number.isFinite(value)) return "—";
  return value.toLocaleString();
}

/**
 * Render an aadhaar value, defensively re-masking through the global
 * formatter. The BE masks before sending (Gap #1 + Gap #10); this is a
 * second line of defence so a misconfigured upstream cannot leak digits
 * through the preview surface.
 */
export function safeAadhaarDisplay(value: string | null | undefined): string {
  if (!value) return "—";
  return maskAadhaar(value);
}

/**
 * Render the borrower bank account masked to last-4 — the BE already
 * masks to `XXXX<last4>` (Gap #10). This component preserves whatever the
 * wire returned and falls back to em-dash if absent.
 */
export function safeBankAccountDisplay(value: string | null | undefined): string {
  if (!value) return "—";
  return value;
}

export function installmentCell(installment: MisPreviewInstallment | undefined): string {
  if (!installment) return "—";
  const due = installment.dueDate ?? "—";
  const paid = formatINR(installment.paidAmount, { compact: true });
  const dueAmt = formatINR(installment.installmentAmount, { compact: true });
  return `${due} · ${paid}/${dueAmt}`;
}
