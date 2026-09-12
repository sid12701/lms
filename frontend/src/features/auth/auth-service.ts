/**
 * Live-backend auth service used by the SessionProvider, LoginPage, and
 * ChangePasswordPage.
 *
 * All entry points obey the auth coordinator's owning intent, captured
 * BEFORE queue/async work; stale completions never write storage.
 * Login/password-change advance + clear identity at intent START (before any
 * async), so pending B never exposes A. Refresh/password revalidations never
 * rebase: they run under the intent captured before queue. Cookie-affecting
 * exchanges (login/refresh/logout/password) serialize through the
 * coordinator's cross-tab Web Lock + unique owner marker and fail closed
 * when ordering cannot be proven. A benign 401 TOKEN_ROTATED (direct-parent
 * loser) triggers one bounded coordinator-owned resync on the already
 * settled winner cookie under the ORIGINAL intent — never logout/revoke.
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
import {
  AuthStaleResultError,
  advanceAuthGeneration,
  captureAuthIntent,
  enqueueCookieOp,
  isStaleIntent,
  type AuthIntent,
} from "@/features/auth/auth-coordinator";
import { getStoredAccessToken } from "@/lib/api/session-storage";

const UI_ROLE_PRIORITY: Role[] = [
  "SYSTEM_ADMIN",
  "OPS_USER",
  "PRODUCT_ADMIN",
  "LSP_UI_WRITE",
  "LSP_UI_READ",
];

const LEGACY_USER_ID_STORAGE_KEY = "bhawana-lms-user-id";

export type SessionRestoreFailureKind =
  | "REFRESH_UNAVAILABLE"
  | "CONTEXT_UNAVAILABLE"
  | "CONTEXT_INVALID";

export class SessionRestoreError extends Error {
  readonly kind: SessionRestoreFailureKind;

  constructor(kind: SessionRestoreFailureKind, cause: unknown) {
    super("The session could not be restored.", { cause });
    this.name = "SessionRestoreError";
    this.kind = kind;
  }
}

function isContextTemporarilyUnavailable(error: unknown): boolean {
  if (error instanceof TypeError) return true;
  if (typeof DOMException !== "undefined" && error instanceof DOMException) {
    return error.name === "AbortError" || error.name === "NetworkError";
  }
  if (error instanceof AuthStaleResultError) return false;
  if (!(error instanceof ApiError)) return false;
  return error.status === 0 || error.status === 408 || error.status === 429 || error.status >= 500;
}

export type RefreshSessionResult =
  | { status: "authenticated"; session: SessionType }
  | { status: "signed-out"; code: string | null };

let refreshInFlight: { intent: AuthIntent; promise: Promise<RefreshSessionResult> } | null = null;

/**
 * No OPS_USER fallback. Unknown live roles fail closed (invalid
 * context, no UI exposure). LSP_API_CLIENT has no UI surface, so it is not
 * selected as a UI session role either — treat as unknown for browser
 * sessions (narrow compatibility: API clients never mint UI sessions).
 */
