/**
 * Compatibility shim for the mock-router idempotency cache (BR-5).
 *
 * Mutating routes registered with `{ mutating: true }` get transparent
 * idempotency-key replay handling from {@link ../router}: the router
 * consults the singleton cache before calling the handler, and stores the
 * handler's result under the request's `Idempotency-Key` header on success.
 * Handlers therefore do not need to invoke `withIdempotency` directly —
 * registering the route is enough.
 *
 * This module exposes:
 *   - `resetIdempotency()` — test-setup helper that clears the cache. Mirrors
 *     the router's `_clearIdempotencyCacheForTests` under a stable name so
 *     test files in this folder don't have to reach into router internals.
 *   - `withIdempotency<T>(key, compute)` — a manual replay helper for the
 *     rare case where a sub-step inside a handler wants to short-circuit on
 *     its own key (separate from the request's outer key). Backed by the
 *     same `IdempotencyCache` primitive used by the router.
 */
import { createIdempotencyCache, DEFAULT_IDEMPOTENCY_TTL_MS } from "@/lib/idempotency";
import { _clearIdempotencyCacheForTests } from "./router";

const localCache = createIdempotencyCache(DEFAULT_IDEMPOTENCY_TTL_MS);

/**
 * Run `compute()` and cache its result under `key` for 30 seconds. A
 * subsequent call with the same key returns the cached value instead of
 * re-running `compute()`. Returns the result of `compute()` directly when
 * `key` is null/undefined (no caching).
 */
export function withIdempotency<T>(key: string | null | undefined, compute: () => T): T {
  if (!key) return compute();
  const cached = localCache.lookup<T>(key);
  if (cached !== undefined) return cached;
  const value = compute();
  localCache.cache(key, value);
  return value;
}

/**
 * Reset both the router-level idempotency cache and the local cache backing
 * `withIdempotency`. Call from `beforeEach` so test isolation is preserved.
 */
export function resetIdempotency(): void {
  _clearIdempotencyCacheForTests();
  localCache.clear();
}
