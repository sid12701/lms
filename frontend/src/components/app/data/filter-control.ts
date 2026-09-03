/**
 * The one filter-control spec.
 *
 * Lives in its own module rather than beside the components in
 * `FilterBarShell.tsx` so that file exports only components — a mixed module
 * breaks React Fast Refresh for every component in it.
 *
 * Every leaf control in a filter bar — input, select, date trigger — renders at
 * these dimensions so a row of them shares a baseline. Bars previously mixed
 * 24 / 32 / 36px heights and 12 / 14px type, including two heights interleaved
 * on a single row and centred 4px apart, which is the mechanical cause of the
 * "thin" feeling the audit set out to explain.
 */
import { cn } from "@/lib/utils";

export const FILTER_CONTROL_CLASS =
  "border-border-control bg-input/20 text-foreground placeholder:text-foreground-muted focus-visible:border-ring focus-visible:ring-ring h-7 rounded-control border px-2 text-xs outline-none focus-visible:ring-2";

/**
 * Applied state, as a visible property of the control itself.
 *
 * A set control gets a primary tint, a primary border and medium weight — three
 * signals, not one, so it survives both a greyscale print and a colour-vision
 * deficiency. Before this, a set control ("Disbursed") and an unset one
 * ("All LSPs") rendered identical colour, fill, border and weight.
 */
export function filterControlClass(isSet: boolean, className?: string): string {
  return cn(
    FILTER_CONTROL_CLASS,
    isSet && "border-primary bg-primary/10 text-primary-tinted font-medium",
    className,
  );
}
