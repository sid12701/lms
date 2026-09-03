import { forwardRef, type HTMLAttributes, type ReactNode, type Ref } from "react";
import { cn } from "@/lib/utils";

interface FormSectionProps extends Omit<HTMLAttributes<HTMLElement>, "title"> {
  title?: string;
  description?: string;
  children: ReactNode;
  className?: string;
}

/**
 * Visual + semantic grouping inside a form. When `title` is provided the
 * section renders as a `<fieldset>` with a `<legend>` (exposed to
 * assistive tech). Without a title it collapses to a plain `<div>` so it
 * can still be used as a layout affordance without polluting the
 * accessibility tree.
 */
export const FormSection = forwardRef<HTMLElement, FormSectionProps>(function FormSection(
  { title, description, children, className, ...rest },
  ref,
) {
  if (!title) {
    return (
      <div
        data-slot="form-section"
        ref={ref as Ref<HTMLDivElement>}
        className={cn("flex flex-col gap-4", className)}
        {...rest}
      >
        {description ? (
          <p className="text-foreground-muted text-sm leading-[1.375rem]">{description}</p>
        ) : null}
        {children}
      </div>
    );
  }

  return (
    <fieldset
      data-slot="form-section"
      ref={ref as Ref<HTMLFieldSetElement>}
      className={cn(
        "border-border bg-surface shadow-e1 rounded-container flex flex-col gap-4 border p-5",
        className,
      )}
      {...rest}
    >
      <legend className="text-foreground px-1 text-sm leading-5 font-semibold">{title}</legend>
      {description ? (
        <p className="text-foreground-muted -mt-2 text-sm leading-[1.375rem]">{description}</p>
      ) : null}
      {children}
    </fieldset>
  );
});
