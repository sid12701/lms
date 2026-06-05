/**
 * Filter bar for `/products`.
 *
 * Page-local state (URL-bound by the parent page). Status tabs +
 * code/name search. Density: comfortable.
 */
import { useDebouncedControlledText } from "@/lib/hooks/use-debounced-controlled-text";
import {
  FilterBarClearButton,
  FilterBarSearchField,
  FilterBarShell,
  FilterBarStatusTabs,
} from "@/components/app/data/FilterBarShell";
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

export function ProductsFilterBar({ filters, onChange, className }: ProductsFilterBarProps) {
  const searchField = useDebouncedControlledText(
    filters.q,
    (q) => onChange({ ...filters, q, page: 0 }),
    SEARCH_DEBOUNCE_MS,
  );

  const setStatusTab = (next: ProductStatus | "ALL") => {
    onChange({
      ...filters,
      status: next === "ALL" ? undefined : next,
      page: 0,
    });
  };

  const clearAll = () => {
    searchField.clearPending();
    searchField.onChange("");
    onChange({ page: 0 });
  };

  const active = Boolean(filters.q) || Boolean(filters.status);
  const activeTab: ProductStatus | "ALL" = filters.status ?? "ALL";

  return (
    <FilterBarShell
      dataSlot="products-filter-bar"
      ariaLabel="Product filters"
      className={className}
    >
      <FilterBarStatusTabs
        tabs={STATUS_TABS}
        active={activeTab}
        onSelect={setStatusTab}
        dataSlotPrefix="products-tab"
      />

      <FilterBarSearchField
        value={searchField.value}
        onChange={searchField.onChange}
        placeholder="Search code or name"
        ariaLabel="Search products"
        dataSlot="products-search"
        className="min-w-[220px]"
      />

      <FilterBarClearButton
        onClick={clearAll}
        disabled={!active}
        dataSlot="products-filter-clear"
      />
    </FilterBarShell>
  );
}
