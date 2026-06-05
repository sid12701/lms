import { forwardRef, useMemo } from "react";
import { Link } from "react-router-dom";
import { ChevronRight, FilePlus2, Files, HelpCircle, type LucideIcon } from "lucide-react";
import { Card } from "@/components/ui/card";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { cn } from "@/lib/utils";

export interface LspLinkCardGridProps {
  className?: string;
}

interface LinkCardSpec {
  key: string;
  to: string;
  title: string;
  description: string;
  icon: LucideIcon;
  /** When true, render as a disabled card with a tooltip rather than a link. */
  disabled?: boolean;
  disabledReason?: string;
}

const CARDS: readonly LinkCardSpec[] = [
  {
    key: "submit",
    to: "/my-loans/new",
    title: "Submit new loan",
    description: "Start a new application for a borrower in your portfolio.",
    icon: FilePlus2,
  },
  {
    key: "my-loans",
    to: "/my-loans",
    title: "My loans",
    description: "Review applications and accounts you originated.",
    icon: Files,
  },
  {
    key: "help",
    to: "#",
    title: "Help & docs",
    description: "Integration guides, lifecycle reference, and API playground.",
    icon: HelpCircle,
    disabled: true,
    disabledReason: "Coming soon",
  },
];

function prefersReducedMotion(): boolean {
  if (typeof window === "undefined" || typeof window.matchMedia !== "function") {
    return false;
  }
  try {
    return window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  } catch {
    return false;
  }
}

/**
 * Visual content of each card — used by both the active `<Link>` and the
 * disabled fallback so the two states stay pixel-identical except for
 * focus/hover affordances.
 */
function CardBody({
  icon: Icon,
  title,
  description,
}: Pick<LinkCardSpec, "icon" | "title" | "description">) {
  return (
    <>
      <div className="bg-surface-muted text-foreground flex size-9 items-center justify-center rounded-md">
        <Icon aria-hidden="true" className="size-5" />
      </div>
      <div className="flex min-w-0 flex-1 flex-col gap-1">
        <span className="text-foreground text-sm font-semibold">{title}</span>
        <span className="text-foreground-muted text-xs leading-[1.25rem]">{description}</span>
      </div>
      <ChevronRight aria-hidden="true" className="text-foreground-muted size-4 shrink-0" />
    </>
  );
}

/**
 * 1×3 shortcut grid for the LSP home. Cards navigate via react-router
 * `<Link>` (so Enter activates them naturally). The "Help & docs" tile
 * is disabled today because `/help` isn't wired up yet — it renders as
 * a non-interactive card with a "Coming soon" tooltip and
 * `aria-disabled`.
 *
 * Entrance animation is a 200ms fade-in; it is suppressed entirely when
 * the user has set `prefers-reduced-motion: reduce`.
 */
export const LspLinkCardGrid = forwardRef<HTMLDivElement, LspLinkCardGridProps>(
  function LspLinkCardGrid({ className }, ref) {
    const reducedMotion = useMemo(() => prefersReducedMotion(), []);
    const animationClass = reducedMotion
      ? ""
      : "motion-safe:animate-in motion-safe:fade-in motion-safe:duration-200";

    const cardClasses = cn(
      "border-border bg-surface shadow-e1 hover:border-border-strong hover:bg-surface-muted/60 focus-visible:ring-ring focus-visible:ring-2 focus-visible:ring-offset-2 flex flex-row items-center gap-3 rounded-md border p-4 outline-none transition-colors",
      animationClass,
    );

    return (
      <div
        ref={ref}
        data-slot="lsp-link-card-grid"
        data-reduced-motion={reducedMotion || undefined}
        className={cn("grid grid-cols-1 gap-4 md:grid-cols-3", className)}
      >
        {CARDS.map((card) => {
          const ariaLabel = `${card.title}. ${card.description}${card.disabled ? `. ${card.disabledReason}` : ""}`;

          if (card.disabled) {
            return (
              <Tooltip key={card.key}>
                <TooltipTrigger asChild>
                  <Card
                    role="link"
                    aria-disabled="true"
                    aria-label={ariaLabel}
                    data-slot="lsp-link-card"
                    data-disabled="true"
                    tabIndex={0}
                    className={cn(cardClasses, "cursor-not-allowed opacity-70")}
                  >
                    <CardBody icon={card.icon} title={card.title} description={card.description} />
                  </Card>
                </TooltipTrigger>
                <TooltipContent side="top">{card.disabledReason ?? "Unavailable"}</TooltipContent>
              </Tooltip>
            );
          }

          return (
            <Link
              key={card.key}
              to={card.to}
              aria-label={ariaLabel}
              data-slot="lsp-link-card"
              className={cardClasses}
            >
              <CardBody icon={card.icon} title={card.title} description={card.description} />
            </Link>
          );
        })}
      </div>
    );
  },
);
