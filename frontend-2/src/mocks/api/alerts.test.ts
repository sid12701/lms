/**
 * Alerts handler tests.
 *
 * Mirrors `loan-applications.test.ts` setup: reseed via `resetMockApi()` so
 * the deterministic dashboard seed (12 alerts) is available to address by
 * index. Imports the alerts module directly so its self-registration runs.
 */
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { setLatencyOverride } from "../latency";
import { scenario } from "../scenarios";
import { resetIdempotency } from "../idempotency";
import { resetMockApi, auth } from "./index";
import { listAlerts, acknowledgeAlert } from "./alerts";
import { newIdempotencyKey } from "@/lib/idempotency";

function pad4(n: number): string {
  return n.toString(16).padStart(4, "0");
}
// Matches `alertId` in dashboard-seed.ts (`01000000-${pad4(i)}-4000-8000-000000000000`).
function alertId(i: number): string {
  return `01000000-${pad4(i)}-4000-8000-000000000000`;
}

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

describe("alerts.list", () => {
  it("requires an active session (401)", async () => {
    await expect(listAlerts()).rejects.toMatchObject({
      code: "UNAUTHORIZED",
      httpStatus: 401,
    });
  });

  it("rejects roles outside SYSTEM_ADMIN / OPS_USER (401)", async () => {
    await auth.login({ username: "lsp.read", password: "any" });
    await expect(listAlerts()).rejects.toMatchObject({
      code: "UNAUTHORIZED",
      httpStatus: 401,
    });
  });

  it("SYSTEM_ADMIN sees all 12 seeded alerts (newest first)", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const res = await listAlerts({ pageSize: 100 });
    expect(res.total).toBe(12);
    expect(res.items.length).toBe(12);
    // Sorted descending by createdAt — alert #1 is newest (daysAgo(1)).
    for (let i = 1; i < res.items.length; i++) {
      expect(res.items[i - 1]!.createdAt >= res.items[i]!.createdAt).toBe(true);
    }
  });

  it("status filter narrows to OPEN rows (8 of 12)", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const res = await listAlerts({ status: "OPEN", pageSize: 100 });
    expect(res.total).toBe(8);
    expect(res.items.every((a) => a.status === "OPEN")).toBe(true);
  });

  it("status=ACKNOWLEDGED returns the 4 acked rows", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const res = await listAlerts({ status: "ACKNOWLEDGED", pageSize: 100 });
    expect(res.total).toBe(4);
    expect(res.items.every((a) => a.status === "ACKNOWLEDGED")).toBe(true);
  });

  it("severity multi-filter narrows correctly", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const res = await listAlerts({
      severity: ["CRITICAL", "HIGH"],
      pageSize: 100,
    });
    expect(res.total).toBeGreaterThan(0);
    expect(
      res.items.every((a) => a.severity === "CRITICAL" || a.severity === "HIGH"),
    ).toBe(true);
  });

  it("subjectType narrows to LOAN_APPLICATION rows", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const res = await listAlerts({
      subjectType: "LOAN_APPLICATION",
      pageSize: 100,
    });
    expect(res.items.every((a) => a.subjectType === "LOAN_APPLICATION")).toBe(
      true,
    );
  });

  it("q search matches against title", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const res = await listAlerts({ q: "Alert 1", pageSize: 100 });
    expect(res.total).toBeGreaterThan(0);
    expect(res.items.every((a) => /alert 1/i.test(a.title))).toBe(true);
  });

  it("paginates: page 0/pageSize 5 returns first 5 newest", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const page0 = await listAlerts({ page: 0, pageSize: 5 });
    const page1 = await listAlerts({ page: 1, pageSize: 5 });
    expect(page0.items.length).toBe(5);
    expect(page1.items.length).toBe(5);
    const page0Ids = new Set(page0.items.map((i) => i.id));
    for (const item of page1.items) {
      expect(page0Ids.has(item.id)).toBe(false);
    }
  });

  it("resolves acknowledgedByName for acked rows", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const res = await listAlerts({ status: "ACKNOWLEDGED", pageSize: 100 });
    expect(res.items.length).toBeGreaterThan(0);
    expect(res.items.every((a) => typeof a.acknowledgedByName === "string")).toBe(
      true,
    );
  });
});

describe("alerts.acknowledge", () => {
  it("requires an active session (401)", async () => {
    await expect(
      acknowledgeAlert(alertId(1), {
        note: null,
        idempotencyKey: newIdempotencyKey(),
      }),
    ).rejects.toMatchObject({ code: "UNAUTHORIZED", httpStatus: 401 });
  });

  it("flips OPEN → ACKNOWLEDGED, stamps actor + note", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const id = alertId(1);
    const res = await acknowledgeAlert(id, {
      note: "investigating",
      idempotencyKey: newIdempotencyKey(),
    });
    expect(res.alert.id).toBe(id);
    expect(res.alert.status).toBe("ACKNOWLEDGED");
    expect(res.alert.acknowledgmentNote).toBe("investigating");
    expect(res.alert.acknowledgedBy).toBeTruthy();
    expect(res.alert.acknowledgedAt).toBeTruthy();
    expect(res.alert.acknowledgedByName).toBe("ops.admin");
  });

  it("404s when alert does not exist", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    await expect(
      acknowledgeAlert("01000000-9999-4000-8000-000000000000", {
        note: null,
        idempotencyKey: newIdempotencyKey(),
      }),
    ).rejects.toMatchObject({ code: "NOT_FOUND", httpStatus: 404 });
  });

  it("BR-5 idempotency replay returns the cached response", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const id = alertId(2);
    const key = newIdempotencyKey();
    const first = await acknowledgeAlert(id, { note: "first", idempotencyKey: key });
    // A second call with the same key MUST replay the cached row even if the
    // input note differs.
    const second = await acknowledgeAlert(id, {
      note: "second",
      idempotencyKey: key,
    });
    expect(second.alert.acknowledgmentNote).toBe(first.alert.acknowledgmentNote);
    expect(second.alert.acknowledgedAt).toBe(first.alert.acknowledgedAt);
  });

  it("already-ACKNOWLEDGED alert returns the existing row unchanged", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    // Alerts #9-#12 are seeded ACKNOWLEDGED.
    const id = alertId(10);
    const res = await acknowledgeAlert(id, {
      note: "noop",
      idempotencyKey: newIdempotencyKey(),
    });
    expect(res.alert.id).toBe(id);
    expect(res.alert.status).toBe("ACKNOWLEDGED");
    // The seed note was "ack" — our "noop" should not overwrite it.
    expect(res.alert.acknowledgmentNote).toBe("ack");
  });

  it("rejects an empty idempotency key (400)", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    await expect(
      acknowledgeAlert(alertId(3), {
        note: null,
        idempotencyKey: "",
      }),
    ).rejects.toMatchObject({ code: "BAD_REQUEST", httpStatus: 400 });
  });

  it("rejects roles outside SYSTEM_ADMIN / OPS_USER (401)", async () => {
    await auth.login({ username: "lsp.write", password: "any" });
    await expect(
      acknowledgeAlert(alertId(4), {
        note: null,
        idempotencyKey: newIdempotencyKey(),
      }),
    ).rejects.toMatchObject({ code: "UNAUTHORIZED", httpStatus: 401 });
  });
});
