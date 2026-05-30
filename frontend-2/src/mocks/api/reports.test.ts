/**
 * Reports handler tests.
 *
 * Covers role gating, MIS summary derivation, MIS preview pagination + range
 * filtering, request CRUD, BR-5 idempotency replay, BAD_REQUEST on inverted
 * date ranges, the QUEUED → PROCESSING → COMPLETED auto-completion ticker,
 * and download-URL gating.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { setLatencyOverride } from "../latency";
import { scenario } from "../scenarios";
import { resetIdempotency } from "../idempotency";
import { getDb } from "../db/state";
import { LSP_BHAW_DEMO } from "../db/seed";
import { resetMockApi, auth } from "./index";
// Side-effect import: registers report routes the first time it runs.
import * as reports from "./reports";
import { tickReportRequests } from "./reports";
import type { ReportRequest } from "@/types";

beforeEach(() => {
  setLatencyOverride(0);
  scenario.reset();
  resetIdempotency();
  resetMockApi();
});

afterEach(() => {
  scenario.reset();
  setLatencyOverride(null);
  vi.useRealTimers();
});

// ─── Auth + role gate ────────────────────────────────────────────────────────

describe("reports — role gate", () => {
  it("misSummary: UNAUTHORIZED when no session", async () => {
    await expect(reports.misSummary()).rejects.toMatchObject({
      code: "UNAUTHORIZED",
      httpStatus: 401,
    });
  });

  it("misSummary: FORBIDDEN for OPS_USER (non-SYSTEM_ADMIN)", async () => {
    await auth.login({ username: "ops.user", password: "any" });
    await expect(reports.misSummary()).rejects.toMatchObject({
      code: "FORBIDDEN",
      httpStatus: 403,
    });
  });

  it("misSummary: FORBIDDEN for LSP_UI_READ", async () => {
    await auth.login({ username: "lsp.read", password: "any" });
    await expect(reports.misSummary()).rejects.toMatchObject({
      code: "FORBIDDEN",
      httpStatus: 403,
    });
  });
});

// ─── Summary ─────────────────────────────────────────────────────────────────

describe("reports.misSummary", () => {
  it("returns aggregate KPIs derived from the seeded portfolio", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const summary = await reports.misSummary();
    // MIS reports the count of disbursed loans (loans with an account).
    // The seeded portfolio has 28 disbursed loans across statuses.
    expect(summary.totalLoanCount).toBe(28);
    expect(summary.activeLoanCount).toBeGreaterThan(0);
    expect(summary.totalDisbursedMtd).toBeGreaterThanOrEqual(0);
    expect(summary.weightedAvgYieldPct).toBeGreaterThanOrEqual(0);
    expect(summary.portfolioAtRisk30Pct).toBeGreaterThanOrEqual(0);
  });

  it("filters by lspId so the count narrows to the tenant", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const scoped = await reports.misSummary({ lspId: LSP_BHAW_DEMO });
    expect(scoped.totalLoanCount).toBe(7); // BHAW_DEMO's slice of the 28 disbursed loans.
  });

  it("rejects an inverted date range with BAD_REQUEST", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    await expect(
      reports.misSummary({ dateFrom: "2026-05-10", dateTo: "2026-04-01" }),
    ).rejects.toMatchObject({ code: "BAD_REQUEST", httpStatus: 400 });
  });
});

// ─── Preview ─────────────────────────────────────────────────────────────────

describe("reports.misPreview", () => {
  it("returns full row payload for every app in scope (default page)", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const res = await reports.misPreview({ pageSize: 100 });
    expect(res.total).toBe(53);
    expect(res.items.length).toBe(53);
    const first = res.items[0]!;
    expect(first.loanId).toBeTruthy();
    expect(first.lspCode).toBeTruthy();
    expect(first.productCode).toBeTruthy();
    // Non-Aadhaar data is visible on the MIS surface.
    expect(first.pan === null || first.pan.includes("•")).toBe(false);
    expect(first.bankAccount === null || first.bankAccount.includes("•")).toBe(false);
  });

  it("paginates the preview cleanly", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const page0 = await reports.misPreview({ page: 0, pageSize: 10 });
    const page1 = await reports.misPreview({ page: 1, pageSize: 10 });
    expect(page0.items.length).toBe(10);
    expect(page1.items.length).toBe(10);
    const ids = new Set(page0.items.map((r) => r.loanId));
    for (const row of page1.items) {
      expect(ids.has(row.loanId)).toBe(false);
    }
  });

  it("filters by lspId", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const res = await reports.misPreview({ lspId: LSP_BHAW_DEMO, pageSize: 100 });
    expect(res.total).toBe(13);
  });
});

// ─── Requests CRUD ───────────────────────────────────────────────────────────

describe("reports.listRequests / createRequest", () => {
  it("creates a QUEUED request, then lists it back newest-first", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const before = await reports.listRequests();
    expect(before.items.length).toBe(0);

    const created = await reports.createRequest({
      type: "PORTFOLIO_MIS",
      lspId: null,
      dateFrom: "2026-05-01",
      dateTo: "2026-05-31",
      notificationEmail: "ops@example.com",
    });
    expect(created.status).toBe("QUEUED");
    expect(created.requestedBy).toBeTruthy();

    const after = await reports.listRequests();
    expect(after.items.length).toBe(1);
    expect(after.items[0]?.id).toBe(created.id);
  });

  it("BR-5: replays under the same idempotencyKey without a second insert", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const key = "test-create-replay";
    const first = await reports.createRequest({ type: "PORTFOLIO_MIS" }, { idempotencyKey: key });
    const second = await reports.createRequest(
      { type: "PORTFOLIO_MIS", lspId: LSP_BHAW_DEMO },
      { idempotencyKey: key },
    );
    expect(second.id).toBe(first.id);
    expect(getDb().reportRequests.size).toBe(1);
  });

  it("rejects inverted date range on create with BAD_REQUEST", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    await expect(
      reports.createRequest({
        type: "PORTFOLIO_MIS",
        dateFrom: "2026-06-01",
        dateTo: "2026-01-01",
      }),
    ).rejects.toMatchObject({ code: "BAD_REQUEST", httpStatus: 400 });
  });
});

// ─── Lifecycle ticker (5s + 10s) ─────────────────────────────────────────────

describe("tickReportRequests", () => {
  function seedQueuedRequest(queuedAt: string): ReportRequest {
    const db = getDb();
    const id = `req-${db.reportRequests.size + 1}`;
    const record: ReportRequest = {
      id,
      type: "PORTFOLIO_MIS",
      status: "QUEUED",
      requestedBy: "user-test",
      lspId: null,
      dateFrom: null,
      dateTo: null,
      notificationEmail: null,
      fileMeta: null,
      errorMessage: null,
      queuedAt,
      completedAt: null,
    };
    db.reportRequests.set(id, record);
    return record;
  }

  it("advances QUEUED → PROCESSING after 5s elapsed", () => {
    const now = new Date("2026-05-18T10:00:00.000Z");
    const queuedAt = new Date(now.getTime() - 5_500).toISOString();
    const req = seedQueuedRequest(queuedAt);
    tickReportRequests(getDb(), now);
    const after = getDb().reportRequests.get(req.id)!;
    expect(after.status).toBe("PROCESSING");
    expect(after.completedAt).toBeNull();
  });

  it("advances PROCESSING → COMPLETED after 10s elapsed", () => {
    const now = new Date("2026-05-18T10:00:00.000Z");
    const queuedAt = new Date(now.getTime() - 11_000).toISOString();
    const req = seedQueuedRequest(queuedAt);
    tickReportRequests(getDb(), now);
    const after = getDb().reportRequests.get(req.id)!;
    expect(after.status).toBe("COMPLETED");
    expect(after.completedAt).not.toBeNull();
    expect(after.fileMeta).not.toBeNull();
    expect(after.fileMeta?.storageKey).toContain("mock-reports/");
  });

  it("leaves a freshly-queued request untouched (< 5s elapsed)", () => {
    const now = new Date("2026-05-18T10:00:00.000Z");
    const queuedAt = new Date(now.getTime() - 1_000).toISOString();
    const req = seedQueuedRequest(queuedAt);
    tickReportRequests(getDb(), now);
    expect(getDb().reportRequests.get(req.id)?.status).toBe("QUEUED");
  });

  it("listRequests invokes the ticker so polling drives state forward", async () => {
    // Only fake `Date` + `setTimeout` so awaited dispatch microtasks still
    // resolve (vitest's default `useFakeTimers` also fakes `queueMicrotask`,
    // which would otherwise stall every `await reports.*()` call).
    // Only fake Date — leave setTimeout real so `dispatch` (via the saveDb
    // debounce) doesn't hang. Time-advancement still drives `tickReportRequests`
    // because it reads `Date.now()`.
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(new Date("2026-05-18T10:00:00.000Z"));
    await auth.login({ username: "ops.admin", password: "any" });
    const created = await reports.createRequest({ type: "PORTFOLIO_MIS" });
    expect(created.status).toBe("QUEUED");

    // Advance 5s → next list should show PROCESSING.
    vi.advanceTimersByTime(5_000);
    const afterFive = await reports.listRequests();
    expect(afterFive.items[0]?.status).toBe("PROCESSING");

    // Advance another 5s (10s total) → COMPLETED.
    vi.advanceTimersByTime(5_000);
    const afterTen = await reports.listRequests();
    expect(afterTen.items[0]?.status).toBe("COMPLETED");
  });
});

// ─── Download ────────────────────────────────────────────────────────────────

describe("reports.downloadRequest", () => {
  it("rejects when the request is still QUEUED", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const created = await reports.createRequest({ type: "PORTFOLIO_MIS" });
    await expect(reports.downloadRequest(created.id)).rejects.toMatchObject({
      code: "BAD_REQUEST",
    });
  });

  it("returns a data: URL once the request has COMPLETED", async () => {
    // Only fake Date — leave setTimeout real so `dispatch` (via the saveDb
    // debounce) doesn't hang. Time-advancement still drives `tickReportRequests`
    // because it reads `Date.now()`.
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(new Date("2026-05-18T10:00:00.000Z"));
    await auth.login({ username: "ops.admin", password: "any" });
    const created = await reports.createRequest({ type: "PORTFOLIO_MIS" });
    vi.advanceTimersByTime(11_000);
    // First touch advances state via tick; download call also runs tick.
    const dl = await reports.downloadRequest(created.id);
    expect(dl.url.startsWith("data:text/csv;base64,")).toBe(true);
  });

  it("404s on an unknown request id", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    await expect(reports.downloadRequest("does-not-exist")).rejects.toMatchObject({
      code: "NOT_FOUND",
      httpStatus: 404,
    });
  });
});
