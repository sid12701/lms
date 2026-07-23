import { forwardRef, type HTMLAttributes } from "react";
import { cn } from "@/lib/utils";
import { Skeleton } from "@/components/ui/skeleton";

interface CardSkeletonProps extends HTMLAttributes<HTMLDivElement> {
  /** Number of body lines to render below the title. */
  lines?: number;
  className?: string;
}

/**
 * Generic card placeholder — title bar + a stack of body lines wrapped in
 * the same border/shadow surface as `PageSection`.
 */
export const CardSkeleton = forwardRef<HTMLDivElement, CardSkeletonProps>(function CardSkeleton(
  { lines = 3, className, ...rest },
  ref,
) {
  return (
    <div
      ref={ref}
      data-slot="card-skeleton"
      data-pending="true"
      role="status"
      aria-label="Loading content"
      className={cn(
        "border-border bg-surface shadow-e1 flex flex-col gap-3 rounded-md border p-5",
        className,
      )}
      {...rest}
    >
      <Skeleton className="h-4 w-1/3" />
      <div className="flex flex-col gap-2">
        {Array.from({ length: lines }).map((_, i) => (
          <Skeleton key={i} className={cn("h-3", i === lines - 1 ? "w-2/3" : "w-full")} />
        ))}
      </div>
    </div>
  );
});
