import { useEffect, useState, type ReactElement, type ReactNode } from "react";
import { QueryClientProvider } from "@tanstack/react-query";
import { createAppQueryClient } from "@/app/query-client";
import { useSession } from "@/features/auth/use-session";
import type { Session } from "@/features/auth/session-types";

function scopeKey(session: Session | null): string {
  if (!session) return "signed-out";
  // M19 — the scope key embeds the full effective role set, not just the
  // primary role: a capability change that leaves the primary role intact
  // (e.g. PRODUCT_ADMIN dropped from an OPS_USER+PRODUCT_ADMIN user) must
  // still retire the old client so stale protected data cannot persist.
  return `${session.user.id}|${session.user.lspId ?? ""}|${session.user.roles.join(",")}`;
}

function ScopedClientProvider({ children }: { children: ReactNode }): ReactElement {
  const [client] = useState(() => createAppQueryClient());
  useEffect(() => {
    return () => {
      void client.cancelQueries();
      client.clear();
    };
  }, [client]);
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}

// One QueryClient per authorization context. A new scope remounts the inner
// provider, so in-flight query/mutation callbacks can only write to a retired
// cache the new session never reads. Same-identity token refreshes keep the
// scope — and the client.
export function AuthScopedQueryProvider({ children }: { children: ReactNode }): ReactElement {
  const { session } = useSession();
  return <ScopedClientProvider key={scopeKey(session)}>{children}</ScopedClientProvider>;
}
