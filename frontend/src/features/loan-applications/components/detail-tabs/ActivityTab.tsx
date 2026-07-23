import { useMemo } from "react";
import { AuditTimeline } from "@/components/app/audit/AuditTimeline";
import type { AuditEvent } from "@/components/app/audit/types";
import { ErrorState } from "@/components/app/feedback/ErrorState";
import { Skeleton } from "@/components/ui/skeleton";
import { useLoanApplicationActivity } from "../../hooks/useLoanApplicationActivity";

export interface ActivityTabProps {
  applicationId: string;
}

/**
 * Activity tab — renders the per-application audit timeline. The activity
 * endpoint returns `ApplicationAuditEvent[]`; the timeline component
 * consumes the wider discriminated union, so we wrap each row in the
 * `APPLICATION` kind before passing it through.
 */
export function ActivityTab({ applicationId }: ActivityTabProps) {
  const query = useLoanApplicationActivity(applicationId);

  const events = useMemo<AuditEvent[]>(() => {
    if (!query.data) return [];
    return query.data.events.map((event) => ({
      kind: "APPLICATION" as const,
      event,
    }));
  }, [query.data]);

  if (query.isPending) {
    return (
      <div data-slot="activity-tab-loading" className="flex flex-col gap-3">
        <Skeleton className="h-16 w-full" />
        <Skeleton className="h-16 w-full" />
        <Skeleton className="h-16 w-full" />
      </div>
    );
  }

  if (query.isError) {
    return (
      <ErrorState
        title="Couldn't load activity"
        description="The audit timeline failed to load. Try again, and contact support if the problem persists."
        retry={{ label: "Retry", onClick: () => void query.refetch() }}
      />
    );
  }

  return (
    <div data-slot="activity-tab">
      <AuditTimeline
        events={events}
        emptyTitle="No activity yet"
        emptyDescription="Status transitions and other lifecycle events will appear here."
      />
    </div>
  );
}
