import { useEffect, useEffectEvent, useRef } from "react";

/** Run `flush` after `open` transitions from true → false (never during render). */
export function useFlushOnClose(open: boolean, flush: () => void): void {
  const wasOpenRef = useRef(open);
  const onFlush = useEffectEvent(flush);

  useEffect(() => {
    if (wasOpenRef.current && !open) {
      onFlush();
    }
    wasOpenRef.current = open;
  }, [open]);
}
