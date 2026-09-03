import { createContext, useContext, type ReactNode } from "react";
import { cn } from "@/lib/utils";
import { formatDateTime, formatRelative } from "@/lib/format";

/**
 * Whether the surrounding layout is tight enough to justify carrying the
 * absolute instant on `title` instead of rendering it.
 *
 * `variant="relative"` trades the visible absolute for `dateTime` + `title`,
 * which is the right call in a dense table column. It is the wrong call the
 * moment the same cell is re-rendered somewhere roomy — and it is actively
 * broken on touch, where there is no hover and `title` therefore never appears
 * at all. That would leave a relative-only timestamp with *no* path to the
 * instant, which the F-13 policy rules out.
 *
 * Layouts that have room opt out via {@link TimeLayoutProvider}; the default is
 * dense so existing table columns keep behaving exactly as before.
 */
const DenseTimeLayoutContext = createContext(true);

/**
 * Declares whether descendant timestamps sit in a dense layout. Set
 * `dense={false}` in stacked/card layouts, where a full timestamp costs a line
 * that is available anyway and hover may not exist.
 */
export function TimeLayoutProvider({ dense, children }: { dense: boolean; children: ReactNode }) {
  return (
    <DenseTimeLayoutContext.Provider value={dense}>{children}</DenseTimeLayoutContext.Provider>
  );
}

export interface AbsoluteRelativeTimeProps {
  iso: string;
  /** Pin "now" for deterministic relative copy in tests. */
  now?: Date;
  className?: string;
  compact?: boolean;
  /**
   * `"full"` (default) renders `13 Jul 2026, 00:38 · 22 days ago` — for
   * evidence surfaces (ledgers, activity, audit) where an agent has to cite
   * the instant to a borrower.
   *
   * `"relative"` renders only `22 days ago`, with the absolute carried by
   * `dateTime` and `title`. For dense list columns, where the full form would
   * dominate the row — but where a relative-only timestamp with *no* path to
   * the absolute value is still not acceptable.
   *
   * Note this is a *request*, not a guarantee: it applies only inside a dense
   * layout. A `TimeLayoutProvider` with `dense={false}` renders the full form
   * regardless, because `title` is not a path to the instant on touch.
   */
  variant?: "full" | "relative";
}

/**
 * The single datetime renderer (finding F-13: one record was showing three
 * temporal conventions across its tabs). Use for instants — posted-at,
 * occurred-at, created-at — not for calendar due dates, which are dates
 * rather than moments and render as plain absolute.
 */
export function AbsoluteRelativeTime({
  iso,
  now,
  className,
  compact = false,
  variant = "full",
}: AbsoluteRelativeTimeProps) {
  const dense = useContext(DenseTimeLayoutContext);
  const absolute = formatDateTime(iso);
  const relative = formatRelative(iso, now);

  if (relative === "—") {
    return (
      <span
        data-slot="absolute-relative-time"
        className={cn("text-foreground-muted", compact ? "text-xs" : "text-sm", className)}
      >
        —
      </span>
    );
  }

  const classes = cn(
    "text-foreground-muted tabular-nums",
    compact ? "text-xs" : "text-sm",
    className,
  );

  // Only collapse to relative-only where the layout is genuinely dense; a roomy
  // layout falls through to the full reading so the instant stays reachable
  // without hover.
  if (variant === "relative" && dense) {
    return (
      <time dateTime={iso} title={absolute} data-slot="absolute-relative-time" className={classes}>
        <span data-slot="absolute-relative-relative">{relative}</span>
      </time>
    );
  }

  return (
    <time dateTime={iso} data-slot="absolute-relative-time" className={classes}>
      <span data-slot="absolute-relative-absolute">{absolute}</span>
      <span aria-hidden="true" className="px-1">
        ·
      </span>
      <span data-slot="absolute-relative-relative">{relative}</span>
    </time>
  );
}
