/**
 * Activity tab — combined audit feed for a single borrower.
 *
 * The borrower activity endpoint joins three of the five audit streams
 * (BR-7 + BRD §6.5):
 *
 *   - APPLICATION     — every `ApplicationAuditEvent` for an application
 *                       owned by this borrower
 *   - PII_REVEAL      — every `PiiRevealEvent` whose `subjectBorrowerId`
 *                       matches this borrower
 *   - DOCUMENT_ACCESS — every `DocumentAccessEvent` on a document attached
 *                       to one of this borrower's applications
 *
 * Mapping into `AuditTimeline`:
 *
 *   `BorrowerActivityEntry`'s discriminator (`kind`) and payload (`event`)
 *   are structurally identical to the wider `AuditEvent` union the
 *   timeline consumes — same field names, same per-kind event types. So
 *   the "wrap" is a passthrough cast: each entry already has the shape
 *   `{ kind: "APPLICATION" | "PII_REVEAL" | "DOCUMENT_ACCESS"; event: … }`.
 *
 *   `AuditEventNode` discriminates on `entry.kind`, knows about all three
 *   of these kinds natively (plus `INTAKE` + `PRODUCT` which never occur
 *   here), so no synthetic envelope construction is required — the row
 *   text (headline + detail) is rendered by the node itself.
 *
 *   We still walk the array (rather than `as` casting) to (a) keep the
 *   type-narrowing in one place for future changes, and (b) preserve a
 *   stable identity reference for `useMemo` consumers.
 */
import { useMemo } from "react";
import { AuditTimeline } from "@/components/app/audit/AuditTimeline";
import type { AuditEvent } from "@/components/app/audit/types";
import { ErrorState } from "@/components/app/feedback/ErrorState";
import { Skeleton } from "@/components/ui/skeleton";
import { useBorrowerActivity } from "../../hooks/useBorrowerActivity";
import type { BorrowerActivityEntry } from "../../types";

export interface ActivityTabProps {
  borrowerId: string;
}

/**
 * Map a `BorrowerActivityEntry` to an `AuditEvent`. The two unions share
 * the same `{ kind, event }` envelope; we re-emit it as a fresh literal
 * so the discriminator is widened from the borrower-narrow set
 * (APPLICATION | PII_REVEAL | DOCUMENT_ACCESS) into the timeline-wide
 * `AuditEvent` union without a structural cast.
 */
function toTimelineEvent(entry: BorrowerActivityEntry): AuditEvent {
  switch (entry.kind) {
    case "APPLICATION":
      return { kind: "APPLICATION", event: entry.event };
    case "PII_REVEAL":
      return { kind: "PII_REVEAL", event: entry.event };
    case "DOCUMENT_ACCESS":
      return { kind: "DOCUMENT_ACCESS", event: entry.event };
  }
}

export function ActivityTab({ borrowerId }: ActivityTabProps) {
  const query = useBorrowerActivity(borrowerId);

  const events = useMemo<AuditEvent[]>(() => {
    if (!query.data) return [];
    return query.data.entries.map(toTimelineEvent);
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
        emptyDescription="No activity yet for this borrower."
      />
    </div>
  );
}

export default ActivityTab;
