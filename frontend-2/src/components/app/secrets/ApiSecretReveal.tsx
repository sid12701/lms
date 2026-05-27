import { useEffect, useRef, useState } from "react";
import { Check, Copy, ShieldAlert } from "lucide-react";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

export interface ApiSecretRevealProps {
  /** The freshly minted (or rotated) secret value, in cleartext. */
  secret: string;
  /** Called when the operator acknowledges that they've saved the secret. */
  onAcknowledge: () => void;
  /** Optional client-name hint surfaced in the warning copy. */
  clientLabel?: string;
  className?: string;
}

const COPY_RESET_MS = 2000;

/**
 * Reveal-once banner shown immediately after an API-client secret is created
 * or rotated. The cleartext secret is rendered exactly once: the operator must
 * copy it to a safe location, then click "I've saved it" to dismiss the
 * banner — at which point the parent unmounts the component and only metadata
 * remains.
 *
 * The component intentionally does not persist its own dismissed state. The
 * parent owns the "do we still have a secret in flight" flag so a tab refresh
 * cannot accidentally re-render a stale secret.
 *
 * Pattern parallels — but does not import from — `PiiRevealDialog` /
 * `MaskedField`. Secrets are a separate audit stream from PII reveals.
 */
export function ApiSecretReveal({
  secret,
  onAcknowledge,
  clientLabel,
  className,
}: ApiSecretRevealProps) {
  const [copied, setCopied] = useState(false);
  const timerRef = useRef<number | null>(null);

  // Always clear any pending reset timer when the component unmounts so we
  // don't call `setState` on an unmounted node after acknowledge fires.
  useEffect(() => {
    return () => {
      if (timerRef.current !== null) {
        window.clearTimeout(timerRef.current);
      }
    };
  }, []);

  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(secret);
    } catch {
      // Clipboard write can fail if the browser denies permission. We still
      // flip to the "copied" affordance so AT users get a state change; the
      // operator can fall back to selecting the secret manually.
    }
    setCopied(true);
    if (timerRef.current !== null) {
      window.clearTimeout(timerRef.current);
    }
    timerRef.current = window.setTimeout(() => {
      setCopied(false);
      timerRef.current = null;
    }, COPY_RESET_MS);
  };

  const subjectSuffix = clientLabel ? ` for ${clientLabel}` : "";

  return (
    <Alert
      data-slot="api-secret-one-time"
      className={cn(
        "border-warning/40 bg-warning/5 text-foreground [&>svg]:text-warning",
        className,
      )}
    >
      <ShieldAlert aria-hidden="true" />
      <AlertTitle>Save this secret now</AlertTitle>
      <AlertDescription>
        <p>
          This is the only time you&rsquo;ll see this secret{subjectSuffix} in cleartext. After
          acknowledging, only metadata is shown.
        </p>
        <div className="mt-3 flex w-full flex-col gap-2">
          <code
            data-slot="api-secret-value"
            className="bg-surface-muted text-foreground block w-full overflow-x-auto rounded-md border px-3 py-2 font-mono text-sm tabular-nums"
          >
            {secret}
          </code>
          <div
            data-slot="api-secret-announce"
            aria-live="polite"
            aria-atomic="true"
            className="sr-only"
          >
            Secret available once. Copy now.
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={handleCopy}
              aria-label="Copy secret to clipboard"
              data-slot="api-secret-copy"
              data-copied={copied ? "true" : "false"}
            >
              {copied ? (
                <Check className="h-4 w-4" aria-hidden="true" />
              ) : (
                <Copy className="h-4 w-4" aria-hidden="true" />
              )}
              <span>{copied ? "Copied" : "Copy"}</span>
            </Button>
            <Button
              type="button"
              variant="default"
              size="sm"
              onClick={onAcknowledge}
              data-slot="api-secret-ack"
            >
              I&rsquo;ve saved it
            </Button>
          </div>
        </div>
      </AlertDescription>
    </Alert>
  );
}
