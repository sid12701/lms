import type { ReactNode } from "react";
import { Search, X } from "lucide-react";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

const FILTER_BAR_SHELL_CLASS =
  "border-border bg-surface flex flex-wrap items-center gap-2 rounded-md border p-2";

const FILTER_BAR_SEARCH_INPUT_CLASS =
  "border-border bg-surface text-foreground placeholder:text-foreground-muted focus-visible:border-ring focus-visible:ring-ring/50 h-8 w-full rounded-md border pr-2 pl-7.5 text-sm outline-none focus-visible:ring-[3px]";

export interface FilterBarShellProps {
  dataSlot: string;
  ariaLabel: string;
  className?: string;
  /** Optional leading label/chip (e.g. audit explorer "Filters"). */
  lead?: ReactNode;
  children: ReactNode;
}

export function FilterBarShell({
  dataSlot,
  ariaLabel,
  className,
  lead,
  children,
}: FilterBarShellProps) {
  return (
    <div
      data-slot={dataSlot}
      role="group"
      aria-label={ariaLabel}
      className={cn(FILTER_BAR_SHELL_CLASS, className)}
    >
      {lead}
      {children}
    </div>
  );
}

export interface FilterBarSearchFieldProps {
  value: string;
  onChange: (value: string) => void;
  placeholder: string;
  ariaLabel: string;
  dataSlot: string;
  className?: string;
}

export function FilterBarSearchField({
  value,
  onChange,
  placeholder,
  ariaLabel,
  dataSlot,
  className,
}: FilterBarSearchFieldProps) {
  return (
    <label className={cn("relative min-w-[200px] flex-1", className)}>
      <span className="sr-only">{ariaLabel}</span>
      <Search
        aria-hidden="true"
        className="text-foreground-muted pointer-events-none absolute top-1/2 left-2.5 size-3.5 -translate-y-1/2"
      />
      <input
        type="search"
        id={dataSlot}
        name={dataSlot}
        data-slot={dataSlot}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={placeholder}
        aria-label={ariaLabel}
        className={FILTER_BAR_SEARCH_INPUT_CLASS}
      />
    </label>
  );
}

export interface FilterBarClearButtonProps {
  onClick: () => void;
  disabled?: boolean;
  dataSlot: string;
}

export function FilterBarClearButton({
  onClick,
  disabled = false,
  dataSlot,
}: FilterBarClearButtonProps) {
  return (
    <Button
      type="button"
      variant="ghost"
      size="sm"
      onClick={onClick}
      disabled={disabled}
      data-slot={dataSlot}
      aria-label="Clear all filters"
      className="gap-1"
    >
      <X aria-hidden="true" className="size-3.5" />
      Clear
    </Button>
  );
}

export interface FilterBarStatusTab<T extends string> {
  value: T;
  label: string;
}

export interface FilterBarStatusTabsProps<T extends string> {
  tabs: readonly FilterBarStatusTab<T>[];
  active: T;
  onSelect: (value: T) => void;
  ariaLabel?: string;
  dataSlotPrefix: string;
}

export function FilterBarStatusTabs<T extends string>({
  tabs,
  active,
  onSelect,
  ariaLabel = "Status",
  dataSlotPrefix,
}: FilterBarStatusTabsProps<T>) {
  return (
    <div
      role="tablist"
      aria-label={ariaLabel}
      className="border-border flex items-center gap-1 rounded-md border p-0.5"
    >
      {tabs.map((tab) => (
        <Button
          key={tab.value}
          type="button"
          role="tab"
          aria-selected={active === tab.value}
          variant={active === tab.value ? "default" : "ghost"}
          size="sm"
          data-slot={`${dataSlotPrefix}-${String(tab.value).toLowerCase()}`}
          onClick={() => onSelect(tab.value)}
        >
          {tab.label}
        </Button>
      ))}
    </div>
  );
}
