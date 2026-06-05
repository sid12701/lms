import { LayoutList, Rows3 } from "lucide-react";
import { useDensity } from "@/app/providers";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

export interface DensityToggleProps {
  /** Accessible name for the segmented control wrapper (rendered as legend). */
  label?: string;
  className?: string;
}

/**
 * Segmented two-option control toggling between `comfortable` and `compact`
 * row density. The selected state is stored in the global `DensityProvider`
 * and persisted to localStorage; surfaces that need a different density
 * temporarily can pass an explicit `density` prop to `DataTable` instead of
 * mutating the global state.
 *
 * Wraps the two `role="radio"` Buttons in a real `<fieldset>` + sr-only
 * `<legend>` (item 4 of the audit-polish punch list). The fieldset element is
 * the semantically correct group; the `radiogroup` ARIA role keeps assistive
 * tech that understands the pattern happy without redundancy.
 */
export function DensityToggle({ label = "Row density", className }: DensityToggleProps) {
  const { density, setDensity } = useDensity();

  return (
    <fieldset
      role="radiogroup"
      aria-label={label}
      data-slot="density-toggle"
      className={cn(
        "border-border bg-surface inline-flex items-center gap-0.5 rounded-md border p-0.5",
        className,
      )}
    >
      <legend className="sr-only">{label}</legend>
      <Button
        type="button"
        size="sm"
        variant={density === "comfortable" ? "secondary" : "ghost"}
        role="radio"
        aria-checked={density === "comfortable"}
        onClick={() => setDensity("comfortable")}
        data-state={density === "comfortable" ? "on" : "off"}
        data-slot="density-comfortable"
        className="h-7 gap-1.5 px-2 text-xs"
      >
        <Rows3 aria-hidden="true" className="size-3.5" />
        <span>Comfortable</span>
      </Button>
      <Button
        type="button"
        size="sm"
        variant={density === "compact" ? "secondary" : "ghost"}
        role="radio"
        aria-checked={density === "compact"}
        onClick={() => setDensity("compact")}
        data-state={density === "compact" ? "on" : "off"}
        data-slot="density-compact"
        className="h-7 gap-1.5 px-2 text-xs"
      >
        <LayoutList aria-hidden="true" className="size-3.5" />
        <span>Compact</span>
      </Button>
    </fieldset>
  );
}
