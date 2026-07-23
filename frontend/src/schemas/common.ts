/**
 * Shared Zod primitives used across all domain schemas.
 *
 * Currency is modelled as `number` of rupees (paise are not modelled per
 * `docs/UI pages.md` data summary). Datetimes are ISO 8601 strings.
 *
 * Indian-format identifiers (PAN, Aadhaar, mobile) are validated against
 * canonical regexes; storage layer is expected to keep them un-spaced.
 */
import { z } from "zod";

/** UUID v4 string. */
export const Uuid = z.string().uuid();
type Uuid = z.infer<typeof Uuid>;

/** ISO-8601 datetime string (must include time component). */
export const Iso8601 = z.string().datetime({ offset: true });
type Iso8601 = z.infer<typeof Iso8601>;

/** ISO-8601 calendar date (`YYYY-MM-DD`). Used for due dates / DOB. */
export const IsoDate = z.string().regex(/^\d{4}-\d{2}-\d{2}$/u, "must be YYYY-MM-DD");
type IsoDate = z.infer<typeof IsoDate>;

/**
 * INR amount in rupees. Non-negative, capped to 1e12 to keep arithmetic
 * comfortably within JS number precision (15-16 significant digits).
 */
export const MoneyINR = z.number().nonnegative().max(1e12, "amount exceeds 1e12");
type MoneyINR = z.infer<typeof MoneyINR>;

/** Same as MoneyINR but strictly > 0 — for fields like principal/installmentAmount. */
export const MoneyINRPositive = z.number().positive().max(1e12);
type MoneyINRPositive = z.infer<typeof MoneyINRPositive>;

/** Indian PAN — five letters, four digits, one letter. */
export const Pan = z.string().regex(/^[A-Z]{5}[0-9]{4}[A-Z]$/u, "invalid PAN format");
type Pan = z.infer<typeof Pan>;

/** 12-digit Aadhaar (no spaces). */
export const Aadhaar = z.string().regex(/^[0-9]{12}$/u, "invalid Aadhaar format");
type Aadhaar = z.infer<typeof Aadhaar>;

/** 10-digit Indian mobile, leading 6/7/8/9 (TRAI block). */
export const Mobile = z.string().regex(/^[6-9][0-9]{9}$/u, "invalid Indian mobile number");
type Mobile = z.infer<typeof Mobile>;

/** Email — uses Zod's built-in. */
export const Email = z.string().email();
type Email = z.infer<typeof Email>;

/**
 * IPv4 address with optional CIDR (`/0`–`/32`). IPv6 is intentionally
 * excluded — the LSP API allow-list contract is IPv4-only.
 */
export const IpCidr = z
  .string()
  .regex(
    /^((25[0-5]|2[0-4]\d|[01]?\d\d?)\.){3}(25[0-5]|2[0-4]\d|[01]?\d\d?)(\/(3[0-2]|[12]?\d))?$/u,
    "invalid IPv4 / CIDR",
  );
type IpCidr = z.infer<typeof IpCidr>;

/** Indian IFSC — 4 letters, 0, 6 alphanumerics. */
export const Ifsc = z.string().regex(/^[A-Z]{4}0[A-Z0-9]{6}$/u, "invalid IFSC");
type Ifsc = z.infer<typeof Ifsc>;

/** Bank account number (sanity bound, not regulator-prescribed). */
export const BankAccount = z.string().regex(/^[0-9]{9,20}$/u, "invalid account number");
type BankAccount = z.infer<typeof BankAccount>;

/** PIN code (Indian zip, 6 digits, no leading zero). */
export const PinCode = z.string().regex(/^[1-9][0-9]{5}$/u, "invalid PIN code");
type PinCode = z.infer<typeof PinCode>;

/**
 * Idempotency-Key header value. UUID format per BR-5; we accept any
 * non-empty string up to 80 chars to stay forward-compatible with
 * upstream LSP clients that already use other id formats.
 */
export const IdempotencyKey = z.string().min(1).max(80);
type IdempotencyKey = z.infer<typeof IdempotencyKey>;
