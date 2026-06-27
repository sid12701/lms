import { forwardRef, type HTMLAttributes } from "react";
import type { LucideIcon } from "lucide-react";
import { Inbox } from "lucide-react";
import { cva, type VariantProps } from "class-variance-authority";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";

export type EmptyVariant = "no-data" | "filtered-empty" | "no-permission";

const emptyVariants = cva(
  "flex flex-col items-center justify-center gap-3 py-24 px-6 text-center",
  {
    variants: {
      variant: {
        "no-data": "text-foreground",
        "filtered-empty": "text-foreground",
        "no-permission": "text-foreground",
      },
    },
    defaultVariants: { variant: "no-data" },
  },
);

const iconToneByVariant: Record<EmptyVariant, string> = {
  "no-data": "text-foreground-muted",
  "filtered-empty": "text-foreground-muted",
  "no-permission": "text-warning",
};

export interface EmptyStateProps
  extends Omit<HTMLAttributes<HTMLDivElement>, "title">, VariantProps<typeof emptyVariants> {
  variant?: EmptyVariant;
  title: string;
  description?: string;
  action?: {
    label: string;
    onClick: () => void;
    tone?: "primary" | "secondary";
  };
  secondaryAction?: {
    label: string;
    onClick: () => void;
  };
  /** Defaults to `Inbox` from lucide-react. */
  icon?: LucideIcon;
  className?: string;
}

/**
 * Centered empty-state block for tables, lists, and panels with no rows
 * to display. Three variants share the same shape but vary in iconography
 * and copy emphasis: `no-data` (neutral), `filtered-empty` (suggests
 * loosening filters), `no-permission` (gold-tinted, locked).
 */
export const EmptyState = forwardRef<HTMLDivElement, EmptyStateProps>(function EmptyState(
  {
    variant = "no-data",
    title,
    description,
    action,
    secondaryAction,
    icon: Icon = Inbox,
    className,
    ...rest
  },
  ref,
) {
  return (
    <div
      ref={ref}
      data-slot="empty-state"
      data-variant={variant}
      className={cn(emptyVariants({ variant }), className)}
      {...rest}
    >
      <Icon aria-hidden="true" className={cn("h-8 w-8", iconToneByVariant[variant])} />
      <div className="flex max-w-sm flex-col gap-1">
        <p className="text-foreground text-base leading-6 font-semibold">{title}</p>
        {description ? (
          <p className="text-foreground-muted text-sm leading-5.5">{description}</p>
        ) : null}
      </div>
      {action || secondaryAction ? (
        <div className="mt-2 flex flex-wrap items-center justify-center gap-2">
          {action ? (
            <Button
              type="button"
              variant={action.tone === "secondary" ? "outline" : "default"}
              onClick={action.onClick}
            >
              {action.label}
            </Button>
          ) : null}
          {secondaryAction ? (
            <Button type="button" variant="outline" onClick={secondaryAction.onClick}>
              {secondaryAction.label}
            </Button>
          ) : null}
        </div>
      ) : null}
    </div>
  );
});
