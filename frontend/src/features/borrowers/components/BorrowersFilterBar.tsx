/**
 * URL-bound search bar for the `/borrowers` directory.
 *
 * The single text input drives a case-insensitive substring search
 * across the borrower's name, PAN, mobile, and email. We debounce the
 * input by 200ms before it reaches the URL so the cache key doesn't
 * churn with every keystroke.
 */
import { useDebouncedControlledText } from "@/lib/hooks/use-debounced-controlled-text";
import {
  FilterBarClearButton,
  FilterBarSearchField,
  FilterBarShell,
} from "@/components/app/data/FilterBarShell";
import { useUrlFilters } from "@/lib/url-state";
import { BorrowerListFilters } from "../list-types";

const SEARCH_DEBOUNCE_MS = 200;

export interface BorrowersFilterBarProps {
  className?: string;
}

export function BorrowersFilterBar({ className }: BorrowersFilterBarProps) {
  const [filters, setFilters] = useUrlFilters(BorrowerListFilters);
  const searchField = useDebouncedControlledText(
    filters.q,
    (q) => setFilters({ q, page: 0 }),
    SEARCH_DEBOUNCE_MS,
  );

  const clearAll = () => {
    searchField.clearPending();
    searchField.onChange("");
    setFilters({ q: undefined, page: 0 });
  };

  const active = Boolean(filters.q && filters.q.length > 0);

  return (
    <FilterBarShell
      dataSlot="borrowers-filter-bar"
      ariaLabel="Borrowers filters"
      className={className}
    >
      <FilterBarSearchField
        value={searchField.value}
        onChange={searchField.onChange}
        placeholder="Search by name, PAN, mobile, or email"
        ariaLabel="Search borrowers"
        dataSlot="borrowers-search"
        className="min-w-[260px]"
      />

      <div className="flex-1" />

      <FilterBarClearButton
        onClick={clearAll}
        disabled={!active}
        dataSlot="borrowers-filter-clear"
      />
    </FilterBarShell>
  );
}
