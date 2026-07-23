import { forwardRef, type HTMLAttributes } from "react";
import { cn } from "@/lib/utils";
import { formatINR } from "@/lib/format";

type TabularVariant = "number" | "currency" | "percent" | "raw";

interface TabularNumberProps extends Omit<HTMLAttributes<HTMLSpanElement>, "children"> {
  value: number;
  variant?: TabularVariant;
  /** Decimal precision; defaults to `2` for percent/currency, `0` otherwise. */
  decimals?: 0 | 2;
  className?: string;
}

/**
 * Number display with `font-variant-numeric: tabular-nums` (via the
 * `data-tabular` attribute wired up in `globals.css`) so columns align by
 * digit. Variants:
 *
 *   - `number` — locale-formatted en-IN number with thousands separators
 *   - `currency` — `formatINR` (₹ prefix, en-IN grouping)
 *   - `percent` — divides by 100 and formats with `%` suffix
 *   - `raw` — same as `number`; provided for API symmetry
 */
export const TabularNumber = forwardRef<HTMLSpanElement, TabularNumberProps>(function TabularNumber(
  { value, variant = "number", decimals, className, ...rest },
  ref,
) {
  const formatted = format(value, variant, decimals);
  return (
    <span
      ref={ref}
      data-tabular=""
      data-slot="tabular-number"
      data-variant={variant}
      className={cn("font-mono", className)}
      {...rest}
    >
      {formatted}
    </span>
  );
});

function format(value: number, variant: TabularVariant, decimals?: 0 | 2): string {
  if (variant === "currency") {
    return formatINR(value, { decimals: decimals ?? 0 });
  }
  if (variant === "percent") {
    const d = decimals ?? 2;
    return `${value.toLocaleString("en-IN", {
      minimumFractionDigits: d,
      maximumFractionDigits: d,
    })}%`;
  }
  // `number` and `raw` share locale formatting today; `raw` exists so consumers
  // can opt into "no currency, no percent" semantics regardless of defaults.
  const d = decimals ?? 0;
  return value.toLocaleString("en-IN", {
    minimumFractionDigits: d,
    maximumFractionDigits: d,
  });
}
