/**
 * Idempotency-Key utilities (BR-5).
 *
 * - `newIdempotencyKey()` mints a UUID v4 via `crypto.randomUUID()`.
 * - `createIdempotencyCache()` is an in-memory store for same-key replay
 *   within `ttlMs` — useful in tests.
 *
 * No React, no fetch, no DOM — pure JS suitable for Vitest + Node.
 */
export function newIdempotencyKey(): string {
  // crypto.randomUUID exists in Node 20+ and all browsers we target.
  return crypto.randomUUID();
}

interface CacheEntry<T> {
  value: T;
  expiresAt: number;
}

export interface IdempotencyCache {
  /** Cache hit returns the previously stored value. */
  lookup<T>(key: string | undefined): T | undefined;
  /** Stores `value` under `key`. No-op when `key` is undefined. */
  cache<T>(key: string | undefined, value: T): void;
  /** Drops every entry. Helpful in tests + after scenario reset. */
  clear(): void;
}

/** Default TTL for the in-memory idempotency cache. */
export const DEFAULT_IDEMPOTENCY_TTL_MS = 30_000;

export function createIdempotencyCache(
  ttlMs: number = DEFAULT_IDEMPOTENCY_TTL_MS,
): IdempotencyCache {
  const store = new Map<string, CacheEntry<unknown>>();

  return {
    lookup<T>(key: string | undefined): T | undefined {
      if (!key) return undefined;
      const entry = store.get(key);
      if (!entry) return undefined;
      if (entry.expiresAt <= Date.now()) {
        store.delete(key);
        return undefined;
      }
      return entry.value as T;
    },
    cache<T>(key: string | undefined, value: T): void {
      if (!key) return;
      store.set(key, { value, expiresAt: Date.now() + ttlMs });
    },
    clear(): void {
      store.clear();
    },
  };
}
