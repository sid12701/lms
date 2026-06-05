import { useEffect } from "react";

export interface ShortcutOptions {
  /** Cmd on macOS, Ctrl elsewhere. */
  meta?: boolean;
  shift?: boolean;
  alt?: boolean;
  /** Do not trigger when focus is in an input/textarea/contenteditable. */
  ignoreInputs?: boolean;
}

function isEditableTarget(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false;
  const tag = target.tagName;
  if (tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT") return true;
  return target.isContentEditable;
}

/**
 * Tiny global keyboard shortcut hook. Matches one key with optional modifier
 * combinations. Cmd or Ctrl satisfy the `meta` flag (cross-platform).
 */
export function useShortcut(
  key: string,
  handler: (event: KeyboardEvent) => void,
  options: ShortcutOptions = {},
): void {
  useEffect(() => {
    function onKey(event: KeyboardEvent) {
      if (event.key.toLowerCase() !== key.toLowerCase()) return;
      const metaPressed = event.metaKey || event.ctrlKey;
      if (options.meta && !metaPressed) return;
      if (!options.meta && metaPressed) return;
      if (options.shift !== undefined && options.shift !== event.shiftKey) return;
      if (options.alt !== undefined && options.alt !== event.altKey) return;
      if (options.ignoreInputs !== false && isEditableTarget(event.target)) return;
      handler(event);
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [key, handler, options.meta, options.shift, options.alt, options.ignoreInputs]);
}
