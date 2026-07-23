import { useEffect, useEffectEvent, useRef } from "react";

/** Run `sync` after `open` transitions from false → true. */
export function useSyncOnOpen(open: boolean, sync: () => void): void {
  const wasOpenRef = useRef(open);
  const onSync = useEffectEvent(sync);

  useEffect(() => {
    if (!wasOpenRef.current && open) {
      onSync();
    }
    wasOpenRef.current = open;
  }, [open]);
}
