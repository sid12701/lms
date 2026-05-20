/**
 * Formatting + masking helpers.
 *
 * - INR formatting uses `Intl.NumberFormat("en-IN")` with `currency` style.
 * - Date formatting uses `date-fns` for stable test output.
 * - Masks default to a single bullet glyph; consumers can wrap further.
 *
 * BR-7: masking is the default presentation of PII; `format.ts` does not
 * itself reveal — `MaskedField` (A6) coordinates the audit write.
 */
import { format, formatDistanceStrict, formatDistanceToNowStrict, parseISO } from "date-fns";

const BULLET = "•"; // •

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
  if (now) {
    return formatDistanceStrict(date, now, { addSuffix: true });
  }
  return formatDistanceToNowStrict(date, { addSuffix: true });
}

/** "ABCDE••••F" — first 5 + 4 bullets + last 1 (PAN is 10 chars). */
export function maskPan(pan: string): string {
  if (pan.length !== 10) return BULLET.repeat(Math.max(0, pan.length));
  return `${pan.slice(0, 5)}${BULLET.repeat(4)}${pan.slice(9)}`;
}

/** "XXXX XXXX 1234" — last 4 visible. */
export function maskAadhaar(aadhaar: string): string {
  if (aadhaar.length !== 12) return BULLET.repeat(Math.max(0, aadhaar.length));
  return `XXXX XXXX ${aadhaar.slice(8)}`;
}

/** "••••••3210" — last 4 visible. */
export function maskMobile(mobile: string): string {
  if (mobile.length < 4) return BULLET.repeat(mobile.length);
  return `${BULLET.repeat(mobile.length - 4)}${mobile.slice(-4)}`;
}

/** "••••••3456" — last 4 visible, regardless of account length. */
export function maskAccount(account: string): string {
  if (account.length < 4) return BULLET.repeat(account.length);
  return `${BULLET.repeat(account.length - 4)}${account.slice(-4)}`;
}

/** "user@example.com" → "u••••@example.com". */
export function maskEmail(email: string): string {
  const at = email.indexOf("@");
  if (at < 1) return email; // no local part to mask
  const local = email.slice(0, at);
  const domain = email.slice(at);
  const visible = local.charAt(0);
  return `${visible}${BULLET.repeat(Math.max(1, local.length - 1))}${domain}`;
}
