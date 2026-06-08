import { afterEach, describe, expect, it, vi } from "vitest";

const requestJsonMock = vi.hoisted(() => vi.fn());

vi.mock("@/lib/api/http-client", () => ({
  requestJson: requestJsonMock,
}));

import { listUsers, revokeUserSessions } from "./api";

describe("listUsers", () => {
  afterEach(() => {
    requestJsonMock.mockReset();
  });

  it("maps lockout fields from the backend response", async () => {
    requestJsonMock.mockResolvedValue([
      {
        id: "11111111-1111-1111-1111-111111111111",
        username: "locked.user",
        email: "locked.user@bhawana.local",
        status: "ACTIVE",
        lspId: null,
        lspName: null,
        roles: ["OPS_USER"],
        lockedAt: "2026-06-08T10:00:00.000Z",
        lockReason: "BRUTE_FORCE",
      },
    ]);

    const result = await listUsers();

    expect(result.items[0]?.lockedAt).toBe("2026-06-08T10:00:00.000Z");
    expect(result.items[0]?.lockReason).toBe("BRUTE_FORCE");
  });
});

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
