import { afterEach, describe, expect, it, vi } from "vitest";

const requestJsonMock = vi.hoisted(() => vi.fn());
const requestJsonWithHeadersMock = vi.hoisted(() => vi.fn());

vi.mock("@/lib/api/http-client", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api/http-client")>();
  return {
    ...actual,
    requestJson: requestJsonMock,
    requestJsonWithHeaders: requestJsonWithHeadersMock,
  };
});

import { listUsers, revokeUserSessions, createUser } from "./api";
import { makeCreateUserInput } from "./test-utils";

function listResult(items: unknown[], headers: Record<string, string> = {}) {
  return { data: items, headers: new Headers(headers) };
}

describe("listUsers", () => {
  afterEach(() => {
    requestJsonWithHeadersMock.mockReset();
  });

  it("maps lockout fields from the backend response", async () => {
    requestJsonWithHeadersMock.mockResolvedValue(
      listResult([
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
      ]),
    );

    const result = await listUsers();

    expect(result.items[0]?.lockedAt).toBe("2026-06-08T10:00:00.000Z");
    expect(result.items[0]?.lockReason).toBe("BRUTE_FORCE");
  });

  it("maps passwordChangeRequired from the backend response", async () => {
    requestJsonWithHeadersMock.mockResolvedValue(
      listResult([
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
      ]),
    );

    const result = await listUsers();

    expect(result.items[0]?.mustChangePassword).toBe(true);
  });

  // M20 — filters and pagination travel to the backend; no local filtering.
  it("sends filters and pagination params, and reads totals from headers", async () => {
    requestJsonWithHeadersMock.mockResolvedValue(
      listResult([], { "X-Total-Count": "137", "X-Limit": "50", "X-Offset": "50" }),
    );

    const result = await listUsers({
      q: "ops",
      role: "OPS_USER",
      status: "DISABLED",
      lspId: "33333333-3333-3333-3333-333333333333",
      page: 1,
      pageSize: 50,
    });

    const [path, init] = requestJsonWithHeadersMock.mock.calls[0] as [string, RequestInit];
    const url = new URL(path, "http://localhost");
    expect(url.pathname).toBe("/api/v1/internal/admin/users");
    expect(url.searchParams.get("q")).toBe("ops");
    expect(url.searchParams.get("role")).toBe("OPS_USER");
    expect(url.searchParams.get("status")).toBe("INACTIVE");
    expect(url.searchParams.get("lspId")).toBe("33333333-3333-3333-3333-333333333333");
    expect(url.searchParams.get("offset")).toBe("50");
    expect(url.searchParams.get("limit")).toBe("50");
    expect(url.searchParams.get("paginationDetails")).toBe("ON");
    expect(init.signal).toBeUndefined();

    expect(result.total).toBe(137);
    expect(result.page).toBe(1);
    expect(result.pageSize).toBe(50);
    expect(result.items).toEqual([]);
  });

  it("defaults to the first page and falls back to the item count when headers are absent", async () => {
    requestJsonWithHeadersMock.mockResolvedValue(
      listResult([
        {
          id: "11111111-1111-1111-1111-111111111111",
          username: "ops.user",
          email: "ops.user@bhawana.local",
          status: "ACTIVE",
          lspId: null,
          lspName: null,
          roles: ["OPS_USER"],
          createdAt: "2026-06-08T10:00:00.000Z",
        },
      ]),
    );

    const result = await listUsers();

    const [path] = requestJsonWithHeadersMock.mock.calls[0] as [string];
    const url = new URL(path, "http://localhost");
    expect(url.searchParams.get("offset")).toBe("0");
    expect(url.searchParams.get("limit")).toBe("25");
    expect(url.searchParams.get("q")).toBeNull();
    expect(result.total).toBe(1);
    expect(result.page).toBe(0);
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
