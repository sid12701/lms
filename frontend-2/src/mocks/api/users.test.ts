/**
 * Users-admin handler tests (Phase 9 — Agent C).
 *
 * Mirrors `alerts.test.ts` setup: reseed via `resetMockApi()` so the 6 seeded
 * users are available; imports the users module directly so its
 * self-registration runs.
 */
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { setLatencyOverride } from "../latency";
import { scenario } from "../scenarios";
import { resetIdempotency } from "../idempotency";
import { resetMockApi, auth } from "./index";
import {
  createUser,
  listUsers,
  resetUserPassword,
  updateUser,
} from "./users";
import { newIdempotencyKey } from "@/lib/idempotency";
import { LSP_BHAW_DEMO, USER_LSP_READ, USER_OPS_USER } from "../db/seed";
import { getDb } from "../db/state";

beforeEach(() => {
  setLatencyOverride(0);
  scenario.reset();
  resetIdempotency();
  resetMockApi();
});

afterEach(() => {
  scenario.reset();
  setLatencyOverride(null);
});

describe("users.list", () => {
  it("requires an active session (401)", async () => {
    await expect(listUsers()).rejects.toMatchObject({
      code: "UNAUTHORIZED",
      httpStatus: 401,
    });
  });

  it("rejects non-SYSTEM_ADMIN roles (401)", async () => {
    await auth.login({ username: "ops.user", password: "any" });
    await expect(listUsers()).rejects.toMatchObject({
      code: "UNAUTHORIZED",
      httpStatus: 401,
    });
  });

  it("SYSTEM_ADMIN sees all 6 seeded users", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const res = await listUsers({ pageSize: 100 });
    expect(res.total).toBe(6);
    expect(res.items.length).toBe(6);
    // lspName is resolved for LSP-scoped rows.
    const lspRow = res.items.find((u) => u.id === USER_LSP_READ);
    expect(lspRow?.lspName).toBe("Bhawana Demo LSP");
    const adminRow = res.items.find((u) => u.role === "SYSTEM_ADMIN");
    expect(adminRow?.lspName).toBeNull();
  });

  it("role filter narrows rows", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const res = await listUsers({ role: "LSP_UI_READ", pageSize: 100 });
    expect(res.items.length).toBe(1);
    expect(res.items[0]?.role).toBe("LSP_UI_READ");
  });

  it("lspId filter narrows rows to that tenant", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const res = await listUsers({ lspId: LSP_BHAW_DEMO, pageSize: 100 });
    expect(res.items.every((u) => u.lspId === LSP_BHAW_DEMO)).toBe(true);
    expect(res.items.length).toBe(2); // lsp.read + lsp.write
  });

  it("q matches against username or email (case-insensitive)", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const byUsername = await listUsers({ q: "PRODUCT" });
    expect(byUsername.items.length).toBe(1);
    expect(byUsername.items[0]?.username).toBe("product.admin");
    const byEmail = await listUsers({ q: "bhaw-demo" });
    expect(byEmail.items.length).toBe(2);
  });

  it("paginates", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const p0 = await listUsers({ page: 0, pageSize: 5 });
    const p1 = await listUsers({ page: 1, pageSize: 5 });
    expect(p0.items.length).toBe(5);
    expect(p1.items.length).toBe(1);
    const p0Ids = new Set(p0.items.map((i) => i.id));
    for (const r of p1.items) expect(p0Ids.has(r.id)).toBe(false);
  });
});

describe("users.create", () => {
  it("requires SYSTEM_ADMIN (401)", async () => {
    await auth.login({ username: "ops.user", password: "any" });
    await expect(
      createUser({
        username: "new.user",
        email: "new.user@example.com",
        role: "OPS_USER",
        lspId: null,
        idempotencyKey: newIdempotencyKey(),
      }),
    ).rejects.toMatchObject({ code: "UNAUTHORIZED" });
  });

  it("creates an internal user + returns a 12-char temp password", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const res = await createUser({
      username: "audit.new",
      email: "audit.new@bhawana.example",
      role: "OPS_USER",
      lspId: null,
      idempotencyKey: newIdempotencyKey(),
    });
    expect(res.user.username).toBe("audit.new");
    expect(res.user.role).toBe("OPS_USER");
    expect(res.user.mustChangePassword).toBe(true);
    expect(res.user.status).toBe("ACTIVE");
    expect(res.user.lspName).toBeNull();
    expect(res.temporaryPassword).toMatch(/^[A-Za-z0-9]{8}Aa1!$/);

    // Temp password is NEVER persisted on the row.
    const db = getDb();
    const stored = db.users.get(res.user.id);
    expect(stored).toBeTruthy();
    expect(JSON.stringify(stored)).not.toContain(res.temporaryPassword);
  });

  it("creates an LSP-scoped user when role+lspId are consistent", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const res = await createUser({
      username: "lsp.demo.new",
      email: "lsp.demo.new@bhaw-demo.example",
      role: "LSP_UI_WRITE",
      lspId: LSP_BHAW_DEMO,
      idempotencyKey: newIdempotencyKey(),
    });
    expect(res.user.role).toBe("LSP_UI_WRITE");
    expect(res.user.lspId).toBe(LSP_BHAW_DEMO);
    expect(res.user.lspName).toBe("Bhawana Demo LSP");
  });

  it("rejects tenant-scoped role without lspId (400)", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    await expect(
      createUser({
        username: "lsp.bad",
        email: "lsp.bad@example.com",
        role: "LSP_UI_READ",
        lspId: null,
        idempotencyKey: newIdempotencyKey(),
      }),
    ).rejects.toMatchObject({ code: "BAD_REQUEST", httpStatus: 400 });
  });

  it("rejects internal role carrying an lspId (400)", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    await expect(
      createUser({
        username: "ops.bad",
        email: "ops.bad@example.com",
        role: "OPS_USER",
        lspId: LSP_BHAW_DEMO,
        idempotencyKey: newIdempotencyKey(),
      }),
    ).rejects.toMatchObject({ code: "BAD_REQUEST", httpStatus: 400 });
  });

  it("rejects duplicate usernames (409)", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    await expect(
      createUser({
        username: "ops.user", // already seeded
        email: "ops.user.dup@example.com",
        role: "OPS_USER",
        lspId: null,
        idempotencyKey: newIdempotencyKey(),
      }),
    ).rejects.toMatchObject({ code: "CONFLICT", httpStatus: 409 });
  });

  it("BR-5 idempotency replay returns the cached response", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const key = newIdempotencyKey();
    const first = await createUser({
      username: "idem.first",
      email: "idem.first@example.com",
      role: "OPS_USER",
      lspId: null,
      idempotencyKey: key,
    });
    // A second call with the same key MUST replay — even with different body.
    const second = await createUser({
      username: "idem.different",
      email: "idem.different@example.com",
      role: "OPS_USER",
      lspId: null,
      idempotencyKey: key,
    });
    expect(second.user.id).toBe(first.user.id);
    expect(second.user.username).toBe(first.user.username);
    expect(second.temporaryPassword).toBe(first.temporaryPassword);
  });
});

