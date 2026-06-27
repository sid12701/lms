/**
 * URL-bound filter bar for `/loan-applications`.
 *
 * Drives `LoanApplicationListFilters` directly through `useUrlFilters` so
 * deep-links round-trip cleanly. The search input is debounced (200 ms)
 * before it reaches the URL — that keeps the cache key from churning
 * with every keystroke while leaving paste/blur snappy.
 *
 * Product / LSP dropdowns render their static placeholders
 * for now; agents wiring `useLsps()` / `useProducts()` swap the option
 * lists in a follow-up without changing this component's contract.
 */
import { useMemo } from "react";
import { useDebouncedControlledText } from "@/lib/hooks/use-debounced-controlled-text";
import { DatePickerField } from "@/components/app/data/DatePickerField";
import { MultiSelectChip } from "@/components/app/data/MultiSelectChip";
import {
  FilterBarClearButton,
  FilterBarSearchField,
  FilterBarShell,
} from "@/components/app/data/FilterBarShell";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { useUrlFilters } from "@/lib/url-state";
import { LoanApplicationListFilters } from "../types";
import { STATUS_META } from "@/lib/lifecycle";
import type { LoanStatus } from "@/types";

const STATUS_OPTIONS: readonly { value: LoanStatus; label: string }[] = (
  Object.keys(STATUS_META) as LoanStatus[]
).map((s) => ({ value: s, label: STATUS_META[s].label }));

const SEARCH_DEBOUNCE_MS = 200;

/**
 * The shadcn `Select` primitive does not accept an empty-string value
 * for `<SelectItem>`. We use this sentinel for the "all" option, then
 * translate it back to `undefined` in the change handler.
 */
const ALL_SENTINEL = "__all__";

interface SingleSelectProps {
  value: string | undefined;
  onChange: (next: string | undefined) => void;
  placeholder: string;
  ariaLabel: string;
  options: readonly { value: string; label: string }[];
  testId: string;
}

function SingleSelect({
  value,
  onChange,
  placeholder,
  ariaLabel,
  options,
  testId,
}: SingleSelectProps) {
  return (
    <Select
      value={value ?? ALL_SENTINEL}
      onValueChange={(next) => onChange(next === ALL_SENTINEL ? undefined : next)}
    >
      <SelectTrigger size="sm" aria-label={ariaLabel} data-slot={testId} className="w-40">
        <SelectValue placeholder={placeholder} />
      </SelectTrigger>
      <SelectContent>
        <SelectItem value={ALL_SENTINEL}>{placeholder}</SelectItem>
        {options.map((o) => (
          <SelectItem key={o.value} value={o.value}>
            {o.label}
          </SelectItem>
        ))}
      </SelectContent>
    </Select>
  );
}

function hasAnyFilter(filters: Partial<LoanApplicationListFilters>): boolean {
  if (filters.q && filters.q.trim() !== "") return true;
  if (filters.lspLoanId && filters.lspLoanId.trim() !== "") return true;
  if (filters.bhawLoanId && filters.bhawLoanId.trim() !== "") return true;
  if (filters.disbursalDateFrom) return true;
  if (filters.disbursalDateTo) return true;
  if (filters.status && filters.status.length > 0) return true;
  if (filters.lspId) return true;
  if (filters.productId) return true;
  return false;
}

export interface LoanApplicationsFilterBarProps {
  /**
   * Optional LSP options. Wire from `useLsps()` in a follow-up; the bar
   * defaults to an empty list, in which case the dropdown shows only the
   * "All LSPs" placeholder.
   */
  lspOptions?: readonly { value: string; label: string }[];
  /** Optional product options — see `lspOptions` comment. */
  productOptions?: readonly { value: string; label: string }[];
  className?: string;
}

export function LoanApplicationsFilterBar({
  lspOptions = [],
  productOptions = [],
  className,
}: LoanApplicationsFilterBarProps) {
  const [filters, setFilters] = useUrlFilters(LoanApplicationListFilters);

  // Search input: locally controlled, debounced into the URL.
  const searchField = useDebouncedControlledText(
    filters.q,
    (q) => setFilters({ q, page: 0 }),
    SEARCH_DEBOUNCE_MS,
  );

  const clearAll = () => {
    searchField.clearPending();
    searchField.onChange("");
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

  const active = useMemo(() => hasAnyFilter(filters), [filters]);

  return (
    <FilterBarShell
      dataSlot="loan-applications-filter-bar"
      ariaLabel="Loan application filters"
      className={className}
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
          className="border-border bg-surface text-foreground placeholder:text-foreground-muted focus-visible:border-ring focus-visible:ring-ring/50 h-8 w-full rounded-md border px-2 text-sm outline-none focus-visible:ring-[3px]"
        />
      </label>

      <label className="flex min-w-[150px] flex-col gap-1">
        <span className="sr-only">Bhawana loan ID</span>
        <input
          type="search"
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
          className="border-border bg-surface text-foreground placeholder:text-foreground-muted focus-visible:border-ring focus-visible:ring-ring/50 h-8 w-full rounded-md border px-2 text-sm outline-none focus-visible:ring-[3px]"
        />
      </label>

      <MultiSelectChip<LoanStatus>
        label="All statuses"
        listboxLabel="Status"
        dataSlot="loan-applications-status-filter"
        options={STATUS_OPTIONS}
        selected={filters.status ?? []}
        onToggle={(next) => setFilters({ status: next.length > 0 ? next : undefined, page: 0 })}
      />

      <SingleSelect
        value={filters.lspId}
        onChange={(next) => setFilters({ lspId: next, page: 0 })}
        placeholder="All LSPs"
        ariaLabel="LSP filter"
        options={lspOptions}
        testId="loan-applications-lsp-filter"
      />

      <DatePickerField
        value={filters.disbursalDateFrom}
        onChange={(next) => setFilters({ disbursalDateFrom: next, page: 0 })}
        ariaLabel="Disbursed from"
        dataSlot="loan-applications-disbursed-from"
        className="w-40"
      />

      <DatePickerField
        value={filters.disbursalDateTo}
        onChange={(next) => setFilters({ disbursalDateTo: next, page: 0 })}
        ariaLabel="Disbursed to"
        dataSlot="loan-applications-disbursed-to"
        className="w-40"
      />

      <SingleSelect
        value={filters.productId}
        onChange={(next) => setFilters({ productId: next, page: 0 })}
        placeholder="All products"
        ariaLabel="Product filter"
        options={productOptions}
        testId="loan-applications-product-filter"
      />

      <div className="flex-1" />

      <FilterBarClearButton
        onClick={clearAll}
        disabled={!active}
        dataSlot="loan-applications-filter-clear"
      />
    </FilterBarShell>
  );
}
