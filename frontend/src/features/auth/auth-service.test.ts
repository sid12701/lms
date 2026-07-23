import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api/http-client";
import { refreshSession } from "@/features/auth/auth-service";

vi.mock("@/lib/api/auth-api", () => ({
  refreshAccessToken: vi.fn(),
  fetchSystemContext: vi.fn(),
}));

vi.mock("@/lib/api/session-storage", () => ({
  clearStoredSession: vi.fn(),
  saveStoredSession: vi.fn(),
}));

import { fetchSystemContext, refreshAccessToken } from "@/lib/api/auth-api";
import { clearStoredSession, saveStoredSession } from "@/lib/api/session-storage";

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
  afterEach(() => {
    vi.clearAllMocks();
  });

  it("signs out only when the refresh endpoint explicitly rejects the cookie", async () => {
    vi.mocked(refreshAccessToken).mockRejectedValue(
      new ApiError("Refresh token was revoked", 401, "", "TOKEN_REVOKED"),
    );

    await expect(refreshSession()).resolves.toEqual({
      status: "signed-out",
      code: "TOKEN_REVOKED",
    });
    expect(clearStoredSession).toHaveBeenCalledTimes(1);
  });

  it.each([
    ["network failure", new TypeError("Failed to fetch")],
    ["backend failure", new ApiError("Unavailable", 503, "", "SERVICE_UNAVAILABLE")],
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
    ["backend outage", new ApiError("Unavailable", 503, "", "SERVICE_UNAVAILABLE")],
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
      new ApiError("Forbidden", 403, "", "ACCESS_DENIED"),
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
    expect(refreshAccessToken).toHaveBeenCalledTimes(1);

    resolveRefresh(REFRESHED_TOKEN);
    const [firstResult, secondResult] = await Promise.all([first, second]);
    expect(firstResult).toEqual(secondResult);
    expect(fetchSystemContext).toHaveBeenCalledTimes(1);
  });
});
