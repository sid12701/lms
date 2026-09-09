import { StrictMode, useEffect, useState, type ReactElement, type ReactNode } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useMutation, useQuery, useQueryClient, type QueryClient } from "@tanstack/react-query";
import { SessionProvider } from "@/features/auth/session-provider";
import { AuthScopedQueryProvider } from "@/app/auth-scoped-query-provider";
import { useSession } from "@/features/auth/use-session";
import {
  logout as serviceLogout,
  refreshSession as serviceRefresh,
} from "@/features/auth/auth-service";
import { adminSession, lspReadSession } from "@/test/session-fixtures";
import type { Session } from "@/features/auth/session-types";

vi.mock("@/lib/api/session-storage", () => ({
  loadStoredSession: vi.fn(() => null),
  saveStoredSession: vi.fn(),
  clearStoredSession: vi.fn(),
}));

vi.mock("@/features/auth/auth-service", async () => {
  const actual = await vi.importActual<typeof import("@/features/auth/auth-service")>(
    "@/features/auth/auth-service",
  );
  return {
    ...actual,
    refreshSession: vi.fn(),
    logout: vi.fn(),
  };
});

const SHARED_KEY = ["my-loans"] as const;

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((res) => {
    resolve = res;
  });
  return { promise, resolve };
}

let scopedClient: QueryClient | null = null;

function ClientProbe() {
  const client = useQueryClient();
  useEffect(() => {
    scopedClient = client;
  }, [client]);
  return null;
}

function currentClient(): QueryClient {
  if (!scopedClient) throw new Error("no scoped QueryClient rendered");
  return scopedClient;
}

function SessionButtons() {
  const { session, signIn, signOut, refresh } = useSession();
  return (
    <div>
      <div data-testid="session-user">{session ? session.user.id : "signed-out"}</div>
      <button type="button" onClick={() => signIn(adminSession)}>
        signin-a
      </button>
      <button type="button" onClick={() => signIn(lspReadSession)}>
        signin-b
      </button>
      <button type="button" onClick={() => void signOut()}>
        signout
      </button>
      <button type="button" onClick={() => void refresh()}>
        refresh
      </button>
    </div>
  );
}

// Stateful but session-unaware: its state must reset on an auth switch,
// proving consumers remount instead of reusing old observers/data.
function StableMarker() {
  const [count, setCount] = useState(0);
  return (
    <div>
      <div data-testid="stable-count">{count}</div>
      <button type="button" onClick={() => setCount((c) => c + 1)}>
        bump-stable
      </button>
    </div>
  );
}

function Loans({ queryFn }: { queryFn: () => Promise<string> }) {
  const query = useQuery({ queryKey: SHARED_KEY, queryFn });
  return <div data-testid="loans">{query.data ?? "no-data"}</div>;
}

function Shell({
  queryFn,
  extra,
}: {
  queryFn: () => Promise<string>;
  extra?: ReactNode;
}): ReactElement {
  const { session } = useSession();
  return (
    <div>
      <SessionButtons />
      <StableMarker />
      {session ? <Loans queryFn={queryFn} /> : <div data-testid="loans">signed-out-no-data</div>}
      {extra}
    </div>
  );
}

// Same shape as useUploadLspDocument: captured client + setQueryData on success.
function UploadProbe({ gate }: { gate: () => Promise<string> }) {
  const queryClient = useQueryClient();
  const mutation = useMutation({
    mutationFn: gate,
    onSuccess: (value) => {
      queryClient.setQueryData<string>(SHARED_KEY, value);
    },
  });
  return (
    <button type="button" onClick={() => mutation.mutate()}>
      start-upload
    </button>
  );
}

function renderAuthTree(
  initial: Session | null,
  queryFn: () => Promise<string>,
  extra?: ReactNode,
) {
  return render(
    <SessionProvider skipBootstrap initialSession={initial}>
      <AuthScopedQueryProvider>
        <ClientProbe />
        <Shell queryFn={queryFn} extra={extra} />
      </AuthScopedQueryProvider>
    </SessionProvider>,
  );
}

async function signInAsB(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole("button", { name: "signin-b" }));
  await waitFor(() =>
    expect(screen.getByTestId("session-user")).toHaveTextContent(lspReadSession.user.id),
  );
}

