/**
 * api.ts tests — verifies the wrapper's transport contract.
 *
 * NOTE: at the time this test was authored the real handler in
 * `@/mocks/api/home.ts` (agent A) had not landed. Rather than depend on its
 * runtime, we register a route directly against the mock router and seed
 * shaped fixtures — that way the wrapper's typing + Zod parse-on-return is
 * exercised end-to-end against the dispatch contract.
 */
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import {
  registerRoute,
  _resetRoutesForTests,
  _clearIdempotencyCacheForTests,
} from "@/mocks/router";
import { setLatencyOverride } from "@/mocks/latency";
import { scenario } from "@/mocks/scenarios";
import { fetchHomeKpis, HomeKpisSchema } from "./api";
import type { HomeKpis } from "./types";

const INTERNAL_FIXTURE: HomeKpis = {
  kind: "internal",
  data: {
    applicationsAwaitingApproval: 12,
    applicationsInDisbursement: 4,
    mtdDisbursedAmount: 4_750_000,
    overdueLoansCount: 3,
    overdueAmount: 125_000,
    avgApprovalTatHours: 18.5,
    applicationsByStatus: [
      { status: "AWAITING_APPROVAL", count: 12 },
      { status: "UNDER_REPAYMENT", count: 47 },
    ],
    dpdBuckets: [
      { bucket: "B0", count: 41 },
      { bucket: "B1_30", count: 3 },
      { bucket: "B31_60", count: 2 },
      { bucket: "B61_90", count: 1 },
      { bucket: "B90_PLUS", count: 0 },
    ],
    recentApplications: [
      {
        id: "loan-1",
        externalLoanId: "EXT-001",
        borrowerNameMasked: "A•••a Devi",
        lspName: "Acme NBFC",
        productName: "PL-A",
        status: "AWAITING_APPROVAL",
        requestedAmount: 250_000,
        createdAt: "2026-05-10T08:00:00.000Z",
        assignedToName: "Ops User",
      },
    ],
    openAlerts: [
      {
        id: "alert-1",
        severity: "HIGH",
        title: "Webhook delivery failing",
        subjectType: "WEBHOOK_DELIVERY",
        subjectId: "wd-1",
        createdAt: "2026-05-11T07:00:00.000Z",
      },
    ],
  },
};

const LSP_FIXTURE: HomeKpis = {
  kind: "lsp",
  data: {
    myActiveApplications: 7,
    myInDisbursement: 1,
    myMtdDisbursedAmount: 900_000,
    myOverdueLoansCount: 0,
    recentApplications: [],
    openAlerts: [],
  },
};

beforeEach(() => {
  setLatencyOverride(0);
  scenario.reset();
  _clearIdempotencyCacheForTests();
  _resetRoutesForTests();
});

afterEach(() => {
  scenario.reset();
  setLatencyOverride(null);
});

describe("fetchHomeKpis", () => {
  it("returns the internal-shape payload parsed against HomeKpisSchema", async () => {
    registerRoute("GET", "/api/v1/home/kpis", () => INTERNAL_FIXTURE);
    const result = await fetchHomeKpis();
    expect(result.kind).toBe("internal");
    if (result.kind !== "internal") throw new Error("narrow failed");
    expect(result.data.applicationsAwaitingApproval).toBe(12);
    expect(result.data.applicationsByStatus).toHaveLength(2);
    // Parse-trip — proves the schema mirrors the type.
    expect(() => HomeKpisSchema.parse(result)).not.toThrow();
  });

  it("returns the lsp-shape payload parsed against HomeKpisSchema", async () => {
    registerRoute("GET", "/api/v1/home/kpis", () => LSP_FIXTURE);
    const result = await fetchHomeKpis();
    expect(result.kind).toBe("lsp");
    if (result.kind !== "lsp") throw new Error("narrow failed");
    expect(result.data.myActiveApplications).toBe(7);
    expect(() => HomeKpisSchema.parse(result)).not.toThrow();
  });

  it("rejects malformed handler payloads (drift detection)", async () => {
    registerRoute("GET", "/api/v1/home/kpis", () => ({
      kind: "internal",
      data: { wrong: "shape" },
    }));
    await expect(fetchHomeKpis()).rejects.toBeDefined();
  });

  it("surfaces NotFoundError when no route is registered", async () => {
    // No route registered → router throws NotFoundError.
    await expect(fetchHomeKpis()).rejects.toMatchObject({ code: "NOT_FOUND" });
  });
});

describe("HomeKpisSchema", () => {
  it("parses both branches of the discriminated union", () => {
    expect(HomeKpisSchema.parse(INTERNAL_FIXTURE)).toEqual(INTERNAL_FIXTURE);
    expect(HomeKpisSchema.parse(LSP_FIXTURE)).toEqual(LSP_FIXTURE);
  });

  it("rejects an unknown discriminator", () => {
    expect(() =>
      HomeKpisSchema.parse({ kind: "other", data: {} } as unknown as HomeKpis),
    ).toThrow();
  });
});
