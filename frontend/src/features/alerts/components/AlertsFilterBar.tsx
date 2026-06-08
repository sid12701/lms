/**
 * Filter bar for `/alerts`.
 *
 * State is owned by the parent page (the URL is intentionally not bound —
 * the alerts inbox is an internal-ops surface, not a deep-link target).
 * The bar emits the filter delta via `onChange`.
 */
import type { Dispatch, SetStateAction } from "react";
import { MultiSelectChip } from "@/components/app/data/MultiSelectChip";
import {
  FilterBarClearButton,
  FilterBarSearchField,
  FilterBarShell,
  FilterBarStatusTabs,
} from "@/components/app/data/FilterBarShell";
import { useDebouncedControlledText } from "@/lib/hooks/use-debounced-controlled-text";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
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
  onChange: Dispatch<SetStateAction<AlertsListFilters>>;
  className?: string;
}

export function AlertsFilterBar({ filters, onChange, className }: AlertsFilterBarProps) {
  const searchField = useDebouncedControlledText(
    filters.q,
    (q) => onChange((prev) => ({ ...prev, q, page: 0 })),
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
    onChange({ page: 0, pageSize: filters.pageSize ?? 25 });
    // Keep the input visually empty; debounced publish uses functional updates so
    // a delayed callback cannot resurrect filters cleared above.
    searchField.onChange("");
  };

  const active =
    Boolean(filters.q) ||
    Boolean(filters.status) ||
    Boolean(filters.subjectType) ||
    (filters.severity?.length ?? 0) > 0;

  const activeTab: AlertStatus | "ALL" = filters.status ?? "ALL";

  return (
    <FilterBarShell dataSlot="alerts-filter-bar" ariaLabel="Alert filters" className={className}>
      <FilterBarStatusTabs
        tabs={STATUS_TABS}
        active={activeTab}
        onSelect={setStatusTab}
        dataSlotPrefix="alerts-tab"
      />

      <MultiSelectChip<AlertSeverity>
        label="All severities"
        listboxLabel="Severity"
        dataSlot="alerts-severity-filter"
        options={SEVERITY_OPTIONS}
        selected={filters.severity ?? []}
        onToggle={setSeverity}
      />

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

      <FilterBarSearchField
        value={searchField.value}
        onChange={searchField.onChange}
        placeholder="Search title or message"
        ariaLabel="Search alerts"
        dataSlot="alerts-search"
      />

      <FilterBarClearButton onClick={clearAll} disabled={!active} dataSlot="alerts-filter-clear" />
    </FilterBarShell>
  );
}
