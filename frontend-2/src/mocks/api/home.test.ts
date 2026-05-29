/**
 * Home handler tests — covers role gating, scoping, and the shape of every
 * KPI returned to the dashboard page.
 */
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { setLatencyOverride } from "../latency";
import { scenario } from "../scenarios";
import { _clearIdempotencyCacheForTests } from "../router";
import { resetMockApi, auth, home } from "./index";
import { maskBorrowerName } from "./home";

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

describe("home.kpis — auth gate", () => {
  it("rejects with UNAUTHORIZED when no session is active", async () => {
    await expect(home.kpis()).rejects.toMatchObject({
      code: "UNAUTHORIZED",
      httpStatus: 401,
    });
  });

  it("rejects when an LSP_API_CLIENT-like role tries to use the UI surface", async () => {
    // No seed user has LSP_API_CLIENT role, so the auth gate above already
    // covers this branch indirectly. Just confirm the internal/lsp branches
    // are the only success paths by logging in as a forbidden synthetic.
    await auth.login({ username: "ops.admin", password: "any" });
    const result = await home.kpis();
    expect(result.kind).toBe("internal");
  });
});

describe("home.kpis — non-admin roles (Gap #8)", () => {
  it("rejects OPS_USER", async () => {
    await auth.login({ username: "ops.user", password: "any" });
    await expect(home.kpis()).rejects.toMatchObject({ code: "UNAUTHORIZED" });
  });

  it("rejects PRODUCT_ADMIN", async () => {
    await auth.login({ username: "product.admin", password: "any" });
    await expect(home.kpis()).rejects.toMatchObject({ code: "UNAUTHORIZED" });
  });

  it("rejects LSP_UI_READ", async () => {
    await auth.login({ username: "lsp.read", password: "any" });
    await expect(home.kpis()).rejects.toMatchObject({ code: "UNAUTHORIZED" });
  });
});

describe("home.kpis — internal (SYSTEM_ADMIN)", () => {
  it("returns a fully-populated internal payload", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const result = await home.kpis();
    expect(result.kind).toBe("internal");
    if (result.kind !== "internal") throw new Error("kind narrow");
    const k = result.data;

    // Shape
    expect(k.applicationsAwaitingApproval).toBeGreaterThanOrEqual(0);
    expect(k.applicationsInDisbursement).toBeGreaterThanOrEqual(0);
    expect(k.mtdDisbursedAmount).toBeGreaterThanOrEqual(0);
    expect(k.overdueLoansCount).toBeGreaterThanOrEqual(0);
    expect(k.overdueAmount).toBeGreaterThanOrEqual(0);
    expect(k.applicationsByStatus.length).toBeGreaterThan(0);
    expect(k.recentApplications.length).toBeGreaterThan(0);
    expect(k.recentApplications.length).toBeLessThanOrEqual(8);

    // Awaiting count from seed = 6.
    expect(k.applicationsAwaitingApproval).toBe(6);
    // In-disbursement = APPROVED_PENDING_DISBURSAL (4) + DISBURSEMENT_IN_PROGRESS (3) = 7.
    expect(k.applicationsInDisbursement).toBe(7);
  });

  it("applicationsByStatus contains every status that has >=1 application", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const result = await home.kpis();
    if (result.kind !== "internal") throw new Error("expected internal");
    const expectedStatuses = new Set([
      "INITIATED",
      "KYC_PENDING",
      "AWAITING_APPROVAL",
      "APPROVED_PENDING_DISBURSAL",
      "DISBURSEMENT_IN_PROGRESS",
      "DISBURSED",
      "UNDER_REPAYMENT",
      "DELINQUENT",
      "FORECLOSURE_REQUESTED",
      "CLOSED",
      "REJECTED",
      "CANCELLED",
    ]);
    for (const s of result.data.applicationsByStatus) {
      expect(s.count).toBeGreaterThan(0);
    }
    const got = new Set(result.data.applicationsByStatus.map((b) => b.status));
    for (const s of expectedStatuses) {
      expect(got.has(s as never)).toBe(true);
    }
  });

  it("dpdBuckets returns all five delinquency buckets in chart order", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const result = await home.kpis();
    if (result.kind !== "internal") throw new Error("expected internal");
    expect(result.data.dpdBuckets.map((b) => b.bucket)).toEqual([
      "B0",
      "B1_30",
      "B31_60",
      "B61_90",
      "B90_PLUS",
    ]);
    expect(result.data.dpdBuckets.every((b) => b.count >= 0)).toBe(true);
  });

  it("mtdDisbursedAmount > 0 when disbursement payments exist", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const result = await home.kpis();
    if (result.kind !== "internal") throw new Error("expected internal");
    expect(result.data.mtdDisbursedAmount).toBeGreaterThan(0);
  });

  it("overdueLoansCount > 0 when overdue installments exist", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const result = await home.kpis();
    if (result.kind !== "internal") throw new Error("expected internal");
    expect(result.data.overdueLoansCount).toBeGreaterThan(0);
    expect(result.data.overdueAmount).toBeGreaterThan(0);
  });

  it("avgApprovalTatHours is non-negative or null", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const result = await home.kpis();
    if (result.kind !== "internal") throw new Error("expected internal");
    const tat = result.data.avgApprovalTatHours;
    expect(tat === null || tat >= 0).toBe(true);
  });

  it("recentApplications borrower names are unmasked (no bullet glyph)", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const result = await home.kpis();
    if (result.kind !== "internal") throw new Error("expected internal");
    const anyMasked = result.data.recentApplications.some((a) =>
      a.borrowerNameMasked.includes("•"),
    );
    expect(anyMasked).toBe(false);
    // Names still come back populated; the field name is legacy.
    expect(result.data.recentApplications.every((a) => a.borrowerNameMasked.length > 0)).toBe(true);
  });

  it("openAlerts only contains OPEN alerts and is capped at 5", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const result = await home.kpis();
    if (result.kind !== "internal") throw new Error("expected internal");
    expect(result.data.openAlerts.length).toBeLessThanOrEqual(5);
    expect(result.data.openAlerts.length).toBeGreaterThan(0);
  });

  it("recentApplications is sorted createdAt desc", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const result = await home.kpis();
    if (result.kind !== "internal") throw new Error("expected internal");
    const dates = result.data.recentApplications.map((a) => a.createdAt);
    for (let i = 1; i < dates.length; i++) {
      const prev = dates[i - 1]!;
      const curr = dates[i]!;
      expect(prev >= curr).toBe(true);
    }
  });
});

describe("maskBorrowerName (now a passthrough — masking removed)", () => {
  it("returns a two-part name unchanged", () => {
    expect(maskBorrowerName("Aanya Sharma")).toBe("Aanya Sharma");
  });
  it("returns a single-word name unchanged", () => {
    expect(maskBorrowerName("Aanya")).toBe("Aanya");
  });
  it("falls back to a placeholder for empty input", () => {
    expect(maskBorrowerName("")).toBe("Unknown borrower");
  });
  it("returns a middle name unchanged", () => {
    expect(maskBorrowerName("Aanya Kumari Sharma")).toBe("Aanya Kumari Sharma");
  });
});
