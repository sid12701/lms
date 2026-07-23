import { forwardRef, type HTMLAttributes, type ReactNode } from "react";
import { cn } from "@/lib/utils";

interface KpiStripProps extends HTMLAttributes<HTMLDivElement> {
  /** Slot for KPI/metric cards. The cards themselves live in a later batch. */
  children: ReactNode;
  className?: string;
}

/**
 * Responsive grid wrapper for the KPI row at the top of dashboards.
 *
 *   - 1 column at base (mobile)
 *   - 2 columns at `md` (≥768px)
 *   - 4 columns at `xl` (≥1280px)
 *   - 16px gutters on every breakpoint.
 *
 * The actual metric/KPI card component is deliberately out of scope for this
 * primitive batch; `KpiStrip` only enforces layout.
 */
export const KpiStrip = forwardRef<HTMLDivElement, KpiStripProps>(function KpiStrip(
  { children, className, ...rest },
  ref,
) {
  return (
    <div
      ref={ref}
      data-slot="kpi-strip"
      className={cn("grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-4", className)}
      {...rest}
    >
      {children}
    </div>
  );
});
