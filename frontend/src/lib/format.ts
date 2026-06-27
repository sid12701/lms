/**
 * Formatting + masking helpers.
 *
 * - INR formatting uses `Intl.NumberFormat("en-IN")` with `currency` style.
 * - Date formatting uses `date-fns` for stable test output.
 * - Masks default to a single bullet glyph; consumers can wrap further.
 *
 * Per `docs/gap-fixes.md` § Gap #1, masking is the only presentation of
 * PII — there is no reveal-on-demand flow.
 */
import { format, formatDistanceStrict, formatDistanceToNowStrict, parseISO } from "date-fns";

const BULLET = "•"; // •
const MIN_PLAUSIBLE_YEAR = 2000;

function isPlausibleInstant(date: Date): boolean {
  return !Number.isNaN(date.getTime()) && date.getFullYear() >= MIN_PLAUSIBLE_YEAR;
}

/** ₹ in en-IN locale, tabular figures. */
export function formatINR(amount: number, opts?: { compact?: boolean; decimals?: 0 | 2 }): string {
  const decimals = opts?.decimals ?? 0;
  if (opts?.compact) {
    // Indian compact: lakh / crore — Intl supports `notation: "compact"` with en-IN.
    return new Intl.NumberFormat("en-IN", {
      style: "currency",
      currency: "INR",
      notation: "compact",
      maximumFractionDigits: decimals,
    }).format(amount);
  }
  return new Intl.NumberFormat("en-IN", {
    style: "currency",
    currency: "INR",
    minimumFractionDigits: decimals,
    maximumFractionDigits: decimals,
  }).format(amount);
}

/** "09/05/2026" — dd/MM/yyyy for date picker display. */
export function formatPickerDate(iso: string): string {
  return format(parseISO(iso), "dd/MM/yyyy");
}

/** "09 May 2026" */
export function formatDate(iso: string): string {
  return format(parseISO(iso), "dd MMM yyyy");
}

/** "09 May 2026, 14:32" */
export function formatDateTime(iso: string): string {
  return format(parseISO(iso), "dd MMM yyyy, HH:mm");
}

/**
 * "2 hours ago" — pass a fixed `now` (e.g. in tests) for deterministic output.
 */
export function formatRelative(iso: string, now?: Date): string {
  const date = parseISO(iso);
  if (!isPlausibleInstant(date)) {
    return "—";
  }
  if (now) {
    return formatDistanceStrict(date, now, { addSuffix: true });
  }
  return formatDistanceToNowStrict(date, { addSuffix: true });
}

/**
 * Mask aadhaar: "XXXXXXXX1234" — last 4 visible, fixed prefix.
 *
 * Idempotent on already-masked values (XXXXXXXX1234 → XXXXXXXX1234) and
 * tolerant of separators/non-digit chars in the input. Matches the
 * backend's mask shape (see `LspLoanApplicationResponses.maskAadharNumber`
 * + `BorrowerAdminController.maskAadhar`) so a value can flow from the
 * backend to FE without double-masking.
 */
export function maskAadhaar(aadhaar: string | null | undefined): string {
  if (!aadhaar) return "";
  if (/^X{8}\d{4}$/.test(aadhaar)) return aadhaar;
  const digits = aadhaar.replace(/\D/g, "");
  if (digits.length === 0) return aadhaar;
  return `XXXXXXXX${digits.slice(-4).padStart(4, "X")}`;
}

/** "••••••3456" — last 4 visible, regardless of account length. */
export function maskAccount(account: string): string {
  if (account.length < 4) return BULLET.repeat(account.length);
  return `${BULLET.repeat(account.length - 4)}${account.slice(-4)}`;
}
