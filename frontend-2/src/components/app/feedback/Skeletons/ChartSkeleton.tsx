import { forwardRef, type HTMLAttributes } from "react";
import { Skeleton } from "@/components/ui/skeleton";
import { cn } from "@/lib/utils";

export interface ChartSkeletonProps extends HTMLAttributes<HTMLDivElement> {
  bars?: number;
  className?: string;
}

/**
 * Chart-card placeholder with a title area and fixed-height vertical bars.
 * The 280px plot area mirrors Recharts cards to avoid layout shift.
 */
export const ChartSkeleton = forwardRef<HTMLDivElement, ChartSkeletonProps>(function ChartSkeleton(
  { bars = 5, className, ...rest },
  ref,
) {
  return (
    <div
      ref={ref}
      data-slot="chart-skeleton"
      data-pending="true"
      role="status"
      aria-label="Loading chart"
      className={cn(
        "border-border bg-surface shadow-e1 flex flex-col gap-4 rounded-md border p-5 opacity-100 transition-opacity duration-200 motion-reduce:transition-none",
        className,
      )}
      {...rest}
    >
      <div className="flex flex-col gap-2">
        <Skeleton className="h-4 w-2/5" />
        <Skeleton className="h-3 w-3/5" />
      </div>
      <div className="border-border/60 flex h-[280px] items-end gap-3 rounded-md border border-dashed px-4 py-5">
        {Array.from({ length: bars }).map((_, index) => (
          <Skeleton
            key={index}
            className="min-h-10 flex-1"
            style={{ height: `${36 + ((index * 17) % 48)}%` }}
          />
        ))}
      </div>
    </div>
  );
});
