import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactElement,
  type ReactNode,
} from "react";
import { clearStoredSession, loadStoredSession } from "@/lib/api/session-storage";
import { setRefreshCallback } from "@/lib/api/http-client";
import {
  clearLastRefreshFailureCode,
  getLastRefreshFailureCode,
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
  const [isLoading, setIsLoading] = useState<boolean>(() => {
    if (skipBootstrap || initialSession !== null) return false;
    return loadStoredSession() !== null;
  });
  const didBootstrap = useRef<boolean>(false);

  useEffect(() => {
    setRefreshCallback(async () => {
      clearLastRefreshFailureCode();
      const refreshed = await serviceRefresh();
      setLastRefreshFailureCode(getLastRefreshFailureCode());
      if (refreshed) {
        setSession(refreshed);
        return refreshed.accessToken;
      }
      setSession(null);
      return null;
    });
    return () => setRefreshCallback(null);
  }, []);

  const refresh = useCallback(async () => {
    clearLastRefreshFailureCode();
    const next = await serviceRefresh();
    setLastRefreshFailureCode(getLastRefreshFailureCode());
    setSession(next);
  }, []);

  useEffect(() => {
    if (skipBootstrap) return;
    if (didBootstrap.current) return;
    didBootstrap.current = true;

    const persisted = loadStoredSession();
    if (!persisted) {
      return;
    }
    clearLastRefreshFailureCode();
    void serviceRefresh()
      .then((next) => {
        setLastRefreshFailureCode(getLastRefreshFailureCode());
        if (next) {
          setSession(next);
        } else {
          clearStoredSession();
          setSession(null);
        }
      })
      .finally(() => setIsLoading(false));
  }, [skipBootstrap]);

  const signIn = useCallback((next: Session) => {
    clearLastRefreshFailureCode();
    setLastRefreshFailureCode(null);
    setSession(next);
  }, []);

  const signOut = useCallback(async () => {
    await serviceLogout();
    clearLastRefreshFailureCode();
    setLastRefreshFailureCode(null);
    setSession(null);
  }, []);

  const value = useMemo<SessionContextValue>(
    () => ({ session, isLoading, lastRefreshFailureCode, signIn, signOut, refresh }),
    [session, isLoading, lastRefreshFailureCode, signIn, signOut, refresh],
  );

  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}
