/**
 * api.ts tests — verifies the wrapper's transport contract.
 *
 * NOTE: at the time this test was authored the real handler in
 * `@/mocks/api/home.ts` (agent A) had not landed. Rather than depend on its
 * runtime, we register a route directly against the mock router and seed
 * shaped fixtures — that way the wrapper's typing + Zod parse-on-return is
 * exercised end-to-end against the dispatch contract.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  registerRoute,
  _resetRoutesForTests,
  _clearIdempotencyCacheForTests,
} from "@/mocks/router";
import { setLatencyOverride } from "@/mocks/latency";
import { scenario } from "@/mocks/scenarios";
import { clearStoredSession, saveStoredSession } from "@/lib/api/session-storage";
import { saveInternalSession } from "@/test/internal-session";
import { fetchHomeKpis, HomeKpisSchema } from "./api";
import type { HomeKpis } from "./types";

const BACKEND_OVERVIEW_FIXTURE = {
  totalDisbursedAmount: 4_750_000,
  totalOutstandingAmount: 0,
  dpd90PlusAmount: 125_000,
  dpd90PlusLoanCount: 3,
  applicationsAwaitingApproval: 12,
  applicationsInDisbursement: 4,
  avgApprovalTatHours: 18.5,
  applicationsByStatus: [
    { status: "AWAITING_APPROVAL", count: 12 },
    { status: "UNDER_REPAYMENT", count: 47 },
  ],
  dpdBuckets: [
    { bucket: "CURRENT", count: 41 },
    { bucket: "DPD_1_30", count: 3 },
  ],
  openAlerts: 1,
  openAlertSummaries: [
    {
      id: "alert-1",
      severity: "HIGH",
      title: "Webhook delivery failing",
      subjectType: "WEBHOOK_DELIVERY",
      subjectId: "wd-1",
      createdAt: "2026-05-11T07:00:00.000Z",
    },
  ],
  priorityAccounts: [
    {
      applicationId: "loan-1",
      externalLoanId: "EXT-001",
      customerName: "Aanya Devi",
      lspCode: "Acme NBFC",
      principalAmount: 250_000,
      overdueAmount: 0,
      daysPastDue: 0,
      loanStatusDisplay: "AWAITING_APPROVAL",
    },
  ],
};

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
  clearStoredSession();
  saveInternalSession();
  setLatencyOverride(0);
  scenario.reset();
  _clearIdempotencyCacheForTests();
  _resetRoutesForTests();
});

afterEach(() => {
  clearStoredSession();
  vi.unstubAllGlobals();
  scenario.reset();
  setLatencyOverride(null);
});

describe("fetchHomeKpis", () => {
  it("returns the internal-shape payload from the live backend overview", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify(BACKEND_OVERVIEW_FIXTURE), {
          status: 200,
          headers: { "content-type": "application/json" },
        }),
      ),
    );
    const result = await fetchHomeKpis();
    expect(result.kind).toBe("internal");
    if (result.kind !== "internal") throw new Error("narrow failed");
    expect(result.data.applicationsAwaitingApproval).toBe(12);
    expect(result.data.applicationsByStatus).toHaveLength(2);
    expect(() => HomeKpisSchema.parse(result)).not.toThrow();
  });

  it("rejects non-system-admin sessions before dispatching dashboard requests", async () => {
    clearStoredSession();
    saveStoredSession({
      user: {
        id: "33333333-3333-4333-8333-333333333333",
        username: "ops.user",
        role: "OPS_USER",
        lspId: null,
        mustChangePassword: false,
      },
      accessToken: "ops-token",
      expiresAt: "2026-05-26T10:00:00.000Z",
    });
    registerRoute("GET", "/api/v1/home/kpis", () => LSP_FIXTURE);
    await expect(fetchHomeKpis()).rejects.toMatchObject({
      code: "FORBIDDEN",
      status: 403,
    });
  });

  it("propagates backend 404 instead of falling through to mock (#78)", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ code: "NOT_FOUND", message: "not found" }), {
          status: 404,
          headers: { "content-type": "application/json" },
        }),
      ),
    );
    await expect(fetchHomeKpis()).rejects.toMatchObject({
      status: 404,
      code: "NOT_FOUND",
    });
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
