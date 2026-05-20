/**
 * Multi-select chips for picking LSPs. Each chip is a toggleable button —
 * keyboard-accessible, no separate trigger/popover needed for a list of < 10.
 */
import { Check } from "lucide-react";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import type { LspChoice } from "../hooks/useLspChoices";

export interface LspMultiSelectProps {
  choices: readonly LspChoice[];
  selected: readonly string[];
  onChange: (next: string[]) => void;
  className?: string;
}

export function LspMultiSelect({
  choices,
  selected,
  onChange,
  className,
}: LspMultiSelectProps) {
  const selectedSet = new Set(selected);

  const toggle = (id: string) => {
    if (selectedSet.has(id)) {
      onChange(selected.filter((s) => s !== id));
    } else {
      onChange([...selected, id]);
    }
  };

  return (
    <ul
      role="listbox"
      aria-label="LSP selection"
      aria-multiselectable="true"
      data-slot="lsp-multi-select"
      className={cn("flex flex-wrap gap-2", className)}
    >
      {choices.map((choice) => {
        const isSelected = selectedSet.has(choice.id);
        return (
          <li key={choice.id}>
            <Button
              type="button"
              variant={isSelected ? "default" : "outline"}
              size="sm"
              role="option"
              aria-selected={isSelected}
              data-slot="lsp-multi-select-chip"
              data-selected={isSelected}
              data-lsp-id={choice.id}
              onClick={() => toggle(choice.id)}
              className="gap-1.5"
            >
              {isSelected ? (
                <Check aria-hidden="true" className="size-3.5" />
              ) : null}
              <span>{choice.name}</span>
              <span className="text-foreground-muted text-[10px] font-mono uppercase">
                {choice.code}
              </span>
            </Button>
          </li>
        );
      })}
    </ul>
  );
}
