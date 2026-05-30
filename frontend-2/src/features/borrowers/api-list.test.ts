/**
 * api-list.ts tests — verifies the transport contract for the
 * `/borrowers` directory list page. We register handlers directly
 * against the mock router so the test exercises the client → parser
 * → response shape contract, not the dispatch internals.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  registerRoute,
  _resetRoutesForTests,
  _clearIdempotencyCacheForTests,
} from "@/mocks/router";
import { setLatencyOverride } from "@/mocks/latency";
import { scenario } from "@/mocks/scenarios";
import { fetchBorrowersList, buildBorrowersListQuery } from "./api-list";
import type { BorrowerListResponse, BorrowerSummary } from "./list-types";

const SUMMARY_A: BorrowerSummary = {
  id: "11111111-1111-4111-8111-111111111111",
  fullName: "Anika Sharma",
  pan: "ABCDE1234F",
  mobile: "9999999991",
  email: "anika@example.com",
  city: "Bengaluru",
  state: "Karnataka",
  aadharNumberMasked: "XXXXXXXX9012",
  visibleLspIds: [],
};

const SUMMARY_B: BorrowerSummary = {
  id: "22222222-2222-4222-8222-222222222222",
  fullName: "Rahul Shah",
  pan: "ZXCVB1234N",
  mobile: "9876543210",
  email: "rahul@example.com",
  city: "Delhi",
  state: "Delhi",
  aadharNumberMasked: null,
  visibleLspIds: [],
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
  vi.restoreAllMocks();
});

describe("buildBorrowersListQuery", () => {
  it("returns empty string when no filters are set", () => {
    expect(buildBorrowersListQuery({})).toBe("");
  });

  it("encodes the search query, offset, and limit", () => {
    expect(buildBorrowersListQuery({ q: "rahul", page: 2, pageSize: 25 })).toBe(
      "q=rahul&offset=50&limit=25&paginationDetails=ON",
    );
  });

  it("trims blank search queries", () => {
    expect(buildBorrowersListQuery({ q: "   " })).toBe("");
  });

  it("defaults to page 0 with pageSize when only page-size is given", () => {
    expect(buildBorrowersListQuery({ pageSize: 10 })).toBe(
      "offset=0&limit=10&paginationDetails=ON",
    );
  });
});

describe("fetchBorrowersList (mock fallback)", () => {
  it("parses an array response and surfaces it as { items, total }", async () => {
    registerRoute("GET", "/api/v1/borrowers", () => [SUMMARY_A, SUMMARY_B]);

    const result: BorrowerListResponse = await fetchBorrowersList({});
    expect(result.items).toHaveLength(2);
    expect(result.items[0]?.fullName).toBe("Anika Sharma");
    expect(result.total).toBe(2);
    expect(result.page).toBe(0);
    expect(result.pageSize).toBeGreaterThan(0);
  });

  it("threads page/pageSize through and reports them on the response", async () => {
    registerRoute("GET", "/api/v1/borrowers", () => [SUMMARY_A]);

    const result = await fetchBorrowersList({ page: 1, pageSize: 25 });
    expect(result.page).toBe(1);
    expect(result.pageSize).toBe(25);
  });

  it("rejects payloads with non-string ids (drift detection)", async () => {
    registerRoute("GET", "/api/v1/borrowers", () => [{ ...SUMMARY_A, id: 12345 }]);
    await expect(fetchBorrowersList({})).rejects.toBeDefined();
  });

  it("accepts an empty list", async () => {
    registerRoute("GET", "/api/v1/borrowers", () => []);
    const result = await fetchBorrowersList({});
    expect(result.items).toHaveLength(0);
    expect(result.total).toBe(0);
  });
});
