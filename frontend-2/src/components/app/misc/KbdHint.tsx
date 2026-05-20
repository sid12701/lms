import { forwardRef, Fragment, type HTMLAttributes } from "react";
import { cn } from "@/lib/utils";

export interface KbdHintProps extends HTMLAttributes<HTMLSpanElement> {
  /** Ordered list of keys to chord, e.g. `["Cmd", "K"]`. */
  keys: string[];
  className?: string;
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
  return (
    <span
      ref={ref}
      data-slot="kbd-hint"
      className={cn("inline-flex items-center gap-1 font-mono text-xs", className)}
      {...rest}
    >
      {keys.map((key, i) => (
        <Fragment key={`${key}-${i}`}>
          {i > 0 ? (
            <span aria-hidden="true" className="text-foreground-muted">
              +
            </span>
          ) : null}
          <kbd className="border-border bg-surface-muted text-foreground shadow-e1 inline-flex min-w-5 items-center justify-center rounded border px-1.5 py-0.5 text-[11px] leading-4 font-medium">
            {key}
          </kbd>
        </Fragment>
      ))}
    </span>
  );
});
