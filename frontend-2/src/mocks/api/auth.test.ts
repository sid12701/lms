/**
 * Auth handler tests — verifies the seed users + change-password flow.
 */
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { setLatencyOverride } from "../latency";
import { scenario } from "../scenarios";
import { _clearIdempotencyCacheForTests } from "../router";
import { resetMockApi, auth } from "./index";

beforeEach(() => {
  setLatencyOverride(0);
  scenario.reset();
  _clearIdempotencyCacheForTests();
  resetMockApi();
});

afterEach(() => {
  scenario.reset();
  setLatencyOverride(null);
});

describe("auth.login", () => {
  it("returns a session for ops.admin (mustChangePassword=false)", async () => {
    const session = await auth.login({ username: "ops.admin", password: "any" });
    expect(session.user.username).toBe("ops.admin");
    expect(session.user.role).toBe("SYSTEM_ADMIN");
    expect(session.user.mustChangePassword).toBe(false);
    expect(session.accessToken).toMatch(/^mock\./);
  });

  it("flags temp.user with mustChangePassword=true", async () => {
    const session = await auth.login({ username: "temp.user", password: "any" });
    expect(session.user.mustChangePassword).toBe(true);
  });

  it("rejects unknown users with UnauthorizedError", async () => {
    await expect(auth.login({ username: "nope", password: "x" })).rejects.toMatchObject({
      code: "UNAUTHORIZED",
      httpStatus: 401,
    });
  });

  it("idempotency: same key returns the cached session", async () => {
    const key = "test-idem-1";
    const a = await auth.login({ username: "ops.admin", password: "any" }, { idempotencyKey: key });
    const b = await auth.login({ username: "ops.admin", password: "any" }, { idempotencyKey: key });
    expect(b).toEqual(a);
  });
});

describe("auth.session + logout", () => {
  it("session() returns null when nobody is logged in", async () => {
    const s = await auth.session();
    expect(s).toBeNull();
  });

  it("session() reflects the active login", async () => {
    await auth.login({ username: "lsp.write", password: "any" });
    const s = await auth.session();
    expect(s?.user.username).toBe("lsp.write");
    expect(s?.user.role).toBe("LSP_UI_WRITE");
    expect(s?.user.lspId).not.toBeNull();
  });

  it("logout clears the session", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    await auth.logout();
    expect(await auth.session()).toBeNull();
  });
});

describe("auth.completePasswordChange", () => {
  it("clears the mustChangePassword flag", async () => {
    const before = await auth.login({ username: "temp.user", password: "any" });
    expect(before.user.mustChangePassword).toBe(true);

    const after = await auth.completePasswordChange({ newPassword: "freshpass1" });
    expect(after.user.mustChangePassword).toBe(false);

    const refreshed = await auth.session();
    expect(refreshed?.user.mustChangePassword).toBe(false);
  });

  it("rejects too-short passwords", async () => {
    await auth.login({ username: "temp.user", password: "any" });
    await expect(auth.completePasswordChange({ newPassword: "short" })).rejects.toMatchObject({
      code: "BAD_REQUEST",
    });
  });

  it("rejects when no session is active", async () => {
    await expect(auth.completePasswordChange({ newPassword: "freshpass1" })).rejects.toMatchObject({
      code: "UNAUTHORIZED",
    });
  });
});
