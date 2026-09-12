import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api/http-client";
import {
  completePasswordChange,
  login,
  logout,
  refreshSession,
  resetAuthServiceForTests,
} from "@/features/auth/auth-service";
import {
  getAuthGeneration,
  resetAuthCoordinatorForTests,
  setCookieLockManagerForTests,
} from "@/features/auth/auth-coordinator";
import { createTestCookieLockManager } from "@/features/auth/test-cookie-lock";

vi.mock("@/lib/api/auth-api", () => ({
  refreshAccessToken: vi.fn(),
  fetchSystemContext: vi.fn(),
  loginWithPassword: vi.fn(),
  logoutSession: vi.fn(),
  completePasswordChange: vi.fn(),
}));

vi.mock("@/lib/api/session-storage", () => ({
  clearStoredSession: vi.fn(),
  saveStoredSession: vi.fn(),
  getStoredAccessToken: vi.fn(() => "bearer-A"),
}));

import {
  completePasswordChange as backendCompletePassword,
  fetchSystemContext,
  loginWithPassword,
  logoutSession,
  refreshAccessToken,
} from "@/lib/api/auth-api";
import {
  clearStoredSession,
  getStoredAccessToken,
  saveStoredSession,
} from "@/lib/api/session-storage";

const REFRESHED_TOKEN = {
  accessToken: "fresh-token",
  tokenType: "Bearer",
  expiresInSeconds: 1800,
  passwordChangeRequired: false,
};

const SYSTEM_CONTEXT = {
  application: "bhawana-lms",
  activeProfiles: ["test"],
  id: "3f2504e0-4f89-41d3-9a0c-0305e82c3301",
  username: "ops.admin",
  roles: ["SYSTEM_ADMIN"],
  correlationId: null,
  lspId: null,
  lspName: null,
};

