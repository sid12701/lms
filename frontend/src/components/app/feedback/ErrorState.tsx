import { forwardRef, useState, type HTMLAttributes } from "react";
import type { LucideIcon } from "lucide-react";
import { AlertTriangle, Copy, Check } from "lucide-react";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";

export interface ErrorStateProps extends Omit<HTMLAttributes<HTMLDivElement>, "title"> {
  title?: string;
  description?: string;
  /** Correlation ID surfaced for support; rendered copyable. */
  correlationId?: string;
  retry?: { label?: string; onClick: () => void };
  icon?: LucideIcon;
  className?: string;
}

/**
 * Centered error block. Renders with `role="alert"` so assistive tech
 * announces it on mount. The danger-tinted icon and faintly tinted
 * background avoid relying on colour alone — the heading + alert role
 * carry the meaning. Provides an optional retry button and a copyable
 * correlation id for support hand-off.
 */
export const ErrorState = forwardRef<HTMLDivElement, ErrorStateProps>(function ErrorState(
  {
    title = "Something went wrong",
    description,
    correlationId,
    retry,
    icon: Icon = AlertTriangle,
    className,
    ...rest
  },
  ref,
) {
  const [copied, setCopied] = useState(false);

  const handleCopy = () => {
    if (!correlationId) return;
    void navigator.clipboard?.writeText(correlationId).then(() => {
      setCopied(true);
      window.setTimeout(() => setCopied(false), 2000);
    });
  };

  return (
    <div
      ref={ref}
      role="alert"
      data-slot="error-state"
      className={cn(
        "border-border bg-danger/5 flex flex-col items-center justify-center gap-3 rounded-md border px-6 py-16 text-center",
        className,
      )}
      {...rest}
    >
      <Icon aria-hidden="true" className="text-danger h-8 w-8" />
      <div className="flex max-w-md flex-col gap-1">
        <p className="text-foreground text-base leading-6 font-semibold">{title}</p>
        {description ? (
          <p className="text-foreground-muted text-sm leading-[1.375rem]">{description}</p>
        ) : null}
      </div>
      {correlationId ? (
        <div className="text-foreground-muted flex items-center gap-2 text-xs">
          <span>Reference</span>
          <code
            data-slot="correlation-id"
            className="bg-surface-muted text-foreground rounded px-2 py-0.5 font-mono"
          >
            {correlationId}
          </code>
          <Button
            type="button"
            variant="ghost"
            size="icon-xs"
            onClick={handleCopy}
            aria-label={copied ? "Copied correlation ID" : "Copy correlation ID"}
          >
            {copied ? (
              <Check className="text-success h-3 w-3" aria-hidden="true" />
            ) : (
              <Copy className="h-3 w-3" aria-hidden="true" />
            )}
          </Button>
        </div>
      ) : null}
      {retry ? (
        <Button type="button" variant="outline" onClick={retry.onClick} className="mt-2">
          {retry.label ?? "Try again"}
        </Button>
      ) : null}
    </div>
  );
});
