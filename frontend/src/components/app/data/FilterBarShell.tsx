/**
 * Shared filter-bar chrome — Structure A: toolbar + applied chips.
 *
 * The audit measured three problems on `/loan-applications`, and this module is
 * the single fix for all of them:
 *
 *   1. **No shared control spec.** The bar mixed two heights ({24, 32}px) and
 *      two type sizes ({12, 14}px) on one row — 32px inputs at y=221 beside
 *      24px selects at y=225, so tops and bottoms never aligned. That 4px
 *      baseline mismatch is the mechanical cause of the "thin" feeling.
 *      `FILTER_CONTROL_CLASS` is now the only control spec: 28px, 12px text,
 *      control radius, one border token.
 *
 *   2. **No applied-state feedback.** With three filters active, a set control
 *      ("Disbursed") and an unset one ("All LSPs") rendered identical colour,
 *      fill, border and weight. In a lending queue, not knowing whether you are
 *      seeing the whole book or a slice is a correctness problem, not a
 *      cosmetic one. `filterControlClass(isSet)` tints set controls, and
 *      `FilterAppliedChips` states the full applied set with a result count and
 *      per-filter clear — which is what Carbon requires of a closed filter.
 *
 *   3. **A frame with no job.** The container was `bg-surface` + 1px border, in
 *      light `#fff` on a `#fff` page, so the fields were the same colour as the
 *      bar containing them and every edge was a hairline at 1.25:1. Controls
 *      now carry a fill and a 3:1 boundary (`--color-border-control`), which is
 *      what DESIGN.md specified for inputs all along — the bar had opted out.
 *
 * Filters stay *visible* in the toolbar rather than collapsing into a "Filters"
 * popover: Carbon is explicit that multi-category filters should never be put
 * inside a menu or dropdown.
 */
import { useState, type ReactNode } from "react";
import { Search, TriangleAlert, X } from "lucide-react";
import { Button } from "@/components/ui/button";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { filterControlClass } from "./filter-control";
import { prefersReducedMotion } from "@/lib/prefers-reduced-motion";
import { cn } from "@/lib/utils";

const FILTER_BAR_SHELL_CLASS = "border-border bg-surface rounded-container border";

export interface FilterBarShellProps {
  dataSlot: string;
  ariaLabel: string;
  className?: string;
  /**
   * `"bare"` drops the border and fill, keeping only the group semantics.
   *
   * For bars with a single control. `/borrowers` wrapped one search input in a
   * 1520px bordered rectangle: a frame around one field is chrome that states
   * nothing, and the border implied a group of filters that was not there.
   */
  variant?: "panel" | "bare";
  /** Optional leading label/chip (e.g. audit explorer "Filters"). */
  lead?: ReactNode;
  /**
   * Applied-filter summary row. Rendered under a divider, below the controls,
   * and only when there is something to say.
   */
  appliedChips?: ReactNode;
  /**
   * Warning row shown above the controls — e.g. filters the URL asked for that
   * could not be applied. Sits above rather than below because it qualifies
   * what the controls are about to show.
   */
  notice?: ReactNode;
  children: ReactNode;
}

export function FilterBarShell({
  dataSlot,
  ariaLabel,
  className,
  variant = "panel",
  lead,
  appliedChips,
  notice,
  children,
}: FilterBarShellProps) {
  return (
    <div
      data-slot={dataSlot}
      role="group"
      aria-label={ariaLabel}
      className={cn(variant === "panel" && FILTER_BAR_SHELL_CLASS, className)}
    >
      {notice}
      <div
        className={cn("flex flex-wrap items-center gap-2", variant === "panel" ? "p-2" : "py-0.5")}
      >
        {lead}
        {children}
      </div>
      {appliedChips}
    </div>
  );
}

export interface IgnoredFilterNoticeProps {
  /** Schema keys the URL supplied that could not be applied. */
  ignoredKeys: readonly string[];
  /** Maps a schema key to the label the user sees on its control. */
  labelFor: (key: string) => string;
  dataSlot: string;
}

/**
 * States which filters from the address bar were not applied.
 *
 * A deep link whose filter is dropped silently turns a slice of the book into
 * the whole book with no visible difference — in a lending queue that is a
 * correctness problem, not a cosmetic one. `role="status"` rather than `alert`:
 * it is worth announcing, but it does not interrupt.
 */
