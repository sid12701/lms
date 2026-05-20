/**
 * api-tabs.ts tests — verifies the per-tab transport contract for the
 * borrower-detail surface. Routes are registered directly against the
 * mock router rather than booting agent A's handler module, so the tests
 * exercise dispatch + parse without the seeded fixtures.
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
import { fetchBorrowerActivity, fetchBorrowerLoans } from "./api-tabs";

const BORROWER_ID = "11111111-1111-4111-8111-111111111111";

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

describe("fetchBorrowerLoans", () => {
  it("dispatches GET to the loans endpoint", async () => {
    let capturedPath: string | null = null;
    let capturedMethod: string | null = null;
    registerRoute(
      "GET",
      "/api/v1/borrowers/:id/loans",
      (req: MockRequest) => {
        capturedPath = req.path;
        capturedMethod = req.method;
        return { loans: [] };
      },
    );

    const result = await fetchBorrowerLoans(BORROWER_ID);

    expect(capturedPath).toBe(`/api/v1/borrowers/${BORROWER_ID}/loans`);
    expect(capturedMethod).toBe("GET");
    expect(result).toEqual({ loans: [] });
  });

  it("returns loan rows when present", async () => {
    const row = {
      applicationId: "app-1",
      externalLoanId: null,
      lspId: "lsp-1",
      lspName: "Acme LSP",
      productId: "prod-1",
      productName: "Personal",
      requestedAmount: 50_000,
      tenureMonths: 12,
      status: "DISBURSED",
      createdAt: "2026-05-01T10:00:00.000Z",
      updatedAt: "2026-05-10T10:00:00.000Z",
    };
    registerRoute("GET", "/api/v1/borrowers/:id/loans", () => ({ loans: [row] }));

    const result = await fetchBorrowerLoans(BORROWER_ID);
    expect(result.loans).toHaveLength(1);
    expect(result.loans[0]?.applicationId).toBe("app-1");
  });

  it("surfaces NOT_FOUND when no route is registered", async () => {
    await expect(fetchBorrowerLoans(BORROWER_ID)).rejects.toMatchObject({
      code: "NOT_FOUND",
    });
  });

  it("rejects when the handler returns a drift-shaped payload", async () => {
    registerRoute("GET", "/api/v1/borrowers/:id/loans", () => ({}));
    await expect(fetchBorrowerLoans(BORROWER_ID)).rejects.toBeDefined();
  });
});

describe("fetchBorrowerActivity", () => {
  it("dispatches GET to the activity endpoint", async () => {
    let capturedPath: string | null = null;
    let capturedMethod: string | null = null;
    registerRoute(
      "GET",
      "/api/v1/borrowers/:id/activity",
      (req: MockRequest) => {
        capturedPath = req.path;
        capturedMethod = req.method;
        return { entries: [] };
      },
    );

    const result = await fetchBorrowerActivity(BORROWER_ID);

    expect(capturedPath).toBe(`/api/v1/borrowers/${BORROWER_ID}/activity`);
    expect(capturedMethod).toBe("GET");
    expect(result).toEqual({ entries: [] });
  });

  it("returns mixed activity entries when present", async () => {
    const entries = [
      { kind: "APPLICATION", event: { id: "e-1", createdAt: "2026-05-10T10:00:00Z" } },
      { kind: "PII_REVEAL", event: { id: "e-2", revealedAt: "2026-05-09T10:00:00Z" } },
      { kind: "DOCUMENT_ACCESS", event: { id: "e-3", accessedAt: "2026-05-08T10:00:00Z" } },
    ];
    registerRoute(
      "GET",
      "/api/v1/borrowers/:id/activity",
      () => ({ entries }),
    );

    const result = await fetchBorrowerActivity(BORROWER_ID);
    expect(result.entries).toHaveLength(3);
  });

  it("surfaces NOT_FOUND when no route is registered", async () => {
    await expect(fetchBorrowerActivity(BORROWER_ID)).rejects.toMatchObject({
      code: "NOT_FOUND",
    });
  });

  it("rejects when the handler returns a drift-shaped payload", async () => {
    registerRoute("GET", "/api/v1/borrowers/:id/activity", () => ({}));
    await expect(fetchBorrowerActivity(BORROWER_ID)).rejects.toBeDefined();
  });
});
