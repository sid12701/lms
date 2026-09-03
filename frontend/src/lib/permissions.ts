/**
 * Role → Permission set mapping per blueprint §6.
 *
 * The granularity is intentionally finer than the role list so the UI can
 * still render "permission denied" copy even when the role bundles change.
 */
import type { Permission, Role } from "@/types";

const SYSTEM_ADMIN_PERMS: Permission[] = [
  "LOAN_READ",
  "LOAN_WRITE",
  "LOAN_STATUS_UPDATE",
  "PRODUCT_READ",
  "PRODUCT_WRITE",
  "USER_READ",
  "USER_WRITE",
  "REPORT_REQUEST",
  "REPORT_READ",
  "DISBURSEMENT_TRIGGER",
  "FORECLOSURE_TRIGGER",
  "ALL_LSP_VIEW",
];

const OPS_USER_PERMS: Permission[] = [
  "LOAN_READ",
  "LOAN_WRITE",
  "LOAN_STATUS_UPDATE",
  "PRODUCT_READ",
  "REPORT_REQUEST",
  "REPORT_READ",
  "ALL_LSP_VIEW",
];

const PRODUCT_ADMIN_PERMS: Permission[] = ["PRODUCT_READ", "PRODUCT_WRITE", "LOAN_READ"];

const LSP_UI_READ_PERMS: Permission[] = ["LOAN_READ", "REPORT_READ"];

const LSP_UI_WRITE_PERMS: Permission[] = ["LOAN_READ", "LOAN_WRITE", "REPORT_READ"];

const LSP_API_CLIENT_PERMS: Permission[] = ["LOAN_READ", "LOAN_WRITE", "LOAN_STATUS_UPDATE"];

const PERMISSIONS_BY_ROLE: Record<Role, Permission[]> = {
  SYSTEM_ADMIN: SYSTEM_ADMIN_PERMS,
  OPS_USER: OPS_USER_PERMS,
  PRODUCT_ADMIN: PRODUCT_ADMIN_PERMS,
  LSP_UI_READ: LSP_UI_READ_PERMS,
  LSP_UI_WRITE: LSP_UI_WRITE_PERMS,
  LSP_API_CLIENT: LSP_API_CLIENT_PERMS,
};

export function hasPermission(role: Role, permission: Permission): boolean {
  return PERMISSIONS_BY_ROLE[role].includes(permission);
}
