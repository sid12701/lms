/**
 * Real-provider generation tests — mount through the actual
 * coordinator/auth-service/provider (only backend HTTP is deferred), never
 * by mocking serviceRefresh or hand-calling signIn for the refreshed path.
 */
import { useRef, useState } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { SessionProvider } from "@/features/auth/session-provider";
import { AuthScopedQueryProvider } from "@/app/auth-scoped-query-provider";
import { useSession } from "@/features/auth/use-session";
import {
  adoptRemoteIntent,
  captureAuthIntent,
  advanceAuthGeneration,
  AUTH_GENERATION_STORAGE_KEY,
  resetAuthCoordinatorForTests,
  setCookieLockManagerForTests,
} from "@/features/auth/auth-coordinator";
import { createTestCookieLockManager } from "@/features/auth/test-cookie-lock";
import { resetAuthServiceForTests } from "@/features/auth/auth-service";
import { clearStoredSession, saveStoredSession } from "@/lib/api/session-storage";
import { setRefreshCallback } from "@/lib/api/http-client";
import { adminSession, lspReadSession } from "@/test/session-fixtures";

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

const REFRESH_TOKEN_A = {
  accessToken: "fresh-token-A",
  tokenType: "Bearer",
  expiresInSeconds: 1800,
  passwordChangeRequired: false,
};

const LOGIN_TOKEN_B = {
  accessToken: "fresh-token-B",
  tokenType: "Bearer",
  expiresInSeconds: 1800,
  passwordChangeRequired: false,
};

function contextFor(user: typeof adminSession.user) {
  return {
    application: "bhawana-lms",
    activeProfiles: ["test"],
    id: user.id,
    username: user.username,
    roles: [user.role],
    correlationId: null,
    lspId: user.lspId,
    lspName: null as string | null,
  };
}

function SessionState({ initialIntent }: { initialIntent: ReturnType<typeof captureAuthIntent> }) {
  const { session, signOut, signIn, refresh } = useSession();
  const captured = useRef(initialIntent);
  const [accepted, setAccepted] = useState<boolean | null>(null);
  return (
    <div>
      <div data-testid="publication-accepted">{String(accepted)}</div>
      <button type="button" onClick={() => setAccepted(signIn(adminSession, captured.current))}>
        publish captured A
      </button>
      <div data-testid="session-user">{session ? session.user.id : "signed-out"}</div>
      <div data-testid="session-token">{session ? session.accessToken : "no-token"}</div>
      <button type="button" onClick={() => void signOut()}>
        signout
      </button>
      <button
        type="button"
        onClick={() => {
          advanceAuthGeneration("peer-login-published");
          saveStoredSession(lspReadSession);
          signIn(lspReadSession);
        }}
      >
        publish B
      </button>
      <button type="button" onClick={() => void refresh()}>
        refresh
      </button>
    </div>
  );
}

function renderRealTree(initial: typeof adminSession | null) {
  return render(
    <SessionProvider skipBootstrap initialSession={initial}>
      <AuthScopedQueryProvider>
        <SessionState initialIntent={captureAuthIntent()} />
      </AuthScopedQueryProvider>
    </SessionProvider>,
  );
}

beforeEach(() => {
  resetAuthCoordinatorForTests({ clearStorage: true });
  setCookieLockManagerForTests(createTestCookieLockManager());
  resetAuthServiceForTests();
  clearStoredSession();
  setRefreshCallback(null);
  window.localStorage.clear();
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.clearAllMocks();
  setRefreshCallback(null);
  clearStoredSession();
  resetAuthServiceForTests();
  resetAuthCoordinatorForTests({ clearStorage: true });
});

