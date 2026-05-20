/**
 * Pure role predicates.
 *
 * Mirrors plan §F + UI pages.md "Role and navigation model" + blueprint §6.
 * No React imports — every gate is a synchronous boolean check.
 */
import type { Role } from "@/types";

export function isLspUiUser(role: Role): boolean {
  return role === "LSP_UI_READ" || role === "LSP_UI_WRITE";
}

export function isInternalUser(role: Role): boolean {
  return role === "SYSTEM_ADMIN" || role === "OPS_USER" || role === "PRODUCT_ADMIN";
}

export function canManageLsps(role: Role): boolean {
  return role === "SYSTEM_ADMIN";
}

export function canManageProducts(role: Role): boolean {
  return role === "SYSTEM_ADMIN" || role === "PRODUCT_ADMIN";
}

export function canManageUsers(role: Role): boolean {
  return role === "SYSTEM_ADMIN";
}

export function canManageApiClients(role: Role): boolean {
  return role === "SYSTEM_ADMIN";
}

export function canViewAlerts(role: Role): boolean {
  return role === "SYSTEM_ADMIN" || role === "OPS_USER";
}

export function canAccessReports(role: Role): boolean {
  return role === "SYSTEM_ADMIN";
}

export function canViewAuditLog(role: Role): boolean {
  return role === "SYSTEM_ADMIN";
}

export function canPostRepayment(role: Role): boolean {
  return role === "SYSTEM_ADMIN" || role === "OPS_USER";
}

export function canDisburse(role: Role): boolean {
  return role === "SYSTEM_ADMIN";
}

export function canApprove(role: Role): boolean {
  return role === "SYSTEM_ADMIN";
}

/** LSP_UI_WRITE may invalidate own-tenant loans (UI pages.md /my-loans). */
export function canInvalidateOwnTenantLoan(role: Role): boolean {
  return role === "LSP_UI_WRITE";
}

/** Default landing route per plan §1 + UI pages.md "Role and navigation". */
export function defaultLandingFor(role: Role): "/home" | "/my-loans" {
  return isLspUiUser(role) ? "/my-loans" : "/home";
}
