/**
 * Filter bar for `/api-clients`.
 *
 * State owned by the parent page (URL is intentionally not bound — admin
 * surfaces are not deep-link targets). Emits the next filter snapshot via
 * `onChange`.
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
import type { ApiClientStatus } from "@/schemas/user";
import type { ApiClientsListFilters } from "../types";

const SEARCH_DEBOUNCE_MS = 200;

const STATUS_TABS: readonly { value: ApiClientStatus | "ALL"; label: string }[] =
  [
    { value: "ALL", label: "All" },
    { value: "ACTIVE", label: "Active" },
    { value: "DISABLED", label: "Disabled" },
  ];

const ALL_SENTINEL = "__all__";

export interface LspOption {
  id: string;
  name: string;
}

export interface ApiClientsFilterBarProps {
  filters: ApiClientsListFilters;
  onChange: (next: ApiClientsListFilters) => void;
  /** LSP options for the dropdown (resolved from `db.lsps`). */
  lspOptions: readonly LspOption[];
  className?: string;
}

export function ApiClientsFilterBar({
  filters,
  onChange,
  lspOptions,
  className,
}: ApiClientsFilterBarProps) {
  const [search, setSearch] = useState<string>(filters.q ?? "");
  const debounceRef = useRef<number | null>(null);

  useEffect(() => {
    setSearch(filters.q ?? "");
  }, [filters.q]);

  useEffect(() => {
    return () => {
      if (debounceRef.current !== null) {
        window.clearTimeout(debounceRef.current);
      }
    };
  }, []);

  const setStatusTab = (next: ApiClientStatus | "ALL") => {
    onChange({
      ...filters,
      status: next === "ALL" ? undefined : next,
      page: 0,
    });
  };

  const setLspId = (next: string | undefined) => {
    onChange({ ...filters, lspId: next, page: 0 });
  };

  const onSearchChange = (next: string) => {
    setSearch(next);
    if (debounceRef.current !== null) {
      window.clearTimeout(debounceRef.current);
    }
    debounceRef.current = window.setTimeout(() => {
      const trimmed = next.trim();
      onChange({
        ...filters,
        q: trimmed === "" ? undefined : trimmed,
        page: 0,
      });
    }, SEARCH_DEBOUNCE_MS);
  };

  const clearAll = () => {
    if (debounceRef.current !== null) {
      window.clearTimeout(debounceRef.current);
    }
    setSearch("");
    onChange({ page: 0 });
  };

  const active =
    Boolean(filters.q) || Boolean(filters.status) || Boolean(filters.lspId);

  const activeTab: ApiClientStatus | "ALL" = filters.status ?? "ALL";

  return (
    <div
      data-slot="api-clients-filter-bar"
      role="group"
      aria-label="API client filters"
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
            data-slot={`api-clients-tab-${tab.value.toLowerCase()}`}
            onClick={() => setStatusTab(tab.value)}
          >
            {tab.label}
          </Button>
        ))}
      </div>

      <Select
        value={filters.lspId ?? ALL_SENTINEL}
        onValueChange={(next) =>
          setLspId(next === ALL_SENTINEL ? undefined : next)
        }
      >
        <SelectTrigger
          size="sm"
          aria-label="LSP filter"
          data-slot="api-clients-lsp-filter"
          className="w-56"
        >
          <SelectValue placeholder="All LSPs" />
        </SelectTrigger>
        <SelectContent>
          <SelectItem value={ALL_SENTINEL}>All LSPs</SelectItem>
          {lspOptions.map((o) => (
            <SelectItem key={o.id} value={o.id}>
              {o.name}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>

      <label className="relative min-w-[200px] flex-1">
        <span className="sr-only">Search API clients</span>
        <Search
          aria-hidden="true"
          className="text-foreground-muted pointer-events-none absolute top-1/2 left-2.5 size-3.5 -translate-y-1/2"
        />
        <input
          type="search"
          data-slot="api-clients-search"
          value={search}
          onChange={(e) => onSearchChange(e.target.value)}
          placeholder="Search by name or client id"
          aria-label="Search API clients"
          className="border-border bg-surface text-foreground placeholder:text-foreground-muted focus-visible:border-ring focus-visible:ring-ring/50 h-8 w-full rounded-md border pl-7.5 pr-2 text-sm outline-none focus-visible:ring-[3px]"
        />
      </label>

      <Button
        type="button"
        variant="ghost"
        size="sm"
        onClick={clearAll}
        disabled={!active}
        data-slot="api-clients-filter-clear"
        aria-label="Clear all filters"
        className="gap-1"
      >
        <X aria-hidden="true" className="size-3.5" />
        Clear
      </Button>
    </div>
  );
}
