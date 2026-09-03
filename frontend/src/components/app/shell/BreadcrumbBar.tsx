import { Link, useLocation } from "react-router-dom";
import { ChevronRight } from "lucide-react";
import { cn } from "@/lib/utils";
import { resolveBreadcrumbLabel } from "./breadcrumb-labels";
import { usePageMetaState } from "./page-meta-context";

export interface BreadcrumbBarProps {
  className?: string;
}

/**
 * Phase-2 breadcrumb derived from the current pathname. Splits on `/`,
 * resolves each segment to a display label via the shared
 * `BREADCRUMB_LABELS` map (sibling `breadcrumb-labels.ts`), and links every
 * level except the last.
 */
export function BreadcrumbBar({ className }: BreadcrumbBarProps) {
  const { pathname } = useLocation();
  const { breadcrumbLabel } = usePageMetaState();
  const segments = pathname.split("/").filter(Boolean);

  /*
   * The trail is always rooted at Home, so /home rendered "Home > Home". Only
   * that duplicate case is suppressed: on every other route the trail still
   * earns its place as a one-click path back, and the redundancy the audit
   * flagged was the *eyebrow* restating the sidebar group, which is now gone.
   * The bar keeps its height either way so routes do not shift vertically.
   */
  if (segments.length === 0 || (segments.length === 1 && segments[0] === "home")) {
    return (
      <div
        aria-hidden="true"
        className={cn("bg-background border-b border-[var(--color-border)] px-6 py-1.5", className)}
      />
    );
  }

  const crumbs = segments.map((segment, idx) => {
    const to = "/" + segments.slice(0, idx + 1).join("/");
    const isLast = idx === segments.length - 1;
    return {
      to,
      label: resolveBreadcrumbLabel(segment, isLast ? breadcrumbLabel : undefined),
    };
  });

  return (
    <nav
      aria-label="Breadcrumb"
      className={cn(
        "bg-background border-b border-[var(--color-border)] px-6 py-1.5 text-xs",
        className,
      )}
    >
      <ol className="text-foreground-muted flex flex-wrap items-center gap-1">
        <li>
          <Link
            to="/home"
            className="hover:text-foreground inline-flex min-h-6 items-center px-1 transition-colors"
          >
            Home
          </Link>
        </li>
        {crumbs.map((c, idx) => (
          <li key={c.to} className="flex items-center gap-1">
            <ChevronRight aria-hidden="true" className="h-3 w-3" />
            {idx === crumbs.length - 1 ? (
              <span aria-current="page" className="text-foreground font-medium">
                {c.label}
              </span>
            ) : (
              <Link
                to={c.to}
                className="hover:text-foreground inline-flex min-h-6 items-center px-1 transition-colors"
              >
                {c.label}
              </Link>
            )}
          </li>
        ))}
      </ol>
    </nav>
  );
}
