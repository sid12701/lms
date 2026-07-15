import type { ReactNode } from "react";
import { MoreHorizontal, type LucideIcon } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { cn } from "@/lib/utils";

export interface EntityRowActionItem {
  id: string;
  label: string;
  icon?: LucideIcon;
  onSelect: () => void;
  dataSlot?: string;
  variant?: "default" | "outline" | "ghost";
  /** Overrides the visible label for assistive tech (inline mode). */
  ariaLabel?: string;
  /** Renders the action disabled (inline mode). */
  disabled?: boolean;
  /** Tooltip explaining why the action is disabled. */
  disabledTitle?: string;
}

export interface EntityRowActionsProps {
  mode?: "menu" | "inline";
  ariaLabel?: string;
  items: readonly EntityRowActionItem[];
  triggerDataSlot?: string;
  /** When inline and `items` is empty, render this (e.g. em-dash). */
  emptyFallback?: ReactNode;
  /** Inline row alignment; menu mode ignores this. */
  align?: "start" | "end";
}

export function EntityRowActions({
  mode = "menu",
  ariaLabel = "Row actions",
  items,
  triggerDataSlot,
  emptyFallback = null,
  align = "start",
}: EntityRowActionsProps): ReactNode {
  if (mode === "inline") {
    if (items.length === 0) return emptyFallback;
    return (
      <div className={cn("flex flex-wrap items-center gap-1", align === "end" && "justify-end")}>
        {items.map((item) => (
          <Button
            key={item.id}
            type="button"
            variant={item.variant ?? "outline"}
            size="sm"
            data-slot={item.dataSlot}
            disabled={item.disabled}
            title={item.disabled ? item.disabledTitle : undefined}
            onClick={(e) => {
              e.stopPropagation();
              item.onSelect();
            }}
            aria-label={item.ariaLabel}
            className={item.icon ? "gap-2" : undefined}
          >
            {item.icon ? <item.icon aria-hidden="true" className="size-4 shrink-0" /> : null}
            <span>{item.label}</span>
          </Button>
        ))}
      </div>
    );
  }

  return (
    <Popover>
      <PopoverTrigger asChild>
        <Button
          type="button"
          variant="ghost"
          size="icon-sm"
          data-slot={triggerDataSlot}
          aria-label={ariaLabel}
          onClick={(e) => e.stopPropagation()}
        >
          <MoreHorizontal aria-hidden="true" className="size-4" />
        </Button>
      </PopoverTrigger>
      <PopoverContent align="end" className="w-48 p-1">
        <ul className="flex flex-col">
          {items.map((item) => {
            const Icon = item.icon;
            return (
              <li key={item.id}>
                <Button
                  type="button"
                  variant="ghost"
                  size="sm"
                  data-slot={item.dataSlot}
                  onClick={(e) => {
                    e.stopPropagation();
                    item.onSelect();
                  }}
                  className="w-full justify-start gap-2"
                >
                  {Icon ? <Icon aria-hidden="true" className="size-4" /> : null}
                  {item.label}
                </Button>
              </li>
            );
          })}
        </ul>
      </PopoverContent>
    </Popover>
  );
}
