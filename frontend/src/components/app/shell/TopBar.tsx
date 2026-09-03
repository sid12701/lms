import { useNavigate } from "react-router-dom";
import { Bell, Menu } from "lucide-react";
import { Button } from "@/components/ui/button";
import { RoleScopeBadge } from "./RoleScopeBadge";
import { ThemeToggle } from "./ThemeToggle";
import { UserMenu } from "./UserMenu";
import { useSession } from "@/features/auth/session-context";
import { useAlerts } from "@/features/alerts/hooks/useAlerts";
import { cn } from "@/lib/utils";

export interface TopBarProps {
  /** Mobile-only menu trigger. When provided, renders the hamburger on < lg. */
  onOpenMobileNav?: () => void;
  className?: string;
}

const INTERNAL_ALERT_ROLES = new Set(["SYSTEM_ADMIN", "OPS_USER"]);

/** Anything above this renders as "9+" so the badge never widens the bar. */
const MAX_BADGE_COUNT = 9;

/**
 * Notifications bell, with the count it was missing.
 *
 * The audit's finding was that the bell carried no indicator while `/alerts`
 * held a HIGH-severity queue — the one control whose entire job is to say
 * "something needs you" said nothing, so the operator had to navigate to find
 * out. It is now a live count, and the accessible name states it rather than
 * relying on the badge glyph.
 */
function AlertsBell({ onOpen }: { onOpen: () => void }) {
  const { data } = useAlerts({ status: "OPEN", page: 0, pageSize: 1 });
  const count = data?.total ?? 0;
  const hasAlerts = count > 0;

  return (
    <Button
      type="button"
      variant="ghost"
      size="icon-sm"
      aria-label={hasAlerts ? `Notifications, ${count} open` : "Notifications, none open"}
      title={hasAlerts ? `${count} open alert${count === 1 ? "" : "s"}` : "No open alerts"}
      onClick={onOpen}
      data-slot="alerts-bell"
      data-open-count={count}
      className="relative"
    >
      <Bell className="h-4 w-4" />
      {hasAlerts ? (
        <span
          aria-hidden="true"
          className="bg-danger text-background text-badge absolute -top-1 -right-1 inline-flex h-4 min-w-4 items-center justify-center rounded-full px-1 font-semibold tabular-nums"
        >
          {count > MAX_BADGE_COUNT ? `${MAX_BADGE_COUNT}+` : count}
        </span>
      ) : null}
    </Button>
  );
}

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

      {showAlerts ? <AlertsBell onOpen={() => navigate("/alerts")} /> : null}

      <ThemeToggle />

      <UserMenu />
    </header>
  );
}
