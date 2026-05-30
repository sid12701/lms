import { useEffect, type RefObject } from "react";

/** Focus `ref` after Radix/dialog mount when `open` becomes true. */
export function useFocusOnOpen(open: boolean, ref: RefObject<HTMLElement | null>): void {
  useEffect(() => {
    if (!open) return;
    const id = window.setTimeout(() => ref.current?.focus(), 0);
    return () => window.clearTimeout(id);
  }, [open, ref]);
}
