/**
 * Filter bar for `/lsps` admin list.
 *
 * Mirrors the AlertsFilterBar shape but with single-status select + free-text
 * search by code/name. URL-bound from the page via `useSearchParams`.
 */
import { Search, X } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { Button } from "@/components/ui/button";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { cn } from "@/lib/utils";
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

export function LspsFilterBar({
  filters,
  onChange,
  className,
}: LspsFilterBarProps) {
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

  const setStatus = (next: string | undefined) => {
    onChange({
      ...filters,
      status: next === undefined ? undefined : (next as LspStatus),
      page: 0,
    });
  };

  const clearAll = () => {
    if (debounceRef.current !== null) window.clearTimeout(debounceRef.current);
    setSearch("");
    onChange({ page: 0 });
  };

  const active = Boolean(filters.q) || Boolean(filters.status);

  return (
    <div
      data-slot="lsps-filter-bar"
      role="group"
      aria-label="LSP filters"
      className={cn(
        "border-border bg-surface flex flex-wrap items-center gap-2 rounded-md border p-2",
        className,
      )}
    >
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

      <label className="relative min-w-[200px] flex-1">
        <span className="sr-only">Search LSPs</span>
        <Search
          aria-hidden="true"
          className="text-foreground-muted pointer-events-none absolute top-1/2 left-2.5 size-3.5 -translate-y-1/2"
        />
        <input
          type="search"
          data-slot="lsps-search"
          value={search}
          onChange={(e) => onSearchChange(e.target.value)}
          placeholder="Search code or name"
          aria-label="Search LSPs"
          className="border-border bg-surface text-foreground placeholder:text-foreground-muted focus-visible:border-ring focus-visible:ring-ring/50 h-8 w-full rounded-md border pl-7.5 pr-2 text-sm outline-none focus-visible:ring-[3px]"
        />
      </label>

      <Button
        type="button"
        variant="ghost"
        size="sm"
        onClick={clearAll}
        disabled={!active}
        data-slot="lsps-filter-clear"
        aria-label="Clear all filters"
        className="gap-1"
      >
        <X aria-hidden="true" className="size-3.5" />
        Clear
      </Button>
    </div>
  );
}
