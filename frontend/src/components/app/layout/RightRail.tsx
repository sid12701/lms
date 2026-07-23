import { forwardRef, type HTMLAttributes, type ReactNode } from "react";
import { cn } from "@/lib/utils";

interface RightRailProps extends HTMLAttributes<HTMLElement> {
  /** Slot for status / recent-activity / quick-action blocks. */
  children: ReactNode;
  className?: string;
}

/**
 * Sticky right-rail container for detail pages. 288px wide, pinned to
 * `top-[88px]` (clears the global header), and only visible at `xl` and up
 * via `hidden xl:block`. Below `xl` consumers are expected to surface the
 * same content as a tab — `RightRail` itself simply hides.
 */
export const RightRail = forwardRef<HTMLElement, RightRailProps>(function RightRail(
  { children, className, ...rest },
  ref,
) {
  return (
    <aside
      ref={ref}
      data-slot="right-rail"
      aria-label="Detail sidebar"
      className={cn("sticky top-[88px] hidden w-72 shrink-0 xl:block", className)}
      {...rest}
    >
      {children}
    </aside>
  );
});
