import type { ReactNode } from "react";
import { Info } from "lucide-react";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { cn } from "@/lib/utils";

export interface MetricHintProps {
  /** The figure's on-screen label; used to name the control for assistive tech. */
  label: string;
  /** How the figure is derived, in plain language. */
  children: ReactNode;
  className?: string;
}

/**
 * Explains how a computed figure is derived.
 *
 * Deliberately a popover rather than a tooltip: these are the figures an LSP
 * agent has to defend to a borrower on the phone, and Radix tooltips never
 * open on touch — the partner surface is used on phones. A popover answers to
 * pointer, keyboard and touch alike.
 *
 * The trigger is 24px so it clears the minimum target size in a row of dense
 * eyebrow labels.
 */
export function MetricHint({ label, children, className }: MetricHintProps) {
  return (
    <Popover>
      <PopoverTrigger
        type="button"
        aria-label={`How ${label} is calculated`}
        className={cn(
          "text-foreground-muted hover:text-foreground focus-visible:ring-ring rounded-control inline-flex size-6 shrink-0 items-center justify-center transition-colors duration-150 outline-none focus-visible:ring-2",
          className,
        )}
      >
        <Info aria-hidden="true" className="size-3.5" />
      </PopoverTrigger>
      <PopoverContent
        data-slot="metric-hint"
        align="start"
        // Radix renders the panel as `role="dialog"`, which needs its own
        // accessible name — labelling only the content inside it is not enough.
        aria-label={`How ${label} is calculated`}
        // Without this the panel sits flush against the viewport edge when its
        // trigger is in the last column of the KPI row.
        collisionPadding={8}
        className="w-64 gap-2"
      >
        <p className="text-foreground leading-5 normal-case">{children}</p>
      </PopoverContent>
    </Popover>
  );
}
