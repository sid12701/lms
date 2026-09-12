/**
 * H22 isolated cookie-transport harness entry (served through Vite).
 * Runs PRODUCTION provider + service + coordinator logic against the
 * harness same-origin HTTP API (real Set-Cookie browser jar, no
 * page.route). The React tree mirrors the app wiring: SessionProvider owns
 * visible state; service calls drive it exactly like LoginPage (login then
 * signIn) and the guards (signOut / refresh).
 */
/* eslint-disable react-refresh/only-export-components -- harness entry, not a component module */
import { useEffect } from "react";
import { createRoot } from "react-dom/client";
import { SessionProvider } from "@/features/auth/session-provider";
import { useSession } from "@/features/auth/use-session";
import type { Session } from "@/features/auth/session-types";
import { login } from "@/features/auth/auth-service";
import { logoutSession } from "@/lib/api/auth-api";
import {
  type AuthIntent,
  COOKIE_JAR_OWNER_STORAGE_KEY,
  captureAuthIntent,
  enqueueCookieOp,
  getAuthGeneration,
  isCookieExchangeBlocked,
} from "@/features/auth/auth-coordinator";

interface CtxSnapshot {
  signIn: (session: Session, expectedIntent?: AuthIntent) => boolean;
  signOut: () => Promise<void>;
  refresh: () => Promise<void>;
}

let latest: CtxSnapshot | null = null;

function Probe() {
  const ctx = useSession();
  // Publish outside render: reassignment during render is a side effect.
  useEffect(() => {
    latest = {
      signIn: ctx.signIn,
      signOut: ctx.signOut,
      refresh: ctx.refresh,
    };
  });
  return (
    <div
      id="harness-probe"
      data-user={ctx.session?.user.id ?? "signed-out"}
      data-generation={getAuthGeneration()}
    />
  );
}

function probeUserId(): string | null {
  const value = document.getElementById("harness-probe")?.getAttribute("data-user");
  if (!value || value === "signed-out") return null;
  return value;
}

async function ctxReady(): Promise<CtxSnapshot> {
  const deadline = Date.now() + 10000;
  for (;;) {
    if (latest) return latest;
    if (Date.now() > deadline) throw new Error("harness provider not mounted");
    await new Promise((resolve) => setTimeout(resolve, 25));
  }
}

const api = {
  // Trigger + settle only (mirrors the app's Retry button path). The
  // OBSERVABLE outcome is provider state — tests poll sessionUserId(),
  // because React commits asynchronously after the service settles.
  async refresh(): Promise<void> {
    const ctx = await ctxReady();
    await ctx.refresh();
  },
  async logout(): Promise<string> {
    const ctx = await ctxReady();
    await ctx.signOut();
    return "signed-out";
  },
  async login(email: string, password: string): Promise<string> {
    // Same wiring as LoginPage: service mints, provider publishes — the
    // sync-after-await handoff cannot interleave another intent.
    const pending = login({ email, password });
    const intent = captureAuthIntent();
    const session = await pending;
    const ctx = await ctxReady();
    if (!ctx.signIn(session, intent)) return "stale-publication";
    return `${session.user.id}:${session.accessToken}`;
  },
  getGeneration(): number {
    return getAuthGeneration();
  },
  sessionUserId(): string | null {
    return probeUserId();
  },
  isBlocked(): boolean {
    return isCookieExchangeBlocked();
  },
  readJarOwner(): { generation: number; intent: string; cleared: boolean } | null {
    const raw = window.localStorage.getItem(COOKIE_JAR_OWNER_STORAGE_KEY);
    if (!raw) return null;
    return JSON.parse(raw) as { generation: number; intent: string; cleared: boolean };
  },
  async dispatchStaleLogout(stale: { generation: number; intent: string }): Promise<string> {
    try {
      await enqueueCookieOp("logout", stale, () => logoutSession());
      return "dispatched";
    } catch (error) {
      const name = error instanceof Error ? error.name : String(error);
      return `rejected:${name}`;
    }
  },
};

(window as unknown as { __harness: typeof api }).__harness = api;
document.title = "H22 cookie harness";
const root = document.createElement("div");
root.id = "harness-root";
document.body.appendChild(root);
createRoot(root).render(
  <SessionProvider skipBootstrap>
    <Probe />
  </SessionProvider>,
);
