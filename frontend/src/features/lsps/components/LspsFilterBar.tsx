/**
 * Filter bar for `/lsps` admin list.
 *
 * Mirrors the AlertsFilterBar shape but with single-status select + free-text
 * search by code/name. URL-bound from the page via `useSearchParams`.
 */
import { useDebouncedControlledText } from "@/lib/hooks/use-debounced-controlled-text";
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
import type { LspsListFilters } from "../types";
import type { LspStatus } from "@/schemas/lsp";

const SEARCH_DEBOUNCE_MS = 200;

const ALL_SENTINEL = "__all__";

const STATUS_OPTIONS: readonly { value: LspStatus; label: string }[] = [
  { value: "ACTIVE", label: "Active" },
  { value: "SUSPENDED", label: "Suspended" },
  { value: "INACTIVE", label: "Inactive" },
];

export interface LspsFilterBarProps {
  filters: LspsListFilters;
  onChange: (next: LspsListFilters) => void;
  className?: string;
}

export function LspsFilterBar({ filters, onChange, className }: LspsFilterBarProps) {
  const searchField = useDebouncedControlledText(
    filters.q,
    (q) => onChange({ ...filters, q, page: 0 }),
    SEARCH_DEBOUNCE_MS,
  );

  const setStatus = (next: string | undefined) => {
    onChange({
      ...filters,
      status: next === undefined ? undefined : (next as LspStatus),
      page: 0,
    });
  };

  const clearAll = () => {
    searchField.clearPending();
    searchField.onChange("");
    onChange({ page: 0 });
  };

  const active = Boolean(filters.q) || Boolean(filters.status);

  return (
    <FilterBarShell dataSlot="lsps-filter-bar" ariaLabel="LSP filters" className={className}>
      <Select
        value={filters.status ?? ALL_SENTINEL}
        onValueChange={(next) => setStatus(next === ALL_SENTINEL ? undefined : next)}
      >
        <SelectTrigger
          size="sm"
          aria-label="Status filter"
          data-slot="lsps-status-filter"
          className="w-40"
        >
          <SelectValue placeholder="All statuses" />
        </SelectTrigger>
        <SelectContent>
          <SelectItem value={ALL_SENTINEL}>All statuses</SelectItem>
          {STATUS_OPTIONS.map((o) => (
            <SelectItem key={o.value} value={o.value}>
              {o.label}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>

      <FilterBarSearchField
        value={searchField.value}
        onChange={searchField.onChange}
        placeholder="Search code or name"
        ariaLabel="Search LSPs"
        dataSlot="lsps-search"
      />

      <FilterBarClearButton onClick={clearAll} disabled={!active} dataSlot="lsps-filter-clear" />
    </FilterBarShell>
  );
}
