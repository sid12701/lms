import { useCallback, useState } from "react";
import type { VisibilityState } from "@tanstack/react-table";

/**
 * Column visibility persisted per table, per browser.
 *
 * Mirrors how row density already persists: a list view's column choice is a
 * durable operator preference, not per-visit state. Persisting it is what makes
 * a non-default column set safe to ship — anything hidden by default stays one
 * click away in the "Columns" popover, and restoring it sticks.
 *
 * Unknown keys from an older build are dropped on read so a renamed column
 * cannot permanently hide a column that no longer matches.
 */
export function useColumnVisibility(
  storageKey: string,
  defaults: VisibilityState,
): [VisibilityState, (next: VisibilityState) => void] {
  const [visibility, setVisibility] = useState<VisibilityState>(() => read(storageKey, defaults));

  const update = useCallback(
    (next: VisibilityState) => {
      setVisibility(next);
      try {
        window.localStorage.setItem(storageKey, JSON.stringify(next));
      } catch {
        // Private mode or a full quota — the in-memory value still applies.
      }
    },
    [storageKey],
  );

  return [visibility, update];
}

function read(storageKey: string, defaults: VisibilityState): VisibilityState {
  try {
    const raw = window.localStorage.getItem(storageKey);
    if (!raw) return defaults;
    const parsed: unknown = JSON.parse(raw);
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) return defaults;
    const known = new Set(Object.keys(defaults));
    const out: VisibilityState = { ...defaults };
    for (const [key, value] of Object.entries(parsed as Record<string, unknown>)) {
      if (typeof value === "boolean" && known.has(key)) out[key] = value;
    }
    return out;
  } catch {
    return defaults;
  }
}
