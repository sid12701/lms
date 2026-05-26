/**
 * api.ts tests — verifies the transport contract for the borrower-detail
 * surface. We register handlers directly against the mock router so we
 * don't depend on agent A's full handler runtime; the router's drift
 * detection (Zod parse on return) is exercised by the malformed-payload
 * cases.
 *
 * Per `docs/gap-fixes.md` § Gap #1, there is no audited PII reveal call —
 * masking is enforced at every read site; reveal tests have been removed.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  registerRoute,
  _resetRoutesForTests,
  _clearIdempotencyCacheForTests,
} from "@/mocks/router";
import { setLatencyOverride } from "@/mocks/latency";
import { scenario } from "@/mocks/scenarios";
import { fetchBorrowerDetail } from "./api";
import type { BorrowerDetail } from "./types";

const DETAIL_FIXTURE: BorrowerDetail = {
  borrower: {
    id: "bor-1",
    fullName: "Aanya Devi",
  } as unknown as BorrowerDetail["borrower"],
  visibleLsps: [
    { id: "lsp-1", name: "Acme NBFC" },
    { id: "lsp-2", name: "Bharat Credit" },
  ],
  totals: {
    openApplicationsCount: 1,
    closedApplicationsCount: 2,
    lifetimeDisbursedAmount: 350_000,
    activeOverdueAmount: 0,
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
  vi.restoreAllMocks();
});

describe("fetchBorrowerDetail", () => {
  it("returns the detail payload parsed against the schema", async () => {
    registerRoute("GET", "/api/v1/borrowers/:id", () => DETAIL_FIXTURE);
    const result = await fetchBorrowerDetail("bor-1");
    expect(result.visibleLsps).toHaveLength(2);
    expect(result.totals.lifetimeDisbursedAmount).toBe(350_000);
  });

  it("rejects payloads missing the totals (drift detection)", async () => {
    registerRoute("GET", "/api/v1/borrowers/:id", () => ({
      borrower: {},
      visibleLsps: [],
      // missing totals entirely
    }));
    await expect(fetchBorrowerDetail("bor-1")).rejects.toBeDefined();
  });

  it("rejects payloads with negative totals (drift detection)", async () => {
    registerRoute("GET", "/api/v1/borrowers/:id", () => ({
      borrower: {},
      visibleLsps: [],
      totals: {
        openApplicationsCount: -1,
        closedApplicationsCount: 0,
        lifetimeDisbursedAmount: 0,
        activeOverdueAmount: 0,
      },
    }));
    await expect(fetchBorrowerDetail("bor-1")).rejects.toBeDefined();
  });

  it("surfaces NOT_FOUND when no route is registered", async () => {
    await expect(fetchBorrowerDetail("missing")).rejects.toMatchObject({
      code: "NOT_FOUND",
    });
  });

  it("URL-encodes the borrower id", async () => {
    const seen: string[] = [];
    registerRoute("GET", "/api/v1/borrowers/:id", (req) => {
      seen.push(req.path);
      return DETAIL_FIXTURE;
    });
    await fetchBorrowerDetail("bor/with slash");
    expect(seen[0]).toContain("bor%2Fwith%20slash");
  });
});
