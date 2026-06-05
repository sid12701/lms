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
  username: string;
  password: string;
}

function readEnv(key: string): string | undefined {
  const value = import.meta.env[key];
  if (typeof value !== "string") return undefined;
  const trimmed = value.trim();
  return trimmed.length > 0 ? trimmed : undefined;
}

function usernameEnvKey(role: LoginUiRole): string {
  return `VITE_LOGIN_${role}_USERNAME`;
}

/**
 * Optional quick-fill credentials for local sign-in. Values must match real
 * `app_user` rows in the Spring backend — configure in `.env.local`.
 * Returns `null` when no username is configured for the role.
 */
export function loginPresetForRole(role: LoginUiRole): LoginRolePreset | null {
  const username = readEnv(usernameEnvKey(role));
  if (!username) return null;

  const password =
    role === "SYSTEM_ADMIN"
      ? readEnv("VITE_LOGIN_BOOTSTRAP_PASSWORD")
      : readEnv("VITE_LOGIN_DEFAULT_PASSWORD");
  if (!password) return null;

  return { username, password };
}
