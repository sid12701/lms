import { Link, useLocation } from "react-router-dom";
import { ChevronRight } from "lucide-react";
import { cn } from "@/lib/utils";
import { resolveBreadcrumbLabel } from "./breadcrumb-labels";

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
  const segments = pathname.split("/").filter(Boolean);

  // Skip rendering on root or single-segment top-level pages — caller decides.
  if (segments.length === 0) {
    return (
      <div
        aria-hidden="true"
        className={cn("bg-background border-b border-[var(--color-border)] px-6 py-1.5", className)}
      />
    );
  }

  const crumbs = segments.map((segment, idx) => {
    const to = "/" + segments.slice(0, idx + 1).join("/");
    return { to, label: resolveBreadcrumbLabel(segment) };
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
          <Link to="/home" className="hover:text-foreground transition-colors">
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
              <Link to={c.to} className="hover:text-foreground transition-colors">
                {c.label}
              </Link>
            )}
          </li>
        ))}
      </ol>
    </nav>
  );
}
