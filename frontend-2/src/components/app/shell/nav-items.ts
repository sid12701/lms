import {
  Home,
  FileText,
  Folder,
  Building2,
  Layers,
  Users,
  KeyRound,
  Bell,
  BarChart3,
  ScrollText,
  type LucideIcon,
} from "lucide-react";
import type { Role } from "@/types";
import { isLspUiUser } from "@/lib/role-gates";

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

/**
 * Build the role-aware sidebar navigation tree.
 *
 * Gap #8: Home is admin-only. Non-admin roles never see the Home nav
 * entry; they land directly on their primary work surface after sign-in
 * (handled by `defaultLandingFor`).
 */
export function getNavItems(role: Role): NavGroup[] {
  if (isLspUiUser(role)) {
    return [
      {
        label: "Workspace",
        items: [
          { to: "/my-loans", label: "My loans", icon: Folder, match: "startsWith" },
        ],
      },
    ];
  }

  const workspace: NavItem[] = [];
  if (role === "SYSTEM_ADMIN") {
    workspace.push({ to: "/home", label: "Home", icon: Home, match: "exact" });
  }
  workspace.push({
    to: "/loan-applications",
    label: "Loan applications",
    icon: FileText,
    match: "startsWith",
  });

  const reporting: NavItem[] = [];
  if (role === "SYSTEM_ADMIN" || role === "OPS_USER") {
    reporting.push({ to: "/alerts", label: "Alerts", icon: Bell, match: "startsWith" });
  }
  if (role === "SYSTEM_ADMIN") {
    reporting.push({ to: "/reports", label: "Reports", icon: BarChart3, match: "startsWith" });
  }

  const administration: NavItem[] = [];
  if (role === "SYSTEM_ADMIN") {
    administration.push({ to: "/lsps", label: "LSPs", icon: Building2, match: "startsWith" });
  }
  if (role === "SYSTEM_ADMIN" || role === "PRODUCT_ADMIN") {
    administration.push({ to: "/products", label: "Products", icon: Layers, match: "startsWith" });
  }
  if (role === "SYSTEM_ADMIN") {
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
