/**
 * URL-bound filter bar for `/loan-applications`.
 *
 * Drives `LoanApplicationListFilters` directly through `useUrlFilters` so
 * deep-links round-trip cleanly. The search input is debounced (200 ms)
 * before it reaches the URL — that keeps the cache key from churning
 * with every keystroke while leaving paste/blur snappy.
 *
 * Structure A (toolbar + applied chips). Everything visual lives in
 * `FilterBarShell`; this file owns only the URL wiring and which filters exist.
 * The old bar had no applied-state feedback at all — three active filters
 * looked exactly like zero — and ended in a `flex-1` spacer that occupied 710px
 * (62%) of its own second row. Both are gone: set controls tint, applied
 * filters are named in a chip row with per-filter clear, and the spacer is
 * replaced by the chip row's own `ml-auto` group.
 */
import { useMemo } from "react";
import { useDebouncedControlledText } from "@/lib/hooks/use-debounced-controlled-text";
import { DatePickerField } from "@/components/app/data/DatePickerField";
import {
  FilterAppliedChips,
  FilterBarFieldGroup,
  FilterBarSearchField,
  FilterBarShell,
  FilterBarSingleSelect,
  IgnoredFilterNotice,
  type AppliedFilter,
} from "@/components/app/data/FilterBarShell";
import { filterControlClass } from "@/components/app/data/filter-control";
import { StatusBadge } from "@/components/app/status/StatusBadge";
import { useUrlFilters } from "@/lib/url-state";
import { LoanApplicationListFilters } from "../types";
import { STATUS_META } from "@/lib/lifecycle";
import type { LoanStatus } from "@/types";

/**
 * Schema key → the label the operator sees on that control, so a rejected URL
 * param is named the way it appears on screen rather than as a field name.
 */
const FILTER_LABELS: Record<string, string> = {
  q: "Search",
  lspLoanId: "LSP loan ID",
  bhawLoanId: "Bhawana loan ID",
  status: "Status",
  lspId: "LSP",
  productId: "Product",
  disbursalDateFrom: "Disbursed from",
  disbursalDateTo: "Disbursed to",
  page: "Page",
  pageSize: "Rows per page",
  sortBy: "Sort column",
  sortDir: "Sort direction",
};

const filterLabelFor = (key: string): string => FILTER_LABELS[key] ?? key;

const STATUS_OPTIONS: readonly { value: LoanStatus; label: string }[] = (
  Object.keys(STATUS_META) as LoanStatus[]
).map((s) => ({ value: s, label: STATUS_META[s].label }));

const SEARCH_DEBOUNCE_MS = 200;
const EMPTY_FILTER_OPTIONS: readonly { value: string; label: string }[] = [];

export interface LoanApplicationsFilterBarProps {
  /**
   * Optional LSP options. Wire from `useLsps()` in a follow-up; the bar
   * defaults to an empty list, in which case the dropdown shows only the
   * "All LSPs" placeholder.
   */
  lspOptions?: readonly { value: string; label: string }[];
  /** Optional product options — see `lspOptions` comment. */
  productOptions?: readonly { value: string; label: string }[];
  /**
   * Rows the current filter set matches. Rendered in the applied-chips row so
   * the operator can see whether they are looking at the whole book or a slice.
   */
  resultCount?: number;
  className?: string;
}

