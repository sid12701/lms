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
const INR_STANDARD_FORMATTERS: Record<0 | 2, Intl.NumberFormat> = {
  0: new Intl.NumberFormat("en-IN", {
    style: "currency",
    currency: "INR",
    minimumFractionDigits: 0,
    maximumFractionDigits: 0,
  }),
  2: new Intl.NumberFormat("en-IN", {
    style: "currency",
    currency: "INR",
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }),
};
const INR_COMPACT_FORMATTERS: Record<0 | 2, Intl.NumberFormat> = {
  0: new Intl.NumberFormat("en-IN", {
    style: "currency",
    currency: "INR",
    notation: "compact",
    maximumFractionDigits: 1,
  }),
  2: new Intl.NumberFormat("en-IN", {
    style: "currency",
    currency: "INR",
    notation: "compact",
    maximumFractionDigits: 2,
  }),
};

/**
 * The product's business timezone — Asia/Kolkata, matching the backend's
 * `TimeConfig.BUSINESS_ZONE`. Contractual dates (approval anchoring, due
 * dates, DPD) are computed in this zone on the backend, so instants shown to
 * operators render in it too — never in the browser's timezone, which would
 * disagree with the business date at day boundaries (M09).
 */
export const BUSINESS_TIME_ZONE = "Asia/Kolkata";

const BUSINESS_DATE_FORMATTER = new Intl.DateTimeFormat("en-IN", {
  timeZone: BUSINESS_TIME_ZONE,
  day: "2-digit",
  month: "short",
  year: "numeric",
});
const BUSINESS_DATE_TIME_FORMATTER = new Intl.DateTimeFormat("en-IN", {
  timeZone: BUSINESS_TIME_ZONE,
  day: "2-digit",
  month: "short",
  year: "numeric",
  hour: "2-digit",
  minute: "2-digit",
  hour12: false,
});

/** `YYYY-MM-DD` — a contractual calendar date, not an instant. */
const DATE_ONLY_PATTERN = /^\d{4}-\d{2}-\d{2}$/;

function isPlausibleInstant(date: Date): boolean {
  return !Number.isNaN(date.getTime()) && date.getFullYear() >= MIN_PLAUSIBLE_YEAR;
}

/** ₹ in en-IN locale, tabular figures. */
export function formatINR(amount: number, opts?: { compact?: boolean; decimals?: 0 | 2 }): string {
  const decimals = opts?.decimals ?? 0;
  if (opts?.compact) {
    // Indian compact: lakh / crore — Intl supports `notation: "compact"` with en-IN.
    // Keep one fraction digit unless the caller asks for more: with 0 digits,
    // ₹1,50,000 rounds to "₹2L" — a materially wrong figure on finance surfaces.
    return INR_COMPACT_FORMATTERS[decimals].format(amount);
  }
  return INR_STANDARD_FORMATTERS[decimals].format(amount);
}

/** "09/05/2026" — dd/MM/yyyy for date picker display. */
export function formatPickerDate(iso: string): string {
  return format(parseISO(iso), "dd/MM/yyyy");
}

/**
 * "09 May 2026" — calendar-date display.
 *
 * A date-only value (`2026-05-09`) is a contractual date, not an instant: it
 * renders literally and cannot be shifted by the browser's timezone. A value
 * carrying a time component is an instant and renders the date it falls on in
 * the business zone, so it agrees with backend business-date math (M09).
 */
export function formatDate(iso: string): string {
  if (DATE_ONLY_PATTERN.test(iso)) {
    return format(parseISO(iso), "dd MMM yyyy");
  }
  return BUSINESS_DATE_FORMATTER.format(parseISO(iso));
}

/**
 * "09 May 2026, 14:32 IST" — an actual instant rendered in the business zone
 * and labeled with it. Browser-local rendering used to print e.g. a London
 * wall-clock time with no zone marker (or worse, an "IST" label on local
 * time); the label is now always accurate (M09).
 */
export function formatDateTime(iso: string): string {
  return `${BUSINESS_DATE_TIME_FORMATTER.format(parseISO(iso))} IST`;
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
