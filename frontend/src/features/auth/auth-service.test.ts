import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api/http-client";
import {
  clearLastRefreshFailureCode,
  getLastRefreshFailureCode,
  refreshSession,
} from "@/features/auth/auth-service";

vi.mock("@/lib/api/auth-api", () => ({
  refreshAccessToken: vi.fn(),
  fetchSystemContext: vi.fn(),
}));

vi.mock("@/lib/api/session-storage", () => ({
  clearStoredSession: vi.fn(),
  saveStoredSession: vi.fn(),
}));

import { refreshAccessToken } from "@/lib/api/auth-api";

describe("auth-service refresh failures", () => {
  afterEach(() => {
    vi.clearAllMocks();
    clearLastRefreshFailureCode();
  });

  it("surfaces TOKEN_REVOKED from a refresh 401 response body", async () => {
    vi.mocked(refreshAccessToken).mockRejectedValue(
      new ApiError(
        "Refresh token was revoked",
        401,
        JSON.stringify({ code: "TOKEN_REVOKED", message: "Refresh token was revoked" }),
        "TOKEN_REVOKED",
      ),
    );

    await expect(refreshSession()).resolves.toBeNull();
    expect(getLastRefreshFailureCode()).toBe("TOKEN_REVOKED");
  });

  it("clears the stored refresh failure code before a new refresh attempt", async () => {
    vi.mocked(refreshAccessToken)
      .mockRejectedValueOnce(
        new ApiError("Refresh token was revoked", 401, "", "TOKEN_REVOKED"),
      )
      .mockRejectedValueOnce(new ApiError("Refresh token has expired", 401, "", "TOKEN_EXPIRED"));

    await refreshSession();
    expect(getLastRefreshFailureCode()).toBe("TOKEN_REVOKED");

    await refreshSession();
    expect(getLastRefreshFailureCode()).toBe("TOKEN_EXPIRED");
  });
});
