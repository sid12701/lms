import { NavLink } from "react-router-dom";
import type { LucideIcon } from "lucide-react";
import { cn } from "@/lib/utils";

export interface SidebarItemProps {
  to: string;
  label: string;
  icon: LucideIcon;
  /** When true, renders icon + accessible label only (collapsed sidebar). */
  collapsed?: boolean;
  match?: "exact" | "startsWith";
  onNavigate?: () => void;
}

/**
 * Renders a single sidebar nav link. The active state is a 10% primary tint
 * with `--color-primary-tinted` text (a dark-safe foreground — the preset's
 * dark `--primary` is a background value and measured 2.23:1 as text) plus a
 * 2px primary bar on the leading edge. Focus-visible ring, reduced-motion safe.
 */
export function SidebarItem({
  to,
  label,
  icon: Icon,
  collapsed = false,
  match = "startsWith",
  onNavigate,
}: SidebarItemProps) {
  return (
    <NavLink
      to={to}
      end={match === "exact"}
      onClick={onNavigate}
      className={({ isActive }) =>
        cn(
          "group focus-visible:ring-ring rounded-control relative flex items-center gap-3 px-3 py-2 text-sm font-medium transition-colors duration-150 outline-none focus-visible:ring-2",
          isActive
            ? "bg-primary/10 text-primary-tinted"
            : "text-foreground-muted hover:bg-surface-muted hover:text-foreground",
          collapsed && "justify-center px-2",
        )
      }
    >
      {({ isActive }) => (
        <>
          {isActive ? (
            <span
              aria-hidden="true"
              className="bg-primary absolute top-1.5 bottom-1.5 left-0 w-0.5 rounded-r"
            />
          ) : null}
          <Icon aria-hidden="true" className="h-4 w-4 shrink-0" />
          {collapsed ? <span className="sr-only">{label}</span> : <span>{label}</span>}
        </>
      )}
    </NavLink>
  );
}