describe("users.update", () => {
  it("requires SYSTEM_ADMIN (401)", async () => {
    await auth.login({ username: "ops.user", password: "any" });
    await expect(
      updateUser(USER_OPS_USER, {
        status: "DISABLED",
        idempotencyKey: newIdempotencyKey(),
      }),
    ).rejects.toMatchObject({ code: "UNAUTHORIZED" });
  });

  it("404s for unknown ids", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    await expect(
      updateUser("aaaaaaaa-9999-4aaa-8aaa-aaaaaaaaaaaa", {
        status: "DISABLED",
        idempotencyKey: newIdempotencyKey(),
      }),
    ).rejects.toMatchObject({ code: "NOT_FOUND", httpStatus: 404 });
  });

  it("flips status ACTIVE → DISABLED", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const res = await updateUser(USER_OPS_USER, {
      status: "DISABLED",
      idempotencyKey: newIdempotencyKey(),
    });
    expect(res.user.status).toBe("DISABLED");
  });

  it("rejects role-lspId mismatch (400)", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    // USER_LSP_READ is LSP_UI_READ + LSP_BHAW_DEMO. Try clearing its lspId.
    await expect(
      updateUser(USER_LSP_READ, {
        lspId: null,
        idempotencyKey: newIdempotencyKey(),
      }),
    ).rejects.toMatchObject({ code: "BAD_REQUEST", httpStatus: 400 });
  });

  it("promoting tenant role to internal role clears lspId in payload", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const res = await updateUser(USER_LSP_READ, {
      role: "OPS_USER",
      lspId: null,
      idempotencyKey: newIdempotencyKey(),
    });
    expect(res.user.role).toBe("OPS_USER");
    expect(res.user.lspId).toBeNull();
    expect(res.user.lspName).toBeNull();
  });
});

describe("users.resetPassword", () => {
  it("requires SYSTEM_ADMIN (401)", async () => {
    await auth.login({ username: "ops.user", password: "any" });
    await expect(
      resetUserPassword(USER_OPS_USER, {
        idempotencyKey: newIdempotencyKey(),
      }),
    ).rejects.toMatchObject({ code: "UNAUTHORIZED" });
  });

  it("returns a fresh temp password and flips mustChangePassword", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const res = await resetUserPassword(USER_OPS_USER, {
      idempotencyKey: newIdempotencyKey(),
    });
    expect(res.user.id).toBe(USER_OPS_USER);
    expect(res.user.mustChangePassword).toBe(true);
    expect(res.temporaryPassword).toMatch(/^[A-Za-z0-9]{8}Aa1!$/);

    // Temp password is NEVER persisted on the row.
    const db = getDb();
    const stored = db.users.get(USER_OPS_USER);
    expect(JSON.stringify(stored)).not.toContain(res.temporaryPassword);
  });

  it("BR-5 idempotency replay returns the same temp password", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const key = newIdempotencyKey();
    const first = await resetUserPassword(USER_OPS_USER, {
      idempotencyKey: key,
    });
    const second = await resetUserPassword(USER_OPS_USER, {
      idempotencyKey: key,
    });
    expect(second.temporaryPassword).toBe(first.temporaryPassword);
  });

  it("404s for unknown users", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    await expect(
      resetUserPassword("aaaaaaaa-9999-4aaa-8aaa-aaaaaaaaaaaa", {
        idempotencyKey: newIdempotencyKey(),
      }),
    ).rejects.toMatchObject({ code: "NOT_FOUND" });
  });
});
