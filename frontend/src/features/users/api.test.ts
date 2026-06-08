import { afterEach, describe, expect, it, vi } from "vitest";

const requestJsonMock = vi.hoisted(() => vi.fn());

vi.mock("@/lib/api/http-client", () => ({
  requestJson: requestJsonMock,
}));

import { revokeUserSessions } from "./api";

describe("revokeUserSessions", () => {
  afterEach(() => {
    requestJsonMock.mockReset();
  });

  it("posts to the revoke-sessions endpoint with optional reason", async () => {
    requestJsonMock.mockResolvedValue({
      status: "OK",
      previousTokenVersion: 0,
      newTokenVersion: 1,
      refreshTokensRevoked: 2,
    });

    await revokeUserSessions("user-1", {
      reason: "Suspected compromise",
      idempotencyKey: "idem-80",
    });

    expect(requestJsonMock).toHaveBeenCalledOnce();
    expect(requestJsonMock).toHaveBeenCalledWith(
      "/api/v1/internal/admin/users/user-1/revoke-sessions",
      {
        method: "POST",
        body: JSON.stringify({ reason: "Suspected compromise" }),
      },
      { idempotencyKey: "idem-80" },
    );
  });
});
