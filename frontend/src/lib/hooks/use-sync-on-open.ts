import { useState } from "react";

/** Run `sync` when `open` transitions from false → true (render phase, not an effect). */
export function useSyncOnOpen(open: boolean, sync: () => void): void {
  const [wasOpen, setWasOpen] = useState(open);
  if (open !== wasOpen) {
    setWasOpen(open);
    if (open) sync();
  }
}
