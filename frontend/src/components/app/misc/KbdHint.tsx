import { forwardRef, Fragment, type HTMLAttributes } from "react";
import { cn } from "@/lib/utils";

interface KbdHintProps extends HTMLAttributes<HTMLSpanElement> {
  /** Ordered list of keys to chord, e.g. `["Cmd", "K"]`. */
  keys: string[];
  className?: string;
}

function identifyChordKeys(keys: readonly string[]): Array<{ id: string; label: string }> {
  const occurrences = new Map<string, number>();
  return keys.map((label) => {
    const occurrence = (occurrences.get(label) ?? 0) + 1;
    occurrences.set(label, occurrence);
    return { id: `${label}-${occurrence}`, label };
  });
}

/**
 * Renders a keyboard shortcut as a sequence of `<kbd>` chips separated by
 * `+`. Useful for surfacing global shortcuts (command palette, save, etc.)
 * inline with menu items, tooltips, or empty-state copy.
 */
export const KbdHint = forwardRef<HTMLSpanElement, KbdHintProps>(function KbdHint(
  { keys, className, ...rest },
  ref,
) {
  const chordKeys = identifyChordKeys(keys);
  return (
    <span
      ref={ref}
      data-slot="kbd-hint"
      className={cn("inline-flex items-center gap-1 font-mono text-xs", className)}
      {...rest}
    >
      {chordKeys.map(({ id, label }, position) => (
        <Fragment key={id}>
          {position > 0 ? (
            <span aria-hidden="true" className="text-foreground-muted">
              +
            </span>
          ) : null}
          <kbd className="border-border bg-surface-muted text-foreground shadow-e1 inline-flex min-w-5 items-center justify-center rounded border px-1.5 py-0.5 text-xs font-medium">
            {label}
          </kbd>
        </Fragment>
      ))}
    </span>
  );
});
