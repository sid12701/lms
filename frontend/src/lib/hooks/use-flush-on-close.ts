import { useEffect, useRef } from "react";

/** Run `flush` after `open` transitions from true → false (never during render). */
export function useFlushOnClose(open: boolean, flush: () => void): void {
  const wasOpenRef = useRef(open);

  useEffect(() => {
    if (wasOpenRef.current && !open) {
      flush();
    }
    wasOpenRef.current = open;
  }, [open, flush]);
}
