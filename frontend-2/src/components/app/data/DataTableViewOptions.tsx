import { Check, Settings2 } from "lucide-react";
import type { Table } from "@tanstack/react-table";
import { Button } from "@/components/ui/button";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { cn } from "@/lib/utils";

export interface DataTableViewOptionsProps<TData> {
  table: Table<TData>;
  /** Button label (also used for aria-label). */
  label?: string;
  className?: string;
}

/**
 * Popover-driven column visibility toggle. Lists every TanStack column that
 * opts into hiding (`column.getCanHide()`) as a checkbox-styled `Button` with
 * `aria-pressed`. Toggling fires `column.toggleVisibility`. We use buttons
 * (not raw `<input type="checkbox">`) because raw inputs are reserved for
 * RHF-driven forms by repo policy — this is a UI-state control.
 */
export function DataTableViewOptions<TData>({
  table,
  label = "Columns",
  className,
}: DataTableViewOptionsProps<TData>) {
  const hideableColumns = table
    .getAllLeafColumns()
    .filter((c) => typeof c.accessorFn !== "undefined" && c.getCanHide());

  return (
    <Popover>
      <PopoverTrigger asChild>
        <Button
          type="button"
          variant="outline"
          size="sm"
          aria-label={label}
          data-slot="view-options-trigger"
          className={cn("gap-2", className)}
        >
          <Settings2 aria-hidden="true" className="size-4" />
          <span>{label}</span>
        </Button>
      </PopoverTrigger>
      <PopoverContent align="end" className="w-56 p-2" data-slot="view-options-content">
        <p className="text-foreground-muted px-2 pb-1 text-xs font-medium tracking-wide uppercase">
          Toggle columns
        </p>
        <div role="group" aria-label={label} className="flex flex-col gap-0.5">
          {hideableColumns.length === 0 ? (
            <p className="text-foreground-muted px-2 py-2 text-sm">No toggleable columns.</p>
          ) : (
            hideableColumns.map((column) => {
              const visible = column.getIsVisible();
              const text =
                typeof column.columnDef.meta === "object" &&
                column.columnDef.meta !== null &&
                "label" in column.columnDef.meta &&
                typeof (column.columnDef.meta as { label?: unknown }).label === "string"
                  ? (column.columnDef.meta as { label: string }).label
                  : column.id;
              return (
                <Button
                  key={column.id}
                  type="button"
                  variant="ghost"
                  size="sm"
                  role="switch"
                  aria-checked={visible}
                  data-slot="view-options-item"
                  data-column-id={column.id}
                  data-state={visible ? "on" : "off"}
                  onClick={() => column.toggleVisibility(!visible)}
                  className="text-foreground hover:bg-surface-muted h-8 w-full justify-start gap-2 px-2 text-sm font-normal"
                >
                  <span
                    aria-hidden="true"
                    className={cn(
                      "border-border-strong flex size-4 shrink-0 items-center justify-center rounded border",
                      visible && "bg-primary text-primary-foreground border-transparent",
                    )}
                  >
                    {visible ? <Check className="size-3" /> : null}
                  </span>
                  <span className="truncate">{text}</span>
                </Button>
              );
            })
          )}
        </div>
      </PopoverContent>
    </Popover>
  );
}
