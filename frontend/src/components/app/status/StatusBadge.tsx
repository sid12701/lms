import { forwardRef, type HTMLAttributes } from "react";
import type { LucideIcon } from "lucide-react";
import {
  AlertTriangle,
  Ban,
  Check,
  CheckCircle2,
  Clock,
  CircleCheck,
  CircleDot,
  CircleSlash,
  FileSearch,
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

export interface StatusBadgeProps extends Omit<HTMLAttributes<HTMLSpanElement>, "children"> {
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
  INITIATED: Send,
  KYC_PENDING: FileSearch,
  DOCS_PENDING: FileSearch,
  UNDER_REVIEW: FileSearch,
  AWAITING_APPROVAL: Clock,
  APPROVED: Check,
  APPROVED_PENDING_DISBURSAL: Clock,
  DISBURSED: CircleCheck,
  FULLY_REPAID: CircleCheck,
  CLOSED: CircleCheck,
  FORECLOSED: PauseCircle,
  CANCELLED: Ban,
  REJECTED: XCircle,
  INVALIDATED: CircleSlash,
};

const INTENT_CLASSES: Record<Intent, { default: string; subtle: string; iconColor: string }> = {
  success: {
    default: "border-transparent bg-success text-white",
    subtle: "border-success/30 bg-success/10 text-success",
    iconColor: "text-success",
  },
  warning: {
    default: "border-transparent bg-warning text-white",
    subtle: "border-warning/30 bg-warning/10 text-warning",
    iconColor: "text-warning",
  },
  danger: {
    default: "border-transparent bg-danger text-white",
    subtle: "border-danger/30 bg-danger/10 text-danger",
    iconColor: "text-danger",
  },
  info: {
    default: "border-transparent bg-info text-white",
    subtle: "border-info/30 bg-info/10 text-info",
    iconColor: "text-info",
  },
  progress: {
    default: "border-transparent bg-progress text-white",
    subtle: "border-progress/30 bg-progress/10 text-progress",
    iconColor: "text-progress",
  },
  revoked: {
    default: "border-transparent bg-revoked text-white",
    subtle: "border-revoked/30 bg-revoked/10 text-revoked",
    iconColor: "text-revoked",
  },
  neutral: {
    default: "border-border-strong bg-surface-muted text-foreground",
    subtle: "border-border bg-surface-muted text-foreground-muted",
    iconColor: "text-foreground-muted",
  },
};

export const StatusBadge = forwardRef<HTMLSpanElement, StatusBadgeProps>(function StatusBadge(
  { status, delinquency, variant = "subtle", hideIcon = false, className, ...rest },
  ref,
) {
  const meta = resolveStatusMeta(status, { delinquency });
  const Icon = STATUS_ICON[status] ?? INTENT_ICON[meta.intent];
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
