import { cloneElement, isValidElement, type ReactElement } from "react";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";

export type { TransitionGates } from "./gates";
export { resolveDisabledReason } from "./gates";

export interface TransitionDisabledTooltipProps {
  /** Pre-computed reason; when null the child renders enabled, no tooltip. */
  disabledReason: string | null;
  children: ReactElement<{ disabled?: boolean; "aria-disabled"?: boolean }>;
}

/**
 * Wraps a button-like child with a tooltip. When `disabledReason` is non-null
 * the wrapped child is forced into the disabled state and the tooltip surfaces
 * the reason on hover/focus. When null, the child renders unmodified.
 *
 * Implementation note: a disabled `<button>` does not fire pointer events,
 * which would suppress the tooltip. We work around this by wrapping the child
 * in a `<span>` that hosts the trigger, mirroring the standard Radix recipe.
 */
export function TransitionDisabledTooltip({
  disabledReason,
  children,
}: TransitionDisabledTooltipProps) {
  if (disabledReason === null) {
    return children;
  }

  if (!isValidElement(children)) {
    return children;
  }

  const disabledChild = cloneElement(children, {
    disabled: true,
    "aria-disabled": true,
  });

  return (
    <Tooltip>
      <TooltipTrigger asChild>
        {/* A wrapping span keeps tooltip events flowing even though the
            inner button is disabled (pointer-events: none on disabled). */}
        <span data-slot="transition-disabled-wrap" className="inline-flex">
          {disabledChild}
        </span>
      </TooltipTrigger>
      <TooltipContent role="tooltip">{disabledReason}</TooltipContent>
    </Tooltip>
  );
}
