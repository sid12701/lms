import { useRef } from "react";
import { Check, ChevronDown } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { cn } from "@/lib/utils";

export interface MultiSelectChipOption<T extends string> {
  value: T;
  label: string;
}

export interface MultiSelectChipProps<T extends string> {
  label: string;
  options: readonly MultiSelectChipOption<T>[];
  selected: readonly T[];
  onToggle: (next: T[]) => void;
  /** Optional `data-slot` on the trigger button. */
  dataSlot?: string;
  /** Popover listbox `aria-label`; defaults to `label`. */
  listboxLabel?: string;
}

export function MultiSelectChip<T extends string>({
  label,
  options,
  selected,
  onToggle,
  dataSlot,
  listboxLabel,
}: MultiSelectChipProps<T>) {
  const buttonRef = useRef<HTMLButtonElement | null>(null);
  const selectedValues = new Set(selected);
  const summary =
    selected.length === 0
      ? label
      : selected.length === 1
        ? `${label}: ${options.find((o) => o.value === selected[0])?.label ?? selected[0]}`
        : `${label}: ${selected.length} selected`;

  const toggle = (val: T) => {
    if (selectedValues.has(val)) {
      onToggle(selected.filter((v) => v !== val));
    } else {
      onToggle([...selected, val]);
    }
  };

  return (
    <Popover>
      <PopoverTrigger asChild>
        <Button
          ref={buttonRef}
          type="button"
          variant="outline"
          size="sm"
          data-slot={dataSlot}
          aria-label={summary}
          className={cn(
            "gap-1",
            selected.length > 0 ? "border-ring text-foreground" : "text-foreground-muted",
          )}
        >
          <span>{summary}</span>
          <ChevronDown aria-hidden="true" className="size-3.5" />
        </Button>
      </PopoverTrigger>
      <PopoverContent align="start" className="w-56 p-1">
        <ul
          role="listbox"
          aria-label={listboxLabel ?? label}
          aria-multiselectable="true"
          className="flex flex-col"
        >
          {options.map((opt) => {
            const checked = selectedValues.has(opt.value);
            return (
              <li key={opt.value}>
                <Button
                  type="button"
                  variant="ghost"
                  size="sm"
                  role="option"
                  aria-selected={checked}
                  onClick={() => toggle(opt.value)}
                  className="w-full justify-start gap-2"
                >
                  <span
                    aria-hidden="true"
                    className={cn(
                      "border-border inline-flex size-4 items-center justify-center rounded-sm border",
                      checked ? "bg-primary border-primary text-primary-foreground" : null,
                    )}
                  >
                    {checked ? <Check className="size-3" aria-hidden="true" /> : null}
                  </span>
                  <span>{opt.label}</span>
                </Button>
              </li>
            );
          })}
        </ul>
      </PopoverContent>
    </Popover>
  );
}
