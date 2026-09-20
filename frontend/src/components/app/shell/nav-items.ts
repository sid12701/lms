import {
  Home,
  FileText,
  Folder,
  Building2,
  Layers,
  Users,
  UserSquare2,
  KeyRound,
  Bell,
  BarChart3,
  ScrollText,
  type LucideIcon,
} from "lucide-react";
import type { Role } from "@/types";
import { hasAnyRole, isLspUiUser } from "@/lib/role-gates";

export interface NavItem {
  to: string;
  label: string;
  icon: LucideIcon;
  /** Used by tests + active-state matchers. */
  match?: "exact" | "startsWith";
}

export interface NavGroup {
  label: string;
  items: NavItem[];
}

const OPS_ROLES: readonly Role[] = ["SYSTEM_ADMIN", "OPS_USER"];
const PRODUCT_ROLES: readonly Role[] = ["SYSTEM_ADMIN", "PRODUCT_ADMIN"];

/**
 * Build the role-aware sidebar navigation tree.
 *
 * Gap #8: Home is admin-only. Non-admin roles never see the Home nav
 * entry; they land directly on their primary work surface after sign-in
 * (handled by `defaultLandingFor`).
 *
 * M19: takes the session's FULL role set — a multi-role user (e.g.
 * OPS_USER + PRODUCT_ADMIN) sees the union of every granted role's nav
 * items, matching what the route guards and backend allow. The primary
 * role only decides the landing route; it never hides a granted surface.
 */
export function getNavItems(roles: readonly Role[]): NavGroup[] {
  const workspace: NavItem[] = [];
  if (roles.includes("SYSTEM_ADMIN")) {
    workspace.push({ to: "/home", label: "Home", icon: Home, match: "exact" });
  }
  if (hasAnyRole(roles, OPS_ROLES)) {
    workspace.push({
      to: "/loan-applications",
      label: "Loan applications",
      icon: FileText,
      match: "startsWith",
    });
    workspace.push({
      to: "/borrowers",
      label: "Borrowers",
      icon: UserSquare2,
      match: "startsWith",
    });
  }
  if (isLspUiUser(roles)) {
    workspace.push({
      to: "/my-loans",
      label: "My loans",
      icon: Folder,
      match: "startsWith",
    });
  }

  const reporting: NavItem[] = [];
  if (hasAnyRole(roles, OPS_ROLES)) {
    reporting.push({ to: "/alerts", label: "Alerts", icon: Bell, match: "startsWith" });
  }
  if (roles.includes("SYSTEM_ADMIN")) {
    reporting.push({ to: "/reports", label: "Reports", icon: BarChart3, match: "startsWith" });
  }

  const administration: NavItem[] = [];
  if (roles.includes("SYSTEM_ADMIN")) {
    administration.push({ to: "/lsps", label: "LSPs", icon: Building2, match: "startsWith" });
  }
  if (hasAnyRole(roles, PRODUCT_ROLES)) {
    administration.push({ to: "/products", label: "Products", icon: Layers, match: "startsWith" });
  }
  if (roles.includes("SYSTEM_ADMIN")) {
    administration.push({ to: "/users", label: "Users", icon: Users, match: "startsWith" });
    administration.push({
      to: "/api-clients",
      label: "API clients",
      icon: KeyRound,
      match: "startsWith",
    });
    administration.push({
      to: "/audit",
      label: "Audit log",
      icon: ScrollText,
      match: "startsWith",
    });
  }

  const groups: NavGroup[] = [{ label: "Workspace", items: workspace }];
  if (reporting.length > 0) groups.push({ label: "Reporting", items: reporting });
  if (administration.length > 0) groups.push({ label: "Administration", items: administration });
  return groups;
}
