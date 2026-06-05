import { createContext } from "react";
import type { Session } from "@/features/auth/session-types";

export interface SessionContextValue {
  session: Session | null;
  isLoading: boolean;
  signIn: (s: Session) => void;
  signOut: () => Promise<void>;
  refresh: () => Promise<void>;
}

/** Stable context instance — keep in its own module so Vite HMR does not replace it. */
export const SessionContext = createContext<SessionContextValue | null>(null);
