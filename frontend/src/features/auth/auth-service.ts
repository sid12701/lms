/**
 * Live-backend auth service used by the SessionProvider, LoginPage, and
 * ChangePasswordPage.
 */
import { ApiError } from "@/lib/api/http-client";
import {
  type BackendTokenResponse,
  fetchSystemContext,
  loginWithPassword as backendLogin,
  logoutSession as backendLogout,
  refreshAccessToken as backendRefresh,
  completePasswordChange as backendCompletePassword,
} from "@/lib/api/auth-api";
import { clearStoredSession, saveStoredSession } from "@/lib/api/session-storage";
import { Session, SessionUser, type Session as SessionType } from "@/features/auth/session-types";
import type { LoginInput } from "@/schemas/auth";
import type { Role } from "@/types";

const ROLE_PRIORITY: Role[] = [
  "SYSTEM_ADMIN",
  "OPS_USER",
  "PRODUCT_ADMIN",
  "LSP_UI_WRITE",
  "LSP_UI_READ",
  "LSP_API_CLIENT",
];

const LEGACY_USER_ID_STORAGE_KEY = "bhawana-lms-user-id";

let lastRefreshFailureCode: string | null = null;

/** Most recent refresh rejection code from the backend, for downstream UX readers. */
export function getLastRefreshFailureCode(): string | null {
  return lastRefreshFailureCode;
}

export function clearLastRefreshFailureCode(): void {
  lastRefreshFailureCode = null;
}

function selectPrimaryRole(roles: readonly string[]): Role {
  for (const candidate of ROLE_PRIORITY) {
    if (roles.includes(candidate)) return candidate;
  }
  return "OPS_USER";
}

function clearLegacyPersistedUserId(): void {
  if (typeof window === "undefined") return;
  try {
    window.localStorage.removeItem(LEGACY_USER_ID_STORAGE_KEY);
  } catch {
    // best effort only
  }
}

function expiresAtFromToken(token: BackendTokenResponse): string {
  const seconds = Math.max(60, token.expiresInSeconds || 0);
  return new Date(Date.now() + seconds * 1000).toISOString();
}

async function buildSessionFromToken(
  token: BackendTokenResponse,
  options: { accessToken?: string } = {},
): Promise<SessionType> {
  const context = await fetchSystemContext(options.accessToken ?? token.accessToken);
  const role = selectPrimaryRole(context.roles);
  clearLegacyPersistedUserId();
  const user: SessionUser = SessionUser.parse({
    id: context.id,
    username: context.username,
    role,
    lspId: context.lspId,
    lspName: context.lspName,
    mustChangePassword: token.passwordChangeRequired ?? false,
  });
  return Session.parse({
    user,
    accessToken: token.accessToken,
    expiresAt: expiresAtFromToken(token),
  });
}

export async function login(input: LoginInput): Promise<SessionType> {
  if (!input.email.trim() || !input.password.trim()) {
    throw new ApiError("Email and password are required.", 400, "", "VALIDATION");
  }
  const token = await backendLogin(input.email.trim(), input.password);
  let session: SessionType;
  try {
    session = await buildSessionFromToken(token);
  } catch (error) {
    // Credentials were accepted; the workspace-context fetch failed. The raw
    // backend message ("An unexpected error occurred") reads like a bad
    // password — tell the user what actually happened.
    const status = error instanceof ApiError ? error.status : 0;
    throw new ApiError(
      "Signed in, but your workspace couldn't be loaded. Try again in a moment or contact an administrator.",
      status,
      "",
      "SESSION_CONTEXT_FAILED",
    );
  }
  saveStoredSession(session);
  return session;
}

export async function logout(): Promise<void> {
  try {
    await backendLogout();
  } catch {
    // Local cleanup runs regardless of backend response.
  }
  clearStoredSession();
  clearLegacyPersistedUserId();
}

/**
 * Refresh the active session using the httpOnly `lms-refresh` cookie.
 *
 * Returns the refreshed session, or `null` if the backend rejects the
 * refresh — in which case the caller should treat the user as signed out.
 */
export async function refreshSession(): Promise<SessionType | null> {
  lastRefreshFailureCode = null;
  try {
    const token = await backendRefresh();
    const session = await buildSessionFromToken(token);
    saveStoredSession(session);
    return session;
  } catch (error) {
    if (error instanceof ApiError && error.code) {
      lastRefreshFailureCode = error.code;
    }
    clearStoredSession();
    return null;
  }
}

export async function completePasswordChange(input: { newPassword: string }): Promise<SessionType> {
  const token = await backendCompletePassword(input.newPassword);
  const session = await buildSessionFromToken(token);
  saveStoredSession(session);
  return session;
}
