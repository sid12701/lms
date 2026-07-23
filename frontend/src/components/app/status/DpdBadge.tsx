import { forwardRef, type HTMLAttributes } from "react";
import { AlertTriangle, CheckCircle2, CircleSlash, XCircle } from "lucide-react";
import type { LucideIcon } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { cn } from "@/lib/utils";
import type { DelinquencyBucket } from "@/types";

/** Extended bucket: schemas top out at B90_PLUS; NPA is surfaced separately. */
export type DpdBucketKey = DelinquencyBucket | "NPA";

interface BucketDef {
  label: string;
  tone: string;
  icon: LucideIcon;
}

/**
 * DPD bucket meta — matches the design-system spec in plan §5.5.
 *
 *   B0      → success
 *   B1_30   → warning (light)
 *   B31_60  → warning
 *   B61_90  → danger (light)
 *   B90_PLUS→ danger
 *   NPA     → revoked
 */
const BUCKET_META: Record<DpdBucketKey, BucketDef> = {
  B0: {
    label: "Current (0 DPD)",
    tone: "border-success/30 bg-success/10 text-success",
    icon: CheckCircle2,
  },
  B1_30: {
    label: "1–30 DPD",
    tone: "border-warning/30 bg-warning/10 text-warning",
    icon: AlertTriangle,
  },
  B31_60: {
    label: "31–60 DPD",
    tone: "border-warning/40 bg-warning/15 text-warning",
    icon: AlertTriangle,
  },
  B61_90: {
    label: "61–90 DPD",
    tone: "border-danger/30 bg-danger/10 text-danger",
    icon: XCircle,
  },
  B90_PLUS: {
    label: "90+ DPD",
    tone: "border-danger/40 bg-danger/15 text-danger",
    icon: XCircle,
  },
  NPA: {
    label: "NPA",
    tone: "border-revoked/30 bg-revoked/10 text-revoked",
    icon: CircleSlash,
  },
};

interface DpdBadgeProps extends Omit<HTMLAttributes<HTMLSpanElement>, "children"> {
  bucket: DpdBucketKey;
  /** Optional concrete day count to append, e.g. "31–60 DPD · 47 days". */
  days?: number;
  className?: string;
}

/**
 * Bucketed delinquency indicator. Always renders the bucket label + icon
 * + colour token; passing `days` appends the specific count for cell-level
 * precision in the repayment schedule table.
 */
export const DpdBadge = forwardRef<HTMLSpanElement, DpdBadgeProps>(function DpdBadge(
  { bucket, days, className, ...rest },
  ref,
) {
  const meta = BUCKET_META[bucket];
  const Icon = meta.icon;
  return (
    <Badge
      ref={ref}
      data-slot="dpd-badge"
      data-bucket={bucket}
      className={cn(meta.tone, className)}
      {...rest}
    >
      <Icon aria-hidden="true" className="size-3 text-current" />
      <span>
        {meta.label}
        {typeof days === "number" ? (
          <>
            {" · "}
            <span className="font-mono">{days}d</span>
          </>
        ) : null}
      </span>
    </Badge>
  );
});
