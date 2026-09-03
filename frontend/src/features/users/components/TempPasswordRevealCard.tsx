import { useEffect, useRef, useState } from "react";
import { Check, Copy, KeyRound } from "lucide-react";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

export interface TempPasswordRevealCardProps {
  /** The freshly minted temporary password, in cleartext. */
  password: string;
  /** Called when the operator acknowledges that they've saved the password. */
  onAcknowledge: () => void;
  /** Optional username hint surfaced in the warning copy. */
  username?: string;
  className?: string;
}

const COPY_RESET_MS = 2000;

/**
 * Reveal-once card for a one-time temporary password — shown immediately
 * after `createUser` or `resetUserPassword`. Mirrors `ApiSecretReveal` in
 * structure (Alert + code block + copy button + acknowledge) but is a
 * separate component on purpose: PII reveal, API-secret reveal, and
 * temp-password reveal are three DISTINCT reveal-once surfaces in this
 * codebase. Each carries its own audit semantics and copy.
 *
 * The parent owns the "do we still have a temp password in flight" flag so a
 * tab refresh cannot accidentally re-render a stale value.
 */
export function TempPasswordRevealCard({
  password,
  onAcknowledge,
  username,
  className,
}: TempPasswordRevealCardProps) {
  const [copied, setCopied] = useState(false);
  const timerRef = useRef<number | null>(null);

  useEffect(() => {
    return () => {
      if (timerRef.current !== null) {
        window.clearTimeout(timerRef.current);
      }
    };
  }, []);

  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(password);
    } catch {
      // Clipboard write can fail if the browser denies permission. We still
      // flip the "copied" affordance so AT users get a state change.
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

  const subjectSuffix = username ? ` for ${username}` : "";

  return (
    <Alert
      data-slot="temp-password-one-time"
      className={cn(
        "border-warning/40 bg-warning/5 text-foreground [&>svg]:text-warning",
        className,
      )}
    >
      <KeyRound aria-hidden="true" />
      <AlertTitle>Save this temporary password now</AlertTitle>
      <AlertDescription>
        <p>
          This is the only time you&rsquo;ll see this temporary password
          {subjectSuffix} in cleartext. After acknowledging, only the &ldquo;must change
          password&rdquo; flag remains on the user.
        </p>
        <div className="mt-3 flex w-full flex-col gap-2">
          <code
            data-slot="temp-password-value"
            className="bg-surface-muted text-foreground rounded-container block w-full overflow-x-auto border px-3 py-2 font-mono text-sm tabular-nums"
          >
            {password}
          </code>
          <div
            data-slot="temp-password-announce"
            aria-live="polite"
            aria-atomic="true"
            className="sr-only"
          >
            Temporary password available once. Copy now.
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={handleCopy}
              aria-label="Copy temporary password to clipboard"
              data-slot="temp-password-copy"
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
              data-slot="temp-password-ack"
            >
              I&rsquo;ve saved it
            </Button>
          </div>
        </div>
      </AlertDescription>
    </Alert>
  );
}
