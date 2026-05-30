/**
 * Filter bar for `/alerts`.
 *
 * State is owned by the parent page (the URL is intentionally not bound —
 * the alerts inbox is an internal-ops surface, not a deep-link target).
 * The bar emits the filter delta via `onChange`.
 */
import { Check, ChevronDown, Search, X } from "lucide-react";
import { useDebouncedControlledText } from "@/lib/hooks/use-debounced-controlled-text";
import { Button } from "@/components/ui/button";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { cn } from "@/lib/utils";
import type { AlertSeverity, AlertStatus, AlertSubjectType } from "@/schemas/alert";
import type { AlertsListFilters } from "../types";

const SEARCH_DEBOUNCE_MS = 200;

const SEVERITY_OPTIONS: readonly { value: AlertSeverity; label: string }[] = [
  { value: "CRITICAL", label: "Critical" },
  { value: "HIGH", label: "High" },
  { value: "MEDIUM", label: "Medium" },
  { value: "LOW", label: "Low" },
];

const SUBJECT_OPTIONS: readonly { value: AlertSubjectType; label: string }[] = [
  { value: "LOAN_APPLICATION", label: "Loan application" },
  { value: "LOAN_ACCOUNT", label: "Loan account" },
  { value: "BORROWER", label: "Borrower" },
  { value: "WEBHOOK_DELIVERY", label: "Webhook delivery" },
  { value: "REPORT_REQUEST", label: "Report request" },
  { value: "SYSTEM", label: "System" },
];

const STATUS_TABS: readonly { value: AlertStatus | "ALL"; label: string }[] = [
  { value: "ALL", label: "All" },
  { value: "OPEN", label: "Open" },
  { value: "ACKNOWLEDGED", label: "Acknowledged" },
];

const ALL_SENTINEL = "__all__";

export interface AlertsFilterBarProps {
  filters: AlertsListFilters;
  onChange: (next: AlertsListFilters) => void;
  className?: string;
}

function SeverityMultiSelect({
  selected,
  onChange,
}: {
  selected: readonly AlertSeverity[];
  onChange: (next: AlertSeverity[]) => void;
}) {
  const summary =
    selected.length === 0
      ? "All severities"
      : selected.length === 1
        ? `Severity: ${selected[0]?.toLowerCase()}`
        : `Severity: ${selected.length} selected`;

  const toggle = (val: AlertSeverity) => {
    if (selected.includes(val)) {
      onChange(selected.filter((v) => v !== val));
    } else {
      onChange([...selected, val]);
    }
  };

  return (
    <Popover>
      <PopoverTrigger asChild>
        <Button
          type="button"
          variant="outline"
          size="sm"
          data-slot="alerts-severity-filter"
          aria-label={summary}
          className={cn(
            "gap-1",
            selected.length > 0 ? "border-ring text-foreground" : "text-foreground-muted",
          )}
        >
          <span>{summary}</span>
          <ChevronDown aria-hidden="true" className="size-3.5" />
        </Button>
      </PopoverTrigger>
      <PopoverContent align="start" className="w-48 p-1">
        <ul
          role="listbox"
          aria-label="Severity"
          aria-multiselectable="true"
          className="flex flex-col"
        >
          {SEVERITY_OPTIONS.map((opt) => {
            const checked = selected.includes(opt.value);
            return (
              <li key={opt.value}>
                <Button
                  type="button"
                  variant="ghost"
                  size="sm"
                  role="option"
                  aria-selected={checked}
                  onClick={() => toggle(opt.value)}
                  className="w-full justify-start gap-2"
                >
                  <span
                    aria-hidden="true"
                    className={cn(
                      "border-border inline-flex size-4 items-center justify-center rounded-sm border",
                      checked ? "bg-primary border-primary text-primary-foreground" : null,
                    )}
                  >
                    {checked ? <Check className="size-3" aria-hidden="true" /> : null}
                  </span>
                  <span>{opt.label}</span>
                </Button>
              </li>
            );
          })}
        </ul>
      </PopoverContent>
    </Popover>
  );
}

