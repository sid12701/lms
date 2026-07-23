/**
 * Session auth context — public barrel.
 *
 * `SessionContext` lives in `session-context-state.ts` so Vite fast refresh does not
 * replace the context object while `SessionProvider` / route guards hot reload.
 */
export { SessionContext, type SessionContextValue } from "@/features/auth/session-context-state";
export { SessionProvider } from "@/features/auth/session-provider";
export { useSession } from "@/features/auth/use-session";
