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
import { STATUS_META, type Intent } from "@/lib/lifecycle";
import type { LoanAccountStatus, LoanStatus } from "@/types";

export type AnyStatus = LoanStatus | LoanAccountStatus;
export type StatusBadgeVariant = "default" | "subtle";

export interface StatusBadgeProps extends Omit<HTMLAttributes<HTMLSpanElement>, "children"> {
  status: AnyStatus;
  variant?: StatusBadgeVariant;
  /** Hide the leading icon (color + label still convey meaning). */
  hideIcon?: boolean;
  className?: string;
}

interface ResolvedMeta {
  label: string;
  intent: Intent;
}

/**
 * Loan-account statuses live in a separate enum from `LoanStatus` and are
 * not present in `STATUS_META`. We map them onto the same intent vocabulary
 * here so a single component can render both.
 */
const ACCOUNT_STATUS_META: Record<LoanAccountStatus, ResolvedMeta> = {
  PENDING_DISBURSEMENT: { label: "Pending disbursement", intent: "progress" },
  ACTIVE: { label: "Active", intent: "success" },
  CLOSED: { label: "Closed", intent: "neutral" },
  FORECLOSED: { label: "Foreclosed", intent: "neutral" },
};

/** Lookup that succeeds for either enum. Always returns a non-empty meta. */
export function resolveStatusMeta(status: AnyStatus): ResolvedMeta {
  if (status in STATUS_META) {
    const m = STATUS_META[status as LoanStatus];
    return { label: m.label, intent: m.intent };
  }
  if (status in ACCOUNT_STATUS_META) {
    return ACCOUNT_STATUS_META[status as LoanAccountStatus];
  }
  // Should be unreachable thanks to the AnyStatus union, but a defensive
  // fallback keeps colour-rendering from blowing up if a new enum value lands
  // before the meta tables are updated.
  return { label: String(status), intent: "neutral" };
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

/**
 * Per-status icon overrides — chosen for semantic clarity in the LMS
 * lifecycle (e.g. "Initiated" reads better with a Send icon than a generic
 * neutral dot). Falls through to `INTENT_ICON` when no override exists.
 */
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

/** Solid + subtle Tailwind class triplets keyed by intent. */
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

/**
 * Generic status pill for both `LoanStatus` and `LoanAccountStatus` values.
 *
 * Color is never the only signal — every badge ships with text, and (unless
 * `hideIcon` is set) a Lucide icon matched to the lifecycle intent.
 */
export const StatusBadge = forwardRef<HTMLSpanElement, StatusBadgeProps>(function StatusBadge(
  { status, variant = "subtle", hideIcon = false, className, ...rest },
  ref,
) {
  const meta = resolveStatusMeta(status);
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
