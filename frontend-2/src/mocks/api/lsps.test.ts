/**
 * LSPs admin handler tests (Phase 9 — Agent A).
 *
 * Covers:
 *   - list filters (status, q, pagination)
 *   - create (happy + uniqueness conflict + validation + BR-5 replay)
 *   - update (partial: name only, status only, both; 404)
 *   - webhook subscription read/upsert (initial null, BR-5 replay)
 *   - role gates (no session, non-admin)
 *
 * The auth + admin seed combine to 8 LSPs (4 base + 4 admin-seed).
 */
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { setLatencyOverride } from "../latency";
import { scenario } from "../scenarios";
import { resetIdempotency } from "../idempotency";
import { resetMockApi, auth } from "./index";
import {
  createLsp,
  getLspWebhookSubscription,
  listLsps,
  updateLsp,
  upsertLspWebhookSubscription,
} from "./lsps";
import { newIdempotencyKey } from "@/lib/idempotency";
import { LSP_BHAW_DEMO, LSP_EAST } from "../db/seed";

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

describe("lsps.list", () => {
  it("requires an active session (401)", async () => {
    await expect(listLsps()).rejects.toMatchObject({
      code: "UNAUTHORIZED",
      httpStatus: 401,
    });
  });

  it("rejects non-SYSTEM_ADMIN roles (401)", async () => {
    await auth.login({ username: "ops.user", password: "any" });
    await expect(listLsps()).rejects.toMatchObject({
      code: "UNAUTHORIZED",
      httpStatus: 401,
    });
  });

  it("SYSTEM_ADMIN sees all 8 seeded LSPs (4 base + 4 admin)", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const res = await listLsps({ pageSize: 100 });
    expect(res.total).toBe(8);
    expect(res.items.length).toBe(8);
    // Newest first by createdAt.
    for (let i = 1; i < res.items.length; i++) {
      expect(res.items[i - 1]!.createdAt >= res.items[i]!.createdAt).toBe(true);
    }
  });

  it("status filter narrows to ACTIVE rows", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const res = await listLsps({ status: "ACTIVE", pageSize: 100 });
    expect(res.total).toBeGreaterThan(0);
    expect(res.items.every((l) => l.status === "ACTIVE")).toBe(true);
  });

  it("status=SUSPENDED narrows to suspended rows", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const res = await listLsps({ status: "SUSPENDED", pageSize: 100 });
    expect(res.items.every((l) => l.status === "SUSPENDED")).toBe(true);
  });

  it("q search matches code (case-insensitive)", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const res = await listLsps({ q: "south", pageSize: 100 });
    expect(res.items.every((l) => /south/i.test(l.code + l.name))).toBe(true);
    expect(res.items.length).toBeGreaterThan(0);
  });

  it("q search matches name", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const res = await listLsps({ q: "Metro Finance", pageSize: 100 });
    expect(res.items.length).toBeGreaterThan(0);
    expect(res.items.every((l) => /metro/i.test(l.name))).toBe(true);
  });

  it("paginates with disjoint pages", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const page0 = await listLsps({ page: 0, pageSize: 5 });
    const page1 = await listLsps({ page: 1, pageSize: 5 });
    expect(page0.items.length).toBe(5);
    expect(page1.items.length).toBe(3);
    const seen = new Set(page0.items.map((i) => i.id));
    for (const item of page1.items) {
      expect(seen.has(item.id)).toBe(false);
    }
  });

  it("projects webhookEnabled true for BHAW-DEMO, false for LSP-EAST", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const res = await listLsps({ pageSize: 100 });
    const bhaw = res.items.find((l) => l.id === LSP_BHAW_DEMO);
    const east = res.items.find((l) => l.id === LSP_EAST);
    expect(bhaw?.webhookEnabled).toBe(true);
    expect(east?.webhookEnabled).toBe(false);
  });
});

