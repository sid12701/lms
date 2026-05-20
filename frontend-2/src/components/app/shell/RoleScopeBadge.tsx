import { useSession } from "@/features/auth/session-context";
import { isInternalUser } from "@/lib/role-gates";
import { cn } from "@/lib/utils";

export interface RoleScopeBadgeProps {
  className?: string;
  /** When the user is LSP, optionally pass the LSP name to surface in the chip. */
  lspName?: string;
}

/**
 * Compact chip showing which scope the actor is operating in. For internal
 * users it reads "Internal · All LSPs"; for LSP users, "LSP · {name}".
 */
export function RoleScopeBadge({ className, lspName }: RoleScopeBadgeProps) {
  const { session } = useSession();
  if (!session) return null;
  const internal = isInternalUser(session.user.role);
  const label = internal ? "Internal · All LSPs" : `LSP · ${lspName ?? "Bhawana Demo LSP"}`;
  return (
    <span
      data-slot="role-scope-badge"
      className={cn(
        "inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-medium",
        internal ? "bg-brand-50 text-brand-700" : "bg-accent/10 text-accent-700",
        className,
      )}
    >
      <span
        aria-hidden="true"
        className={cn(
          "inline-block h-1.5 w-1.5 rounded-full",
          internal ? "bg-brand-700" : "bg-accent-700",
        )}
      />
      {label}
    </span>
  );
}
