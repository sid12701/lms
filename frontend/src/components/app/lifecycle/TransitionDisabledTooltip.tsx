import {
  cloneElement,
  isValidElement,
  type KeyboardEvent,
  type MouseEvent,
  type ReactElement,
} from "react";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";

export type { TransitionGates } from "./gates";
export { resolveDisabledReason } from "./gates";

type BlockableChild = ReactElement<{
  disabled?: boolean;
  "aria-disabled"?: boolean;
  onClick?: (event: MouseEvent<HTMLElement>) => void;
  onKeyDown?: (event: KeyboardEvent<HTMLElement>) => void;
}>;

function swallowBlockedActivation(
  event: MouseEvent<HTMLElement> | KeyboardEvent<HTMLElement>,
): void {
  event.preventDefault();
  event.stopPropagation();
}

function swallowBlockedKeyboardActivation(event: KeyboardEvent<HTMLElement>): void {
  if (event.key === "Enter" || event.key === " ") {
    swallowBlockedActivation(event);
  }
}

export interface TransitionDisabledTooltipProps {
  /** Pre-computed reason; when null the child renders enabled, no tooltip. */
  disabledReason: string | null;
  children: BlockableChild;
}

/**
 * Wraps a lifecycle action with the reason it cannot be taken.
 *
 * The blocked child is marked `aria-disabled`, **not** natively `disabled`.
 * A native `disabled` button leaves the tab order entirely, and the reason was
 * hosted on a non-focusable wrapping `<span>` with no `aria-describedby` — so
 * on the most consequential controls in the product ("Approve", "Initiate
 * disbursement") the explanation existed only on mouse hover. A keyboard or
 * screen-reader operator got "Approve, unavailable" and no way to find out why.
 * `PRODUCT.md` principle 1 is explicit that the reason must be legible, not
 * merely enforced.
 *
 * `aria-disabled` keeps the control focusable, so it is now the tooltip trigger
 * itself: Radix opens the tooltip on focus as well as hover and wires the
 * trigger's `aria-describedby` to the reason. Activation is neutralised here
 * rather than by the browser, because an `aria-disabled` control is still
 * clickable — without this the blocked action would become pressable, which is
 * exactly what the money state forbids.
 */
export function TransitionDisabledTooltip({
  disabledReason,
  children,
}: TransitionDisabledTooltipProps) {
  if (disabledReason === null || !isValidElement(children)) {
    return children;
  }

  const blockedChild = cloneElement(children, {
    "aria-disabled": true,
    onClick: swallowBlockedActivation,
    onKeyDown: swallowBlockedKeyboardActivation,
  });

  return (
    <Tooltip>
      <TooltipTrigger asChild>{blockedChild}</TooltipTrigger>
      <TooltipContent role="tooltip">{disabledReason}</TooltipContent>
    </Tooltip>
  );
}
