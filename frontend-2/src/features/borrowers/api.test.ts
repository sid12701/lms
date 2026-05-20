/**
 * api.ts tests — verifies the transport contract for the borrower-detail
 * surface. We register handlers directly against the mock router so we
 * don't depend on agent A's full handler runtime; the router's drift
 * detection (Zod parse on return) is exercised by the malformed-payload
 * cases.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  registerRoute,
  _resetRoutesForTests,
  _clearIdempotencyCacheForTests,
} from "@/mocks/router";
import { setLatencyOverride } from "@/mocks/latency";
import { scenario } from "@/mocks/scenarios";
import { fetchBorrowerDetail, recordPiiReveal } from "./api";
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

describe("recordPiiReveal", () => {
  it("forwards the idempotency key in body AND header", async () => {
    let capturedBody: Record<string, unknown> | undefined;
    let capturedHeader: string | undefined;
    registerRoute(
      "POST",
      "/api/v1/borrowers/:id/pii-reveal",
      (req) => {
        capturedBody = req.body as Record<string, unknown>;
        capturedHeader = req.headers?.["Idempotency-Key"];
        return { value: "ABCDE1234F", auditId: "audit-1" };
      },
      { mutating: true },
    );

    const result = await recordPiiReveal({
      borrowerId: "bor-1",
      field: "PAN",
      reason: "verifying KYC",
      idempotencyKey: "key-fixed-123",
    });

    expect(capturedBody?.idempotencyKey).toBe("key-fixed-123");
    expect(capturedBody?.borrowerId).toBe("bor-1");
    expect(capturedBody?.field).toBe("PAN");
    expect(capturedBody?.reason).toBe("verifying KYC");
    expect(capturedHeader).toBe("key-fixed-123");
    expect(result.value).toBe("ABCDE1234F");
    expect(result.auditId).toBe("audit-1");
  });

  it("mints a fresh idempotency key when none is supplied", async () => {
    let captured: string | undefined;
    registerRoute(
      "POST",
      "/api/v1/borrowers/:id/pii-reveal",
      (req) => {
        captured = (req.body as Record<string, unknown>)?.idempotencyKey as string;
        return { value: "v", auditId: "audit-2" };
      },
      { mutating: true },
    );
    await recordPiiReveal({
      borrowerId: "bor-1",
      field: "MOBILE",
      reason: "callback",
      idempotencyKey: "",
    });
    expect(typeof captured).toBe("string");
    expect((captured ?? "").length).toBeGreaterThan(0);
  });

  it("rejects an invalid field name before dispatching", async () => {
    const handlerSpy = vi.fn();
    registerRoute(
      "POST",
      "/api/v1/borrowers/:id/pii-reveal",
      handlerSpy,
      { mutating: true },
    );

    await expect(
      recordPiiReveal({
        borrowerId: "bor-1",
        // @ts-expect-error -- intentional invalid value
        field: "NOT_A_FIELD",
        reason: "x",
        idempotencyKey: "k",
      }),
    ).rejects.toBeDefined();
    // The handler should never have been hit — the local Zod gate fires first.
    expect(handlerSpy).not.toHaveBeenCalled();
  });

  it("URL-encodes the borrower id on POST", async () => {
    const paths: string[] = [];
    registerRoute(
      "POST",
      "/api/v1/borrowers/:id/pii-reveal",
      (req) => {
        paths.push(req.path);
        return { value: "v", auditId: "a" };
      },
      { mutating: true },
    );
    await recordPiiReveal({
      borrowerId: "bor/special",
      field: "PAN",
      reason: "verifying",
      idempotencyKey: "k1",
    });
    expect(paths[0]).toContain("bor%2Fspecial");
  });

  it("rejects payloads missing the auditId (drift detection)", async () => {
    registerRoute(
      "POST",
      "/api/v1/borrowers/:id/pii-reveal",
      () => ({ value: "v" }),
      { mutating: true },
    );
    await expect(
      recordPiiReveal({
        borrowerId: "bor-1",
        field: "PAN",
        reason: "x",
        idempotencyKey: "k",
      }),
    ).rejects.toBeDefined();
  });
});
