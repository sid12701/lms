import { forwardRef, type HTMLAttributes, type ReactNode } from "react";
import { cn } from "@/lib/utils";
import { PageEyebrow } from "./PageEyebrow";

export interface PageHeaderProps extends Omit<HTMLAttributes<HTMLElement>, "title"> {
  /** Small uppercase label rendered above the title (11px / 16 line-height). */
  eyebrow?: string;
  /** Page title (h1). */
  title: string;
  /** Optional supporting copy below the title. */
  description?: string;
  /** Right-aligned slot for primary/secondary actions. */
  actions?: ReactNode;
  className?: string;
}

/**
 * Top-of-page header. Renders as a semantic `<header>` landmark with an
 * optional eyebrow, the h1 title, optional description, and a right-aligned
 * actions slot. Sticky behaviour is *off* by default — the consuming screen
 * decides whether the surrounding container should pin it.
 */
export const PageHeader = forwardRef<HTMLElement, PageHeaderProps>(function PageHeader(
  { eyebrow, title, description, actions, className, ...rest },
  ref,
) {
  return (
    <header
      ref={ref}
      className={cn(
        "border-border bg-background flex flex-col gap-3 border-b pb-5 sm:flex-row sm:items-start sm:justify-between sm:gap-6",
        className,
      )}
      {...rest}
    >
      <div className="min-w-0 flex-1">
        {eyebrow ? <PageEyebrow className="mb-1">{eyebrow}</PageEyebrow> : null}
        <h1 className="text-foreground truncate text-2xl leading-8 font-semibold tracking-tight">
          {title}
        </h1>
        {description ? (
          <p className="text-foreground-muted mt-1 max-w-2xl text-sm leading-[1.375rem]">
            {description}
          </p>
        ) : null}
      </div>
      {actions ? (
        <div data-slot="actions" className="flex shrink-0 items-center gap-2">
          {actions}
        </div>
      ) : null}
    </header>
  );
});
