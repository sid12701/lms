import { useContext } from "react";
import { SessionContext, type SessionContextValue } from "@/features/auth/session-context-state";

export function useSession(): SessionContextValue {
  const ctx = useContext(SessionContext);
  if (!ctx) {
    throw new Error("useSession must be used within a SessionProvider");
  }
  return ctx;
}
