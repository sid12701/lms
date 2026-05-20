/* eslint-disable react-refresh/only-export-components -- context module co-exports a Provider, hook, and Context */
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
import { auth } from "@/mocks/api";
import { bootstrapMockApi } from "@/mocks/api";

export const SESSION_STORAGE_KEY = "bhawana-lms-session";

export interface SessionContextValue {
  session: Session | null;
  isLoading: boolean;
  signIn: (s: Session) => void;
  signOut: () => Promise<void>;
  refresh: () => Promise<void>;
}

export const SessionContext = createContext<SessionContextValue | null>(null);

function readPersistedToken(): string | null {
  try {
    if (typeof window === "undefined") return null;
    return window.localStorage.getItem(SESSION_STORAGE_KEY);
  } catch {
    return null;
  }
}

function writePersistedToken(token: string | null): void {
  try {
    if (typeof window === "undefined") return;
    if (token) {
      window.localStorage.setItem(SESSION_STORAGE_KEY, token);
    } else {
      window.localStorage.removeItem(SESSION_STORAGE_KEY);
    }
  } catch {
    // Ignore — degrades to in-memory only.
  }
}

interface SessionProviderProps {
  children: ReactNode;
  /** Test seam: skip the initial auth.session() call. */
  skipBootstrap?: boolean;
  /** Test seam: pre-populate the session. */
  initialSession?: Session | null;
}

export function SessionProvider({
  children,
  skipBootstrap = false,
  initialSession = null,
}: SessionProviderProps): ReactElement {
  const [session, setSession] = useState<Session | null>(initialSession);
  const [isLoading, setIsLoading] = useState<boolean>(!skipBootstrap && initialSession === null);
  const didBootstrap = useRef<boolean>(false);

  const refresh = useCallback(async () => {
    try {
      const next = await auth.session();
      setSession(next);
      writePersistedToken(next?.accessToken ?? null);
    } catch {
      setSession(null);
      writePersistedToken(null);
    }
  }, []);

  useEffect(() => {
    if (skipBootstrap) return;
    if (didBootstrap.current) return;
    didBootstrap.current = true;

    bootstrapMockApi();
    // Touch persisted token so we know whether to call session().
    readPersistedToken();
    void refresh().finally(() => setIsLoading(false));
  }, [refresh, skipBootstrap]);

  const signIn = useCallback((next: Session) => {
    setSession(next);
    writePersistedToken(next.accessToken);
  }, []);

  const signOut = useCallback(async () => {
    try {
      await auth.logout();
    } catch {
      // Ignore — clear locally regardless.
    }
    setSession(null);
    writePersistedToken(null);
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
