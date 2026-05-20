import { forwardRef, type HTMLAttributes, type ReactNode } from "react";
import { cn } from "@/lib/utils";

export interface PageEyebrowProps extends HTMLAttributes<HTMLElement> {
  /** Eyebrow label content (typically a short uppercase string). */
  children: ReactNode;
  /**
   * Optional element override. Use `"span"` when an eyebrow must render
   * inline (inside another paragraph or heading). Defaults to `"p"` so
   * the eyebrow lives on its own line with semantic block-level meaning.
   */
  as?: "p" | "span";
  className?: string;
}

/**
 * Shared eyebrow primitive — the single source of truth for the small
 * uppercase tracked label above a page or section title. Codifies the
 * typography spec in `frontend-implementation-plan.md` §5.2 (Eyebrow:
 * 11/16, 0.08em letter-spacing, uppercase, weight 600) so feature pages
 * and auth pages never drift from each other.
 *
 * Renders as `<p data-slot="page-eyebrow">` by default. Pass `as="span"`
 * to embed inline.
 */
export const PageEyebrow = forwardRef<HTMLElement, PageEyebrowProps>(function PageEyebrow(
  { children, as = "p", className, ...rest },
  ref,
) {
  const baseClass = "text-foreground-muted text-[11px] leading-4 font-semibold uppercase tracking-[0.08em]";
  if (as === "span") {
    return (
      <span
        ref={ref as React.Ref<HTMLSpanElement>}
        data-slot="page-eyebrow"
        className={cn(baseClass, className)}
        {...rest}
      >
        {children}
      </span>
    );
  }
  return (
    <p
      ref={ref as React.Ref<HTMLParagraphElement>}
      data-slot="page-eyebrow"
      className={cn(baseClass, className)}
      {...rest}
    >
      {children}
    </p>
  );
});
