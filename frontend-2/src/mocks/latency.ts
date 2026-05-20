/**
 * Simulated network latency for the mock router.
 *
 * - Default jitter: uniform 150–450 ms.
 * - Override via `setLatencyOverride(ms)` (used by the slow-network scenario
 *   + DevTopBarTools "freeze for X ms" button).
 * - In the browser, `?slow=1500` query flag bumps latency for the session.
 */
const MIN_MS = 150;
const MAX_MS = 450;

let override: number | null = null;

function readQueryOverride(): number | null {
  if (typeof window === "undefined") return null;
  try {
    const params = new URLSearchParams(window.location.search);
    const slow = params.get("slow");
    if (!slow) return null;
    const n = Number.parseInt(slow, 10);
    return Number.isFinite(n) && n >= 0 ? n : null;
  } catch {
    return null;
  }
}

function pickDelay(): number {
  if (override !== null) return override;
  const q = readQueryOverride();
  if (q !== null) return q;
  return MIN_MS + Math.random() * (MAX_MS - MIN_MS);
}

export function applyLatency(): Promise<void> {
  const ms = pickDelay();
  // Short-circuit when latency is forced to 0 (test-only override). Going
  // through setTimeout(0) deadlocks under `vi.useFakeTimers()` because the
  // queued callback won't fire until the test advances time, which forces
  // every test that mixes fake timers + async dispatch to interleave
  // advances. Resolving synchronously here keeps timer-faked tests honest.
  if (ms === 0) return Promise.resolve();
  return new Promise((resolve) => setTimeout(resolve, ms));
}

export function setLatencyOverride(ms: number | null): void {
  override = ms;
}

export function getLatencyOverride(): number | null {
  return override;
}
