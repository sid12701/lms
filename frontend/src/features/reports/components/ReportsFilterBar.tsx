/**
 * Filter bar for `/reports`.
 *
 * LSP scope uses the same name dropdown as loan applications and users.
 * Date filters use the shared dd/MM/yyyy picker (browser-locale independent).
 */
import { useMemo } from "react";
import { DatePickerField } from "@/components/app/data/DatePickerField";
import { FilterBarClearButton, FilterBarShell } from "@/components/app/data/FilterBarShell";
import { filterControlClass } from "@/components/app/data/filter-control";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { useLspOptions, toLspFilterOption } from "@/features/lsps/hooks/useLspOptions";
import { cn } from "@/lib/utils";
import type { ReportsPageFilters } from "../types";

const ALL_SENTINEL = "__all__";

export interface ReportsFilterBarProps {
  filters: ReportsPageFilters;
  onChange: (next: ReportsPageFilters) => void;
  className?: string;
}

export function ReportsFilterBar({ filters, onChange, className }: ReportsFilterBarProps) {
  const lspOptionsQuery = useLspOptions();
  const lspOptions = useMemo(
    () => (lspOptionsQuery.data ?? []).map(toLspFilterOption),
    [lspOptionsQuery.data],
  );

  const setDateFrom = (next: string | undefined) => {
    onChange({ ...filters, dateFrom: next && next !== "" ? next : null });
  };

  const setDateTo = (next: string | undefined) => {
    onChange({ ...filters, dateTo: next && next !== "" ? next : null });
  };

  const setLspId = (next: string) => {
    onChange({ ...filters, lspId: next === ALL_SENTINEL ? null : next });
  };

  const clearAll = () => {
    onChange({ lspId: null, dateFrom: null, dateTo: null });
  };

  const active = Boolean(filters.lspId) || Boolean(filters.dateFrom) || Boolean(filters.dateTo);

  return (
    <FilterBarShell
      dataSlot="reports-filter-bar"
      ariaLabel="Report filters"
      className={cn("items-end gap-3 p-3", className)}
    >
      <div className="flex min-w-[220px] flex-col gap-1">
        <span className="text-foreground-muted text-eyebrow uppercase">LSP</span>
        <Select value={filters.lspId ?? ALL_SENTINEL} onValueChange={setLspId}>
          <SelectTrigger
            aria-label="LSP filter"
            data-slot="reports-lsp-filter"
            data-filter-set={filters.lspId != null ? "true" : undefined}
            className={filterControlClass(filters.lspId != null, "w-full min-w-[220px]")}
          >
            <SelectValue placeholder="All LSPs" />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value={ALL_SENTINEL}>All LSPs</SelectItem>
            {lspOptions.map((o) => (
              <SelectItem key={o.value} value={o.value}>
                {o.label}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      <div className="flex min-w-[220px] flex-col gap-1">
        <span className="text-foreground-muted text-eyebrow uppercase">Disbursed from</span>
        <DatePickerField
          value={filters.dateFrom ?? undefined}
          onChange={setDateFrom}
          ariaLabel="Disbursed from"
          dataSlot="reports-date-from"
          className="min-w-[220px]"
        />
      </div>

      <div className="flex min-w-[220px] flex-col gap-1">
        <span className="text-foreground-muted text-eyebrow uppercase">Disbursed to</span>
        <DatePickerField
          value={filters.dateTo ?? undefined}
          onChange={setDateTo}
          ariaLabel="Disbursed to"
          dataSlot="reports-date-to"
          className="min-w-[220px]"
        />
      </div>

      <FilterBarClearButton
        onClick={clearAll}
        disabled={!active}
        dataSlot="reports-filter-clear"
        className="ml-auto"
      />
    </FilterBarShell>
  );
}