export function IgnoredFilterNotice({ ignoredKeys, labelFor, dataSlot }: IgnoredFilterNoticeProps) {
  if (ignoredKeys.length === 0) return null;
  const names = ignoredKeys.map(labelFor);
  const list =
    names.length === 1
      ? names[0]
      : `${names.slice(0, -1).join(", ")} and ${names[names.length - 1]}`;

  return (
    <div
      data-slot={dataSlot}
      role="status"
      className="border-warning/30 bg-warning/10 text-warning flex items-start gap-2 border-b px-3 py-2 text-xs"
    >
      <TriangleAlert aria-hidden="true" className="mt-px size-3.5 shrink-0" />
      <span>
        {names.length === 1 ? "This filter from the link" : "These filters from the link"} could not
        be applied and {names.length === 1 ? "was" : "were"} ignored: {list}. You are seeing every
        result that matches the remaining filters.
      </span>
    </div>
  );
}

/**
 * The shadcn `Select` primitive rejects an empty-string `<SelectItem>` value,
 * so "all" needs a sentinel that is translated back to `undefined`. Six filter
 * bars were each carrying their own copy of this dance; it lives here now.
 */
const ALL_SENTINEL = "__all__";

export interface FilterBarSelectOption {
  value: string;
  label: string;
}

export interface FilterBarSingleSelectProps {
  value: string | undefined;
  onChange: (next: string | undefined) => void;
  /** Shown as both the trigger placeholder and the "all" option's label. */
  placeholder: string;
  ariaLabel: string;
  options: readonly FilterBarSelectOption[];
  /** Custom item body — label remains available for assistive tech via option.label. */
  renderOption?: (option: FilterBarSelectOption) => ReactNode;
  dataSlot: string;
  className?: string;
}

/**
 * Single-select filter control that renders its set state.
 *
 * Single rather than multi on purpose: the list endpoints behind these bars
 * accept one value per filter, and a multi-select that silently applies only
 * the first choice misreports what the operator is looking at.
 */
export function FilterBarSingleSelect({
  value,
  onChange,
  placeholder,
  ariaLabel,
  options,
  renderOption,
  dataSlot,
  className = "w-40",
}: FilterBarSingleSelectProps) {
  const isSet = value !== undefined;
  return (
    <Select
      value={value ?? ALL_SENTINEL}
      onValueChange={(next) => onChange(next === ALL_SENTINEL ? undefined : next)}
    >
      <SelectTrigger
        aria-label={ariaLabel}
        data-slot={dataSlot}
        data-filter-set={isSet ? "true" : undefined}
        className={filterControlClass(isSet, className)}
      >
        <SelectValue placeholder={placeholder} />
      </SelectTrigger>
      <SelectContent>
        <SelectItem value={ALL_SENTINEL}>{placeholder}</SelectItem>
        {options.map((o) => (
          <SelectItem key={o.value} value={o.value} textValue={o.label}>
            {renderOption ? renderOption(o) : o.label}
          </SelectItem>
        ))}
      </SelectContent>
    </Select>
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
  const isSet = value.trim() !== "";
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
        data-filter-set={isSet ? "true" : undefined}
        className={filterControlClass(isSet, "w-full pr-2 pl-7.5")}
      />
    </label>
  );
}

/**
 * A named group of related controls — the disbursed-from / disbursed-to pair,
 * for example.
 *
 * The date pickers previously sat loose in the flex row, so which one set the
 * lower bound was a guess, and at 1440px they split across rows while at 1280px
 * they did not: grouping was a layout accident. A group is one flex item, so it
 * wraps as a unit at every width, and the legend names the pair once.
 */
export interface FilterBarFieldGroupProps {
  label: string;
  children: ReactNode;
  className?: string;
}

export function FilterBarFieldGroup({ label, children, className }: FilterBarFieldGroupProps) {
  return (
    <fieldset
      className={cn(
        "border-border rounded-control flex shrink-0 items-center gap-1.5 border py-0.5 pr-1.5 pl-2",
        className,
      )}
    >
      <legend className="sr-only">{label}</legend>
      <span aria-hidden="true" className="text-foreground-muted shrink-0 text-xs">
        {label}
      </span>
      {children}
    </fieldset>
  );
}

export interface AppliedFilter {
  /** Stable identity for the chip. */
  key: string;
  /** What the filter is — "Status", "LSP", "Disbursed". */
  label: string;
  /** What it is set to — "Disbursed", "Acme Finserv", "01/01/2026". */
  value: string;
  /** Removes just this filter. */
  onClear: () => void;
}

export interface FilterAppliedChipsProps {
  filters: readonly AppliedFilter[];
  onClearAll: () => void;
  /** Number of rows the current filter set matches. */
  resultCount?: number;
  /**
   * Plural noun for the count — "applications", "borrowers". Singularised by
   * dropping a trailing "s" when the count is exactly 1, which covers every
   * noun these bars use; pass `resultNounSingular` for anything irregular.
   */
  resultNoun?: string;
  /** Explicit singular, for nouns a trailing "s" cannot produce. */
  resultNounSingular?: string;
  dataSlot?: string;
}

