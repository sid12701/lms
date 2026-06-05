import { LogOut } from "lucide-react";
import { Button } from "@/components/ui/button";
import { PRODUCT_ORGANIZATION_NAME, PRODUCT_TAGLINE } from "@/lib/product-branding";
import { useSession } from "@/features/auth/session-context";
import { getNavItems } from "./nav-items";
import { SidebarItem } from "./SidebarItem";
import { cn } from "@/lib/utils";
import { useNavigate } from "react-router-dom";

export interface SidebarProps {
  /** When true, renders icon-only width. Mobile/lg breakpoint. */
  collapsed?: boolean;
  /** Called after a nav item is clicked — used by the mobile drawer to close. */
  onNavigate?: () => void;
  className?: string;
}

/**
 * Role-aware sidebar with brand block at top, grouped nav, and an actor
 * footer with sign-out.
 */
export function Sidebar({ collapsed = false, onNavigate, className }: SidebarProps) {
  const { session, signOut } = useSession();
  const navigate = useNavigate();
  if (!session) return null;

  const groups = getNavItems(session.user.role);

  return (
    <aside
      aria-label="Primary navigation"
      data-collapsed={collapsed || undefined}
      className={cn(
        "bg-surface flex h-full shrink-0 flex-col border-r border-[var(--color-border)]",
        collapsed ? "w-16" : "w-64",
        className,
      )}
    >
      {/* Brand block */}
      <div
        className={cn(
          "flex items-center border-b border-[var(--color-border)] px-4 py-4",
          collapsed && "px-3",
        )}
      >
        {collapsed ? (
          <span aria-hidden="true" className="bg-primary inline-block h-8 w-8 rounded">
            <span className="sr-only">{PRODUCT_ORGANIZATION_NAME}</span>
          </span>
        ) : (
          <div>
            <p className="text-foreground text-sm leading-tight font-semibold">
              {PRODUCT_ORGANIZATION_NAME}
            </p>
            <p className="text-foreground-muted text-xs">{PRODUCT_TAGLINE}</p>
          </div>
        )}
      </div>

      {/* Nav */}
      <nav className="flex-1 overflow-y-auto px-3 py-4" aria-label="Sidebar navigation">
        {groups.map((group) => (
          <div key={group.label} className="mb-5 last:mb-0">
            {!collapsed ? (
              <p className="text-foreground-subtle mb-2 px-3 text-[11px] font-semibold tracking-[0.08em] uppercase">
                {group.label}
              </p>
            ) : null}
            <ul className="flex flex-col gap-1">
              {group.items.map((item) => (
                <li key={item.to}>
                  <SidebarItem
                    to={item.to}
                    label={item.label}
                    icon={item.icon}
                    match={item.match}
                    collapsed={collapsed}
                    onNavigate={onNavigate}
                  />
                </li>
              ))}
            </ul>
          </div>
        ))}
      </nav>

      {/* Actor footer */}
      <div
        className={cn(
          "border-t border-[var(--color-border)] px-3 py-3",
          collapsed ? "items-center" : "",
        )}
      >
        {collapsed ? (
          <Button
            type="button"
            variant="ghost"
            size="icon-sm"
            aria-label="Sign out"
            onClick={async () => {
              await signOut();
              navigate("/login", { replace: true });
            }}
          >
            <LogOut className="h-4 w-4" />
          </Button>
        ) : (
          <div className="flex items-center justify-between gap-2">
            <div className="min-w-0">
              <p className="text-foreground truncate text-sm font-medium">
                {session.user.username}
              </p>
              <p className="text-foreground-muted text-xs">{session.user.role}</p>
            </div>
            <Button
              type="button"
              variant="ghost"
              size="icon-sm"
              aria-label="Sign out"
              onClick={async () => {
                await signOut();
                navigate("/login", { replace: true });
              }}
            >
              <LogOut className="h-4 w-4" />
            </Button>
          </div>
        )}
      </div>
    </aside>
  );
}
