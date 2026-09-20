/**
 * Pure role predicates.
 *
 * Mirrors plan §F + the route guards in `@/routes/router.tsx` + blueprint §6.
 * No React imports — every gate is a synchronous boolean check.
 *
 * M19 — gates take the session's FULL role set (`readonly Role[]`), never the
 * primary role alone: a multi-role user (e.g. OPS_USER + PRODUCT_ADMIN)
 * holds the union of every granted role's surfaces. The backend already
 * authorizes by "any matching authority" (hasAnyRole), so the union mirrors
 * the server contract exactly.
 */
import type { Role } from "@/types";

/**
 * UI-surface roles, ordered by deliberate landing priority. `LSP_API_CLIENT`
 * is absent on purpose: it is a machine principal with no browser surface —
 * never inferred into a UI session's capability set.
 */
export const UI_ROLE_PRIORITY = [
  "SYSTEM_ADMIN",
  "OPS_USER",
  "PRODUCT_ADMIN",
  "LSP_UI_WRITE",
  "LSP_UI_READ",
] as const satisfies readonly Role[];

const UI_ROLE_SET: ReadonlySet<string> = new Set(UI_ROLE_PRIORITY);

/**
 * Roles from the backend `roles` claim that are valid for a browser session.
 * Unknown codes and machine-only roles are dropped — never mapped onto a UI
 * permission. A session with no recognized UI role fails closed upstream
 * (CONTEXT_INVALID), so this never fabricates access.
 */
export function uiSessionRoles(roles: readonly string[]): Role[] {
  const recognized = new Set<Role>();
  for (const raw of roles) {
    if (UI_ROLE_SET.has(raw)) recognized.add(raw as Role);
  }
  // Canonical order (priority, not claim order) keeps identity comparisons
  // and the query-scope key stable across backends/serializations.
  return UI_ROLE_PRIORITY.filter((role) => recognized.has(role));
}

/**
 * The deliberate primary role for a session: highest UI priority among the
 * granted roles. Used for ONE decision — the landing route — plus the shell
 * actor label. It is not the capability set; authorization reads `roles`.
 */
export function primaryRoleFor(roles: readonly Role[]): Role | null {
  for (const candidate of UI_ROLE_PRIORITY) {
    if (roles.includes(candidate)) return candidate;
  }
  return null;
}

/** True when the role set includes any internal (non-LSP) role. */
export function isInternalUser(roles: readonly Role[]): boolean {
  return (
    roles.includes("SYSTEM_ADMIN") || roles.includes("OPS_USER") || roles.includes("PRODUCT_ADMIN")
  );
}

/** True when the role set includes an LSP browser role. */
export function isLspUiUser(roles: readonly Role[]): boolean {
  return roles.includes("LSP_UI_READ") || roles.includes("LSP_UI_WRITE");
}

export function canPostRepayment(roles: readonly Role[]): boolean {
  return roles.includes("SYSTEM_ADMIN") || roles.includes("OPS_USER");
}

/** True when any granted role is in `allowed` — route-guard semantics. */
export function hasAnyRole(roles: readonly Role[], allowed: readonly Role[]): boolean {
  return roles.some((role) => allowed.includes(role));
}

/**
 * Default landing route after sign-in.
 *
 * Gap #8: Home is admin-only now (the placeholder home for non-admin
 * roles is deleted). Each non-admin role lands directly on its natural primary
 * work surface, which removes the "empty dashboard" first impression for
 * OPS / PRODUCT_ADMIN / LSP roles.
 *
 * M19: takes the full role set and resolves the deliberate primary role for
 * the landing decision — the priority is used to pick where the user STARTS,
 * never to decide what they may reach afterwards.
 */
export type DefaultLandingRoute = "/home" | "/loan-applications" | "/products" | "/my-loans";

export function defaultLandingFor(roles: readonly Role[]): DefaultLandingRoute {
  switch (primaryRoleFor(roles)) {
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
      // Unreachable for a live session (roles is non-empty by schema and the
      // builder rejects empty role sets), but fail toward the richest safe
      // surface rather than a route a restricted user cannot serve.
      return "/my-loans";
  }
}
