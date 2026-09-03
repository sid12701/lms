import { useEffect, useState } from "react";

/**
 * The wait below which nothing should be reported at all.
 *
 * The established response bands are: no indicator under ~300ms, a spinner from
 * 300ms to 1s, a skeleton from 1s. Only the first threshold is implemented here
 * — a skeleton that appears and disappears inside 300ms is a flicker in the
 * layout, which reads as jank rather than as the wait it was reporting.
 */
export const SKELETON_DELAY_MS = 300;

/**
 * Reports `active` only once it has been continuously true for `delayMs`.
 *
 * Deliberately *not* folded into `Skeleton` / `TableSkeleton` / `CardSkeleton`.
 * Those render inside a panel that keeps its own frame, so suppressing them for
 * 300ms leaves a hole rather than removing a flash; and every test that asserts
 * a skeleton the moment `isLoading` goes true would have to adopt fake timers.
 * Page-level gates — where the skeleton stands in for the whole screen and the
 * flash is a full-page flicker — opt in explicitly instead.
 */
export function useDelayedFlag(active: boolean, delayMs: number = SKELETON_DELAY_MS): boolean {
  const [elapsed, setElapsed] = useState(false);
  const [wasActive, setWasActive] = useState(active);

  // Reset during render rather than from an effect — React's documented pattern
  // for deriving state from a changed prop, and the one the
  // `set-state-in-effect` rule points at. Resetting from an effect would render
  // one frame with the previous activation's `elapsed` still true, which for a
  // reopened panel is exactly the flash this hook exists to suppress.
  if (wasActive !== active) {
    setWasActive(active);
    setElapsed(false);
  }

  useEffect(() => {
    if (!active) return;
    const timer = window.setTimeout(() => setElapsed(true), delayMs);
    return () => window.clearTimeout(timer);
  }, [active, delayMs]);

  return active && elapsed;
}
