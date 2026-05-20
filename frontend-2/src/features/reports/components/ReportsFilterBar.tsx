/**
 * Filter bar for `/reports`.
 *
 * Three controls — LSP id (plain text field accepting a UUID; LSPs admin
 * surface lands in Phase 9 so a select-of-LSPs hook is not yet available),
 * a From date input, and a To date input. State is owned by the parent
 * page; the bar emits the filter delta via `onChange`.
 *
 * The lspId input is intentionally a text input (not RHF) — it is a
 * filter, not a form submission, so a debounced commit on blur keeps the
 * query cache stable.
 */
import { Calendar, Search, X } from "lucide-react";
import { useEffect, useState } from "react";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import type { ReportsPageFilters } from "../types";

export interface ReportsFilterBarProps {
  filters: ReportsPageFilters;
  onChange: (next: ReportsPageFilters) => void;
  className?: string;
}

export function ReportsFilterBar({
  filters,
  onChange,
  className,
}: ReportsFilterBarProps) {
  const [lspIdDraft, setLspIdDraft] = useState<string>(filters.lspId ?? "");

  // Keep local draft in sync if parent resets filters.
  useEffect(() => {
    setLspIdDraft(filters.lspId ?? "");
  }, [filters.lspId]);

  const commitLspId = () => {
    const next = lspIdDraft.trim();
    if (next === (filters.lspId ?? "")) return;
    onChange({ ...filters, lspId: next === "" ? null : next });
  };

  const setDateFrom = (next: string) => {
    onChange({ ...filters, dateFrom: next === "" ? null : next });
  };

  const setDateTo = (next: string) => {
    onChange({ ...filters, dateTo: next === "" ? null : next });
  };

  const clearAll = () => {
    setLspIdDraft("");
    onChange({ lspId: null, dateFrom: null, dateTo: null });
  };

  const active =
    Boolean(filters.lspId) ||
    Boolean(filters.dateFrom) ||
    Boolean(filters.dateTo);

  return (
    <div
      data-slot="reports-filter-bar"
      role="group"
      aria-label="Report filters"
      className={cn(
        "border-border bg-surface flex flex-wrap items-end gap-3 rounded-md border p-3",
        className,
      )}
    >
      <label className="flex min-w-[260px] flex-1 flex-col gap-1">
        <span className="text-foreground-muted text-[11px] font-medium tracking-wide uppercase">
          LSP id
        </span>
        <span className="relative">
          <Search
            aria-hidden="true"
            className="text-foreground-muted pointer-events-none absolute top-1/2 left-2.5 size-3.5 -translate-y-1/2"
          />
          <input
            type="search"
            data-slot="reports-lsp-filter"
            value={lspIdDraft}
            onChange={(e) => setLspIdDraft(e.target.value)}
            onBlur={commitLspId}
            onKeyDown={(e) => {
              if (e.key === "Enter") {
                e.preventDefault();
                commitLspId();
              }
            }}
            placeholder="All LSPs (paste a UUID to scope)"
            aria-label="LSP id filter"
            className="border-border bg-surface text-foreground placeholder:text-foreground-muted focus-visible:border-ring focus-visible:ring-ring/50 h-9 w-full rounded-md border pl-8 pr-2 text-sm outline-none focus-visible:ring-[3px]"
          />
        </span>
      </label>

      <label className="flex flex-col gap-1">
        <span className="text-foreground-muted text-[11px] font-medium tracking-wide uppercase">
          From
        </span>
        <span className="relative">
          <Calendar
            aria-hidden="true"
            className="text-foreground-muted pointer-events-none absolute top-1/2 left-2.5 size-3.5 -translate-y-1/2"
          />
          <input
            type="date"
            data-slot="reports-date-from"
            value={filters.dateFrom ?? ""}
            onChange={(e) => setDateFrom(e.target.value)}
            aria-label="Date from"
            className="border-border bg-surface text-foreground focus-visible:border-ring focus-visible:ring-ring/50 h-9 w-44 rounded-md border pl-8 pr-2 text-sm outline-none focus-visible:ring-[3px]"
          />
        </span>
      </label>

      <label className="flex flex-col gap-1">
        <span className="text-foreground-muted text-[11px] font-medium tracking-wide uppercase">
          To
        </span>
        <span className="relative">
          <Calendar
            aria-hidden="true"
            className="text-foreground-muted pointer-events-none absolute top-1/2 left-2.5 size-3.5 -translate-y-1/2"
          />
          <input
            type="date"
            data-slot="reports-date-to"
            value={filters.dateTo ?? ""}
            onChange={(e) => setDateTo(e.target.value)}
            aria-label="Date to"
            className="border-border bg-surface text-foreground focus-visible:border-ring focus-visible:ring-ring/50 h-9 w-44 rounded-md border pl-8 pr-2 text-sm outline-none focus-visible:ring-[3px]"
          />
        </span>
      </label>

      <Button
        type="button"
        variant="ghost"
        size="sm"
        onClick={clearAll}
        disabled={!active}
        data-slot="reports-filter-clear"
        aria-label="Clear all filters"
        className="gap-1"
      >
        <X aria-hidden="true" className="size-3.5" />
        Clear
      </Button>
    </div>
  );
}
