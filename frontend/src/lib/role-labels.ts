import type { Role } from "@/types";

const ROLE_LABELS: Record<Role, string> = {
  SYSTEM_ADMIN: "System admin",
  OPS_USER: "Ops user",
  PRODUCT_ADMIN: "Product admin",
  LSP_UI_READ: "LSP viewer",
  LSP_UI_WRITE: "LSP user",
  LSP_API_CLIENT: "LSP API client",
};

/** Human-readable label for a backend role enum. */
export function formatRoleLabel(role: string): string {
  return ROLE_LABELS[role as Role] ?? role.replace(/_/g, " ").toLowerCase();
}

/** Join role labels for permission-denied copy ("System admin or Ops user"). */
export function formatRoleList(roles: readonly string[]): string {
  return roles.map(formatRoleLabel).join(" or ");
}
