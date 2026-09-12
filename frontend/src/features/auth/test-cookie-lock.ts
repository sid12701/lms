/**
 * TEST-ONLY FIFO mutex standing in for the real cross-tab Web Lock API in
 * unit tests (jsdom provides no navigator.locks). Real browser tests use
 * actual Web Locks. Production with no locks fails closed — never a
 * per-tab fallback.
 */
import type { CookieLockManager } from "@/features/auth/auth-coordinator";

export function createTestCookieLockManager(): CookieLockManager {
  let tail: Promise<void> = Promise.resolve();
  return {
    request<T>(_name: string, fn: () => Promise<T>): Promise<T> {
      const run: Promise<T> = tail.then(() => fn());
      tail = run.then(
        () => undefined,
        () => undefined,
      );
      return run;
    },
  };
}
