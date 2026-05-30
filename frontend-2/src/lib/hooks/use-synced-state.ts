import { useState, type Dispatch, type SetStateAction } from "react";

/**
 * Local state that re-syncs when `external` changes (e.g. URL-driven filters
 * cleared from outside). Uses the render-phase adjustment pattern from the
 * React docs — no effect + setState.
 */
export function useSyncedState<T>(external: T): [T, Dispatch<SetStateAction<T>>] {
  const [state, setState] = useState(external);
  const [prevExternal, setPrevExternal] = useState(external);
  if (external !== prevExternal) {
    setPrevExternal(external);
    setState(external);
  }
  return [state, setState];
}