describe("real-provider generation boundary", () => {
  it("provider rejects same-epoch publication from the losing intent", async () => {
    const user = userEvent.setup();
    renderRealTree(adminSession);
    const captured = captureAuthIntent();
    await act(async () => {
      adoptRemoteIntent({ generation: captured.generation, intent: "zz-winning-peer" });
    });
    await user.click(screen.getByRole("button", { name: "publish captured A" }));
    expect(screen.getByTestId("publication-accepted")).toHaveTextContent("false");
    expect(screen.getByTestId("session-user")).toHaveTextContent("signed-out");
  });

  it("late provider logout completion cannot erase an already published newer identity", async () => {
    const user = userEvent.setup();
    const gate = deferred<Response>();
    const fetchMock = vi.fn(() => gate.promise);
    vi.stubGlobal("fetch", fetchMock);
    saveStoredSession(adminSession);
    renderRealTree(adminSession);
    await user.click(screen.getByRole("button", { name: "signout" }));
    await waitFor(() => expect(fetchMock).toHaveBeenCalledOnce());
    // Publish a newer identity at the provider boundary while the obsolete
    // logout callback is pending. Cookie transport ordering is proved by
    // separate real-browser tests; this assertion is about visible state.
    await user.click(screen.getByRole("button", { name: "publish B" }));
    expect(screen.getByTestId("session-user")).toHaveTextContent(lspReadSession.user.id);
    await act(async () => {
      gate.resolve(new Response(null, { status: 204 }));
    });
    expect(screen.getByTestId("session-user")).toHaveTextContent(lspReadSession.user.id);
    expect(screen.getByTestId("session-token")).toHaveTextContent(lspReadSession.accessToken);
  });

  it("storage propagation invalidates a mounted provider without BroadcastChannel", async () => {
    vi.stubGlobal("BroadcastChannel", undefined);
    saveStoredSession(adminSession);
    renderRealTree(adminSession);
    const next = JSON.stringify({ generation: 20, intent: "peer-logout" });
    window.localStorage.setItem(AUTH_GENERATION_STORAGE_KEY, next);
    await act(async () => {
      window.dispatchEvent(
        new StorageEvent("storage", {
          key: AUTH_GENERATION_STORAGE_KEY,
          newValue: next,
        }),
      );
    });
    expect(screen.getByTestId("session-user")).toHaveTextContent("signed-out");
  });

  it("deferred A refresh success vs logout stays signed-out (no A resurrect)", async () => {
    const user = userEvent.setup();
    const refreshGate = deferred<Response>();
    const contextGate = deferred<Response>();
    const logoutGate = deferred<Response>();
    saveStoredSession(adminSession);

    const fetchMock = vi.fn(async (url: unknown) => {
      const href = String(url);
      if (href.includes("/api/v1/auth/refresh")) return refreshGate.promise;
      if (href.includes("/api/v1/internal/system/context")) return contextGate.promise;
      if (href.includes("/api/v1/auth/logout")) return logoutGate.promise;
      throw new Error(`unexpected fetch ${href}`);
    });
    vi.stubGlobal("fetch", fetchMock);
    renderRealTree(adminSession);
    expect(screen.getByTestId("session-user")).toHaveTextContent(adminSession.user.id);

    // Start A refresh (deferred at the cookie exchange).
    const refreshClick = user.click(screen.getByRole("button", { name: "refresh" }));
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalled());
    expect(fetchMock.mock.calls.some(([u]) => String(u).includes("/api/v1/auth/refresh"))).toBe(
      true,
    );

    // Logout advances NOW and clears visible state before the network settles.
    await user.click(screen.getByRole("button", { name: "signout" }));
    await waitFor(() => expect(screen.getByTestId("session-user")).toHaveTextContent("signed-out"));
    expect(screen.getByTestId("session-token")).toHaveTextContent("no-token");

    // Old A refresh succeeds late — must NOT resurrect A or touch storage.
    await refreshClick;
    refreshGate.resolve(
      new Response(JSON.stringify(REFRESH_TOKEN_A), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      }),
    );
    // Context for the stale token resolves too; provider must still discard.
    await vi.waitFor(() =>
      expect(
        fetchMock.mock.calls.some(([u]) => String(u).includes("/api/v1/internal/system/context")),
      ).toBe(true),
    );
    contextGate.resolve(
      new Response(JSON.stringify(contextFor(adminSession.user)), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      }),
    );
    logoutGate.resolve(new Response(null, { status: 204 }));

    await waitFor(() => expect(screen.getByTestId("session-user")).toHaveTextContent("signed-out"));
    expect(window.localStorage.getItem("bhawana-lms-session")).toBeNull();
  });

  it("deferred A refresh 401 vs logout does not erase B (failed old cannot clear newer)", async () => {
    const user = userEvent.setup();
    const refreshGate = deferred<Response>();
    const logoutGate = deferred<Response>();
    saveStoredSession(adminSession);

    const fetchMock = vi.fn(async (url: unknown, init?: RequestInit) => {
      const href = String(url);
      if (href.includes("/api/v1/auth/refresh")) return refreshGate.promise;
      if (href.includes("/api/v1/auth/logout")) return logoutGate.promise;
      if (href.includes("/api/v1/auth/login")) {
        return new Response(JSON.stringify(LOGIN_TOKEN_B), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        });
      }
      if (href.includes("/api/v1/internal/system/context")) {
        const auth = new Headers(init?.headers).get("Authorization");
        // B's context fetch carries B's bearer, never A's.
        expect(auth).toBe("Bearer fresh-token-B");
        return new Response(JSON.stringify(contextFor(lspReadSession.user)), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        });
      }
      throw new Error(`unexpected fetch ${href}`);
    });
    vi.stubGlobal("fetch", fetchMock);
    renderRealTree(adminSession);

    const refreshClick = user.click(screen.getByRole("button", { name: "refresh" }));
    await vi.waitFor(() =>
      expect(fetchMock.mock.calls.some(([u]) => String(u).includes("/api/v1/auth/refresh"))).toBe(
        true,
      ),
    );
    await user.click(screen.getByRole("button", { name: "signout" }));
    await waitFor(() => expect(screen.getByTestId("session-user")).toHaveTextContent("signed-out"));

    // Old A refresh fails definitively late — must not touch the new state.
    // (B has not logged in yet; the failure must simply stay signed-out.)
    await refreshClick;
    refreshGate.resolve(new Response(JSON.stringify({ code: "TOKEN_REVOKED" }), { status: 401 }));
    logoutGate.resolve(new Response(null, { status: 204 }));

    await waitFor(() => expect(screen.getByTestId("session-user")).toHaveTextContent("signed-out"));
    expect(window.localStorage.getItem("bhawana-lms-session")).toBeNull();
  });

  it("old logout queues before B login (order + final B, no A leak)", async () => {
    const callOrder: string[] = [];
    const logoutGate = deferred<Response>();

    const fetchMock = vi.fn(async (url: unknown) => {
      const href = String(url);
      if (href.includes("/api/v1/auth/logout")) {
        callOrder.push("logout");
        return logoutGate.promise;
      }
      if (href.includes("/api/v1/auth/login")) {
        callOrder.push("login-B");
        return new Response(JSON.stringify(LOGIN_TOKEN_B), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        });
      }
      if (href.includes("/api/v1/internal/system/context")) {
        return new Response(JSON.stringify(contextFor(lspReadSession.user)), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        });
      }
      throw new Error(`unexpected fetch ${href}`);
    });
    vi.stubGlobal("fetch", fetchMock);

    // Drive the real service directly (same coordinator queue the provider uses).
    const { login, logout } = await import("@/features/auth/auth-service");
    saveStoredSession(adminSession);

    const oldLogout = logout();
    // B login enqueues behind the old logout's unsettled exchange.
    await vi.waitFor(() =>
      expect(fetchMock.mock.calls.some(([u]) => String(u).includes("/api/v1/auth/logout"))).toBe(
        true,
      ),
    );
    const bLogin = login({ email: "b@example.com", password: "password123456" });

    // Old logout has not settled; B must not have been sent yet.
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(callOrder).toEqual(["logout"]);
    expect(callOrder).not.toContain("login-B");

    logoutGate.resolve(new Response(null, { status: 204 }));
    await oldLogout;
    const bSession = await bLogin;
    expect(bSession.user.id).toBe(lspReadSession.user.id);
    expect(bSession.accessToken).toBe("fresh-token-B");
    expect(callOrder).toEqual(["logout", "login-B"]);
  });
});
