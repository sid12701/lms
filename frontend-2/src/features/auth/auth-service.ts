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
import type { Role } from "@/types";

const ROLE_PRIORITY: Role[] = [
  "SYSTEM_ADMIN",
  "OPS_USER",
  "PRODUCT_ADMIN",
  "LSP_UI_WRITE",
  "LSP_UI_READ",
  "LSP_API_CLIENT",
];

const USER_ID_STORAGE_KEY = "bhawana-lms-user-id";

function selectPrimaryRole(roles: readonly string[]): Role {
  for (const candidate of ROLE_PRIORITY) {
    if (roles.includes(candidate)) return candidate;
  }
  return "OPS_USER";
}

function readPersistedUserId(): string | null {
  if (typeof window === "undefined") return null;
  try {
    return window.localStorage.getItem(USER_ID_STORAGE_KEY);
  } catch {
    return null;
  }
}

function writePersistedUserId(value: string | null): void {
  if (typeof window === "undefined") return;
  try {
    if (value) window.localStorage.setItem(USER_ID_STORAGE_KEY, value);
    else window.localStorage.removeItem(USER_ID_STORAGE_KEY);
  } catch {
    // best effort only
  }
}

function ensureUserId(): string {
  const existing = readPersistedUserId();
  if (existing) return existing;
  const next =
    typeof crypto !== "undefined" && typeof crypto.randomUUID === "function"
      ? crypto.randomUUID()
      : "00000000-0000-4000-8000-000000000001";
  writePersistedUserId(next);
  return next;
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
  const user: SessionUser = SessionUser.parse({
    id: ensureUserId(),
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
  return session;
}

export async function logout(): Promise<void> {
  try {
    await backendLogout();
  } catch {
    // Local cleanup runs regardless of backend response.
  }
  clearStoredSession();
  writePersistedUserId(null);
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
    return session;
  } catch {
    clearStoredSession();
    return null;
  }
}

export async function completePasswordChange(input: {
  newPassword: string;
}): Promise<SessionType> {
  const token = await backendCompletePassword(input.newPassword);
  const session = await buildSessionFromToken(token);
  saveStoredSession(session);
  return session;
}
