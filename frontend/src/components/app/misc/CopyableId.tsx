import { forwardRef, useState, type HTMLAttributes } from "react";
import { Check, Copy } from "lucide-react";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { cn } from "@/lib/utils";

interface CopyableIdProps extends Omit<HTMLAttributes<HTMLButtonElement>, "value"> {
  value: string;
  /** Aria label, e.g. "Correlation ID". Defaults to "Copy identifier". */
  label?: string;
  /** Truncate the displayed text at N chars + ellipsis (full value still copied). */
  truncate?: number;
  className?: string;
}

/**
 * Mono-font identifier chip. Click copies the *full* `value` to the
 * clipboard and shows a brief "Copied" tooltip. The displayed text may be
 * truncated for layout via `truncate`, but the copied payload is always
 * the full value.
 *
 * Designed for IDs surfaced in lists, audit logs, and detail pages where
 * support handoff requires a copyable reference.
 */
export const CopyableId = forwardRef<HTMLButtonElement, CopyableIdProps>(function CopyableId(
  { value, label = "Copy identifier", truncate, className, ...rest },
  ref,
) {
  const [copied, setCopied] = useState(false);
  const display = truncate && value.length > truncate ? `${value.slice(0, truncate)}…` : value;

  const handleClick = () => {
    void navigator.clipboard?.writeText(value).then(() => {
      setCopied(true);
      window.setTimeout(() => setCopied(false), 2000);
    });
  };

  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <button
          ref={ref}
          type="button"
          onClick={handleClick}
          aria-label={label}
          data-slot="copyable-id"
          data-copied={copied || undefined}
          className={cn(
            "text-foreground hover:border-border hover:bg-surface-muted focus-visible:border-border inline-flex max-w-full items-center gap-1.5 rounded border border-transparent bg-transparent px-1.5 py-0.5 font-mono text-xs transition-colors",
            className,
          )}
          {...rest}
        >
          <span className="truncate">{display}</span>
          {copied ? (
            <Check className="text-success h-3 w-3 shrink-0" aria-hidden="true" />
          ) : (
            <Copy className="text-foreground-muted h-3 w-3 shrink-0" aria-hidden="true" />
          )}
        </button>
      </TooltipTrigger>
      <TooltipContent side="top" role="status">
        {copied ? "Copied" : label}
      </TooltipContent>
    </Tooltip>
  );
});