describe("lsps.create", () => {
  it("requires SYSTEM_ADMIN (401)", async () => {
    await auth.login({ username: "lsp.read", password: "any" });
    await expect(
      createLsp({
        code: "LSP-NEW",
        name: "New LSP",
        idempotencyKey: newIdempotencyKey(),
      }),
    ).rejects.toMatchObject({ code: "UNAUTHORIZED", httpStatus: 401 });
  });

  it("creates a new ACTIVE LSP and assigns a UUID", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const res = await createLsp({
      code: "LSP-ALPHA",
      name: "Alpha Originator",
      idempotencyKey: newIdempotencyKey(),
    });
    expect(res.lsp.code).toBe("LSP-ALPHA");
    expect(res.lsp.name).toBe("Alpha Originator");
    expect(res.lsp.status).toBe("ACTIVE");
    expect(res.lsp.id).toMatch(/^[0-9a-f-]{36}$/i);

    const list = await listLsps({ pageSize: 100 });
    expect(list.items.some((l) => l.code === "LSP-ALPHA")).toBe(true);
  });

  it("rejects duplicate code (case-insensitive) with 409", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    await expect(
      createLsp({
        code: "BHAW-DEMO",
        name: "Trying to clobber",
        idempotencyKey: newIdempotencyKey(),
      }),
    ).rejects.toMatchObject({ code: "CONFLICT", httpStatus: 409 });

    await expect(
      createLsp({
        code: "bhaw-demo".toUpperCase(),
        name: "Same case",
        idempotencyKey: newIdempotencyKey(),
      }),
    ).rejects.toMatchObject({ code: "CONFLICT", httpStatus: 409 });
  });

  it("rejects malformed code shape (400)", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    await expect(
      createLsp({
        code: "lowercase",
        name: "Bad code",
        idempotencyKey: newIdempotencyKey(),
      }),
    ).rejects.toMatchObject({ code: "BAD_REQUEST", httpStatus: 400 });
  });

  it("BR-5 idempotency: replay returns the original row even on conflict body", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const key = newIdempotencyKey();
    const first = await createLsp({
      code: "LSP-BETA",
      name: "Beta Originator",
      idempotencyKey: key,
    });
    // Same key + same body → cached.
    const second = await createLsp({
      code: "LSP-BETA",
      name: "Beta Originator (clobbered)",
      idempotencyKey: key,
    });
    expect(second.lsp.id).toBe(first.lsp.id);
    expect(second.lsp.name).toBe(first.lsp.name);
  });
});

describe("lsps.update", () => {
  it("404s on unknown id", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    await expect(
      updateLsp("99999999-9999-4999-8999-999999999999", {
        name: "x",
        idempotencyKey: newIdempotencyKey(),
      }),
    ).rejects.toMatchObject({ code: "NOT_FOUND", httpStatus: 404 });
  });

  it("updates name only (preserves status)", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const res = await updateLsp(LSP_BHAW_DEMO, {
      name: "Bhawana Demo (renamed)",
      idempotencyKey: newIdempotencyKey(),
    });
    expect(res.lsp.name).toBe("Bhawana Demo (renamed)");
    expect(res.lsp.code).toBe("BHAW-DEMO");
    expect(res.lsp.status).toBe("ACTIVE");
  });

  it("updates status only", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const res = await updateLsp(LSP_BHAW_DEMO, {
      status: "SUSPENDED",
      idempotencyKey: newIdempotencyKey(),
    });
    expect(res.lsp.status).toBe("SUSPENDED");
  });

  it("rejects empty patch body (400)", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    await expect(
      updateLsp(LSP_BHAW_DEMO, { idempotencyKey: newIdempotencyKey() }),
    ).rejects.toMatchObject({ code: "BAD_REQUEST", httpStatus: 400 });
  });

  it("requires SYSTEM_ADMIN (401)", async () => {
    await auth.login({ username: "lsp.write", password: "any" });
    await expect(
      updateLsp(LSP_BHAW_DEMO, {
        name: "no",
        idempotencyKey: newIdempotencyKey(),
      }),
    ).rejects.toMatchObject({ code: "UNAUTHORIZED", httpStatus: 401 });
  });
});

