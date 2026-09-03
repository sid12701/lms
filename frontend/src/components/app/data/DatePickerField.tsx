import { useState } from "react";
import { format, parseISO } from "date-fns";
import { enIN } from "date-fns/locale";
import { Calendar as CalendarIcon, X } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Calendar } from "@/components/ui/calendar";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { cn } from "@/lib/utils";

const ISO_DATE = "yyyy-MM-dd";
const DISPLAY_DATE = "dd/MM/yyyy";

function parseIsoDateOnly(value: string | undefined): Date | undefined {
  if (!value) return undefined;
  const parsed = parseISO(value);
  return Number.isNaN(parsed.getTime()) ? undefined : parsed;
}

export interface DatePickerFieldProps {
  /** ISO calendar date (`yyyy-MM-dd`). */
  value?: string;
  onChange: (value: string | undefined) => void;
  ariaLabel: string;
  dataSlot?: string;
  id?: string;
  placeholder?: string;
  className?: string;
  disabled?: boolean;
}

/**
 * Date filter/control that always displays dd/MM/yyyy regardless of browser locale.
 * Emits ISO `yyyy-MM-dd` for API filters and forms.
 */
export function DatePickerField({
  value,
  onChange,
  ariaLabel,
  dataSlot,
  id,
  placeholder = "dd/mm/yyyy",
  className,
  disabled = false,
}: DatePickerFieldProps) {
  const [open, setOpen] = useState(false);
  const selected = parseIsoDateOnly(value);

  const clear = (event: React.MouseEvent) => {
    event.stopPropagation();
    onChange(undefined);
    setOpen(false);
  };

  return (
    <div className={cn("relative", className)}>
      <Popover open={open} onOpenChange={setOpen}>
        <PopoverTrigger asChild>
          <Button
            id={id}
            type="button"
            variant="outline"
            disabled={disabled}
            data-slot={dataSlot}
            aria-label={ariaLabel}
            data-filter-set={selected ? "true" : undefined}
            className={cn(
              // Matches FILTER_CONTROL_CLASS: 28px, 12px, control radius, the
              // 3:1 affordance border. Was 32px/14px on a bar whose selects
              // were 24px/12px.
              "border-border-control bg-input/20 text-foreground rounded-control h-7 w-full justify-start gap-2 pr-8 pl-2 text-xs font-normal",
              !selected && "text-foreground-muted",
              // A set date reads as set without opening the picker.
              selected && "border-primary bg-primary/10 text-primary-tinted font-medium",
            )}
          >
            <CalendarIcon aria-hidden className="size-3.5 shrink-0 opacity-70" />
            <span className="min-w-0 flex-1 truncate text-left text-xs tabular-nums">
              {selected ? format(selected, DISPLAY_DATE) : placeholder}
            </span>
          </Button>
        </PopoverTrigger>
        <PopoverContent className="w-auto p-0" align="start" aria-label={ariaLabel}>
          <Calendar
            mode="single"
            locale={enIN}
            selected={selected}
            defaultMonth={selected}
            onSelect={(date) => {
              onChange(date ? format(date, ISO_DATE) : undefined);
              setOpen(false);
            }}
          />
        </PopoverContent>
      </Popover>
      {selected ? (
        <button
          type="button"
          aria-label={`Clear ${ariaLabel}`}
          disabled={disabled}
          className="text-foreground-muted hover:text-foreground rounded-control absolute top-1/2 right-2 -translate-y-1/2 p-0.5"
          onClick={clear}
        >
          <X className="size-3" aria-hidden />
        </button>
      ) : null}
    </div>
  );
}
