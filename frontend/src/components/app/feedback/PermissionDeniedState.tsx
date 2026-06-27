import { forwardRef, type HTMLAttributes } from "react";
import { Lock } from "lucide-react";
import { EmptyState, type EmptyStateProps } from "./EmptyState";
import { cn } from "@/lib/utils";

export interface PermissionDeniedStateProps extends Omit<HTMLAttributes<HTMLDivElement>, "title"> {
  title?: string;
  description?: string;
  /** The role currently in session — surfaced to the user for context. */
  currentRole?: string;
  /** Permission(s) that would unblock this view, surfaced for support. */
  missingPermission?: string;
  action?: EmptyStateProps["action"];
  secondaryAction?: EmptyStateProps["secondaryAction"];
  className?: string;
}

const DEFAULT_TITLE = "You don't have access to this page";

/**
 * Domain-agnostic permission-denied screen. Wraps `EmptyState` with the
 * `no-permission` variant + `Lock` icon. Builds a default description by
 * combining the current role and the missing permission when both are
 * supplied; otherwise renders whatever `description` the caller passes.
 */
export const PermissionDeniedState = forwardRef<HTMLDivElement, PermissionDeniedStateProps>(
  function PermissionDeniedState(
    {
      title = DEFAULT_TITLE,
      description,
      currentRole,
      missingPermission,
      action,
      secondaryAction,
      className,
      ...rest
    },
    ref,
  ) {
    const composed =
      description ??
      (currentRole && missingPermission
        ? `Signed in as ${currentRole}. This area requires the ${missingPermission} permission — contact your administrator if you need access.`
        : missingPermission
          ? `This area requires the ${missingPermission} permission.`
          : undefined);

    return (
      <EmptyState
        ref={ref}
        variant="no-permission"
        icon={Lock}
        title={title}
        description={composed}
        action={action}
        secondaryAction={secondaryAction}
        className={cn(className)}
        {...rest}
      />
    );
  },
);
