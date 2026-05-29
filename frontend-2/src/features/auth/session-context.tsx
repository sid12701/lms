import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactElement,
  type ReactNode,
} from "react";
import type { Session } from "@/mocks/api/auth";
import { setRefreshCallback } from "@/lib/api/http-client";
import { clearStoredSession, loadStoredSession } from "@/lib/api/session-storage";
import {
  logout as serviceLogout,
  refreshSession as serviceRefresh,
} from "@/features/auth/auth-service";

export { SESSION_STORAGE_KEY } from "@/lib/api/session-storage";

export interface SessionContextValue {
  session: Session | null;
  isLoading: boolean;
  signIn: (s: Session) => void;
  signOut: () => Promise<void>;
  refresh: () => Promise<void>;
}

export const SessionContext = createContext<SessionContextValue | null>(null);

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
  const [isLoading, setIsLoading] = useState<boolean>(
    !skipBootstrap && initialSession === null && loadStoredSession() === null,
  );
  const didBootstrap = useRef<boolean>(false);
  const sessionRef = useRef<Session | null>(session);

  useEffect(() => {
    sessionRef.current = session;
  }, [session]);

  // Register the 401-refresh hook for the live HTTP transport.
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
      setIsLoading(false);
      return;
    }
    // Attempt a silent refresh; if the backend rejects, persisted session is cleared.
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

export function useSession(): SessionContextValue {
  const ctx = useContext(SessionContext);
  if (!ctx) {
    throw new Error("useSession must be used within a SessionProvider");
  }
  return ctx;
}
