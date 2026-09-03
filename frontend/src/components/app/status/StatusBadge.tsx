import { forwardRef, type HTMLAttributes } from "react";
import type { LucideIcon } from "lucide-react";
import {
  AlertTriangle,
  Check,
  CheckCircle2,
  CircleCheck,
  CircleDot,
  CircleSlash,
  Clock,
  HelpCircle,
  Hourglass,
  PauseCircle,
  Send,
  XCircle,
} from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { cn } from "@/lib/utils";
import type { Intent } from "@/lib/lifecycle";
import { resolveStatusMeta, type AnyStatus, type StatusBadgeDelinquency } from "./statusBadgeMeta";

export type { AnyStatus } from "./statusBadgeMeta";
export type StatusBadgeVariant = "default" | "subtle";

interface StatusBadgeProps extends Omit<HTMLAttributes<HTMLSpanElement>, "children"> {
  status: AnyStatus;
  /** Gap #11 — when set, may override {@code UNDER_REPAYMENT} badge intent. */
  delinquency?: StatusBadgeDelinquency;
  variant?: StatusBadgeVariant;
  /** Hide the leading icon (color + label still convey meaning). */
  hideIcon?: boolean;
  className?: string;
}

const INTENT_ICON: Record<Intent, LucideIcon> = {
  success: CheckCircle2,
  warning: AlertTriangle,
  danger: XCircle,
  info: CircleDot,
  progress: Hourglass,
  neutral: CircleDot,
  revoked: CircleSlash,
};

const STATUS_ICON: Partial<Record<AnyStatus, LucideIcon>> = {
  INITIALIZED: Send,
  AWAITING_APPROVAL: Clock,
  APPROVED_PENDING_DISBURSAL: Clock,
  DISBURSED: CircleCheck,
  CLOSED: CircleCheck,
  FORECLOSED: PauseCircle,
  REJECTED: XCircle,
  INVALID: CircleSlash,
  DISBURSEMENT_RETRY: AlertTriangle,
  UNDER_REPAYMENT: Check,
};

/*
  The solid variant's text is `text-surface`, not `text-white`.

  The intents invert between themes: light fills are dark (`#7d5e14`), dark fills
  are lifted and bright (`#fbbf24`). White text therefore only worked in light —
  in dark it measured 1.66:1 on `warning` and 2.69:1 on `progress`. `--color-surface`
  is white in light and near-black in dark, so it is always the opposite pole of
  whatever the fill is.
*/
const INTENT_CLASSES: Record<Intent, { default: string; subtle: string; iconColor: string }> = {
  success: {
    default: "border-transparent bg-success text-surface",
    subtle: "border-success/30 bg-success/10 text-success",
    iconColor: "text-success",
  },
  warning: {
    default: "border-transparent bg-warning text-surface",
    subtle: "border-warning/30 bg-warning/10 text-warning",
    iconColor: "text-warning",
  },
  danger: {
    default: "border-transparent bg-danger text-surface",
    subtle: "border-danger/30 bg-danger/10 text-danger",
    iconColor: "text-danger",
  },
  info: {
    default: "border-transparent bg-info text-surface",
    subtle: "border-info/30 bg-info/10 text-info",
    iconColor: "text-info",
  },
  progress: {
    default: "border-transparent bg-progress text-surface",
    subtle: "border-progress/30 bg-progress/10 text-progress",
    iconColor: "text-progress",
  },
  revoked: {
    default: "border-transparent bg-revoked text-surface",
    subtle: "border-revoked/30 bg-revoked/10 text-revoked",
    iconColor: "text-revoked",
  },
  neutral: {
    default: "border-border-strong bg-surface-muted text-foreground",
    subtle: "border-border bg-surface-muted text-foreground-muted",
    iconColor: "text-foreground-muted",
  },
};

/**
 * Intents that mean "something is wrong". When a delinquency override pushes a
 * status into one of these, the icon must follow the *intent*, not the status —
 * otherwise a delinquent `UNDER_REPAYMENT` loan renders `Check` inside a red
 * badge, and the icon contradicts the colour instead of reinforcing it. The
 * whole point of pairing colour with an icon (WCAG 1.4.1) is that the second
 * channel agrees with the first.
 */
const ADVERSE_INTENTS = new Set<Intent>(["danger", "warning"]);

function statusIcon(status: AnyStatus, intent: Intent): LucideIcon {
  if (typeof status === "string" && status.startsWith("UNKNOWN:")) {
    return HelpCircle;
  }
  if (ADVERSE_INTENTS.has(intent)) {
    return INTENT_ICON[intent];
  }
  return STATUS_ICON[status] ?? INTENT_ICON[intent];
}

export const StatusBadge = forwardRef<HTMLSpanElement, StatusBadgeProps>(function StatusBadge(
  { status, delinquency, variant = "subtle", hideIcon = false, className, ...rest },
  ref,
) {
  const meta = resolveStatusMeta(status, { delinquency });
  const Icon = statusIcon(status, meta.intent);
  const classes = INTENT_CLASSES[meta.intent];
  const tone = variant === "default" ? classes.default : classes.subtle;
  return (
    <Badge
      ref={ref}
      data-slot="status-badge"
      data-status={status}
      data-intent={meta.intent}
      data-variant={variant}
      className={cn(tone, className)}
      {...rest}
    >
      {hideIcon ? null : (
        <Icon
          aria-hidden="true"
          className={cn("size-3", variant === "default" ? "text-current" : classes.iconColor)}
        />
      )}
      <span>{meta.label}</span>
    </Badge>
  );
});
