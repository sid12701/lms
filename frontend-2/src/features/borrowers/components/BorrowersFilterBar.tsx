/**
 * URL-bound search bar for the `/borrowers` directory.
 *
 * The single text input drives a case-insensitive substring search
 * across the borrower's name, PAN, mobile, and email. We debounce the
 * input by 200ms before it reaches the URL so the cache key doesn't
 * churn with every keystroke.
 */
import { useEffect, useRef, useState } from "react";
import { Search, X } from "lucide-react";
import { Button } from "@/components/ui/button";
import { useUrlFilters } from "@/lib/url-state";
import { cn } from "@/lib/utils";
import { BorrowerListFilters } from "../list-types";

const SEARCH_DEBOUNCE_MS = 200;

export interface BorrowersFilterBarProps {
  className?: string;
}

export function BorrowersFilterBar({ className }: BorrowersFilterBarProps) {
  const [filters, setFilters] = useUrlFilters(BorrowerListFilters);
  const [search, setSearch] = useState(filters.q ?? "");
  const debounceRef = useRef<number | null>(null);

  // Keep the input in sync if filters arrive from a back/forward navigation.
  useEffect(() => {
    setSearch(filters.q ?? "");
  }, [filters.q]);

  const onSearchChange = (next: string) => {
    setSearch(next);
    if (debounceRef.current != null) window.clearTimeout(debounceRef.current);
    debounceRef.current = window.setTimeout(() => {
      const trimmed = next.trim();
      setFilters({
        q: trimmed === "" ? undefined : trimmed,
        page: 0,
      });
    }, SEARCH_DEBOUNCE_MS);
  };

  const clearAll = () => {
    setSearch("");
    setFilters({ q: undefined, page: 0 });
  };

  const active = Boolean(filters.q && filters.q.length > 0);

  return (
    <div
      data-slot="borrowers-filter-bar"
      role="group"
      aria-label="Borrowers filters"
      className={cn(
        "border-border bg-surface flex flex-wrap items-center gap-2 rounded-md border p-2",
        className,
      )}
    >
      <label className="relative min-w-[260px] flex-1">
        <span className="sr-only">Search borrowers</span>
        <Search
          aria-hidden="true"
          className="text-foreground-muted pointer-events-none absolute top-1/2 left-2.5 size-3.5 -translate-y-1/2"
        />
        <input
          type="search"
          data-slot="borrowers-search"
          value={search}
          onChange={(e) => onSearchChange(e.target.value)}
          placeholder="Search by name, PAN, mobile, or email"
          aria-label="Search borrowers"
          className="border-border bg-surface text-foreground placeholder:text-foreground-muted focus-visible:border-ring focus-visible:ring-ring/50 h-8 w-full rounded-md border pr-2 pl-7.5 text-sm outline-none focus-visible:ring-[3px]"
        />
      </label>

      {active ? (
        <Button
          type="button"
          variant="ghost"
          size="sm"
          onClick={clearAll}
          aria-label="Clear filters"
          className="gap-1"
        >
          <X aria-hidden="true" className="size-3.5" />
          Clear
        </Button>
      ) : null}
    </div>
  );
}