export function LoanApplicationsFilterBar({
  lspOptions = EMPTY_FILTER_OPTIONS,
  productOptions = EMPTY_FILTER_OPTIONS,
  resultCount,
  className,
}: LoanApplicationsFilterBarProps) {
  const [filters, setFilters, ignoredFilterKeys] = useUrlFilters(LoanApplicationListFilters);

  // Search input: locally controlled, debounced into the URL.
  const searchField = useDebouncedControlledText(
    filters.q,
    (q) => setFilters({ q, page: 0 }),
    SEARCH_DEBOUNCE_MS,
  );

  const { clearPending, onChange: setSearchValue } = searchField;

  const clearAll = () => {
    clearPending();
    setSearchValue("");
    setFilters({
      q: undefined,
      lspLoanId: undefined,
      bhawLoanId: undefined,
      disbursalDateFrom: undefined,
      disbursalDateTo: undefined,
      status: undefined,
      lspId: undefined,
      productId: undefined,
      page: 0,
    });
  };

  /**
   * One chip per *set* filter, resolved to the label the operator chose rather
   * than the raw id — an LSP chip reading `a3f1b…` would be no better than the
   * silent state it replaces.
   */
  const appliedFilters = useMemo<AppliedFilter[]>(() => {
    const chips: AppliedFilter[] = [];
    const labelFor = (
      options: readonly { value: string; label: string }[],
      value: string,
    ): string => options.find((o) => o.value === value)?.label ?? value;

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
    if (filters.lspLoanId?.trim()) {
      chips.push({
        key: "lspLoanId",
        label: "LSP loan ID",
        value: filters.lspLoanId,
        onClear: () => setFilters({ lspLoanId: undefined, page: 0 }),
      });
    }
    if (filters.bhawLoanId?.trim()) {
      chips.push({
        key: "bhawLoanId",
        label: "Bhawana loan ID",
        value: filters.bhawLoanId,
        onClear: () => setFilters({ bhawLoanId: undefined, page: 0 }),
      });
    }
    if (filters.status?.[0]) {
      chips.push({
        key: "status",
        label: "Status",
        value: labelFor(STATUS_OPTIONS, filters.status[0]),
        onClear: () => setFilters({ status: undefined, page: 0 }),
      });
    }
    if (filters.lspId) {
      chips.push({
        key: "lspId",
        label: "LSP",
        value: labelFor(lspOptions, filters.lspId),
        onClear: () => setFilters({ lspId: undefined, page: 0 }),
      });
    }
    if (filters.productId) {
      chips.push({
        key: "productId",
        label: "Product",
        value: labelFor(productOptions, filters.productId),
        onClear: () => setFilters({ productId: undefined, page: 0 }),
      });
    }
    if (filters.disbursalDateFrom) {
      chips.push({
        key: "disbursalDateFrom",
        label: "Disbursed from",
        value: filters.disbursalDateFrom,
        onClear: () => setFilters({ disbursalDateFrom: undefined, page: 0 }),
      });
    }
    if (filters.disbursalDateTo) {
      chips.push({
        key: "disbursalDateTo",
        label: "Disbursed to",
        value: filters.disbursalDateTo,
        onClear: () => setFilters({ disbursalDateTo: undefined, page: 0 }),
      });
    }
    return chips;
  }, [filters, lspOptions, productOptions, setFilters, clearPending, setSearchValue]);

  return (
    <FilterBarShell
      dataSlot="loan-applications-filter-bar"
      ariaLabel="Loan application filters"
      className={className}
      notice={
        <IgnoredFilterNotice
          ignoredKeys={ignoredFilterKeys}
          labelFor={filterLabelFor}
          dataSlot="loan-applications-ignored-filters"
        />
      }
      appliedChips={
        <FilterAppliedChips
          filters={appliedFilters}
          onClearAll={clearAll}
          resultCount={resultCount}
          resultNoun="applications"
          dataSlot="loan-applications-applied-filters"
        />
      }
    >
      <FilterBarSearchField
        value={searchField.value}
        onChange={searchField.onChange}
        placeholder="Search borrower, PAN, mobile, city"
        ariaLabel="Search loan applications"
        dataSlot="loan-applications-search"
      />

      <label className="flex min-w-[150px] flex-col gap-1">
        <span className="sr-only">LSP loan ID</span>
        <input
          type="search"
          id="loan-applications-lsp-loan-id-filter"
          name="lspLoanId"
          data-slot="loan-applications-lsp-loan-id-filter"
          value={filters.lspLoanId ?? ""}
          onChange={(event) =>
            setFilters({
              lspLoanId: event.target.value.trim() === "" ? undefined : event.target.value,
              page: 0,
            })
          }
          placeholder="LSP Loan ID"
          aria-label="LSP loan ID"
          data-filter-set={filters.lspLoanId?.trim() ? "true" : undefined}
          className={filterControlClass(Boolean(filters.lspLoanId?.trim()), "w-full")}
        />
      </label>

      <label className="flex min-w-[150px] flex-col gap-1">
        <span className="sr-only">Bhawana loan ID</span>
        <input
          type="search"
          id="loan-applications-bhaw-loan-id-filter"
          name="bhawLoanId"
          data-slot="loan-applications-bhaw-loan-id-filter"
          value={filters.bhawLoanId ?? ""}
          onChange={(event) =>
            setFilters({
              bhawLoanId: event.target.value.trim() === "" ? undefined : event.target.value,
              page: 0,
            })
          }
          placeholder="Bhawana loan ID"
          aria-label="Bhawana loan ID"
          data-filter-set={filters.bhawLoanId?.trim() ? "true" : undefined}
          className={filterControlClass(Boolean(filters.bhawLoanId?.trim()), "w-full")}
        />
      </label>

      {/* Single-select on purpose: the ops list endpoint accepts one `status`
          param, and a multi-select that silently applies only the first
          choice misleads (audit F5). */}
      <FilterBarSingleSelect
        value={filters.status?.[0]}
        onChange={(next) =>
          setFilters({ status: next ? [next as LoanStatus] : undefined, page: 0 })
        }
        placeholder="All statuses"
        ariaLabel="Status filter"
        options={STATUS_OPTIONS}
        renderOption={(o) => <StatusBadge status={o.value as LoanStatus} variant="subtle" />}
        dataSlot="loan-applications-status-filter"
      />

      <FilterBarSingleSelect
        value={filters.lspId}
        onChange={(next) => setFilters({ lspId: next, page: 0 })}
        placeholder="All LSPs"
        ariaLabel="LSP filter"
        options={lspOptions}
        dataSlot="loan-applications-lsp-filter"
      />

      <FilterBarSingleSelect
        value={filters.productId}
        onChange={(next) => setFilters({ productId: next, page: 0 })}
        placeholder="All products"
        ariaLabel="Product filter"
        options={productOptions}
        dataSlot="loan-applications-product-filter"
      />

      {/*
        Grouped, not adjacent. As two loose flex items the pair split across
        rows at 1440 but not at 1280 — grouping was decided by viewport width —
        and nothing on screen named which box set the lower bound. One fieldset
        wraps as a unit at every width and labels the pair once.
      */}
      <FilterBarFieldGroup label="Disbursed">
        <DatePickerField
          value={filters.disbursalDateFrom}
          onChange={(next) => setFilters({ disbursalDateFrom: next, page: 0 })}
          ariaLabel="Disbursed from"
          placeholder="From"
          dataSlot="loan-applications-disbursed-from"
          className="w-28"
        />
        <span aria-hidden="true" className="text-foreground-muted text-xs">
          –
        </span>
        <DatePickerField
          value={filters.disbursalDateTo}
          onChange={(next) => setFilters({ disbursalDateTo: next, page: 0 })}
          ariaLabel="Disbursed to"
          placeholder="To"
          dataSlot="loan-applications-disbursed-to"
          className="w-28"
        />
      </FilterBarFieldGroup>
    </FilterBarShell>
  );
}