afterEach(() => {
  scopedClient = null;
  vi.clearAllMocks();
});

describe("auth-scoped query isolation", () => {
  it("keeps a deferred A mutation's cache write out of B's scope", async () => {
    const user = userEvent.setup();
    vi.mocked(serviceLogout).mockResolvedValue(undefined);
    const uploadGate = deferred<string>();
    const bGate = deferred<string>();
    const queryFn = vi
      .fn<() => Promise<string>>()
      .mockResolvedValueOnce("seed-A")
      .mockReturnValue(bGate.promise);
    renderAuthTree(adminSession, queryFn, <UploadProbe gate={() => uploadGate.promise} />);

    expect(await screen.findByText("seed-A")).toBeInTheDocument();
    const oldClient = currentClient();
    expect(oldClient.getQueryData(SHARED_KEY)).toBe("seed-A");

    await user.click(screen.getByRole("button", { name: "bump-stable" }));
    expect(screen.getByTestId("stable-count")).toHaveTextContent("1");
    await user.click(screen.getByRole("button", { name: "start-upload" }));

    await signInAsB(user);
    const newClient = currentClient();
    expect(newClient).not.toBe(oldClient);
    expect(screen.getByTestId("stable-count")).toHaveTextContent("0");

    // The mutation resolves after the switch: its onSuccess still runs, but
    // only into the retired client.
    uploadGate.resolve("private-A");
    await waitFor(() => expect(oldClient.getQueryData(SHARED_KEY)).toBe("private-A"));

    expect(newClient.getQueryData(SHARED_KEY)).toBeUndefined();
    expect(screen.getByTestId("loans")).toHaveTextContent("no-data");
    expect(screen.queryByText("private-A")).not.toBeInTheDocument();
    expect(screen.queryByText("seed-A")).not.toBeInTheDocument();

    bGate.resolve("data-B");
    await waitFor(() => expect(screen.getByTestId("loans")).toHaveTextContent("data-B"));
    expect(newClient.getQueryData(SHARED_KEY)).toBe("data-B");
  });

  it("renders no A data for B while B's query is still unresolved", async () => {
    const user = userEvent.setup();
    const bGate = deferred<string>();
    const queryFn = vi
      .fn<() => Promise<string>>()
      .mockResolvedValueOnce("data-A")
      .mockReturnValue(bGate.promise);
    renderAuthTree(adminSession, queryFn);

    expect(await screen.findByText("data-A")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "bump-stable" }));

    await signInAsB(user);

    expect(screen.getByTestId("stable-count")).toHaveTextContent("0");
    expect(screen.getByTestId("loans")).toHaveTextContent("no-data");
    expect(screen.queryByText("data-A")).not.toBeInTheDocument();
    expect(currentClient().getQueryData(SHARED_KEY)).toBeUndefined();
    expect(queryFn).toHaveBeenCalledTimes(2);

    bGate.resolve("data-B");
    await waitFor(() => expect(screen.getByTestId("loans")).toHaveTextContent("data-B"));
  });

  it("leaves B unaffected when A's pending query resolves after the switch", async () => {
    const user = userEvent.setup();
    const aGate = deferred<string>();
    const bGate = deferred<string>();
    const queryFn = vi
      .fn<() => Promise<string>>()
      .mockReturnValueOnce(aGate.promise)
      .mockReturnValue(bGate.promise);
    renderAuthTree(adminSession, queryFn);
    const oldClient = currentClient();

    await signInAsB(user);
    const newClient = currentClient();
    expect(newClient).not.toBe(oldClient);

    // Retiring the old scope cancels its in-flight queries, so the late
    // resolution is discarded instead of landing anywhere.
    aGate.resolve("stale-A");
    await aGate.promise;
    await waitFor(() => expect(oldClient.getQueryData(SHARED_KEY)).toBeUndefined());

    expect(newClient.getQueryData(SHARED_KEY)).toBeUndefined();
    expect(screen.getByTestId("loans")).toHaveTextContent("no-data");
    expect(screen.queryByText("stale-A")).not.toBeInTheDocument();

    bGate.resolve("data-B");
    await waitFor(() => expect(screen.getByTestId("loans")).toHaveTextContent("data-B"));
  });

  it("keeps late A resolutions detached when B signs in after logout completes", async () => {
    const user = userEvent.setup();
    const logoutGate = deferred<void>();
    vi.mocked(serviceLogout).mockReturnValue(logoutGate.promise);
    const aGate = deferred<string>();
    const uploadGate = deferred<string>();
    const bGate = deferred<string>();
    const queryFn = vi
      .fn<() => Promise<string>>()
      .mockReturnValueOnce(aGate.promise)
      .mockReturnValue(bGate.promise);
    renderAuthTree(adminSession, queryFn, <UploadProbe gate={() => uploadGate.promise} />);
    const oldClient = currentClient();

    await user.click(screen.getByRole("button", { name: "start-upload" }));
    await user.click(screen.getByRole("button", { name: "signout" }));
    logoutGate.resolve();
    await waitFor(() => expect(screen.getByTestId("session-user")).toHaveTextContent("signed-out"));

    await signInAsB(user);
    const newClient = currentClient();
    expect(newClient).not.toBe(oldClient);
    expect(screen.getByTestId("loans")).toHaveTextContent("no-data");

    // Both A resolutions land after B signed in: the cancelled query is
    // discarded, the mutation completes only into the retired client.
    aGate.resolve("stale-A");
    uploadGate.resolve("private-A");
    await waitFor(() => expect(oldClient.getQueryData(SHARED_KEY)).toBe("private-A"));

    expect(newClient.getQueryData(SHARED_KEY)).toBeUndefined();
    expect(screen.getByTestId("loans")).toHaveTextContent("no-data");
    expect(screen.queryByText("stale-A")).not.toBeInTheDocument();
    expect(screen.queryByText("private-A")).not.toBeInTheDocument();

    bGate.resolve("data-B");
    await waitFor(() => expect(screen.getByTestId("loans")).toHaveTextContent("data-B"));
    expect(newClient.getQueryData(SHARED_KEY)).toBe("data-B");
  });

  it("scopes the cache per user on a direct sign-in change", async () => {
    const user = userEvent.setup();
    const queryFn = vi
      .fn<() => Promise<string>>()
      .mockResolvedValueOnce("data-A")
      .mockResolvedValue("data-B");
    renderAuthTree(adminSession, queryFn);

    expect(await screen.findByText("data-A")).toBeInTheDocument();
    const oldClient = currentClient();

    await signInAsB(user);

    expect(currentClient()).not.toBe(oldClient);
    await waitFor(() => expect(screen.getByTestId("loans")).toHaveTextContent("data-B"));
    expect(screen.queryByText("data-A")).not.toBeInTheDocument();
    expect(queryFn).toHaveBeenCalledTimes(2);
  });

  it("treats a role-only change as a new scope", async () => {
    const user = userEvent.setup();
    const changedRole: Session = {
      ...adminSession,
      accessToken: "refreshed.token",
      user: { ...adminSession.user, role: "OPS_USER" },
    };
    const queryFn = vi
      .fn<() => Promise<string>>()
      .mockResolvedValueOnce("before-role-change")
      .mockResolvedValue("after-role-change");

    function RoleShell() {
      const { signIn } = useSession();
      return (
        <div>
          <Shell queryFn={queryFn} />
          <button type="button" onClick={() => signIn(changedRole)}>
            change-role
          </button>
        </div>
      );
    }
    render(
      <SessionProvider skipBootstrap initialSession={adminSession}>
        <AuthScopedQueryProvider>
          <ClientProbe />
          <RoleShell />
        </AuthScopedQueryProvider>
      </SessionProvider>,
    );

    expect(await screen.findByText("before-role-change")).toBeInTheDocument();
    const oldClient = currentClient();

    await user.click(screen.getByRole("button", { name: "change-role" }));

    await waitFor(() => expect(screen.getByTestId("loans")).toHaveTextContent("after-role-change"));
    expect(currentClient()).not.toBe(oldClient);
    expect(screen.queryByText("before-role-change")).not.toBeInTheDocument();
  });

  it("treats an LSP-only change as a new scope", async () => {
    const user = userEvent.setup();
    const otherLsp: Session = {
      ...lspReadSession,
      accessToken: "refreshed.token",
      user: {
        ...lspReadSession.user,
        lspId: "00000000-0000-4000-8000-000000000077",
      },
    };
    const queryFn = vi
      .fn<() => Promise<string>>()
      .mockResolvedValueOnce("before-lsp-change")
      .mockResolvedValue("after-lsp-change");

    function LspShell() {
      const { signIn } = useSession();
      return (
        <div>
          <Shell queryFn={queryFn} />
          <button type="button" onClick={() => signIn(otherLsp)}>
            switch-lsp
          </button>
        </div>
      );
    }
    render(
      <SessionProvider skipBootstrap initialSession={lspReadSession}>
        <AuthScopedQueryProvider>
          <ClientProbe />
          <LspShell />
        </AuthScopedQueryProvider>
      </SessionProvider>,
    );

    expect(await screen.findByText("before-lsp-change")).toBeInTheDocument();
    const oldClient = currentClient();

    await user.click(screen.getByRole("button", { name: "switch-lsp" }));

    await waitFor(() => expect(screen.getByTestId("loans")).toHaveTextContent("after-lsp-change"));
    expect(currentClient()).not.toBe(oldClient);
    expect(screen.queryByText("before-lsp-change")).not.toBeInTheDocument();
  });

  it("drops to a fresh signed-out scope on definitive refresh expiration", async () => {
    const user = userEvent.setup();
    vi.mocked(serviceRefresh).mockResolvedValue({
      status: "signed-out",
      code: "TOKEN_REVOKED",
    });
    renderAuthTree(adminSession, () => Promise.resolve("data-A"));

    expect(await screen.findByText("data-A")).toBeInTheDocument();
    const oldClient = currentClient();

    await user.click(screen.getByRole("button", { name: "refresh" }));
    await waitFor(() => expect(screen.getByTestId("session-user")).toHaveTextContent("signed-out"));

    expect(currentClient()).not.toBe(oldClient);
    expect(currentClient().getQueryData(SHARED_KEY)).toBeUndefined();
    expect(screen.getByTestId("loans")).toHaveTextContent("signed-out-no-data");
  });

  it("keeps the same client and cache on a same-context token refresh", async () => {
    const user = userEvent.setup();
    const refreshed: Session = { ...adminSession, accessToken: "rotated.token" };
    vi.mocked(serviceRefresh).mockResolvedValue({
      status: "authenticated",
      session: refreshed,
    });
    const queryFn = vi.fn<() => Promise<string>>().mockResolvedValue("data-A");
    renderAuthTree(adminSession, queryFn);

    expect(await screen.findByText("data-A")).toBeInTheDocument();
    const client = currentClient();

    await user.click(screen.getByRole("button", { name: "refresh" }));
    await waitFor(() => expect(serviceRefresh).toHaveBeenCalled());

    expect(currentClient()).toBe(client);
    expect(client.getQueryData(SHARED_KEY)).toBe("data-A");
    expect(screen.getByTestId("loans")).toHaveTextContent("data-A");
    expect(queryFn).toHaveBeenCalledTimes(1);
  });

  it("loads and isolates scopes under StrictMode", async () => {
    const user = userEvent.setup();
    const bGate = deferred<string>();
    const queryFn = vi.fn<() => Promise<string>>().mockResolvedValue("data-A");
    render(
      <StrictMode>
        <SessionProvider skipBootstrap initialSession={adminSession}>
          <AuthScopedQueryProvider>
            <ClientProbe />
            <Shell queryFn={queryFn} />
          </AuthScopedQueryProvider>
        </SessionProvider>
      </StrictMode>,
    );

    expect(await screen.findByText("data-A")).toBeInTheDocument();
    const oldClient = currentClient();

    queryFn.mockReturnValue(bGate.promise);
    await signInAsB(user);

    expect(currentClient()).not.toBe(oldClient);
    expect(screen.getByTestId("stable-count")).toHaveTextContent("0");
    expect(screen.getByTestId("loans")).toHaveTextContent("no-data");
    expect(screen.queryByText("data-A")).not.toBeInTheDocument();

    bGate.resolve("data-B");
    await waitFor(() => expect(screen.getByTestId("loans")).toHaveTextContent("data-B"));
    expect(currentClient().getQueryData(SHARED_KEY)).toBe("data-B");
  });
});