export function AlertsFilterBar({ filters, onChange, className }: AlertsFilterBarProps) {
  const searchField = useDebouncedControlledText(
    filters.q,
    (q) => onChange({ ...filters, q, page: 0 }),
    SEARCH_DEBOUNCE_MS,
  );

  const setStatusTab = (next: AlertStatus | "ALL") => {
    onChange({
      ...filters,
      status: next === "ALL" ? undefined : next,
      page: 0,
    });
  };

  const setSeverity = (next: AlertSeverity[]) => {
    onChange({
      ...filters,
      severity: next.length > 0 ? next : undefined,
      page: 0,
    });
  };

  const setSubject = (next: string | undefined) => {
    onChange({
      ...filters,
      subjectType: next as AlertSubjectType | undefined,
      page: 0,
    });
  };

  const clearAll = () => {
    searchField.clearPending();
    searchField.onChange("");
    onChange({ page: 0 });
  };

  const active =
    Boolean(filters.q) ||
    Boolean(filters.status) ||
    Boolean(filters.subjectType) ||
    (filters.severity?.length ?? 0) > 0;

  const activeTab: AlertStatus | "ALL" = filters.status ?? "ALL";

  return (
    <div
      data-slot="alerts-filter-bar"
      role="group"
      aria-label="Alert filters"
      className={cn(
        "border-border bg-surface flex flex-wrap items-center gap-2 rounded-md border p-2",
        className,
      )}
    >
      <div
        role="tablist"
        aria-label="Status"
        className="border-border flex items-center gap-1 rounded-md border p-0.5"
      >
        {STATUS_TABS.map((tab) => (
          <Button
            key={tab.value}
            type="button"
            role="tab"
            aria-selected={activeTab === tab.value}
            variant={activeTab === tab.value ? "default" : "ghost"}
            size="sm"
            data-slot={`alerts-tab-${tab.value.toLowerCase()}`}
            onClick={() => setStatusTab(tab.value)}
          >
            {tab.label}
          </Button>
        ))}
      </div>

      <SeverityMultiSelect selected={filters.severity ?? []} onChange={setSeverity} />

      <Select
        value={filters.subjectType ?? ALL_SENTINEL}
        onValueChange={(next) => setSubject(next === ALL_SENTINEL ? undefined : next)}
      >
        <SelectTrigger
          size="sm"
          aria-label="Subject filter"
          data-slot="alerts-subject-filter"
          className="w-44"
        >
          <SelectValue placeholder="All subjects" />
        </SelectTrigger>
        <SelectContent>
          <SelectItem value={ALL_SENTINEL}>All subjects</SelectItem>
          {SUBJECT_OPTIONS.map((o) => (
            <SelectItem key={o.value} value={o.value}>
              {o.label}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>

      <label className="relative min-w-[200px] flex-1">
        <span className="sr-only">Search alerts</span>
        <Search
          aria-hidden="true"
          className="text-foreground-muted pointer-events-none absolute top-1/2 left-2.5 size-3.5 -translate-y-1/2"
        />
        <input
          type="search"
          data-slot="alerts-search"
          value={searchField.value}
          onChange={(e) => searchField.onChange(e.target.value)}
          placeholder="Search title or message"
          aria-label="Search alerts"
          className="border-border bg-surface text-foreground placeholder:text-foreground-muted focus-visible:border-ring focus-visible:ring-ring/50 h-8 w-full rounded-md border pr-2 pl-7.5 text-sm outline-none focus-visible:ring-[3px]"
        />
      </label>

      <Button
        type="button"
        variant="ghost"
        size="sm"
        onClick={clearAll}
        disabled={!active}
        data-slot="alerts-filter-clear"
        aria-label="Clear all filters"
        className="gap-1"
      >
        <X aria-hidden="true" className="size-3.5" />
        Clear
      </Button>
    </div>
  );
}
