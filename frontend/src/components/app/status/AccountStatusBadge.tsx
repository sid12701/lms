import { forwardRef, type HTMLAttributes } from "react";
import type { LoanAccountStatus } from "@/types";
import { StatusBadge, type StatusBadgeVariant } from "./StatusBadge";

interface AccountStatusBadgeProps extends Omit<HTMLAttributes<HTMLSpanElement>, "children"> {
  status: LoanAccountStatus;
  variant?: StatusBadgeVariant;
  hideIcon?: boolean;
  className?: string;
}

/**
 * Thin wrapper over `StatusBadge` typed specifically to `LoanAccountStatus`.
 * Use this where a column / cell renders an account-level status to make
 * intent obvious at the call site.
 */
export const AccountStatusBadge = forwardRef<HTMLSpanElement, AccountStatusBadgeProps>(
  function AccountStatusBadge({ status, ...rest }, ref) {
    return <StatusBadge ref={ref} status={status} {...rest} />;
  },
);
