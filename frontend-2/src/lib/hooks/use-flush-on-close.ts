import { useState } from "react";

/** Run `flush` when `open` transitions from true → false (render phase, not an effect). */
export function useFlushOnClose(open: boolean, flush: () => void): void {
  const [wasOpen, setWasOpen] = useState(open);
  if (open !== wasOpen) {
    setWasOpen(open);
    if (!open) flush();
  }
}
