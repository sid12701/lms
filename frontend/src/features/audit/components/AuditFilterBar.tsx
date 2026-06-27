/**
 * Page-level filter bar for `/audit`.
 *
 * Distinct from the lower-level primitive at `@/components/app/audit/
 * AuditFilterBar` (which is a controlled, stream + role + date-range
 * picker for the timeline component): this bar carries the page's
 * URL-state surface — actor, dateFrom/dateTo, loanApplicationId,
 * correlationId — and routes every change through the controlled `value` /
 * `onChange` callbacks the page provides.
 *
 * Per CLAUDE.md hard-don't #4 every input lives behind a shadcn primitive
 * (`Input` / `DatePickerField`).
 */
import { useMemo } from "react";
import { useDebouncedControlledText } from "@/lib/hooks/use-debounced-controlled-text";
import { X } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { DatePickerField } from "@/components/app/data/DatePickerField";
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
  if (value.loanApplicationId && value.loanApplicationId.trim() !== "") return true;
  if (value.correlationId && value.correlationId.trim() !== "") return true;
  if (value.dateFrom) return true;
  if (value.dateTo) return true;
  return false;
}

export function AuditPageFilterBar({
  value,
  onChange,
  actorOptions = [],
  className,
}: AuditPageFilterBarProps) {
  const loanApplicationField = useDebouncedControlledText(
    value.loanApplicationId,
    (loanApplicationId) => onChange({ ...value, loanApplicationId, page: 0 }),
    SEARCH_DEBOUNCE_MS,
  );
  const correlationField = useDebouncedControlledText(
    value.correlationId,
    (correlationId) => onChange({ ...value, correlationId, page: 0 }),
    SEARCH_DEBOUNCE_MS,
  );

  const clearAll = () => {
    loanApplicationField.clearPending();
    correlationField.clearPending();
    loanApplicationField.onChange("");
    correlationField.onChange("");
    onChange({
      ...value,
      loanApplicationId: undefined,
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
          id="audit-loan-application-label"
          className="text-foreground-muted text-xs font-medium tracking-wide uppercase"
        >
          Loan
        </span>
        <Input
          type="text"
          data-slot="audit-loan-application-id"
          aria-labelledby="audit-loan-application-label"
          value={loanApplicationField.value}
          onChange={(e) => loanApplicationField.onChange(e.target.value)}
          placeholder="loan application id"
          className="h-8 w-56 px-2 text-xs"
        />
      </div>

      <div className="flex items-center gap-1.5">
        <span
          id="audit-date-from-label"
          className="text-foreground-muted text-xs font-medium tracking-wide uppercase"
        >
          From
        </span>
        <DatePickerField
          dataSlot="audit-date-from"
          ariaLabel="Audit from date"
          value={value.dateFrom}
          onChange={(next) =>
            onChange({
              ...value,
              dateFrom: next,
              page: 0,
            })
          }
          className="h-8 w-36"
        />
      </div>
      <div className="flex items-center gap-1.5">
        <span
          id="audit-date-to-label"
          className="text-foreground-muted text-xs font-medium tracking-wide uppercase"
        >
          To
        </span>
        <DatePickerField
          dataSlot="audit-date-to"
          ariaLabel="Audit to date"
          value={value.dateTo}
          onChange={(next) =>
            onChange({
              ...value,
              dateTo: next,
              page: 0,
            })
          }
          className="h-8 w-36"
        />
      </div>

      <div className="flex items-center gap-1.5">
        <span
          id="audit-correlation-label"
          className="text-foreground-muted text-xs font-medium tracking-wide uppercase"
        >
          Correlation
        </span>
        <Input
          type="text"
          data-slot="audit-correlation-id"
          aria-labelledby="audit-correlation-label"
          value={correlationField.value}
          onChange={(e) => correlationField.onChange(e.target.value)}
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
