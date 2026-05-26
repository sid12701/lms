/**
 * Live-backend auth service used by the SessionProvider, LoginPage, and
 * ChangePasswordPage. Mirrors the public Session shape that the rest of the
 * frontend expects, so consumers don't care that the live backend is
 * speaking a slightly different contract under the hood.
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
import { Session, SessionUser, type Session as SessionType } from "@/mocks/api/auth";
import { clearMockSession, syncMockSession } from "@/features/auth/mock-session-bridge";
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
    mustChangePassword: token.passwordChangeRequired ?? false,
  });
  return Session.parse({
    user,
    accessToken: token.accessToken,
    expiresAt: expiresAtFromToken(token),
  });
}

export interface LoginInput {
  username: string;
  password: string;
}

export async function login(input: LoginInput): Promise<SessionType> {
  if (!input.username.trim() || !input.password.trim()) {
    throw new ApiError("Username and password are required.", 400, "", "VALIDATION");
  }
  const token = await backendLogin(input.username.trim(), input.password);
  const session = await buildSessionFromToken(token);
  saveStoredSession(session);
  syncMockSession(session);
  return session;
}

export async function logout(): Promise<void> {
  try {
    await backendLogout();
  } catch {
    // Local cleanup runs regardless of backend response.
  }
  clearStoredSession();
  clearMockSession();
  clearLegacyPersistedUserId();
}

/**
 * Refresh the active session using the httpOnly `lms-refresh` cookie.
 *
 * Returns the refreshed session, or `null` if the backend rejects the
 * refresh — in which case the caller should treat the user as signed out.
 */
export async function refreshSession(): Promise<SessionType | null> {
  try {
    const token = await backendRefresh();
    const session = await buildSessionFromToken(token);
    saveStoredSession(session);
    syncMockSession(session);
    return session;
  } catch {
    clearStoredSession();
    clearMockSession();
    return null;
  }
}

export async function completePasswordChange(input: {
  newPassword: string;
}): Promise<SessionType> {
  const token = await backendCompletePassword(input.newPassword);
  const session = await buildSessionFromToken(token);
  saveStoredSession(session);
  syncMockSession(session);
  return session;
}
