/**
 * Idempotency-Key utilities (BR-5).
 *
 * - `newIdempotencyKey()` mints a UUID v4 via `crypto.randomUUID()`.
 * No React, no fetch, no DOM — pure JS suitable for Vitest + Node.
 */
export function newIdempotencyKey(): string {
  // crypto.randomUUID exists in Node 20+ and all browsers we target.
  return crypto.randomUUID();
}
