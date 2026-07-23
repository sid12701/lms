import type { FormHTMLAttributes, ReactNode } from "react";
import { type FieldValues, type SubmitHandler, type UseFormReturn } from "react-hook-form";
import { cn } from "@/lib/utils";
import { Form } from "@/components/ui/form";

export interface FormShellProps<TFieldValues extends FieldValues = FieldValues> extends Omit<
  FormHTMLAttributes<HTMLFormElement>,
  "onSubmit"
> {
  /** RHF form instance from the consumer (built with `useForm` + zod resolver). */
  form: UseFormReturn<TFieldValues>;
  onSubmit: SubmitHandler<TFieldValues>;
  children: ReactNode;
  className?: string;
  /** Optional override for the error-summary heading announced to AT. */
  errorSummaryLabel?: string;
}

/**
 * Wraps consumer forms in shadcn's `<Form>` provider plus a real `<form>`
 * element with our submit handler. An `aria-live="polite"` summary at
 * the top collects every field-level RHF error so screen readers announce
 * validation failures even when focus jumps to the first invalid input.
 *
 * The summary stays mounted (with `sr-only` styling) when there are no
 * errors so live-region updates fire reliably across browsers.
 */
export function FormShell<TFieldValues extends FieldValues = FieldValues>({
  form,
  onSubmit,
  children,
  className,
  errorSummaryLabel = "There are errors in this form",
  ...rest
}: FormShellProps<TFieldValues>) {
  const errors = form.formState.errors;
  const messages = collectErrorMessages(errors);

  return (
    <Form {...form}>
      <form
        data-slot="form-shell"
        onSubmit={form.handleSubmit(onSubmit)}
        className={cn("flex flex-col gap-6", className)}
        noValidate
        {...rest}
      >
        <div
          data-slot="form-shell-summary"
          aria-live="polite"
          aria-atomic="true"
          className={cn(
            messages.length === 0
              ? "sr-only"
              : "border-danger/30 bg-danger/5 text-foreground rounded-md border p-4 text-sm",
          )}
        >
          {messages.length > 0 ? (
            <>
              <p className="text-danger font-semibold">{errorSummaryLabel}</p>
              <ul className="text-foreground-muted mt-2 list-disc space-y-1 pl-5">
                {messages.map(({ id, message }) => (
                  <li key={id}>{message}</li>
                ))}
              </ul>
            </>
          ) : null}
        </div>
        {children}
      </form>
    </Form>
  );
}

type ErrorLike = { message?: unknown } | undefined | null;

interface FormErrorMessage {
  id: string;
  message: string;
}

function collectErrorMessages(errors: Record<string, unknown>): FormErrorMessage[] {
  const out: FormErrorMessage[] = [];
  const walk = (node: unknown, path: string) => {
    if (!node || typeof node !== "object") return;
    const maybe = node as ErrorLike & Record<string, unknown>;
    if (typeof maybe.message === "string" && maybe.message.length > 0) {
      out.push({ id: path || maybe.message, message: maybe.message });
      return;
    }
    for (const key of Object.keys(maybe)) {
      walk(maybe[key], path ? `${path}.${key}` : key);
    }
  };
  walk(errors, "");
  return out;
}