const CHIP_CLASS =
  "border-primary/30 bg-primary/10 text-primary-tinted rounded-control inline-flex shrink-0 items-center gap-1 border py-0.5 pr-0.5 pl-1.5 text-xs";

/**
 * One applied-filter chip.
 *
 * Split out of the row so the enter animation can be fixed at *this chip's*
 * mount. Toggling the class on the shared list would restart the animation on
 * every sibling each time one filter was added, which is not what changed.
 */
function AppliedFilterChip({
  filter,
  dataSlot,
  animateOnMount,
}: {
  filter: AppliedFilter;
  dataSlot: string;
  animateOnMount: boolean;
}) {
  return (
    <span
      data-slot={`${dataSlot}-chip`}
      className={cn(CHIP_CLASS, animateOnMount && "animate-in fade-in-0 zoom-in-95 duration-150")}
    >
      <span className="text-foreground-muted">{filter.label}</span>
      <span className="font-medium">{filter.value}</span>
      <button
        type="button"
        onClick={filter.onClear}
        aria-label={`Remove ${filter.label} filter`}
        className="hover:bg-primary/20 focus-visible:ring-ring rounded-control p-0.5 outline-none focus-visible:ring-2"
      >
        <X aria-hidden="true" className="size-3" />
      </button>
    </span>
  );
}

/**
 * The applied-filter summary.
 *
 * Answers the question the old bar could not: *what am I actually looking at?*
 * Every applied filter is named with its value and can be removed individually
 * — the old bar offered only an all-or-nothing "Clear".
 */
export function FilterAppliedChips({
  filters,
  onClearAll,
  resultCount,
  resultNoun = "results",
  resultNounSingular,
  dataSlot = "filter-applied-chips",
}: FilterAppliedChipsProps) {
  /*
   * A chip fades in when the operator adds a filter — a state change where
   * continuity aids comprehension — but not when the row arrives already
   * populated. Filters restored from the URL are a page load, and page-load
   * choreography is the one thing DESIGN.md rules out outright.
   *
   * There is deliberately no exit animation. A cleared chip is removed on the
   * spot, because a chip that outlives its filter misstates what the operator
   * is looking at; instant removal is also, trivially, faster than the 150ms
   * entrance.
   */
  const reducedMotion = prefersReducedMotion();
  /*
   * The filters this row was born with. A lazy `useState` initializer, not a
   * ref: refs may not be read during render, and a mounted-flag set from an
   * effect would be false for the first paint of every chip and true forever
   * after — which is the same thing, only unreadable during render.
   *
   * Trade-off: a filter that is cleared and then re-applied stays in this set,
   * so it re-enters without the fade. Tracking that would need per-key
   * bookkeeping across renders for a 150ms flourish, which is more machinery
   * than the finding is worth.
   */
  const [initialFilterKeys] = useState(() => new Set(filters.map((filter) => filter.key)));

  if (filters.length === 0) return null;

  const noun =
    resultCount === 1 ? (resultNounSingular ?? resultNoun.replace(/s$/, "")) : resultNoun;

  return (
    <div
      data-slot={dataSlot}
      className="border-border flex flex-wrap items-center gap-1.5 border-t px-2 py-1.5"
    >
      <span className="text-foreground-muted shrink-0 text-xs">
        {filters.length === 1 ? "1 filter" : `${filters.length} filters`}
      </span>
      {filters.map((filter) => (
        <AppliedFilterChip
          key={filter.key}
          filter={filter}
          dataSlot={dataSlot}
          animateOnMount={!initialFilterKeys.has(filter.key) && !reducedMotion}
        />
      ))}

      <div className="ml-auto flex shrink-0 items-center gap-2">
        {resultCount === undefined ? null : (
          <span
            data-slot={`${dataSlot}-count`}
            aria-live="polite"
            className="text-foreground-muted text-xs tabular-nums"
          >
            {resultCount.toLocaleString("en-IN")} {noun}
          </span>
        )}
        <Button
          type="button"
          variant="ghost"
          size="xs"
          onClick={onClearAll}
          data-slot={`${dataSlot}-clear-all`}
        >
          Clear all
        </Button>
      </div>
    </div>
  );
}

export interface FilterBarClearButtonProps {
  onClick: () => void;
  disabled?: boolean;
  dataSlot: string;
  /** Usually `ml-auto` — bars push this to the trailing edge. */
  className?: string;
}

export function FilterBarClearButton({
  onClick,
  disabled = false,
  dataSlot,
  className,
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
      className={cn("gap-1", className)}
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
      className="border-border rounded-control flex items-center gap-1 border p-0.5"
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
