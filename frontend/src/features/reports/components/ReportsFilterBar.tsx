/**
 * Filter bar for `/reports`.
 *
 * LSP scope uses the same name dropdown as loan applications and users.
 * Date filters use the shared dd/MM/yyyy picker (browser-locale independent).
 */
import { useEffect, useState } from "react";
import { DatePickerField } from "@/components/app/data/DatePickerField";
import { FilterBarClearButton, FilterBarShell } from "@/components/app/data/FilterBarShell";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { listLspOptions } from "@/features/lsps/options";
import { cn } from "@/lib/utils";
import type { ReportsPageFilters } from "../types";

const ALL_SENTINEL = "__all__";

export interface ReportsFilterBarProps {
  filters: ReportsPageFilters;
  onChange: (next: ReportsPageFilters) => void;
  className?: string;
}

export function ReportsFilterBar({ filters, onChange, className }: ReportsFilterBarProps) {
  const [lspOptions, setLspOptions] = useState<readonly { value: string; label: string }[]>([]);

  useEffect(() => {
    let cancelled = false;
    void listLspOptions()
      .then((rows) => {
        if (cancelled) return;
        setLspOptions(
          rows.map((row) => ({
            value: row.id,
            label: `${row.name} (${row.code})`,
          })),
        );
      })
      .catch(() => {
        if (!cancelled) setLspOptions([]);
      });
    return () => {
      cancelled = true;
    };
  }, []);

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
        <span className="text-foreground-muted text-[11px] font-medium tracking-wide uppercase">
          LSP
        </span>
        <Select value={filters.lspId ?? ALL_SENTINEL} onValueChange={setLspId}>
          <SelectTrigger
            size="sm"
            aria-label="LSP filter"
            data-slot="reports-lsp-filter"
            className="w-full min-w-[220px]"
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
        <span className="text-foreground-muted text-[11px] font-medium tracking-wide uppercase">
          Disbursed from
        </span>
        <DatePickerField
          value={filters.dateFrom ?? undefined}
          onChange={setDateFrom}
          ariaLabel="Disbursed from"
          dataSlot="reports-date-from"
          className="min-w-[220px] [&_button]:h-9"
        />
      </div>

      <div className="flex min-w-[220px] flex-col gap-1">
        <span className="text-foreground-muted text-[11px] font-medium tracking-wide uppercase">
          Disbursed to
        </span>
        <DatePickerField
          value={filters.dateTo ?? undefined}
          onChange={setDateTo}
          ariaLabel="Disbursed to"
          dataSlot="reports-date-to"
          className="min-w-[220px] [&_button]:h-9"
        />
      </div>

      <div className="flex-1" />

      <FilterBarClearButton onClick={clearAll} disabled={!active} dataSlot="reports-filter-clear" />
    </FilterBarShell>
  );
}
