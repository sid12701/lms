import { afterEach, describe, expect, it, vi } from "vitest";

const requestJsonMock = vi.hoisted(() => vi.fn());

vi.mock("@/lib/api/http-client", () => ({
  requestJson: requestJsonMock,
}));

import { listUsers, revokeUserSessions, createUser } from "./api";
import { makeCreateUserInput } from "./test-utils";

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

  it("maps passwordChangeRequired from the backend response", async () => {
    requestJsonMock.mockResolvedValue([
      {
        id: "11111111-1111-1111-1111-111111111111",
        username: "pending.user",
        email: "pending.user@bhawana.local",
        status: "ACTIVE",
        lspId: null,
        lspName: null,
        roles: ["OPS_USER"],
        passwordChangeRequired: true,
        createdAt: "2026-06-08T10:00:00.000Z",
      },
    ]);

    const result = await listUsers();

    expect(result.items[0]?.mustChangePassword).toBe(true);
  });
});

describe("createUser", () => {
  afterEach(() => {
    requestJsonMock.mockReset();
  });

  it("returns mustChangePassword true for newly created users", async () => {
    requestJsonMock.mockResolvedValue({
      id: "22222222-2222-2222-2222-222222222222",
      username: "created.user",
      email: "created.user@bhawana.local",
      status: "ACTIVE",
      lspId: null,
      lspName: "All LSPs",
      roles: ["OPS_USER"],
      passwordChangeRequired: true,
      createdAt: "2026-06-08T10:00:00.000Z",
      temporaryPassword: "server-minted-secret",
    });

    const result = await createUser(makeCreateUserInput());

    expect(result.user.mustChangePassword).toBe(true);
  });

  it("sends no browser-minted password and maps the server temporary password", async () => {
    requestJsonMock.mockResolvedValue({
      id: "22222222-2222-2222-2222-222222222222",
      username: "created.user",
      email: "created.user@bhawana.local",
      status: "ACTIVE",
      lspId: null,
      lspName: "All LSPs",
      roles: ["OPS_USER"],
      passwordChangeRequired: true,
      createdAt: "2026-06-08T10:00:00.000Z",
      temporaryPassword: "server-minted-secret",
    });

    const result = await createUser(makeCreateUserInput());

    expect(requestJsonMock).toHaveBeenCalledOnce();
    const init = requestJsonMock.mock.calls[0]?.[1];
    expect(init).toEqual(expect.objectContaining({ body: expect.any(String) }));
    const sentBody = JSON.parse(String(init?.body));
    expect(sentBody).not.toHaveProperty("password");
    expect(sentBody).toMatchObject({
      username: "created.user",
      email: "created.user@bhawana.local",
      status: "ACTIVE",
      roles: ["OPS_USER"],
    });
    expect(result.temporaryPassword).toBe("server-minted-secret");
    expect(result.user.username).toBe("created.user");
  });

  it("passes through a null credential on idempotent replay without rotating", async () => {
    requestJsonMock.mockResolvedValue({
      id: "22222222-2222-2222-2222-222222222222",
      username: "created.user",
      email: "created.user@bhawana.local",
      status: "ACTIVE",
      lspId: null,
      lspName: "All LSPs",
      roles: ["OPS_USER"],
      passwordChangeRequired: true,
      createdAt: "2026-06-08T10:00:00.000Z",
      temporaryPassword: null,
    });

    const result = await createUser(makeCreateUserInput());

    // Null means "already processed, not rotated" — the operator recovers via
    // a deliberate reset-password command, never a silent re-mint.
    expect(result.temporaryPassword).toBeNull();
    expect(result.user.username).toBe("created.user");
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
