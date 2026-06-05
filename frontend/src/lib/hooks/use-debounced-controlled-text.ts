import { useEffect, useRef } from "react";
import { useSyncedState } from "./use-synced-state";

export interface DebouncedControlledText {
  value: string;
  onChange: (next: string) => void;
  /** Clears any pending debounce timer (e.g. before a full filter reset). */
  clearPending: () => void;
}

/**
 * Controlled text input with local typing state, external sync, and debounced
 * publish of the trimmed value (empty string → `undefined`).
 */
export function useDebouncedControlledText(
  external: string | undefined,
  onDebounced: (trimmed: string | undefined) => void,
  debounceMs: number,
): DebouncedControlledText {
  const externalNorm = external ?? "";
  const [value, setValue] = useSyncedState(externalNorm);
  const timerRef = useRef<number | null>(null);

  const clearPending = () => {
    if (timerRef.current !== null) {
      window.clearTimeout(timerRef.current);
      timerRef.current = null;
    }
  };

  useEffect(() => () => clearPending(), []);

  const onChange = (next: string) => {
    setValue(next);
    clearPending();
    timerRef.current = window.setTimeout(() => {
      const trimmed = next.trim();
      onDebounced(trimmed === "" ? undefined : trimmed);
    }, debounceMs);
  };

  return { value, onChange, clearPending };
}
