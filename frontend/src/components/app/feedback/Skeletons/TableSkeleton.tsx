import { forwardRef, type HTMLAttributes } from "react";
import { cn } from "@/lib/utils";
import { Skeleton } from "@/components/ui/skeleton";

interface TableSkeletonProps extends HTMLAttributes<HTMLDivElement> {
  rows?: number;
  cols?: number;
  className?: string;
}

/**
 * Pulsing table placeholder — sticky-style header band with `cols` cells
 * and `rows` body rows, each rendered as horizontal pulse bars. Reserves
 * the same vertical footprint as a populated TanStack Table so swapping
 * to real data does not shift layout.
 */
export const TableSkeleton = forwardRef<HTMLDivElement, TableSkeletonProps>(function TableSkeleton(
  { rows = 5, cols = 6, className, ...rest },
  ref,
) {
  return (
    <div
      ref={ref}
      data-slot="table-skeleton"
      data-pending="true"
      role="status"
      aria-label="Loading rows"
      className={cn(
        "border-border bg-surface shadow-e1 overflow-hidden rounded-md border",
        className,
      )}
      {...rest}
    >
      <div
        data-slot="table-skeleton-header"
        className="border-border bg-surface-muted flex items-center gap-4 border-b px-4 py-3"
      >
        {Array.from({ length: cols }).map((_, i) => (
          <Skeleton key={`h-${i}`} className="h-3 flex-1" />
        ))}
      </div>
      <div className="divide-border divide-y">
        {Array.from({ length: rows }).map((_, r) => (
          <div
            key={`r-${r}`}
            data-slot="table-skeleton-row"
            className="flex items-center gap-4 px-4 py-4"
          >
            {Array.from({ length: cols }).map((_, c) => (
              <Skeleton key={`c-${r}-${c}`} className="h-3 flex-1" />
            ))}
          </div>
        ))}
      </div>
    </div>
  );
});
