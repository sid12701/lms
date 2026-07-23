import { forwardRef, type HTMLAttributes } from "react";
import { ScrollText } from "lucide-react";
import { cn } from "@/lib/utils";
import { EmptyState } from "@/components/app/feedback/EmptyState";
import { AuditEventNode } from "./AuditEventNode";
import { type AuditEvent, getAuditTimestamp } from "./types";

interface AuditTimelineProps extends HTMLAttributes<HTMLOListElement> {
  events: AuditEvent[];
  /** Compact density per D7 — `/audit` ships compact by default. */
  compact?: boolean;
  /** Sort direction; defaults to most-recent first. */
  order?: "desc" | "asc";
  /** Pin "now" for deterministic relative-time tests. */
  now?: Date;
  /** Override empty-state copy when filters produced no rows. */
  emptyTitle?: string;
  emptyDescription?: string;
  /** Mark the empty state as a "filtered-empty" variant. */
  filtered?: boolean;
  className?: string;
}

/**
 * Vertical timeline of audit events across any of the five streams. The
 * component is presentational; pagination, fetching, and filtering live
 * upstream (the `/audit` page in Phase 8).
 */
export const AuditTimeline = forwardRef<HTMLOListElement, AuditTimelineProps>(
  function AuditTimeline(
    {
      events,
      compact = false,
      order = "desc",
      now,
      emptyTitle,
      emptyDescription,
      filtered = false,
      className,
      ...rest
    },
    ref,
  ) {
    if (events.length === 0) {
      return (
        <EmptyState
          icon={ScrollText}
          variant={filtered ? "filtered-empty" : "no-data"}
          title={emptyTitle ?? (filtered ? "No matching audit events" : "No audit events yet")}
          description={
            emptyDescription ??
            (filtered
              ? "Try clearing one or more filters to surface earlier rows."
              : "Audit events will appear here as activity is recorded.")
          }
        />
      );
    }

    const sorted = [...events].sort((a, b) => {
      const at = getAuditTimestamp(a);
      const bt = getAuditTimestamp(b);
      return order === "desc" ? bt.localeCompare(at) : at.localeCompare(bt);
    });

    return (
      <ol
        ref={ref}
        data-slot="audit-timeline"
        data-compact={compact ? "true" : "false"}
        aria-label="Audit timeline"
        className={cn("flex flex-col", className)}
        {...rest}
      >
        {sorted.map((entry) => (
          <AuditEventNode key={entry.event.id} entry={entry} compact={compact} now={now} />
        ))}
      </ol>
    );
  },
);
