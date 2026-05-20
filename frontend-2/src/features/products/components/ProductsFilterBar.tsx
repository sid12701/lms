/**
 * Filter bar for `/products`.
 *
 * Page-local state (URL-bound by the parent page). Status tabs +
 * code/name search. Density: comfortable.
 */
import { Search, X } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import type { ProductStatus } from "@/schemas/product";
import type { ProductsListFilters } from "../types";

const SEARCH_DEBOUNCE_MS = 200;

const STATUS_TABS: readonly { value: ProductStatus | "ALL"; label: string }[] = [
  { value: "ALL", label: "All" },
  { value: "ACTIVE", label: "Active" },
  { value: "INACTIVE", label: "Inactive" },
];

export interface ProductsFilterBarProps {
  filters: ProductsListFilters;
  onChange: (next: ProductsListFilters) => void;
  className?: string;
}

export function ProductsFilterBar({
  filters,
  onChange,
  className,
}: ProductsFilterBarProps) {
  const [search, setSearch] = useState<string>(filters.q ?? "");
  const debounceRef = useRef<number | null>(null);

  useEffect(() => {
    setSearch(filters.q ?? "");
  }, [filters.q]);

  useEffect(() => {
    return () => {
      if (debounceRef.current !== null) window.clearTimeout(debounceRef.current);
    };
  }, []);

  const onSearchChange = (next: string) => {
    setSearch(next);
    if (debounceRef.current !== null) window.clearTimeout(debounceRef.current);
    debounceRef.current = window.setTimeout(() => {
      const trimmed = next.trim();
      onChange({
        ...filters,
        q: trimmed === "" ? undefined : trimmed,
        page: 0,
      });
    }, SEARCH_DEBOUNCE_MS);
  };

  const setStatusTab = (next: ProductStatus | "ALL") => {
    onChange({
      ...filters,
      status: next === "ALL" ? undefined : next,
      page: 0,
    });
  };

  const clearAll = () => {
    if (debounceRef.current !== null) window.clearTimeout(debounceRef.current);
    setSearch("");
    onChange({ page: 0 });
  };

  const active = Boolean(filters.q) || Boolean(filters.status);
  const activeTab: ProductStatus | "ALL" = filters.status ?? "ALL";

  return (
    <div
      data-slot="products-filter-bar"
      role="group"
      aria-label="Product filters"
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
            data-slot={`products-tab-${tab.value.toLowerCase()}`}
            onClick={() => setStatusTab(tab.value)}
          >
            {tab.label}
          </Button>
        ))}
      </div>

      <label className="relative min-w-[220px] flex-1">
        <span className="sr-only">Search products</span>
        <Search
          aria-hidden="true"
          className="text-foreground-muted pointer-events-none absolute top-1/2 left-2.5 size-3.5 -translate-y-1/2"
        />
        <input
          type="search"
          data-slot="products-search"
          value={search}
          onChange={(e) => onSearchChange(e.target.value)}
          placeholder="Search code or name"
          aria-label="Search products"
          className="border-border bg-surface text-foreground placeholder:text-foreground-muted focus-visible:border-ring focus-visible:ring-ring/50 h-8 w-full rounded-md border pl-7.5 pr-2 text-sm outline-none focus-visible:ring-[3px]"
        />
      </label>

      <Button
        type="button"
        variant="ghost"
        size="sm"
        onClick={clearAll}
        disabled={!active}
        data-slot="products-filter-clear"
        aria-label="Clear all filters"
        className="gap-1"
      >
        <X aria-hidden="true" className="size-3.5" />
        Clear
      </Button>
    </div>
  );
}
