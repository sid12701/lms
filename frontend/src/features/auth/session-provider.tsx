import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactElement,
  type ReactNode,
} from "react";
import { loadStoredSession } from "@/lib/api/session-storage";
import { setRefreshCallback } from "@/lib/api/http-client";
import {
  type RefreshSessionResult,
  SessionRestoreError,
  type SessionRestoreFailureKind,
  logout as serviceLogout,
  refreshSession as serviceRefresh,
} from "@/features/auth/auth-service";
import {
  AuthCookieBlockedError,
  AuthStaleResultError,
  captureAuthIntent,
  isStaleIntent,
  subscribeAuthGeneration,
  type AuthIntent,
} from "@/features/auth/auth-coordinator";
import { SessionContext, type SessionContextValue } from "@/features/auth/session-context-state";
import type { Session } from "@/features/auth/session-types";

interface SessionProviderProps {
  children: ReactNode;
  /** Test seam: skip the initial refresh / persisted-session bootstrap. */
  skipBootstrap?: boolean;
  /** Test seam: pre-populate the session. */
  initialSession?: Session | null;
}

export function SessionProvider({
  children,
  skipBootstrap = false,
  initialSession = null,
}: SessionProviderProps): ReactElement {
  const [session, setSession] = useState<Session | null>(
    () => initialSession ?? loadStoredSession(),
  );
  const [lastRefreshFailureCode, setLastRefreshFailureCode] = useState<string | null>(null);
  const [sessionRestoreError, setSessionRestoreError] = useState<SessionRestoreFailureKind | null>(
    null,
  );
  const [isLoading, setIsLoading] = useState<boolean>(() => {
    if (skipBootstrap || initialSession !== null) return false;
    return loadStoredSession() !== null;
  });
  const didBootstrap = useRef<boolean>(false);
  const appliedIntentRef = useRef<AuthIntent>(captureAuthIntent());

  const applyRefreshResult = useCallback(
    (result: RefreshSessionResult, resultIntent: AuthIntent) => {
      // Stale completions never mutate visible state, headers, or cache.
      if (isStaleIntent(resultIntent)) return;
      appliedIntentRef.current = captureAuthIntent();
      if (result.status === "authenticated") {
        setLastRefreshFailureCode(null);
        setSession(result.session);
        return;
      }
      setLastRefreshFailureCode(result.code);
      setSession(null);
    },
    [],
  );

  const restoreSession = useCallback(async (): Promise<RefreshSessionResult> => {
    const startIntent = captureAuthIntent();
    try {
      const result = await serviceRefresh();
      if (isStaleIntent(startIntent)) {
        throw new AuthStaleResultError(startIntent.generation);
      }
      setSessionRestoreError(null);
      applyRefreshResult(result, startIntent);
      return result;
    } catch (error) {
      if (error instanceof AuthStaleResultError || isStaleIntent(startIntent)) {
        // Superseded by logout/login — never mutate the new identity.
        throw error;
      }
      if (error instanceof AuthCookieBlockedError) {
        setSessionRestoreError("CONTEXT_UNAVAILABLE");
        throw error;
      }
      setSessionRestoreError(error instanceof SessionRestoreError ? error.kind : "CONTEXT_INVALID");
      throw error;
    }
  }, [applyRefreshResult]);

  useEffect(() => {
    setRefreshCallback(async () => {
      const startIntent = captureAuthIntent();
      try {
        const result = await restoreSession();
        if (result.status === "authenticated" && !isStaleIntent(startIntent)) {
          return result.session.accessToken;
        }
        return null;
      } catch {
        return null;
      }
    });
    return () => setRefreshCallback(null);
  }, [restoreSession]);

  const refresh = useCallback(async () => {
    setIsLoading(true);
    try {
      await restoreSession();
    } catch {
      // The typed restore error is exposed through context for the retry UI.
      // Stale and blocked errors are already handled without mutating state.
    } finally {
      setIsLoading(false);
    }
  }, [restoreSession]);

  useEffect(() => {
    if (skipBootstrap) return;
    if (didBootstrap.current) return;
    didBootstrap.current = true;

    const persisted = loadStoredSession();
    if (!persisted) {
      return;
    }
    const startIntent = captureAuthIntent();
    void serviceRefresh()
      .then((result) => {
        if (isStaleIntent(startIntent)) return;
        setSessionRestoreError(null);
        applyRefreshResult(result, startIntent);
      })
      .catch((error: unknown) => {
        if (error instanceof AuthStaleResultError || isStaleIntent(startIntent)) return;
        if (error instanceof AuthCookieBlockedError) {
          setSessionRestoreError("CONTEXT_UNAVAILABLE");
          return;
        }
        setSessionRestoreError(
          error instanceof SessionRestoreError ? error.kind : "CONTEXT_INVALID",
        );
        // The cached identity remains stored, but AppRoot blocks protected UI
        // until a retry verifies fresh workspace context.
      })
      .finally(() => {
        if (!isStaleIntent(startIntent)) setIsLoading(false);
      });
  }, [applyRefreshResult, skipBootstrap]);

  // Cross-tab invalidation: announcements carry (generation, intent) only
  // (no secrets). Any foreign state — newer epoch OR a colliding epoch with
  // a different writer — invalidates locally immediately, even while cookie
  // effects are still queued.
  useEffect(() => {
    const unsubscribe = subscribeAuthGeneration((next) => {
      const applied = appliedIntentRef.current;
      if (next.generation === applied.generation && next.intent === applied.intent) return;
      appliedIntentRef.current = { generation: next.generation, intent: next.intent };
      // A newer/different identity exists elsewhere. Invalidate locally
      // without waiting for cookie effects. Same-identity renewals never
      // advance, so useful cache is retained.
      setLastRefreshFailureCode(null);
      setSession(null);
      setIsLoading(false);
    });
    return unsubscribe;
  }, []);

  const signIn = useCallback((next: Session, expectedIntent?: AuthIntent): boolean => {
    if (expectedIntent && isStaleIntent(expectedIntent)) {
      // Login/password completed for a superseded identity — never expose
      // A data/header/cache to B.
      return false;
    }
    appliedIntentRef.current = captureAuthIntent();
    setLastRefreshFailureCode(null);
    setSessionRestoreError(null);
    setSession(next);
    return true;
  }, []);

  const signOut = useCallback(async () => {
    // Clear visible authenticated state IMMEDIATELY, before awaiting
    // the queued server revocation. Pending B can never see A data.
    appliedIntentRef.current = captureAuthIntent();
    setLastRefreshFailureCode(null);
    setSessionRestoreError(null);
    setSession(null);
    // The service advances the intent synchronously and owns queued cookie
    // cleanup. There is no state left to clear after awaiting it: an obsolete
    // completion must never overwrite a later sign-in.
    await serviceLogout();
  }, []);

  const value = useMemo<SessionContextValue>(
    () => ({
      session,
      isLoading,
      lastRefreshFailureCode,
      sessionRestoreError,
      signIn,
      signOut,
      refresh,
    }),
    [session, isLoading, lastRefreshFailureCode, sessionRestoreError, signIn, signOut, refresh],
  );

  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}
