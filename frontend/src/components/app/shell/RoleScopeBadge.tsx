import { useSession } from "@/features/auth/session-context";
import { isInternalUser } from "@/lib/role-gates";
import { cn } from "@/lib/utils";

export interface RoleScopeBadgeProps {
  className?: string;
}

/**
 * Compact chip showing which scope the actor is operating in. For internal
 * users it reads "Internal · All LSPs"; for LSP users, "LSP · {name}".
 */
export function RoleScopeBadge({ className }: RoleScopeBadgeProps) {
  const { session } = useSession();
  if (!session) return null;
  const internal = isInternalUser(session.user.role);
  const lspLabel = session.user.lspName?.trim();
  const label = internal ? "Internal · All LSPs" : lspLabel ? `LSP · ${lspLabel}` : "LSP workspace";
  return (
    <span
      data-slot="role-scope-badge"
      className={cn(
        "inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-medium",
        internal ? "bg-primary/10 text-primary-tinted" : "bg-muted text-foreground",
        className,
      )}
    >
      <span
        aria-hidden="true"
        className={cn(
          "inline-block h-1.5 w-1.5 rounded-full",
          internal ? "bg-primary" : "bg-muted-foreground",
        )}
      />
      {label}
    </span>
  );
}
