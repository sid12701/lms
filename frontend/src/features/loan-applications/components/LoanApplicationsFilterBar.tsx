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
import { Calendar, Search, X } from "lucide-react";
import { MultiSelectChip } from "@/components/app/data/MultiSelectChip";
import { Button } from "@/components/ui/button";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { useUrlFilters } from "@/lib/url-state";
import { cn } from "@/lib/utils";
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

/**
 * Composed filter bar for the loan-applications list.
 *
 * No props are required — the component owns its own URL-state hook and
 * exposes only optional dropdown option lists for the parent to inject.
 */
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
    <div
      data-slot="loan-applications-filter-bar"
      role="group"
      aria-label="Loan application filters"
      className={cn(
        "border-border bg-surface flex flex-wrap items-center gap-2 rounded-md border p-2",
        className,
      )}
    >
      <label className="relative min-w-[200px] flex-1">
        <span className="sr-only">Search applications</span>
        <Search
          aria-hidden="true"
          className="text-foreground-muted pointer-events-none absolute top-1/2 left-2.5 size-3.5 -translate-y-1/2"
        />
        <input
          type="search"
          data-slot="loan-applications-search"
          value={searchField.value}
          onChange={(e) => searchField.onChange(e.target.value)}
          placeholder="Search borrower, PAN, mobile, city"
          aria-label="Search loan applications"
          className="border-border bg-surface text-foreground placeholder:text-foreground-muted focus-visible:border-ring focus-visible:ring-ring/50 h-8 w-full rounded-md border pr-2 pl-7.5 text-sm outline-none focus-visible:ring-[3px]"
        />
      </label>

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
        <span className="sr-only">Bhaw loan ID</span>
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
          placeholder="Bhaw Loan ID"
          aria-label="Bhaw loan ID"
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

      <label className="relative">
        <span className="sr-only">Disbursed from</span>
        <Calendar
          aria-hidden="true"
          className="text-foreground-muted pointer-events-none absolute top-1/2 left-2.5 size-3.5 -translate-y-1/2"
        />
        <input
          type="date"
          data-slot="loan-applications-disbursed-from"
          value={filters.disbursalDateFrom ?? ""}
          onChange={(event) =>
            setFilters({
              disbursalDateFrom: event.target.value === "" ? undefined : event.target.value,
              page: 0,
            })
          }
          aria-label="Disbursed from"
          className="border-border bg-surface text-foreground focus-visible:border-ring focus-visible:ring-ring/50 h-8 w-40 rounded-md border pr-2 pl-8 text-sm outline-none focus-visible:ring-[3px]"
        />
      </label>

      <label className="relative">
        <span className="sr-only">Disbursed to</span>
        <Calendar
          aria-hidden="true"
          className="text-foreground-muted pointer-events-none absolute top-1/2 left-2.5 size-3.5 -translate-y-1/2"
        />
        <input
          type="date"
          data-slot="loan-applications-disbursed-to"
          value={filters.disbursalDateTo ?? ""}
          onChange={(event) =>
            setFilters({
              disbursalDateTo: event.target.value === "" ? undefined : event.target.value,
              page: 0,
            })
          }
          aria-label="Disbursed to"
          className="border-border bg-surface text-foreground focus-visible:border-ring focus-visible:ring-ring/50 h-8 w-40 rounded-md border pr-2 pl-8 text-sm outline-none focus-visible:ring-[3px]"
        />
      </label>

      <SingleSelect
        value={filters.productId}
        onChange={(next) => setFilters({ productId: next, page: 0 })}
        placeholder="All products"
        ariaLabel="Product filter"
        options={productOptions}
        testId="loan-applications-product-filter"
      />

      <div className="flex-1" />

      <Button
        type="button"
        variant="ghost"
        size="sm"
        onClick={clearAll}
        disabled={!active}
        data-slot="loan-applications-filter-clear"
        aria-label="Clear all filters"
        className="gap-1"
      >
        <X aria-hidden="true" className="size-3.5" />
        Clear filters
      </Button>
    </div>
  );
}
