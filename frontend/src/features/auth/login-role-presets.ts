import type { Role } from "@/schemas/role";

/** UI roles shown on the login quick-fill grid (`LSP_API_CLIENT` has no UI surface). */
export const LOGIN_UI_ROLES = [
  "SYSTEM_ADMIN",
  "OPS_USER",
  "PRODUCT_ADMIN",
  "LSP_UI_WRITE",
  "LSP_UI_READ",
] as const satisfies readonly Role[];

export type LoginUiRole = (typeof LOGIN_UI_ROLES)[number];

export interface LoginRolePreset {
  email: string;
}

function configuredEmailForRole(role: LoginUiRole): string | undefined {
  const valueByRole: Record<LoginUiRole, string | undefined> = {
    SYSTEM_ADMIN: import.meta.env.VITE_LOGIN_SYSTEM_ADMIN_EMAIL,
    OPS_USER: import.meta.env.VITE_LOGIN_OPS_USER_EMAIL,
    PRODUCT_ADMIN: import.meta.env.VITE_LOGIN_PRODUCT_ADMIN_EMAIL,
    LSP_UI_READ: import.meta.env.VITE_LOGIN_LSP_UI_READ_EMAIL,
    LSP_UI_WRITE: import.meta.env.VITE_LOGIN_LSP_UI_WRITE_EMAIL,
  };
  const value = valueByRole[role];
  if (typeof value !== "string") return undefined;
  const trimmed = value.trim();
  return trimmed.length > 0 ? trimmed : undefined;
}

/**
 * Optional quick-fill email for local sign-in. The value must match a real
 * `app_user` row in the Spring backend. Passwords are deliberately excluded:
 * every `VITE_*` value is published to the browser bundle.
 * Returns `null` when no email is configured for the role.
 */
export function loginPresetForRole(role: LoginUiRole): LoginRolePreset | null {
  const email = configuredEmailForRole(role);
  if (!email) return null;
  return { email };
}