function selectPrimaryRole(roles: readonly string[]): Role | null {
  for (const candidate of UI_ROLE_PRIORITY) {
    if (roles.includes(candidate)) return candidate;
  }
  return null;
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
  options: { accessToken?: string; refreshOnUnauthorized?: boolean } = {},
): Promise<SessionType> {
  const context = await fetchSystemContext(options.accessToken ?? token.accessToken, {
    refreshOnUnauthorized: options.refreshOnUnauthorized,
  });
  const role = selectPrimaryRole(context.roles);
  if (!role) {
    throw new SessionRestoreError(
      "CONTEXT_INVALID",
      new Error(`Unknown live roles: ${context.roles.join(",") || "(none)"}`),
    );
  }
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
  // Intent START: advance + clear A synchronously before any async, so a
  // pending B never exposes A data/header/cache. The provider's generation
  // subscription clears visible state on this advance; the login's own
  // owning intent is captured here — never rebased at run time.
  advanceAuthGeneration("login-start");
  clearStoredSession();
  clearLegacyPersistedUserId();
  const intent = captureAuthIntent();
  // B login queues behind any prior cookie op (old logout/refresh). The lock
  // covers the full Set-Cookie exchange; orphan markers fail closed.
  const token = await enqueueCookieOp("login", intent, () =>
    backendLogin(input.email.trim(), input.password),
  );
  let session: SessionType;
  try {
    // Freshly minted token: never trigger the global 401 refresh from inside.
    session = await buildSessionFromToken(token, { refreshOnUnauthorized: false });
  } catch (error) {
    if (error instanceof SessionRestoreError) throw error;
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
  if (isStaleIntent(intent)) {
    // A logout/invalidation advanced while login was in flight. Never expose
    // or persist the superseded identity.
    throw new AuthStaleResultError(intent.generation);
  }
  saveStoredSession(session);
  return session;
}

export async function logout(): Promise<void> {
  // Logical invalidation advances NOW and clears visible state; the server
  // cookie effect queues behind any prior refresh. The effect carries this
  // logout's own intent and never touches storage, so it cannot clear a
  // later session.
  advanceAuthGeneration("logout");
  clearStoredSession();
  clearLegacyPersistedUserId();
  const intent = captureAuthIntent();
  try {
    await enqueueCookieOp("logout", intent, () => backendLogout());
  } catch {
    // Local cleanup already ran. Definitive outcomes settle the marker and
    // keep the queue usable; network uncertainty leaves the marker blocked
    // until explicit clean-context recovery. Never restore the session here —
    // a failed old logout must not erase newer queued intent.
  }
}

/**
 * Refresh the active session using the httpOnly `lms-refresh` cookie.
 *
 * Concurrent callers share one request because the backend rotates the refresh
 * cookie on every success. A definitive 401/403 returns `signed-out` (except
 * a benign 401 TOKEN_ROTATED, which resyncs once on the settled winner
 * cookie under the original intent); transport, server, and
 * response-contract failures throw `SessionRestoreError` without clearing
 * persisted session metadata so the caller can offer a safe retry. Stale
 * completions (superseded intent) throw AuthStaleResultError and never touch
 * storage.
 */
export function refreshSession(): Promise<RefreshSessionResult> {
  // Share one rotating request per owning intent only: a B refresh must
  // never receive a stale A promise. An older record's settle must not
  // clear a newer record.
  const intent = captureAuthIntent();
  const inFlight = refreshInFlight;
  if (
    inFlight &&
    inFlight.intent.generation === intent.generation &&
    inFlight.intent.intent === intent.intent
  ) {
    return inFlight.promise;
  }

  const record: { intent: AuthIntent; promise: Promise<RefreshSessionResult> } = {
    intent,
    promise: null as unknown as Promise<RefreshSessionResult>,
  };
  record.promise = performSessionRefresh(intent).finally(() => {
    if (refreshInFlight === record) refreshInFlight = null;
  });
  refreshInFlight = record;
  return record.promise;
}

async function performSessionRefresh(intent: AuthIntent): Promise<RefreshSessionResult> {
  // Revalidation of the captured owning identity: never rebased at run time.
  let token: BackendTokenResponse;
  try {
    token = await enqueueCookieOp("refresh", intent, () => backendRefresh());
  } catch (error) {
    if (error instanceof AuthStaleResultError) throw error;
    return handleRefreshExchangeFailure(error, intent, false);
  }

  try {
    // The token is already fresh. A context 401 must not invoke the global
    // refresh callback from inside this flow and recursively rotate the cookie.
    const session = await buildSessionFromToken(token, { refreshOnUnauthorized: false });
    if (isStaleIntent(intent)) {
      throw new AuthStaleResultError(intent.generation);
    }
    // Same-identity renewal: no generation advance, so the auth-scoped query
    // cache is retained. (A different user here would change scopeKey and
    // remount anyway; no extra advance needed for isolation.)
    saveStoredSession(session);
    return { status: "authenticated", session };
  } catch (error) {
    if (error instanceof AuthStaleResultError) throw error;
    if (error instanceof SessionRestoreError) {
      // Unknown-role / invalid contract already classified. Only clear on
      // definitive rejection while still current; stale results never clear.
      if (error.kind === "CONTEXT_INVALID") throw error;
      throw error;
    }
    if (error instanceof ApiError && (error.status === 401 || error.status === 403)) {
      return handleRefreshRejection(error, intent);
    }

    if (isStaleIntent(intent)) throw new AuthStaleResultError(intent.generation);
    const kind = isContextTemporarilyUnavailable(error) ? "CONTEXT_UNAVAILABLE" : "CONTEXT_INVALID";
    throw new SessionRestoreError(kind, error);
  }
}

/**
 * Shared 401/terminal handling for the refresh exchange and its one bounded
 * benign-loser resync. TOKEN_ROTATED (direct-parent benign loser per the
 * frozen contract: no successor/cookie issued to the loser, no revoke)
 * resyncs ONCE on the already-settled winner cookie under the ORIGINAL
 * intent — no logout, no revoke, no recursion. Any other definitive 401
 * invalidates only when still current.
 */
async function handleRefreshExchangeFailure(
  error: unknown,
  intent: AuthIntent,
  rotatedRetried: boolean,
): Promise<RefreshSessionResult> {
  if (error instanceof ApiError && error.status === 401 && error.code === "TOKEN_ROTATED") {
    if (!rotatedRetried && !isStaleIntent(intent)) {
      // Bounded resync: the winner's cookie already settled in the jar;
      // present it once more under the original intent.
      try {
        const token = await enqueueCookieOp("refresh", intent, () => backendRefresh());
        return await finishRefreshToken(token, intent);
      } catch (retryError) {
        if (retryError instanceof AuthStaleResultError) throw retryError;
        return handleRefreshExchangeFailure(retryError, intent, true);
      }
    }
    if (isStaleIntent(intent)) throw new AuthStaleResultError(intent.generation);
    advanceAuthGeneration("refresh-signed-out");
    clearStoredSession();
    return { status: "signed-out", code: error.code };
  }
  // Blocked/uncertain markers fail closed; propagate so the caller shows a
  // retryable error without clearing newer intent. Definitive 401 means the
  // family is dead — but only when still current.
  if (error instanceof ApiError && error.status === 401) {
    if (isStaleIntent(intent)) throw new AuthStaleResultError(intent.generation);
    advanceAuthGeneration("refresh-signed-out");
    clearStoredSession();
    return { status: "signed-out", code: error.code };
  }
  if (isStaleIntent(intent)) throw new AuthStaleResultError(intent.generation);
  throw new SessionRestoreError("REFRESH_UNAVAILABLE", error);
}

async function handleRefreshRejection(
  error: ApiError,
  intent: AuthIntent,
): Promise<RefreshSessionResult> {
  if (isStaleIntent(intent)) throw new AuthStaleResultError(intent.generation);
  advanceAuthGeneration("refresh-signed-out");
  clearStoredSession();
  return { status: "signed-out", code: error.code };
}

async function finishRefreshToken(
  token: BackendTokenResponse,
  intent: AuthIntent,
): Promise<RefreshSessionResult> {
  try {
    const session = await buildSessionFromToken(token, { refreshOnUnauthorized: false });
    if (isStaleIntent(intent)) {
      throw new AuthStaleResultError(intent.generation);
    }
    saveStoredSession(session);
    return { status: "authenticated", session };
  } catch (error) {
    if (error instanceof AuthStaleResultError) throw error;
    if (error instanceof SessionRestoreError) {
      if (error.kind === "CONTEXT_INVALID") throw error;
      throw error;
    }
    if (error instanceof ApiError && (error.status === 401 || error.status === 403)) {
      return handleRefreshRejection(error, intent);
    }
    if (isStaleIntent(intent)) throw new AuthStaleResultError(intent.generation);
    const kind = isContextTemporarilyUnavailable(error) ? "CONTEXT_UNAVAILABLE" : "CONTEXT_INVALID";
    throw new SessionRestoreError(kind, error);
  }
}

export async function completePasswordChange(input: { newPassword: string }): Promise<SessionType> {
  // Credential + owning full intent captured BEFORE the intent-start clear:
  // the request must carry this intent's bearer explicitly, never ambient
  // storage (which is cleared below and could later hold B's bearer).
  const presentedBearer = getStoredAccessToken();
  // New-credential intent: advance + clear at START like login.
  advanceAuthGeneration("password-change-start");
  clearStoredSession();
  clearLegacyPersistedUserId();
  const intent = captureAuthIntent();
  const token = await enqueueCookieOp("password", intent, () =>
    backendCompletePassword(input.newPassword, { accessToken: presentedBearer ?? undefined }),
  );
  const session = await buildSessionFromToken(token, { refreshOnUnauthorized: false });
  if (isStaleIntent(intent)) {
    throw new AuthStaleResultError(intent.generation);
  }
  saveStoredSession(session);
  return session;
}

/** Test seam, test/dev builds only: clear the shared refresh promise between tests. */
export function resetAuthServiceForTests(): void {
  if (!import.meta.env.DEV) {
    throw new Error(
      "resetAuthServiceForTests is a test-only seam and is unavailable in production.",
    );
  }
  refreshInFlight = null;
}
