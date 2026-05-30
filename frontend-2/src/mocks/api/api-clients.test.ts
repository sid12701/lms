/**
 * API clients handler tests (Phase 9 — Agent D).
 *
 * Asserts the four routes registered by `api-clients.ts` against the real
 * mock router + seed. Mirrors the `alerts.test.ts` setup: each test reseeds
 * via `resetMockApi()` so the deterministic api-client seed (6 rows) is
 * available to address by counter.
 */
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { setLatencyOverride } from "../latency";
import { scenario } from "../scenarios";
import { resetIdempotency } from "../idempotency";
import { resetMockApi, auth } from "./index";
import {
  _resetApiClientRotationMetaForTests,
  createApiClient,
  getApiClientLastRotatedAt,
  listApiClients,
  rotateApiClientSecret,
  updateApiClient,
} from "./api-clients";
import { newIdempotencyKey } from "@/lib/idempotency";
import { apiClientId, seedApiClientFixtures } from "../db/api-clients-seed";
import { LSP_BHAW_DEMO, LSP_SOUTH } from "../db/seed";
import { getDb } from "../db/state";

beforeEach(() => {
  setLatencyOverride(0);
  scenario.reset();
  resetIdempotency();
  _resetApiClientRotationMetaForTests();
  resetMockApi();
  // The orchestrator wires this into `bootstrapMockApi`; tests seed
  // directly so the suite is self-contained.
  seedApiClientFixtures(getDb());
});

afterEach(() => {
  scenario.reset();
  setLatencyOverride(null);
});

describe("api-clients.list", () => {
  it("requires an active session (401)", async () => {
    await expect(listApiClients()).rejects.toMatchObject({
      code: "UNAUTHORIZED",
      httpStatus: 401,
    });
  });

  it("rejects non-SYSTEM_ADMIN roles (401)", async () => {
    await auth.login({ username: "ops.user", password: "any" });
    await expect(listApiClients()).rejects.toMatchObject({
      code: "UNAUTHORIZED",
      httpStatus: 401,
    });
  });

  it("rejects LSP roles (401)", async () => {
    await auth.login({ username: "lsp.read", password: "any" });
    await expect(listApiClients()).rejects.toMatchObject({
      code: "UNAUTHORIZED",
      httpStatus: 401,
    });
  });

  it("SYSTEM_ADMIN sees all 6 seeded rows", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const res = await listApiClients({ pageSize: 100 });
    expect(res.total).toBe(6);
    expect(res.items).toHaveLength(6);
  });

  it("status=ACTIVE narrows to the 4 active rows", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const res = await listApiClients({ status: "ACTIVE", pageSize: 100 });
    expect(res.items.every((c) => c.status === "ACTIVE")).toBe(true);
    expect(res.total).toBe(4);
  });

  it("lspId narrows to a single LSP", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const res = await listApiClients({ lspId: LSP_SOUTH, pageSize: 100 });
    expect(res.items.every((c) => c.lspId === LSP_SOUTH)).toBe(true);
    expect(res.total).toBe(2);
  });

  it("q matches against name OR clientId", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const byName = await listApiClients({ q: "Northern", pageSize: 100 });
    expect(byName.total).toBeGreaterThan(0);
    expect(byName.items.every((c) => /Northern/i.test(c.name))).toBe(true);

    const byClientId = await listApiClients({
      q: "ak_live_1a2b",
      pageSize: 100,
    });
    expect(byClientId.total).toBe(1);
    expect(byClientId.items[0]!.clientId.startsWith("ak_live_1a2b")).toBe(true);
  });

  it("includes lspName + ipAllowlistCount projection", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const res = await listApiClients({ pageSize: 100 });
    const row = res.items.find((c) => c.id === apiClientId(5));
    expect(row).toBeDefined();
    expect(row!.lspName).toMatch(/Northern/);
    expect(row!.ipAllowlistCount).toBe(3);
  });

  it("paginates via page + pageSize", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const p0 = await listApiClients({ page: 0, pageSize: 5 });
    const p1 = await listApiClients({ page: 1, pageSize: 5 });
    expect(p0.items).toHaveLength(5);
    expect(p1.items).toHaveLength(1);
    const ids = new Set(p0.items.map((c) => c.id));
    for (const c of p1.items) {
      expect(ids.has(c.id)).toBe(false);
    }
  });
});

