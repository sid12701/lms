/**
 * Filter bar for `/api-clients`.
 *
 * State owned by the parent page (URL is intentionally not bound — admin
 * surfaces are not deep-link targets). Emits the next filter snapshot via
 * `onChange`.
 */
import { useDebouncedControlledText } from "@/lib/hooks/use-debounced-controlled-text";
import {
  FilterBarClearButton,
  FilterBarSearchField,
  FilterBarShell,
  FilterBarStatusTabs,
} from "@/components/app/data/FilterBarShell";
import { filterControlClass } from "@/components/app/data/filter-control";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import type { ApiClientStatus } from "@/schemas/user";
import type { ApiClientsListFilters } from "../types";

const SEARCH_DEBOUNCE_MS = 200;

const STATUS_TABS: readonly { value: ApiClientStatus | "ALL"; label: string }[] = [
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
  const searchField = useDebouncedControlledText(
    filters.q,
    (q) => onChange({ ...filters, q, page: 0 }),
    SEARCH_DEBOUNCE_MS,
  );

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

  const clearAll = () => {
    searchField.clearPending();
    searchField.onChange("");
    onChange({ page: 0 });
  };

  const active = Boolean(filters.q) || Boolean(filters.status) || Boolean(filters.lspId);

  const activeTab: ApiClientStatus | "ALL" = filters.status ?? "ALL";

  return (
    <FilterBarShell
      dataSlot="api-clients-filter-bar"
      ariaLabel="API client filters"
      className={className}
    >
      <FilterBarStatusTabs
        tabs={STATUS_TABS}
        active={activeTab}
        onSelect={setStatusTab}
        dataSlotPrefix="api-clients-tab"
      />

      <Select
        value={filters.lspId ?? ALL_SENTINEL}
        onValueChange={(next) => setLspId(next === ALL_SENTINEL ? undefined : next)}
      >
        <SelectTrigger
          aria-label="LSP filter"
          data-slot="api-clients-lsp-filter"
          data-filter-set={filters.lspId !== undefined ? "true" : undefined}
          className={filterControlClass(filters.lspId !== undefined, "w-56")}
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

      <FilterBarSearchField
        value={searchField.value}
        onChange={searchField.onChange}
        placeholder="Search by name or client id"
        ariaLabel="Search API clients"
        dataSlot="api-clients-search"
      />

      <FilterBarClearButton
        onClick={clearAll}
        disabled={!active}
        dataSlot="api-clients-filter-clear"
      />
    </FilterBarShell>
  );
}
