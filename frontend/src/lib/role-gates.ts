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

export function canPostRepayment(role: Role): boolean {
  return role === "SYSTEM_ADMIN" || role === "OPS_USER";
}

/**
 * Default landing route after sign-in.
 *
 * Gap #8: Home is admin-only now (the placeholder home for non-admin
 * roles is deleted). Each non-admin role lands directly on its natural primary
 * work surface, which removes the "empty dashboard" first impression for
 * OPS / PRODUCT_ADMIN / LSP roles.
 */
export type DefaultLandingRoute = "/home" | "/loan-applications" | "/products" | "/my-loans";

export function defaultLandingFor(role: Role): DefaultLandingRoute {
  switch (role) {
    case "SYSTEM_ADMIN":
      return "/home";
    case "OPS_USER":
      return "/loan-applications";
    case "PRODUCT_ADMIN":
      return "/products";
    case "LSP_UI_READ":
    case "LSP_UI_WRITE":
      return "/my-loans";
    default:
      return "/home";
  }
}
