import { Check, ChevronDown } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { cn } from "@/lib/utils";
import type { LspChoice } from "../hooks/useLspChoices";

export interface LspMultiSelectProps {
  choices: readonly LspChoice[];
  selected: readonly string[];
  onChange: (next: string[]) => void;
  className?: string;
}

export function LspMultiSelect({ choices, selected, onChange, className }: LspMultiSelectProps) {
  const selectedSet = new Set(selected);
  const selectedChoices = choices.filter((choice) => selectedSet.has(choice.id));
  const summary =
    selectedChoices.length === 0
      ? "Select LSPs"
      : selectedChoices.length === 1
        ? "1 LSP selected"
        : `${selectedChoices.length} LSPs selected`;

  const toggle = (id: string) => {
    if (selectedSet.has(id)) {
      onChange(selected.filter((s) => s !== id));
    } else {
      onChange([...selected, id]);
    }
  };

  return (
    <Popover>
      <PopoverTrigger asChild>
        <Button
          type="button"
          variant="outline"
          aria-label={summary}
          data-slot="lsp-multi-select-trigger"
          className={cn("w-full justify-between gap-2", className)}
        >
          <span className="truncate">{summary}</span>
          <ChevronDown aria-hidden="true" className="size-4 shrink-0" />
        </Button>
      </PopoverTrigger>
      <PopoverContent align="start" className="w-72 p-1">
        <ul
          role="listbox"
          aria-label="LSP selection"
          aria-multiselectable="true"
          data-slot="lsp-multi-select"
          className="flex max-h-72 flex-col overflow-y-auto"
        >
          {choices.map((choice) => {
            const isSelected = selectedSet.has(choice.id);
            return (
              <li key={choice.id}>
                <Button
                  type="button"
                  variant="ghost"
                  size="sm"
                  role="option"
                  aria-selected={isSelected}
                  data-slot="lsp-multi-select-option"
                  data-selected={isSelected}
                  data-lsp-id={choice.id}
                  onClick={() => toggle(choice.id)}
                  className="w-full justify-start gap-2"
                >
                  <span
                    aria-hidden="true"
                    className={cn(
                      "border-border inline-flex size-4 items-center justify-center rounded-sm border",
                      isSelected ? "bg-primary border-primary text-primary-foreground" : null,
                    )}
                  >
                    {isSelected ? <Check className="size-3" aria-hidden="true" /> : null}
                  </span>
                  <span className="min-w-0 flex-1 truncate text-left">{choice.name}</span>
                  <span className="text-foreground-muted font-mono text-[10px] uppercase">
                    {choice.code}
                  </span>
                </Button>
              </li>
            );
          })}
        </ul>
      </PopoverContent>
    </Popover>
  );
}
