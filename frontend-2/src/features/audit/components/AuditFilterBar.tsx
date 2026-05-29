/**
 * Page-level filter bar for `/audit`.
 *
 * Distinct from the lower-level primitive at `@/components/app/audit/
 * AuditFilterBar` (which is a controlled, stream + role + date-range
 * picker for the timeline component): this bar carries the page's
 * URL-state surface — actor, dateFrom/dateTo, correlationId, and free
 * text — and routes every change through the controlled `value` /
 * `onChange` callbacks the page provides.
 *
 * Search input debounces at 200ms before publishing through onChange so
 * the underlying TanStack Query cache key doesn't churn per keystroke.
 *
 * Per CLAUDE.md hard-don't #4 every input lives behind a shadcn primitive
 * (`Input`); native `<input type="date">` is acceptable because the date
 * range is a filter, not a form field, and there is no `Calendar` primitive
 * on disk yet.
 */
import { useEffect, useMemo, useRef, useState } from "react";
import { Search, X } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { cn } from "@/lib/utils";
import type { AuditEventsFilters } from "../types";

const SEARCH_DEBOUNCE_MS = 200;
const ALL_ACTORS = "__all_actors__";

export interface ActorOption {
  value: string;
  label: string;
}

export interface AuditPageFilterBarProps {
  value: AuditEventsFilters;
  onChange: (next: AuditEventsFilters) => void;
  /** Static actor list rendered in the dropdown. Optional. */
  actorOptions?: ReadonlyArray<ActorOption>;
  className?: string;
}

function hasAnyFilter(value: AuditEventsFilters): boolean {
  if (value.actorId) return true;
  if (value.correlationId && value.correlationId.trim() !== "") return true;
  if (value.dateFrom) return true;
  if (value.dateTo) return true;
  if (value.q && value.q.trim() !== "") return true;
  return false;
}

export function AuditPageFilterBar({
  value,
  onChange,
  actorOptions = [],
  className,
}: AuditPageFilterBarProps) {
  const [q, setQ] = useState<string>(value.q ?? "");
  const [correlationId, setCorrelationId] = useState<string>(value.correlationId ?? "");
  const qTimer = useRef<number | null>(null);
  const corrTimer = useRef<number | null>(null);

  // Re-sync local inputs when the URL changes from outside (clear button etc.).
  useEffect(() => {
    setQ(value.q ?? "");
  }, [value.q]);
  useEffect(() => {
    setCorrelationId(value.correlationId ?? "");
  }, [value.correlationId]);

  useEffect(() => {
    return () => {
      if (qTimer.current !== null) window.clearTimeout(qTimer.current);
      if (corrTimer.current !== null) window.clearTimeout(corrTimer.current);
    };
  }, []);

  const onQChange = (next: string) => {
    setQ(next);
    if (qTimer.current !== null) window.clearTimeout(qTimer.current);
    qTimer.current = window.setTimeout(() => {
      const trimmed = next.trim();
      onChange({ ...value, q: trimmed === "" ? undefined : trimmed, page: 0 });
    }, SEARCH_DEBOUNCE_MS);
  };

  const onCorrelationChange = (next: string) => {
    setCorrelationId(next);
    if (corrTimer.current !== null) window.clearTimeout(corrTimer.current);
    corrTimer.current = window.setTimeout(() => {
      const trimmed = next.trim();
      onChange({
        ...value,
        correlationId: trimmed === "" ? undefined : trimmed,
        page: 0,
      });
    }, SEARCH_DEBOUNCE_MS);
  };

  const clearAll = () => {
    if (qTimer.current !== null) window.clearTimeout(qTimer.current);
    if (corrTimer.current !== null) window.clearTimeout(corrTimer.current);
    setQ("");
    setCorrelationId("");
    onChange({
      ...value,
      q: undefined,
      correlationId: undefined,
      actorId: undefined,
      dateFrom: undefined,
      dateTo: undefined,
      page: 0,
    });
  };

  const active = useMemo(() => hasAnyFilter(value), [value]);

  return (
    <div
      data-slot="audit-page-filter-bar"
      role="group"
      aria-label="Audit log filters"
      className={cn(
        "border-border bg-surface flex flex-wrap items-center gap-2 rounded-md border p-2",
        className,
      )}
    >
      <div className="relative min-w-[200px] flex-1">
        <Search
          aria-hidden="true"
          className="text-foreground-muted pointer-events-none absolute top-1/2 left-2.5 size-3.5 -translate-y-1/2"
        />
        <Input
          type="search"
          data-slot="audit-search"
          value={q}
          onChange={(e) => onQChange(e.target.value)}
          placeholder="Search by headline or actor"
          aria-label="Search audit log"
          className="h-8 pr-2 pl-7.5 text-sm"
        />
      </div>

      <Select
        value={value.actorId ?? ALL_ACTORS}
        onValueChange={(next) =>
          onChange({
            ...value,
            actorId: next === ALL_ACTORS ? undefined : next,
            page: 0,
          })
        }
      >
        <SelectTrigger
          size="sm"
          aria-label="Actor filter"
          data-slot="audit-actor-filter"
          className="w-44"
        >
          <SelectValue placeholder="All actors" />
        </SelectTrigger>
        <SelectContent>
          <SelectItem value={ALL_ACTORS}>All actors</SelectItem>
          {actorOptions.map((opt) => (
            <SelectItem key={opt.value} value={opt.value}>
              {opt.label}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>

      <div className="flex items-center gap-1.5">
        <span
          aria-hidden="true"
          className="text-foreground-muted text-xs font-medium tracking-wide uppercase"
        >
          From
        </span>
        <Input
          type="date"
          data-slot="audit-date-from"
          aria-label="From date"
          value={value.dateFrom ?? ""}
          onChange={(e) =>
            onChange({
              ...value,
              dateFrom: e.target.value === "" ? undefined : e.target.value,
              page: 0,
            })
          }
          className="h-8 w-36 px-2 text-xs tabular-nums"
        />
      </div>
      <div className="flex items-center gap-1.5">
        <span
          aria-hidden="true"
          className="text-foreground-muted text-xs font-medium tracking-wide uppercase"
        >
          To
        </span>
        <Input
          type="date"
          data-slot="audit-date-to"
          aria-label="To date"
          value={value.dateTo ?? ""}
          onChange={(e) =>
            onChange({
              ...value,
              dateTo: e.target.value === "" ? undefined : e.target.value,
              page: 0,
            })
          }
          className="h-8 w-36 px-2 text-xs tabular-nums"
        />
      </div>

      <div className="flex items-center gap-1.5">
        <span
          aria-hidden="true"
          className="text-foreground-muted text-xs font-medium tracking-wide uppercase"
        >
          Correlation
        </span>
        <Input
          type="text"
          data-slot="audit-correlation-id"
          aria-label="Correlation id"
          value={correlationId}
          onChange={(e) => onCorrelationChange(e.target.value)}
          placeholder="correlation id"
          className="h-8 w-48 px-2 text-xs"
        />
      </div>

      <div className="flex-1" />

      <Button
        type="button"
        variant="ghost"
        size="sm"
        onClick={clearAll}
        disabled={!active}
        data-slot="audit-filter-clear"
        aria-label="Clear all audit filters"
        className="gap-1"
      >
        <X aria-hidden="true" className="size-3.5" />
        Clear
      </Button>
    </div>
  );
}
