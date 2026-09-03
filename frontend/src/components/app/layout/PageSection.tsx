import { forwardRef, type HTMLAttributes, type ReactNode } from "react";
import { cn } from "@/lib/utils";
import { PageEyebrow } from "./PageEyebrow";

interface PageSectionProps extends HTMLAttributes<HTMLElement> {
  eyebrow?: string;
  title?: string;
  description?: string;
  actions?: ReactNode;
  children: ReactNode;
  className?: string;
}

/**
 * Card-styled `<section>` for grouping a screen's content. Renders an
 * optional header row (eyebrow + title + description on the left, actions
 * on the right) followed by the children body. Used as the default
 * surface throughout dashboards and detail pages.
 */
export const PageSection = forwardRef<HTMLElement, PageSectionProps>(function PageSection(
  { eyebrow, title, description, actions, children, className, ...rest },
  ref,
) {
  const hasHeader = Boolean(eyebrow || title || description || actions);
  return (
    <section
      ref={ref}
      className={cn("border-border bg-surface shadow-e1 rounded-container border p-5", className)}
      {...rest}
    >
      {hasHeader ? (
        <header
          data-slot="section-header"
          className="mb-4 flex flex-col gap-2 sm:flex-row sm:items-start sm:justify-between sm:gap-6"
        >
          <div className="min-w-0 flex-1">
            {eyebrow ? <PageEyebrow className="mb-1">{eyebrow}</PageEyebrow> : null}
            {title ? (
              <h2 className="text-foreground text-base leading-6 font-semibold">{title}</h2>
            ) : null}
            {description ? (
              <p className="text-foreground-muted mt-1 max-w-2xl text-sm leading-[1.375rem]">
                {description}
              </p>
            ) : null}
          </div>
          {actions ? (
            <div data-slot="section-actions" className="flex shrink-0 items-center gap-2">
              {actions}
            </div>
          ) : null}
        </header>
      ) : null}
      <div data-slot="section-body">{children}</div>
    </section>
  );
});
