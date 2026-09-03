import { forwardRef, type HTMLAttributes } from "react";
import { cn } from "@/lib/utils";
import { Skeleton } from "@/components/ui/skeleton";

interface KpiSkeletonProps extends HTMLAttributes<HTMLDivElement> {
  /** Number of KPI placeholder cards. Defaults to 4 to match `KpiStrip`. */
  count?: number;
  className?: string;
}

/**
 * Pulsing placeholder for the dashboard KPI strip. Mirrors `KpiStrip`'s
 * 1 / 2 / 4 column responsive grid so reserving space avoids CLS when
 * the real metrics arrive.
 */
export const KpiSkeleton = forwardRef<HTMLDivElement, KpiSkeletonProps>(function KpiSkeleton(
  { count = 4, className, ...rest },
  ref,
) {
  return (
    <div
      ref={ref}
      data-slot="kpi-skeleton"
      data-pending="true"
      role="status"
      aria-label="Loading metrics"
      className={cn("grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-4", className)}
      {...rest}
    >
      {Array.from({ length: count }).map((_, i) => (
        <div
          key={i}
          className="border-border bg-surface shadow-e1 rounded-container flex flex-col gap-3 border p-5"
        >
          <Skeleton className="h-3 w-24" />
          <Skeleton className="h-7 w-32" />
          <Skeleton className="h-3 w-20" />
        </div>
      ))}
    </div>
  );
});
