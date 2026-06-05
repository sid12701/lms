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
  const [isLoading, setIsLoading] = useState<boolean>(() => {
    if (skipBootstrap || initialSession !== null) return false;
    return loadStoredSession() !== null;
  });
  const didBootstrap = useRef<boolean>(false);

  useEffect(() => {
    setRefreshCallback(async () => {
      const refreshed = await serviceRefresh();
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
    const next = await serviceRefresh();
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
    void serviceRefresh()
      .then((next) => {
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
    setSession(next);
  }, []);

  const signOut = useCallback(async () => {
    await serviceLogout();
    setSession(null);
  }, []);

  const value = useMemo<SessionContextValue>(
    () => ({ session, isLoading, signIn, signOut, refresh }),
    [session, isLoading, signIn, signOut, refresh],
  );

  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}
