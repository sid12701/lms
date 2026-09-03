import { X } from "lucide-react";
import type { ReactNode } from "react";
import { z } from "zod";
import { Button } from "@/components/ui/button";
import { useUrlFilters } from "@/lib/url-state";
import { cn } from "@/lib/utils";

type AnyZodObject = z.ZodObject<z.ZodRawShape>;

export interface FilterBarProps<T extends AnyZodObject> {
  /** Zod schema describing the URL-bound filter shape. */
  schema: T;
  /**
   * Render-prop receiving the active filter snapshot + a typed setter so
   * each filter input (search, status chips, date pickers, …) can be
   * wired by the consuming page without `FilterBar` knowing the shape.
   */
  children: (api: {
    filters: Partial<z.infer<T>>;
    setFilters: (next: Partial<z.infer<T>>) => void;
  }) => ReactNode;
  /** Optional left-hand label / heading rendered before the inputs. */
  label?: string;
  /** Hide the "Clear all" button (e.g. when filters are mandatory). */
  hideClear?: boolean;
  /** Fired after the URL is reset; parent may invalidate caches. */
  onClear?: () => void;
  className?: string;
}

/**
 * Layout container that composes filter inputs and wires them through
 * `useUrlFilters`. The inputs themselves live in features / other primitives;
 * `FilterBar` only handles layout, the URL round-trip, and the "Clear all"
 * action. Any active filter is cleared by writing the schema's empty object
 * back to the URL.
 *
 * Active-filter detection inspects the parsed `filters` object and treats
 * `undefined`, empty strings, and empty arrays as "no filter set". We expose
 * `clearableKeys` via the count so the button can be disabled when nothing
 * is set, avoiding a spurious URL update.
 */
export function FilterBar<T extends AnyZodObject>({
  schema,
  children,
  label,
  hideClear = false,
  onClear,
  className,
}: FilterBarProps<T>) {
  const [filters, setFilters] = useUrlFilters(schema);

  const activeKeys = Object.entries(filters).filter(([, value]) => {
    if (value === undefined || value === null || value === "") return false;
    if (Array.isArray(value)) return value.length > 0;
    return true;
  });

  const clearAll = () => {
    const empty = Object.fromEntries(
      Object.keys(schema.shape).map((k) => [k, undefined]),
    ) as Partial<z.infer<T>>;
    setFilters(empty);
    onClear?.();
  };

  return (
    <div
      data-slot="filter-bar"
      role="group"
      aria-label={label ?? "Filters"}
      className={cn(
        "border-border bg-surface rounded-container flex flex-wrap items-center gap-2 border p-2",
        className,
      )}
    >
      {label ? (
        <span className="text-foreground-muted px-1 text-xs font-medium tracking-wide uppercase">
          {label}
        </span>
      ) : null}
      <div className="flex flex-1 flex-wrap items-center gap-2">
        {children({ filters, setFilters })}
      </div>
      {hideClear ? null : (
        <Button
          type="button"
          variant="ghost"
          size="sm"
          onClick={clearAll}
          disabled={activeKeys.length === 0}
          data-slot="filter-bar-clear"
          aria-label="Clear all filters"
          className="gap-1"
        >
          <X aria-hidden="true" className="size-3.5" />
          Clear
          {activeKeys.length > 0 ? (
            <span className="text-foreground-muted ml-1 text-xs">({activeKeys.length})</span>
          ) : null}
        </Button>
      )}
    </div>
  );
}
