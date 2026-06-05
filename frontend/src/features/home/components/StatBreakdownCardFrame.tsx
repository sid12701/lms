import { forwardRef, type ReactNode } from "react";
import type { LucideIcon } from "lucide-react";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { EmptyState } from "@/components/app/feedback/EmptyState";
import { cn } from "@/lib/utils";

interface StatBreakdownCardFrameProps {
  dataSlot: string;
  headingId: string;
  summaryId: string;
  title: string;
  description?: string;
  summary: string;
  className?: string;
  empty?: boolean;
  emptyIcon: LucideIcon;
  emptyTitle: string;
  emptyDescription: string;
  children: ReactNode;
}

/**
 * Shared card chrome for home dashboard breakdown charts (DPD, status, etc.).
 */
export const StatBreakdownCardFrame = forwardRef<HTMLDivElement, StatBreakdownCardFrameProps>(
  function StatBreakdownCardFrame(
    {
      dataSlot,
      headingId,
      summaryId,
      title,
      description,
      summary,
      className,
      empty = false,
      emptyIcon,
      emptyTitle,
      emptyDescription,
      children,
    },
    ref,
  ) {
    return (
      <Card
        ref={ref}
        data-slot={dataSlot}
        className={cn(className)}
        aria-labelledby={headingId}
        aria-describedby={summaryId}
      >
        <CardHeader>
          <CardTitle id={headingId}>{title}</CardTitle>
          {description ? <CardDescription>{description}</CardDescription> : null}
        </CardHeader>
        <CardContent>
          <span id={summaryId} className="sr-only">
            {summary}
          </span>
          {empty ? (
            <EmptyState icon={emptyIcon} title={emptyTitle} description={emptyDescription} />
          ) : (
            children
          )}
        </CardContent>
      </Card>
    );
  },
);