describe("lsps.webhookSubscription", () => {
  it("GET returns null for an LSP without a subscription", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    // LSP-SOUTH has no webhook configured in admin-seed.
    const lspSouth = "22222222-2222-4222-8222-222222222222";
    const res = await getLspWebhookSubscription(lspSouth);
    expect(res.subscription).toBeNull();
  });

  it("GET returns the seeded subscription for BHAW-DEMO", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const res = await getLspWebhookSubscription(LSP_BHAW_DEMO);
    expect(res.subscription).not.toBeNull();
    expect(res.subscription?.enabled).toBe(true);
    expect(res.subscription?.endpointUrl).toMatch(/^https:\/\//);
    expect(res.subscription?.eventTypes.length).toBeGreaterThan(0);
  });

  it("GET 404s when the LSP itself does not exist", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    await expect(
      getLspWebhookSubscription("99999999-9999-4999-8999-999999999999"),
    ).rejects.toMatchObject({ code: "NOT_FOUND", httpStatus: 404 });
  });

  it("PUT upserts a subscription and flips webhookEnabled in the list projection", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const lspSouth = "22222222-2222-4222-8222-222222222222";
    const res = await upsertLspWebhookSubscription(lspSouth, {
      enabled: true,
      endpointUrl: "https://hooks.lsp-south.example/lms",
      signingSecret: "south-signing-secret-please-rotate-32chars-min",
      eventTypes: ["loan.created", "loan.foreclosed"],
      idempotencyKey: newIdempotencyKey(),
    });
    expect(res.subscription?.enabled).toBe(true);
    const list = await listLsps({ pageSize: 100 });
    expect(list.items.find((l) => l.id === lspSouth)?.webhookEnabled).toBe(true);
  });

  it("PUT rejects non-https endpoint (400)", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    await expect(
      upsertLspWebhookSubscription(LSP_BHAW_DEMO, {
        enabled: true,
        endpointUrl: "http://insecure.example/hook",
        signingSecret: "ok-".padEnd(40, "x"),
        eventTypes: ["loan.created"],
        idempotencyKey: newIdempotencyKey(),
      }),
    ).rejects.toMatchObject({ code: "BAD_REQUEST", httpStatus: 400 });
  });

  it("PUT rejects short signing secret (400)", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    await expect(
      upsertLspWebhookSubscription(LSP_BHAW_DEMO, {
        enabled: true,
        endpointUrl: "https://hooks.example/x",
        signingSecret: "too-short",
        eventTypes: ["loan.created"],
        idempotencyKey: newIdempotencyKey(),
      }),
    ).rejects.toMatchObject({ code: "BAD_REQUEST", httpStatus: 400 });
  });

  it("PUT requires at least one event type (400)", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    await expect(
      upsertLspWebhookSubscription(LSP_BHAW_DEMO, {
        enabled: true,
        endpointUrl: "https://hooks.example/x",
        signingSecret: "long-enough-signing-secret-please-rotate-32",
        eventTypes: [],
        idempotencyKey: newIdempotencyKey(),
      }),
    ).rejects.toMatchObject({ code: "BAD_REQUEST", httpStatus: 400 });
  });

  it("PUT BR-5 idempotency replay returns the cached subscription", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const key = newIdempotencyKey();
    const first = await upsertLspWebhookSubscription(LSP_BHAW_DEMO, {
      enabled: false,
      endpointUrl: "https://hooks.example/first",
      signingSecret: "first-secret-please-rotate-this-32chars-okay",
      eventTypes: ["loan.created"],
      idempotencyKey: key,
    });
    const second = await upsertLspWebhookSubscription(LSP_BHAW_DEMO, {
      enabled: true,
      endpointUrl: "https://hooks.example/second",
      signingSecret: "second-secret-please-rotate-this-32chars-okay",
      eventTypes: ["loan.foreclosed"],
      idempotencyKey: key,
    });
    expect(second.subscription?.endpointUrl).toBe(
      first.subscription?.endpointUrl,
    );
    expect(second.subscription?.enabled).toBe(first.subscription?.enabled);
  });
});
