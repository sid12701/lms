/**
 * api.ts tests — verifies the loan-applications wrapper's transport contract.
 *
 * Agent A owns the real `@/mocks/api/loan-applications.ts` handler. Rather
 * than depend on its runtime (which may not have landed yet), we register
 * routes directly against the mock router and assert the wrapper's
 * dispatch + Zod parse-on-return behaviour end-to-end.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  registerRoute,
  _resetRoutesForTests,
  _clearIdempotencyCacheForTests,
  type MockRequest,
} from "@/mocks/router";
import { setLatencyOverride } from "@/mocks/latency";
import { scenario } from "@/mocks/scenarios";
import {
  backendQueryFromFilters,
  buildLoanApplicationsQuery,
  fetchLoanApplications,
  LoanApplicationListResponseSchema,
} from "./api";
import type { LoanApplicationListItem, LoanApplicationListResponse } from "./types";

const ROW: LoanApplicationListItem = {
  id: "11111111-1111-4111-8111-111111111111",
  externalLoanId: "EXT-001",
  accountNumber: "LMS-LN-111111111111",
  borrowerId: "22222222-2222-4222-8222-222222222222",
  borrowerNameMasked: "A•••a Devi",
  lspId: "33333333-3333-4333-8333-333333333333",
  lspName: "Acme NBFC",
  productId: "44444444-4444-4444-8444-444444444444",
  productName: "Personal Loan A",
  requestedAmount: 250_000,
  tenureMonths: 24,
  status: "AWAITING_APPROVAL",
  createdAt: "2026-05-10T08:00:00.000Z",
  updatedAt: "2026-05-11T08:00:00.000Z",
};

const RESPONSE: LoanApplicationListResponse = {
  items: [ROW],
  total: 1,
  page: 0,
  pageSize: 25,
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
  vi.clearAllMocks();
});

describe("buildLoanApplicationsQuery", () => {
  it("returns an empty string when no filters are provided", () => {
    expect(buildLoanApplicationsQuery({})).toBe("");
  });

  it("omits empty / undefined values", () => {
    expect(buildLoanApplicationsQuery({ q: "   " })).toBe("");
    expect(buildLoanApplicationsQuery({ status: [] })).toBe("");
  });

  it("serialises scalar filters as set params", () => {
    const q = buildLoanApplicationsQuery({
      q: "demo",
      lspId: "lsp-1",
      productId: "p-1",
      lspLoanId: "LSP-42",
      bhawLoanId: "LMS-LN-42",
      disbursalDateFrom: "2026-04-01",
      disbursalDateTo: "2026-04-30",
      page: 2,
      pageSize: 50,
      sortBy: "updatedAt",
      sortDir: "desc",
    });
    const params = new URLSearchParams(q);
    expect(params.get("q")).toBe("demo");
    expect(params.get("lspId")).toBe("lsp-1");
    expect(params.get("productId")).toBe("p-1");
    expect(params.get("lspLoanId")).toBe("LSP-42");
    expect(params.get("bhawLoanId")).toBe("LMS-LN-42");
    expect(params.get("disbursalDateFrom")).toBe("2026-04-01");
    expect(params.get("disbursalDateTo")).toBe("2026-04-30");
    expect(params.get("page")).toBe("2");
    expect(params.get("pageSize")).toBe("50");
    expect(params.get("sortBy")).toBe("updatedAt");
    expect(params.get("sortDir")).toBe("desc");
  });

  it("joins status arrays with commas", () => {
    const q = buildLoanApplicationsQuery({
      status: ["AWAITING_APPROVAL", "DISBURSED"],
    });
    expect(new URLSearchParams(q).get("status")).toBe("AWAITING_APPROVAL,DISBURSED");
  });

  it("supports page=0 (zero is a real value)", () => {
    const q = buildLoanApplicationsQuery({ page: 0 });
    expect(new URLSearchParams(q).get("page")).toBe("0");
  });
});

describe("LoanApplicationListResponseSchema", () => {
  it("parses a well-formed response", () => {
    expect(LoanApplicationListResponseSchema.parse(RESPONSE)).toEqual(RESPONSE);
  });

  it("rejects negative totals", () => {
    expect(() => LoanApplicationListResponseSchema.parse({ ...RESPONSE, total: -1 })).toThrow();
  });

  it("rejects rows with an unknown status", () => {
    const bad = {
      ...RESPONSE,
      items: [{ ...ROW, status: "BOGUS" }],
    };
    expect(() => LoanApplicationListResponseSchema.parse(bad)).toThrow();
  });
});

describe("fetchLoanApplications", () => {
  it("requests the backend pagination envelope with the enum value the API accepts", () => {
    expect(
      backendQueryFromFilters({
        page: 1,
        pageSize: 10,
        lspLoanId: "LSP-42",
        bhawLoanId: "LMS-LN-42",
        disbursalDateFrom: "2026-04-01",
        disbursalDateTo: "2026-04-30",
      }),
    ).toMatchObject({
      lspLoanId: "LSP-42",
      bhawLoanId: "LMS-LN-42",
      disbursalDateFrom: "2026-04-01",
      disbursalDateTo: "2026-04-30",
      offset: 10,
      limit: 10,
      paginationDetails: "ON",
    });
  });

  it("dispatches a GET against the canonical path with no filters", async () => {
    const received: string[] = [];
    registerRoute("GET", "/api/v1/loan-applications", (req: MockRequest) => {
      received.push(req.path);
      return RESPONSE;
    });
    const result = await fetchLoanApplications({});
    expect(received).toEqual(["/api/v1/loan-applications"]);
    expect(result).toEqual(RESPONSE);
  });

  it("appends the query string when filters are present", async () => {
    // The mock router matches the full path literal (including query
    // string), so we register the exact path the wrapper emits.
    const filters = {
      q: "demo",
      status: ["AWAITING_APPROVAL" as const],
      page: 1,
      pageSize: 10,
      sortBy: "requestedAmount" as const,
      sortDir: "asc" as const,
    };
    const expectedQuery = buildLoanApplicationsQuery(filters);
    expect(expectedQuery).toContain("q=demo");
    expect(expectedQuery).toContain("status=AWAITING_APPROVAL");
    expect(expectedQuery).toContain("page=1");
    expect(expectedQuery).toContain("pageSize=10");
    expect(expectedQuery).toContain("sortBy=requestedAmount");
    expect(expectedQuery).toContain("sortDir=asc");

    let captured: string | null = null;
    registerRoute("GET", `/api/v1/loan-applications?${expectedQuery}`, (req: MockRequest) => {
      captured = req.path;
      return RESPONSE;
    });
    await fetchLoanApplications(filters);
    expect(captured).toBe(`/api/v1/loan-applications?${expectedQuery}`);
  });

  it("surfaces NOT_FOUND when no route is registered", async () => {
    await expect(fetchLoanApplications({})).rejects.toMatchObject({
      code: "NOT_FOUND",
    });
  });

  it("rejects when the handler returns a drift-shaped payload", async () => {
    registerRoute("GET", "/api/v1/loan-applications", () => ({
      items: [],
      // missing total/page/pageSize
    }));
    await expect(fetchLoanApplications({})).rejects.toBeDefined();
  });
});
