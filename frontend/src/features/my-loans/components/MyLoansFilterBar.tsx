/**
 * URL-bound filter bar for `/my-loans`.
 *
 * The partner surface shipped with exactly one control — rows-per-page — over a
 * book that runs to hundreds of loans, so answering "what is the status of my
 * loan" for a borrower on the phone meant paging blind. The LSP list endpoint
 * already accepted `q` and `status`; only the UI was missing.
 *
 * Filters are URL-bound through `useUrlFilters` for the same reasons as the
 * internal bar: deep links round-trip, back/forward behave, and a rejected
 * param is named rather than silently dropped.
 */
import { useMemo } from "react";
import { useDebouncedControlledText } from "@/lib/hooks/use-debounced-controlled-text";
import {
  FilterAppliedChips,
  FilterBarSearchField,
  FilterBarShell,
  FilterBarSingleSelect,
  IgnoredFilterNotice,
  type AppliedFilter,
} from "@/components/app/data/FilterBarShell";
import { StatusBadge } from "@/components/app/status/StatusBadge";
import { useUrlFilters } from "@/lib/url-state";
import { STATUS_META } from "@/lib/lifecycle";
import type { LoanStatus } from "@/types";
import { MyLoanListFilters } from "../types";

const STATUS_OPTIONS: readonly { value: LoanStatus; label: string }[] = (
  Object.keys(STATUS_META) as LoanStatus[]
).map((s) => ({ value: s, label: STATUS_META[s].label }));

const FILTER_LABELS: Record<string, string> = {
  q: "Search",
  status: "Status",
  page: "Page",
  pageSize: "Rows per page",
};

const filterLabelFor = (key: string): string => FILTER_LABELS[key] ?? key;

const SEARCH_DEBOUNCE_MS = 200;

export interface MyLoansFilterBarProps {
  /** Rows the current filter set matches, shown in the applied-chips row. */
  resultCount?: number;
  className?: string;
}

export function MyLoansFilterBar({ resultCount, className }: MyLoansFilterBarProps) {
  const [filters, setFilters, ignoredFilterKeys] = useUrlFilters(MyLoanListFilters);

  const searchField = useDebouncedControlledText(
    filters.q,
    (q) => setFilters({ q, page: 0 }),
    SEARCH_DEBOUNCE_MS,
  );
  const { clearPending, onChange: setSearchValue } = searchField;

  const clearAll = () => {
    clearPending();
    setSearchValue("");
    setFilters({ q: undefined, status: undefined, page: 0 });
  };

  const appliedFilters = useMemo<AppliedFilter[]>(() => {
    const chips: AppliedFilter[] = [];
    if (filters.q?.trim()) {
      chips.push({
        key: "q",
        label: "Search",
        value: filters.q.trim(),
        onClear: () => {
          clearPending();
          setSearchValue("");
          setFilters({ q: undefined, page: 0 });
        },
      });
    }
    if (filters.status) {
      chips.push({
        key: "status",
        label: "Status",
        value: STATUS_META[filters.status]?.label ?? filters.status,
        onClear: () => setFilters({ status: undefined, page: 0 }),
      });
    }
    return chips;
  }, [filters, setFilters, clearPending, setSearchValue]);

  return (
    <FilterBarShell
      dataSlot="my-loans-filter-bar"
      ariaLabel="Loan filters"
      className={className}
      notice={
        <IgnoredFilterNotice
          ignoredKeys={ignoredFilterKeys}
          labelFor={filterLabelFor}
          dataSlot="my-loans-ignored-filters"
        />
      }
      appliedChips={
        <FilterAppliedChips
          filters={appliedFilters}
          onClearAll={clearAll}
          resultCount={resultCount}
          resultNoun="loans"
          dataSlot="my-loans-applied-filters"
        />
      }
    >
      <FilterBarSearchField
        value={searchField.value}
        onChange={searchField.onChange}
        placeholder="Search borrower or reference"
        ariaLabel="Search loans"
        dataSlot="my-loans-search"
      />
      <FilterBarSingleSelect
        value={filters.status}
        onChange={(next) => setFilters({ status: next as LoanStatus | undefined, page: 0 })}
        placeholder="All statuses"
        ariaLabel="Status filter"
        options={STATUS_OPTIONS}
        renderOption={(o) => <StatusBadge status={o.value as LoanStatus} variant="subtle" />}
        dataSlot="my-loans-status-filter"
      />
    </FilterBarShell>
  );
}