describe("api-clients.create", () => {
  it("requires SYSTEM_ADMIN (401 for other roles)", async () => {
    await auth.login({ username: "ops.user", password: "any" });
    await expect(
      createApiClient({
        name: "Nope",
        lspId: LSP_BHAW_DEMO,
        ipAllowList: [],
        idempotencyKey: newIdempotencyKey(),
      }),
    ).rejects.toMatchObject({ code: "UNAUTHORIZED", httpStatus: 401 });
  });

  it("creates a fresh client and returns the cleartext secret once", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const res = await createApiClient({
      name: "New Integration",
      lspId: LSP_BHAW_DEMO,
      ipAllowList: ["10.0.0.0/8"],
      idempotencyKey: newIdempotencyKey(),
    });
    expect(res.client.id).toBeTruthy();
    expect(res.client.name).toBe("New Integration");
    expect(res.client.lspId).toBe(LSP_BHAW_DEMO);
    expect(res.client.status).toBe("ACTIVE");
    expect(res.client.clientId.startsWith("ak_live_")).toBe(true);
    expect(res.client.ipAllowlistCount).toBe(1);
    expect(typeof res.clientSecret).toBe("string");
    expect(res.clientSecret.length).toBeGreaterThanOrEqual(32);

    // Subsequent list returns the new row but never the cleartext secret.
    const list = await listApiClients({ pageSize: 100 });
    const row = list.items.find((c) => c.id === res.client.id);
    expect(row).toBeDefined();
    expect((row as unknown as Record<string, unknown>)["clientSecret"]).toBeUndefined();
  });

  it("rejects an invalid CIDR entry in ipAllowList (400)", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    await expect(
      createApiClient({
        name: "Bad IPs",
        lspId: LSP_BHAW_DEMO,
        ipAllowList: ["10.0.0.0/8", "not-an-ip"],
        idempotencyKey: newIdempotencyKey(),
      }),
    ).rejects.toMatchObject({
      code: "BAD_REQUEST",
      httpStatus: 400,
    });
  });

  it("accepts a wide variety of valid IPv4 / CIDR entries", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const res = await createApiClient({
      name: "Multi-range",
      lspId: LSP_BHAW_DEMO,
      ipAllowList: ["10.0.0.0/8", "192.168.1.0/24", "203.0.113.7", "198.51.100.0/22"],
      idempotencyKey: newIdempotencyKey(),
    });
    expect(res.client.ipAllowList).toHaveLength(4);
    expect(res.client.ipAllowlistCount).toBe(4);
  });

  it("rejects an unknown lspId (400)", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    await expect(
      createApiClient({
        name: "Bad LSP",
        lspId: "99999999-9999-4999-8999-999999999999",
        ipAllowList: [],
        idempotencyKey: newIdempotencyKey(),
      }),
    ).rejects.toMatchObject({ code: "BAD_REQUEST", httpStatus: 400 });
  });

  it("BR-5 idempotency replay returns the cached create response", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const key = newIdempotencyKey();
    const first = await createApiClient({
      name: "Replay Test",
      lspId: LSP_BHAW_DEMO,
      ipAllowList: [],
      idempotencyKey: key,
    });
    const second = await createApiClient({
      name: "Different Name",
      lspId: LSP_BHAW_DEMO,
      ipAllowList: [],
      idempotencyKey: key,
    });
    // Cache hit — second call returns the first's payload regardless of body.
    expect(second.client.id).toBe(first.client.id);
    expect(second.clientSecret).toBe(first.clientSecret);
  });
});