describe("auth-service session refresh", () => {
  beforeEach(() => {
    // Each case starts from a clean browser context (no orphan marker) with
    // a test lock standing in for real Web Locks (absent in jsdom).
    resetAuthCoordinatorForTests({ clearStorage: true });
    setCookieLockManagerForTests(createTestCookieLockManager());
    resetAuthServiceForTests();
  });

  afterEach(() => {
    vi.clearAllMocks();
    resetAuthServiceForTests();
    resetAuthCoordinatorForTests({ clearStorage: true });
  });

  it("signs out only when the refresh endpoint explicitly rejects the cookie", async () => {
    vi.mocked(refreshAccessToken).mockRejectedValue(
      new ApiError("Refresh token was revoked", 401, "", "TOKEN_REVOKED", null, true),
    );

    await expect(refreshSession()).resolves.toEqual({
      status: "signed-out",
      code: "TOKEN_REVOKED",
    });
    expect(clearStoredSession).toHaveBeenCalledTimes(1);
  });

  it.each([
    ["network failure", new TypeError("Failed to fetch")],
    ["backend failure", new ApiError("Unavailable", 503, "", "SERVICE_UNAVAILABLE", null, true)],
  ])("preserves stored metadata for a retryable refresh %s", async (_label, error) => {
    vi.mocked(refreshAccessToken).mockRejectedValue(error);

    await expect(refreshSession()).rejects.toMatchObject({
      kind: "REFRESH_UNAVAILABLE",
    });
    expect(clearStoredSession).not.toHaveBeenCalled();
  });

  it("builds a session only from freshly verified workspace context", async () => {
    vi.mocked(refreshAccessToken).mockResolvedValue(REFRESHED_TOKEN);
    vi.mocked(fetchSystemContext).mockResolvedValue(SYSTEM_CONTEXT);

    const result = await refreshSession();

    expect(result).toMatchObject({
      status: "authenticated",
      session: {
        user: { username: "ops.admin", role: "SYSTEM_ADMIN" },
        accessToken: "fresh-token",
      },
    });
    expect(fetchSystemContext).toHaveBeenCalledWith("fresh-token", {
      refreshOnUnauthorized: false,
    });
    expect(saveStoredSession).toHaveBeenCalledTimes(1);
  });

  it.each([
    ["network failure", new TypeError("Failed to fetch")],
    ["navigation abort", new DOMException("Aborted", "AbortError")],
    ["backend outage", new ApiError("Unavailable", 503, "", "SERVICE_UNAVAILABLE", null, true)],
  ])(
    "does not clear or replace the session when context has a temporary %s",
    async (_label, error) => {
      vi.mocked(refreshAccessToken).mockResolvedValue(REFRESHED_TOKEN);
      vi.mocked(fetchSystemContext).mockRejectedValue(error);

      await expect(refreshSession()).rejects.toMatchObject({
        kind: "CONTEXT_UNAVAILABLE",
      });
      expect(clearStoredSession).not.toHaveBeenCalled();
      expect(saveStoredSession).not.toHaveBeenCalled();
    },
  );

  it("fails closed when fresh context rejects the refreshed access token", async () => {
    vi.mocked(refreshAccessToken).mockResolvedValue(REFRESHED_TOKEN);
    vi.mocked(fetchSystemContext).mockRejectedValue(
      new ApiError("Forbidden", 403, "", "ACCESS_DENIED", null, true),
    );

    await expect(refreshSession()).resolves.toEqual({
      status: "signed-out",
      code: "ACCESS_DENIED",
    });
    expect(clearStoredSession).toHaveBeenCalledTimes(1);
  });

  it("surfaces an invalid context contract instead of trusting cached authorization", async () => {
    vi.mocked(refreshAccessToken).mockResolvedValue(REFRESHED_TOKEN);
    vi.mocked(fetchSystemContext).mockResolvedValue({
      ...SYSTEM_CONTEXT,
      id: "not-a-uuid",
    });

    await expect(refreshSession()).rejects.toMatchObject({
      kind: "CONTEXT_INVALID",
    });
    expect(clearStoredSession).not.toHaveBeenCalled();
    expect(saveStoredSession).not.toHaveBeenCalled();
  });

  it("shares one rotating refresh request across concurrent callers", async () => {
    let resolveRefresh!: (token: typeof REFRESHED_TOKEN) => void;
    vi.mocked(refreshAccessToken).mockReturnValue(
      new Promise((resolve) => {
        resolveRefresh = resolve;
      }),
    );
    vi.mocked(fetchSystemContext).mockResolvedValue(SYSTEM_CONTEXT);

    const first = refreshSession();
    const second = refreshSession();

    expect(first).toBe(second);
    // Cookie exchanges serialize through the coordinator queue + Web
    // Lock, so the backend call lands on a microtask after enqueue.
    await vi.waitFor(() => expect(refreshAccessToken).toHaveBeenCalledTimes(1));

    resolveRefresh(REFRESHED_TOKEN);
    const [firstResult, secondResult] = await Promise.all([first, second]);
    expect(firstResult).toEqual(secondResult);
    expect(fetchSystemContext).toHaveBeenCalledTimes(1);
  });

  it("resyncs once on benign TOKEN_ROTATED without logout/revoke (winner stays authenticated)", async () => {
    vi.mocked(refreshAccessToken)
      .mockRejectedValueOnce(
        new ApiError("Direct parent loser", 401, "", "TOKEN_ROTATED", null, true),
      )
      .mockResolvedValueOnce(REFRESHED_TOKEN);
    vi.mocked(fetchSystemContext).mockResolvedValue(SYSTEM_CONTEXT);
    const generationBefore = getAuthGeneration();

    const result = await refreshSession();

    expect(result).toMatchObject({ status: "authenticated" });
    // Bounded: exactly one resync on the settled winner cookie, no recursion.
    expect(refreshAccessToken).toHaveBeenCalledTimes(2);
    expect(fetchSystemContext).toHaveBeenCalledTimes(1);
    // No logout/revoke: storage saved, never cleared, generation unadvanced.
    expect(saveStoredSession).toHaveBeenCalledTimes(1);
    expect(clearStoredSession).not.toHaveBeenCalled();
    expect(getAuthGeneration()).toBe(generationBefore);
  });

  it("signs out after a repeated TOKEN_ROTATED past the single bounded resync", async () => {
    vi.mocked(refreshAccessToken).mockRejectedValue(
      new ApiError("Direct parent loser", 401, "", "TOKEN_ROTATED", null, true),
    );

    await expect(refreshSession()).resolves.toEqual({
      status: "signed-out",
      code: "TOKEN_ROTATED",
    });
    expect(refreshAccessToken).toHaveBeenCalledTimes(2);
    expect(clearStoredSession).toHaveBeenCalledTimes(1);
  });

  it("advances + clears identity at login intent START, before any async", async () => {
    vi.mocked(loginWithPassword).mockImplementation(async () => {
      // By the time the cookie exchange runs, A must already be gone.
      expect(clearStoredSession).toHaveBeenCalled();
      return REFRESHED_TOKEN;
    });
    vi.mocked(fetchSystemContext).mockResolvedValue(SYSTEM_CONTEXT);
    const generationBefore = getAuthGeneration();

    const session = await login({ email: "b@example.com", password: "password123456" });

    expect(getAuthGeneration()).toBeGreaterThan(generationBefore);
    expect(session.accessToken).toBe("fresh-token");
    expect(saveStoredSession).toHaveBeenCalledTimes(1);
  });

  it("logout clears immediately and never restores on network failure", async () => {
    vi.mocked(logoutSession).mockRejectedValue(new TypeError("Failed to fetch"));

    await logout();

    expect(clearStoredSession).toHaveBeenCalled();
    expect(saveStoredSession).not.toHaveBeenCalled();
  });

  it("sends the captured A bearer on password change (never ambient B), no refresh on 401", async () => {
    // Real auth-api + HTTP boundary (not a mocked token proof): the request
    // must carry the bearer captured before the intent-start clear.
    const actualApi =
      await vi.importActual<typeof import("@/lib/api/auth-api")>("@/lib/api/auth-api");
    vi.mocked(backendCompletePassword).mockImplementation((password, options) =>
      actualApi.completePasswordChange(password, options),
    );
    vi.mocked(getStoredAccessToken).mockReturnValue("bearer-A-at-start");
    vi.mocked(fetchSystemContext).mockResolvedValue(SYSTEM_CONTEXT);
    let observedAuthorization: string | null = null;
    vi.stubGlobal(
      "fetch",
      vi.fn(async (url: unknown, init?: RequestInit) => {
        if (String(url).includes("/api/v1/auth/password")) {
          observedAuthorization = new Headers(init?.headers).get("Authorization");
          return new Response(JSON.stringify(REFRESHED_TOKEN), {
            status: 200,
            headers: { "Content-Type": "application/json" },
          });
        }
        throw new Error(`unexpected fetch ${String(url)}`);
      }),
    );
    try {
      const session = await completePasswordChange({ newPassword: "new-password-123" });

      expect(observedAuthorization).toBe("Bearer bearer-A-at-start");
      expect(session.accessToken).toBe("fresh-token");
      expect(saveStoredSession).toHaveBeenCalledTimes(1);
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it("rejects password 401 directly with no recursive refresh callback", async () => {
    const actualApi =
      await vi.importActual<typeof import("@/lib/api/auth-api")>("@/lib/api/auth-api");
    vi.mocked(backendCompletePassword).mockImplementation((password, options) =>
      actualApi.completePasswordChange(password, options),
    );
    vi.mocked(getStoredAccessToken).mockReturnValue("bearer-A-at-start");
    const { setRefreshCallback } = await import("@/lib/api/http-client");
    const refreshCallback = vi.fn(async () => "fresh-token");
    setRefreshCallback(refreshCallback);
    vi.stubGlobal(
      "fetch",
      vi.fn(async (url: unknown) => {
        if (String(url).includes("/api/v1/auth/password")) {
          return new Response(JSON.stringify({ code: "TOKEN_REVOKED" }), {
            status: 401,
            headers: { "Content-Type": "application/json" },
          });
        }
        throw new Error(`unexpected fetch ${String(url)}`);
      }),
    );
    try {
      // A 401 here must reject directly: invoking the global refresh from
      // inside the lock-held exchange would deadlock the coordinator queue.
      await expect(
        completePasswordChange({ newPassword: "new-password-123" }),
      ).rejects.toMatchObject({ status: 401 });
      expect(refreshCallback).not.toHaveBeenCalled();
      expect(saveStoredSession).not.toHaveBeenCalled();
    } finally {
      vi.unstubAllGlobals();
      setRefreshCallback(null);
    }
  });

  it("keys shared refresh by owning intent: pending A is not handed to B", async () => {
    let resolveA!: (token: typeof REFRESHED_TOKEN) => void;
    vi.mocked(refreshAccessToken).mockReturnValueOnce(
      new Promise((resolve) => {
        resolveA = resolve;
      }),
    );
    vi.mocked(fetchSystemContext).mockResolvedValue(SYSTEM_CONTEXT);

    const pendingA = refreshSession();
    const { advanceAuthGeneration } = await import("@/features/auth/auth-coordinator");
    advanceAuthGeneration("logout");
    // B's request after the advance must NOT receive A's shared promise.
    vi.mocked(refreshAccessToken).mockResolvedValue(REFRESHED_TOKEN);
    const requestB = refreshSession();
    expect(requestB).not.toBe(pendingA);

    resolveA(REFRESHED_TOKEN);
    // A resolves stale (superseded): never publishes.
    await expect(pendingA).rejects.toMatchObject({ name: "AuthStaleResultError" });
    await expect(requestB).resolves.toMatchObject({ status: "authenticated" });
    expect(saveStoredSession).toHaveBeenCalledTimes(1);
  });

  it("discards a login superseded mid-exchange without persisting", async () => {
    let resolveLogin!: (token: typeof REFRESHED_TOKEN) => void;
    vi.mocked(loginWithPassword).mockReturnValue(
      new Promise((resolve) => {
        resolveLogin = resolve;
      }),
    );
    vi.mocked(fetchSystemContext).mockResolvedValue(SYSTEM_CONTEXT);

    const pending = login({ email: "b@example.com", password: "password123456" });
    const { advanceAuthGeneration } = await import("@/features/auth/auth-coordinator");
    advanceAuthGeneration("logout");
    resolveLogin(REFRESHED_TOKEN);

    await expect(pending).rejects.toMatchObject({ name: "AuthStaleResultError" });
    expect(saveStoredSession).not.toHaveBeenCalled();
  });
});
