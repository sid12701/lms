import { format } from "date-fns";
import { RefreshCw } from "lucide-react";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

export interface ApiClientSecretMetaProps {
  /** Non-secret prefix of the API key id, e.g. "ak_live_8f2b". */
  keyIdPrefix: string;
  /** ISO timestamp of secret creation. */
  createdAt: string;
  /** ISO timestamp of last rotation, or null when never rotated. */
  lastRotatedAt: string | null;
  /** Number of CIDR ranges in the per-client IP allow-list (BR-8). */
  ipAllowlistCount: number;
  /** Optional rotate handler — when omitted, the rotate button is hidden. */
  onRotate?: () => void;
  className?: string;
}

const DATE_FMT = "dd MMM yyyy HH:mm 'IST'";

function formatTimestamp(value: string): string {
  // Defensive against bad fixture input — surface the raw value rather than
  // crashing the card if a date is malformed.
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return value;
  return format(d, DATE_FMT);
}

/**
 * Renders the non-secret metadata for an API-client credential. The secret
 * itself is never displayed by this component — that's the reveal-once
 * banner's job. Operators can trigger a rotation via the optional rotate
 * button, which the parent wires to `RotateSecretDialog`.
 */
export function ApiClientSecretMeta({
  keyIdPrefix,
  createdAt,
  lastRotatedAt,
  ipAllowlistCount,
  onRotate,
  className,
}: ApiClientSecretMetaProps) {
  return (
    <Card data-slot="api-client-secret-meta" className={cn(className)}>
      <CardHeader>
        <CardTitle>API client secret</CardTitle>
      </CardHeader>
      <CardContent>
        <dl className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <div data-slot="meta-row" className="flex flex-col gap-1">
            <dt className="text-muted-foreground text-xs tracking-wide uppercase">Key id</dt>
            <dd data-slot="meta-key-id" className="font-mono text-sm tabular-nums">
              {keyIdPrefix}
            </dd>
          </div>
          <div data-slot="meta-row" className="flex flex-col gap-1">
            <dt className="text-muted-foreground text-xs tracking-wide uppercase">Created</dt>
            <dd data-slot="meta-created" className="text-sm tabular-nums">
              {formatTimestamp(createdAt)}
            </dd>
          </div>
          <div data-slot="meta-row" className="flex flex-col gap-1">
            <dt className="text-muted-foreground text-xs tracking-wide uppercase">Last rotated</dt>
            <dd data-slot="meta-rotated" className="text-sm tabular-nums">
              {lastRotatedAt === null ? "Never rotated" : formatTimestamp(lastRotatedAt)}
            </dd>
          </div>
          <div data-slot="meta-row" className="flex flex-col gap-1">
            <dt className="text-muted-foreground text-xs tracking-wide uppercase">IP allow-list</dt>
            <dd data-slot="meta-allowlist" className="text-sm tabular-nums">
              {ipAllowlistCount} {ipAllowlistCount === 1 ? "entry" : "entries"}
            </dd>
          </div>
        </dl>

        {onRotate ? (
          <div className="mt-4 flex justify-end">
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={onRotate}
              data-slot="meta-rotate"
            >
              <RefreshCw className="h-4 w-4" aria-hidden="true" />
              <span>Rotate secret</span>
            </Button>
          </div>
        ) : null}
      </CardContent>
    </Card>
  );
}