describe("api-clients.update", () => {
  it("requires SYSTEM_ADMIN (401 for other roles)", async () => {
    await auth.login({ username: "lsp.write", password: "any" });
    await expect(
      updateApiClient(apiClientId(1), {
        name: "Hacked",
        idempotencyKey: newIdempotencyKey(),
      }),
    ).rejects.toMatchObject({ code: "UNAUTHORIZED", httpStatus: 401 });
  });

  it("updates name + status + ipAllowList on an existing row", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const res = await updateApiClient(apiClientId(1), {
      name: "Renamed Origination",
      status: "DISABLED",
      ipAllowList: ["10.0.0.0/8"],
      idempotencyKey: newIdempotencyKey(),
    });
    expect(res.client.name).toBe("Renamed Origination");
    expect(res.client.status).toBe("DISABLED");
    expect(res.client.ipAllowList).toEqual(["10.0.0.0/8"]);
    expect(res.client.ipAllowlistCount).toBe(1);
  });

  it("404s when the client does not exist", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    await expect(
      updateApiClient("05000000-9999-4000-8000-000000000000", {
        name: "Ghost",
        idempotencyKey: newIdempotencyKey(),
      }),
    ).rejects.toMatchObject({ code: "NOT_FOUND", httpStatus: 404 });
  });

  it("rejects an invalid CIDR in the updated allow-list (400)", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    await expect(
      updateApiClient(apiClientId(1), {
        ipAllowList: ["10.0.0.0/8", "999.0.0.0/8"],
        idempotencyKey: newIdempotencyKey(),
      }),
    ).rejects.toMatchObject({ code: "BAD_REQUEST", httpStatus: 400 });
  });
});

describe("api-clients.rotate", () => {
  it("requires SYSTEM_ADMIN (401 for other roles)", async () => {
    await auth.login({ username: "ops.user", password: "any" });
    await expect(
      rotateApiClientSecret(apiClientId(1), {
        reason: "leak",
        idempotencyKey: newIdempotencyKey(),
      }),
    ).rejects.toMatchObject({ code: "UNAUTHORIZED", httpStatus: 401 });
  });

  it("404s when the client does not exist", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    await expect(
      rotateApiClientSecret("05000000-9999-4000-8000-000000000000", {
        reason: "leak",
        idempotencyKey: newIdempotencyKey(),
      }),
    ).rejects.toMatchObject({ code: "NOT_FOUND", httpStatus: 404 });
  });

  it("requires a non-empty reason (400)", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    await expect(
      rotateApiClientSecret(apiClientId(1), {
        reason: "",
        idempotencyKey: newIdempotencyKey(),
      }),
    ).rejects.toMatchObject({ code: "BAD_REQUEST", httpStatus: 400 });
  });

  it("returns a fresh cleartext secret and records lastRotatedAt", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    expect(getApiClientLastRotatedAt(apiClientId(1))).toBeNull();
    const res = await rotateApiClientSecret(apiClientId(1), {
      reason: "Suspected leak",
      idempotencyKey: newIdempotencyKey(),
    });
    expect(typeof res.clientSecret).toBe("string");
    expect(res.clientSecret.length).toBeGreaterThanOrEqual(32);
    const stamped = getApiClientLastRotatedAt(apiClientId(1));
    expect(stamped).not.toBeNull();
    expect(new Date(stamped!).getTime()).not.toBeNaN();
  });

  it("each rotate call mints a NEW cleartext secret", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const first = await rotateApiClientSecret(apiClientId(2), {
      reason: "First",
      idempotencyKey: newIdempotencyKey(),
    });
    const second = await rotateApiClientSecret(apiClientId(2), {
      reason: "Second",
      idempotencyKey: newIdempotencyKey(),
    });
    expect(first.clientSecret).not.toBe(second.clientSecret);
  });

  it("BR-5 idempotency replay returns the same cleartext secret", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const key = newIdempotencyKey();
    const first = await rotateApiClientSecret(apiClientId(3), {
      reason: "Same key",
      idempotencyKey: key,
    });
    const second = await rotateApiClientSecret(apiClientId(3), {
      reason: "Same key again",
      idempotencyKey: key,
    });
    expect(second.clientSecret).toBe(first.clientSecret);
  });
});
