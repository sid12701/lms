import { useNavigate } from "react-router-dom";
import { Bell, Menu } from "lucide-react";
import { Button } from "@/components/ui/button";
import { RoleScopeBadge } from "./RoleScopeBadge";
import { ThemeToggle } from "./ThemeToggle";
import { UserMenu } from "./UserMenu";
import { useSession } from "@/features/auth/session-context";
import { cn } from "@/lib/utils";

export interface TopBarProps {
  /** Mobile-only menu trigger. When provided, renders the hamburger on < lg. */
  onOpenMobileNav?: () => void;
  className?: string;
}

const INTERNAL_ALERT_ROLES = new Set(["SYSTEM_ADMIN", "OPS_USER"]);

/**
 * Sticky 56px top bar. Renders a mobile hamburger, a brand mark (mobile-only —
 * sidebar owns it on lg+), the role/scope chip, notifications, the user menu,
 * and the theme toggle.
 */
export function TopBar({ onOpenMobileNav, className }: TopBarProps) {
  const navigate = useNavigate();
  const { session } = useSession();
  const showAlerts = session?.user.role != null && INTERNAL_ALERT_ROLES.has(session.user.role);

  return (
    <header
      data-slot="top-bar"
      className={cn(
        "bg-surface sticky top-0 z-40 flex h-14 shrink-0 items-center gap-3 border-b border-(--color-border) px-4",
        className,
      )}
    >
      {onOpenMobileNav ? (
        <Button
          type="button"
          variant="ghost"
          size="icon-sm"
          aria-label="Open navigation"
          className="lg:hidden"
          onClick={onOpenMobileNav}
        >
          <Menu className="h-4 w-4" />
        </Button>
      ) : null}

      {/* Brand mark — visible only on mobile (sidebar owns it on lg+). */}
      <div className="flex flex-1 items-center gap-2 lg:hidden">
        <span aria-hidden="true" className="bg-primary inline-block h-6 w-6 rounded" />
        <span className="text-foreground text-sm font-semibold">Bhawana</span>
      </div>

      <div className="hidden flex-1 lg:block" aria-hidden="true" />

      <RoleScopeBadge className="hidden md:inline-flex" />

      {showAlerts ? (
        <Button
          type="button"
          variant="ghost"
          size="icon-sm"
          aria-label="Notifications"
          title="Open alerts"
          onClick={() => navigate("/alerts")}
        >
          <Bell className="h-4 w-4" />
        </Button>
      ) : null}

      <ThemeToggle />

      <UserMenu />
    </header>
  );
}
