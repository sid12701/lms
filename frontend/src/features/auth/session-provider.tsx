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

  const applyRefreshResult = useCallback((result: RefreshSessionResult) => {
    if (result.status === "authenticated") {
      setLastRefreshFailureCode(null);
      setSession(result.session);
      return;
    }
    setLastRefreshFailureCode(result.code);
    setSession(null);
  }, []);

  const restoreSession = useCallback(async (): Promise<RefreshSessionResult> => {
    try {
      const result = await serviceRefresh();
      setSessionRestoreError(null);
      applyRefreshResult(result);
      return result;
    } catch (error) {
      setSessionRestoreError(error instanceof SessionRestoreError ? error.kind : "CONTEXT_INVALID");
      throw error;
    }
  }, [applyRefreshResult]);

  useEffect(() => {
    setRefreshCallback(async () => {
      const result = await restoreSession();
      return result.status === "authenticated" ? result.session.accessToken : null;
    });
    return () => setRefreshCallback(null);
  }, [restoreSession]);

  const refresh = useCallback(async () => {
    setIsLoading(true);
    try {
      await restoreSession();
    } catch {
      // The typed restore error is exposed through context for the retry UI.
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
    void serviceRefresh()
      .then((result) => {
        setSessionRestoreError(null);
        applyRefreshResult(result);
      })
      .catch((error: unknown) => {
        setSessionRestoreError(
          error instanceof SessionRestoreError ? error.kind : "CONTEXT_INVALID",
        );
        // The cached identity remains stored, but AppRoot blocks protected UI
        // until a retry verifies fresh workspace context.
      })
      .finally(() => setIsLoading(false));
  }, [applyRefreshResult, skipBootstrap]);

  const signIn = useCallback((next: Session) => {
    setLastRefreshFailureCode(null);
    setSessionRestoreError(null);
    setSession(next);
  }, []);

  const signOut = useCallback(async () => {
    await serviceLogout();
    setLastRefreshFailureCode(null);
    setSessionRestoreError(null);
    setSession(null);
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
