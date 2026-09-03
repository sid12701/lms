import { forwardRef, type HTMLAttributes } from "react";
import { CheckCircle2, CircleDot } from "lucide-react";
import type { LucideIcon } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { cn } from "@/lib/utils";
import type { Intent } from "@/lib/lifecycle";
import { resolvePaymentStatusMeta } from "./paymentStatusMeta";

const INTENT_ICON: Record<Intent, LucideIcon> = {
  success: CheckCircle2,
  warning: CircleDot,
  danger: CircleDot,
  info: CircleDot,
  progress: CircleDot,
  neutral: CircleDot,
  revoked: CircleDot,
};

const INTENT_CLASSES: Record<Intent, string> = {
  success: "border-success/30 bg-success/10 text-success",
  warning: "border-warning/30 bg-warning/10 text-warning",
  danger: "border-danger/30 bg-danger/10 text-danger",
  info: "border-info/30 bg-info/10 text-info",
  progress: "border-progress/30 bg-progress/10 text-progress",
  neutral: "border-border bg-surface-muted text-foreground-muted",
  revoked: "border-revoked/30 bg-revoked/10 text-revoked",
};

interface PaymentStatusBadgeProps extends Omit<HTMLAttributes<HTMLSpanElement>, "children"> {
  status: string;
  hideIcon?: boolean;
  className?: string;
}

export const PaymentStatusBadge = forwardRef<HTMLSpanElement, PaymentStatusBadgeProps>(
  function PaymentStatusBadge({ status, hideIcon = false, className, ...rest }, ref) {
    const meta = resolvePaymentStatusMeta(status);
    const Icon = INTENT_ICON[meta.intent];

    return (
      <Badge
        ref={ref}
        data-slot="payment-status-badge"
        data-status={status}
        data-intent={meta.intent}
        className={cn(INTENT_CLASSES[meta.intent], className)}
        {...rest}
      >
        {hideIcon ? null : (
          <Icon aria-hidden="true" className={cn("size-3", "text-current opacity-80")} />
        )}
        <span>{meta.label}</span>
      </Badge>
    );
  },
);
