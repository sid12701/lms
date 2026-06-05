import { forwardRef, type HTMLAttributes } from "react";
import { cn } from "@/lib/utils";
import { Skeleton } from "@/components/ui/skeleton";

export interface FormSkeletonProps extends HTMLAttributes<HTMLDivElement> {
  fields?: number;
  className?: string;
}

/**
 * Pulsing form placeholder — `fields` label-and-input pairs followed by a
 * pair of action buttons at the bottom. Used while loading prefill data
 * on edit screens.
 */
export const FormSkeleton = forwardRef<HTMLDivElement, FormSkeletonProps>(function FormSkeleton(
  { fields = 4, className, ...rest },
  ref,
) {
  return (
    <div
      ref={ref}
      data-slot="form-skeleton"
      data-pending="true"
      role="status"
      aria-label="Loading form"
      className={cn("flex flex-col gap-5", className)}
      {...rest}
    >
      {Array.from({ length: fields }).map((_, i) => (
        <div key={i} className="flex flex-col gap-2">
          <Skeleton className="h-3 w-24" />
          <Skeleton className="h-9 w-full" />
        </div>
      ))}
      <div className="flex justify-end gap-2 pt-2">
        <Skeleton className="h-9 w-24" />
        <Skeleton className="h-9 w-32" />
      </div>
    </div>
  );
});
