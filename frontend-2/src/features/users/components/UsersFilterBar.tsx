/**
 * Filter bar for `/users`.
 *
 * State is owned by the parent page (the URL is intentionally not bound —
 * the admin users surface is SYSTEM_ADMIN-only and not a deep-link target).
 * The bar emits the filter delta via `onChange`.
 */
import { Search, X } from "lucide-react";
import { useDebouncedControlledText } from "@/lib/hooks/use-debounced-controlled-text";
import { Button } from "@/components/ui/button";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { cn } from "@/lib/utils";
import type { Role } from "@/schemas/role";
import type { UserStatus } from "@/schemas/user";
import type { UsersListFilters } from "../types";

const SEARCH_DEBOUNCE_MS = 200;
const ALL_SENTINEL = "__all__";

const ROLE_OPTIONS: readonly { value: Role; label: string }[] = [
  { value: "SYSTEM_ADMIN", label: "System admin" },
  { value: "OPS_USER", label: "Ops user" },
  { value: "PRODUCT_ADMIN", label: "Product admin" },
  { value: "LSP_UI_READ", label: "LSP UI — read" },
  { value: "LSP_UI_WRITE", label: "LSP UI — write" },
  { value: "LSP_API_CLIENT", label: "LSP API client" },
];

const STATUS_OPTIONS: readonly { value: UserStatus; label: string }[] = [
  { value: "ACTIVE", label: "Active" },
  { value: "DISABLED", label: "Disabled" },
];

export interface LspOption {
  id: string;
  name: string;
}

export interface UsersFilterBarProps {
  filters: UsersListFilters;
  onChange: (next: UsersListFilters) => void;
  lspOptions: readonly LspOption[];
  className?: string;
}

export function UsersFilterBar({ filters, onChange, lspOptions, className }: UsersFilterBarProps) {
  const searchField = useDebouncedControlledText(
    filters.q,
    (q) => onChange({ ...filters, q, page: 0 }),
    SEARCH_DEBOUNCE_MS,
  );

  const setRole = (next: string | undefined) => {
    onChange({
      ...filters,
      role: next as Role | undefined,
      page: 0,
    });
  };

  const setStatus = (next: string | undefined) => {
    onChange({
      ...filters,
      status: next as UserStatus | undefined,
      page: 0,
    });
  };

  const setLsp = (next: string | undefined) => {
    onChange({
      ...filters,
      lspId: next,
      page: 0,
    });
  };

  const clearAll = () => {
    searchField.clearPending();
    searchField.onChange("");
    onChange({ page: 0 });
  };

  const active =
    Boolean(filters.q) ||
    Boolean(filters.role) ||
    Boolean(filters.status) ||
    Boolean(filters.lspId);

  return (
    <div
      data-slot="users-filter-bar"
      role="group"
      aria-label="User filters"
      className={cn(
        "border-border bg-surface flex flex-wrap items-center gap-2 rounded-md border p-2",
        className,
      )}
    >
      <Select
        value={filters.role ?? ALL_SENTINEL}
        onValueChange={(next) => setRole(next === ALL_SENTINEL ? undefined : next)}
      >
        <SelectTrigger
          size="sm"
          aria-label="Role filter"
          data-slot="users-role-filter"
          className="w-44"
        >
          <SelectValue placeholder="All roles" />
        </SelectTrigger>
        <SelectContent>
          <SelectItem value={ALL_SENTINEL}>All roles</SelectItem>
          {ROLE_OPTIONS.map((o) => (
            <SelectItem key={o.value} value={o.value}>
              {o.label}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>

      <Select
        value={filters.status ?? ALL_SENTINEL}
        onValueChange={(next) => setStatus(next === ALL_SENTINEL ? undefined : next)}
      >
        <SelectTrigger
          size="sm"
          aria-label="Status filter"
          data-slot="users-status-filter"
          className="w-36"
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

      <Select
        value={filters.lspId ?? ALL_SENTINEL}
        onValueChange={(next) => setLsp(next === ALL_SENTINEL ? undefined : next)}
      >
        <SelectTrigger
          size="sm"
          aria-label="LSP filter"
          data-slot="users-lsp-filter"
          className="w-48"
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
        <span className="sr-only">Search users</span>
        <Search
          aria-hidden="true"
          className="text-foreground-muted pointer-events-none absolute top-1/2 left-2.5 size-3.5 -translate-y-1/2"
        />
        <input
          type="search"
          data-slot="users-search"
          value={searchField.value}
          onChange={(e) => searchField.onChange(e.target.value)}
          placeholder="Search username or email"
          aria-label="Search users"
          className="border-border bg-surface text-foreground placeholder:text-foreground-muted focus-visible:border-ring focus-visible:ring-ring/50 h-8 w-full rounded-md border pr-2 pl-7.5 text-sm outline-none focus-visible:ring-[3px]"
        />
      </label>

      <Button
        type="button"
        variant="ghost"
        size="sm"
        onClick={clearAll}
        disabled={!active}
        data-slot="users-filter-clear"
        aria-label="Clear all filters"
        className="gap-1"
      >
        <X aria-hidden="true" className="size-3.5" />
        Clear
      </Button>
    </div>
  );
}
