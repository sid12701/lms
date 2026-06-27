import { forwardRef, useMemo } from "react";
import { Link } from "react-router-dom";
import { ChevronRight, Files, type LucideIcon } from "lucide-react";
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
}

const CARDS: readonly LinkCardSpec[] = [
  {
    key: "my-loans",
    to: "/my-loans",
    title: "Loan applications",
    description: "Review applications and accounts you originated.",
    icon: Files,
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
        <span className="text-foreground-muted text-xs leading-5">{description}</span>
      </div>
      <ChevronRight aria-hidden="true" className="text-foreground-muted size-4 shrink-0" />
    </>
  );
}

/**
 * Shortcut grid for the LSP workspace. Cards navigate via react-router
 * `<Link>` so Enter activates them naturally.
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
        className={cn("grid grid-cols-1 gap-4 md:grid-cols-2", className)}
      >
        {CARDS.map((card) => {
          return (
            <Link
              key={card.key}
              to={card.to}
              aria-label={`${card.title}. ${card.description}`}
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
