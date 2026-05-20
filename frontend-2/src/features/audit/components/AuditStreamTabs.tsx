/**
 * Sticky stream selector for the audit explorer.
 *
 * Behaves like a tab strip but is implemented as a horizontal group of
 * shadcn buttons — there is no `Tabs` primitive on disk in this codebase
 * yet, and pulling Radix Tabs in would add a new dependency (forbidden
 * without orchestrator approval per CLAUDE.md hard-don't #5).
 *
 * Selecting "All" clears the `streams` filter. Selecting a specific stream
 * sets `streams=[stream]`. The component is fully controlled — URL state
 * lives in the page.
 */
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import { AUDIT_STREAMS, AUDIT_STREAM_LABEL, type AuditStream } from "../types";

export interface AuditStreamTabsProps {
  /** Current stream filter — empty / undefined means "All". */
  value: ReadonlyArray<AuditStream> | undefined;
  onChange: (next: AuditStream[] | undefined) => void;
  className?: string;
}

interface TabDescriptor {
  id: AuditStream | "ALL";
  label: string;
}

const TABS: ReadonlyArray<TabDescriptor> = [
  { id: "ALL", label: "All" },
  ...AUDIT_STREAMS.map((s) => ({ id: s, label: AUDIT_STREAM_LABEL[s] })),
];

function isActive(
  tab: TabDescriptor,
  value: ReadonlyArray<AuditStream> | undefined,
): boolean {
  if (tab.id === "ALL") return !value || value.length === 0;
  return value !== undefined && value.length === 1 && value[0] === tab.id;
}

export function AuditStreamTabs({ value, onChange, className }: AuditStreamTabsProps) {
  return (
    <div
      role="tablist"
      aria-label="Audit stream"
      data-slot="audit-stream-tabs"
      className={cn(
        "border-border bg-surface sticky top-0 z-10 flex flex-wrap items-center gap-1 rounded-md border p-1",
        className,
      )}
    >
      {TABS.map((tab) => {
        const active = isActive(tab, value);
        return (
          <Button
            key={tab.id}
            type="button"
            role="tab"
            aria-selected={active}
            data-active={active || undefined}
            variant={active ? "default" : "ghost"}
            size="sm"
            onClick={() => onChange(tab.id === "ALL" ? undefined : [tab.id])}
            className={cn("h-7 px-3 text-xs font-medium")}
          >
            {tab.label}
          </Button>
        );
      })}
    </div>
  );
}
