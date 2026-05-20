/**
 * api-detail.ts tests — verifies the wrapper's transport contract.
 *
 * Mirrors the pattern in `home/api.test.ts`: we register handlers directly
 * against the mock router so we don't depend on agent A's full handler
 * runtime. The drift-detection (Zod parse on return) is exercised by the
 * "rejects malformed handler payloads" cases.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  registerRoute,
  _resetRoutesForTests,
  _clearIdempotencyCacheForTests,
} from "@/mocks/router";
import { setLatencyOverride } from "@/mocks/latency";
import { scenario } from "@/mocks/scenarios";
import {
  fetchLoanApplicationActivity,
  fetchLoanApplicationDetail,
  fetchLoanApplicationWebhooks,
  postDisbursement,
  postTransition,
} from "./api-detail";
import type {
  LoanApplicationActivityResponse,
  LoanApplicationDetail,
  LoanApplicationWebhooksResponse,
} from "./types";

const DETAIL_FIXTURE: LoanApplicationDetail = {
  // The runtime parser uses Permissive on these — only the top-level shape
  // and the two booleans are asserted, so we can use loose stand-ins.
  application: {
    id: "app-1",
    externalLoanId: "EXT-001",
    borrowerId: "bor-1",
    lspId: "lsp-1",
    productId: "prod-1",
    requestedAmount: 250_000,
    tenureMonths: 12,
    status: "AWAITING_APPROVAL",
    sourceChannel: "UI",
    assignedTo: null,
    createdAt: "2026-05-10T08:00:00.000Z",
    updatedAt: "2026-05-10T08:00:00.000Z",
    invalidatedAt: null,
    invalidReason: null,
  } as unknown as LoanApplicationDetail["application"],
  borrower: { id: "bor-1", fullName: "Aanya Devi" } as unknown as LoanApplicationDetail["borrower"],
  lsp: { id: "lsp-1", name: "Acme NBFC" } as unknown as LoanApplicationDetail["lsp"],
  product: { id: "prod-1", name: "PL-A" } as unknown as LoanApplicationDetail["product"],
  account: null,
  docsComplete: true,
  scheduleValid: false,
};

const ACTIVITY_FIXTURE: LoanApplicationActivityResponse = {
  events: [
    {
      id: "evt-1",
      applicationId: "app-1",
      fromStatus: "UNDER_REVIEW",
      toStatus: "AWAITING_APPROVAL",
      action: "submit-for-approval",
      actorId: "user-1",
      actorRole: "OPS_USER",
      channel: "UI",
      correlationId: "corr-1",
      reason: null,
      createdAt: "2026-05-10T11:30:00.000Z",
    },
  ],
};

const WEBHOOKS_FIXTURE: LoanApplicationWebhooksResponse = {
  deliveries: [
    {
      id: "wd-1",
      eventType: "loan.created",
      endpoint: "https://lsp.example.com/webhook",
      status: "DELIVERED",
      attemptCount: 1,
      lastAttemptAt: "2026-05-10T12:00:05.000Z",
      lastError: null,
      createdAt: "2026-05-10T12:00:00.000Z",
    },
  ],
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

describe("fetchLoanApplicationDetail", () => {
  it("returns the detail payload parsed against the schema", async () => {
    registerRoute("GET", "/api/v1/loan-applications/:id", () => DETAIL_FIXTURE);
    const result = await fetchLoanApplicationDetail("app-1");
    expect(result.docsComplete).toBe(true);
    expect(result.scheduleValid).toBe(false);
    expect(result.application.id).toBe("app-1");
  });

  it("rejects payloads missing the boolean gates (drift detection)", async () => {
    registerRoute("GET", "/api/v1/loan-applications/:id", () => ({
      application: { id: "x" },
      borrower: {},
      lsp: {},
      product: {},
      account: null,
      // missing docsComplete + scheduleValid
    }));
    await expect(fetchLoanApplicationDetail("app-1")).rejects.toBeDefined();
  });

  it("surfaces NOT_FOUND when no route is registered", async () => {
    await expect(fetchLoanApplicationDetail("nope")).rejects.toMatchObject({
      code: "NOT_FOUND",
    });
  });

  it("URL-encodes the id", async () => {
    const seen: string[] = [];
    registerRoute("GET", "/api/v1/loan-applications/:id", (req) => {
      seen.push(req.path);
      return DETAIL_FIXTURE;
    });
    await fetchLoanApplicationDetail("app/with slash");
    expect(seen[0]).toContain("app%2Fwith%20slash");
  });
});

describe("fetchLoanApplicationActivity", () => {
  it("returns the events list", async () => {
    registerRoute("GET", "/api/v1/loan-applications/:id/activity", () => ACTIVITY_FIXTURE);
    const result = await fetchLoanApplicationActivity("app-1");
    expect(result.events).toHaveLength(1);
    expect(result.events[0]?.action).toBe("submit-for-approval");
  });

  it("rejects malformed event rows", async () => {
    registerRoute("GET", "/api/v1/loan-applications/:id/activity", () => ({
      events: [{ id: "x" }],
    }));
    await expect(fetchLoanApplicationActivity("app-1")).rejects.toBeDefined();
  });
});

describe("fetchLoanApplicationWebhooks", () => {
  it("returns the deliveries list", async () => {
    registerRoute("GET", "/api/v1/loan-applications/:id/webhooks", () => WEBHOOKS_FIXTURE);
    const result = await fetchLoanApplicationWebhooks("app-1");
    expect(result.deliveries).toHaveLength(1);
    expect(result.deliveries[0]?.status).toBe("DELIVERED");
  });

  it("rejects payloads with an unknown status enum", async () => {
    registerRoute("GET", "/api/v1/loan-applications/:id/webhooks", () => ({
      deliveries: [
        {
          id: "wd-1",
          eventType: "loan.created",
          endpoint: "https://x",
          status: "UNKNOWN",
          attemptCount: 0,
          lastAttemptAt: null,
          lastError: null,
          createdAt: "2026-05-10T00:00:00.000Z",
        },
      ],
    }));
    await expect(fetchLoanApplicationWebhooks("app-1")).rejects.toBeDefined();
  });
});

describe("postTransition", () => {
  it("forwards the idempotency key in body and header", async () => {
    let capturedBody: Record<string, unknown> | undefined;
    let capturedHeader: string | undefined;
    registerRoute(
      "POST",
      "/api/v1/loan-applications/:id/transitions",
      (req) => {
        capturedBody = req.body as Record<string, unknown>;
        capturedHeader = req.headers?.["Idempotency-Key"];
        return {
          application: { id: "app-1", status: "APPROVED_PENDING_DISBURSAL" },
          event: {
            id: "evt-x",
            applicationId: "app-1",
            fromStatus: "AWAITING_APPROVAL",
            toStatus: "APPROVED_PENDING_DISBURSAL",
            action: "approve",
            actorId: "user-1",
            actorRole: "OPS_USER",
            channel: "UI",
            correlationId: "corr-x",
            reason: null,
            createdAt: "2026-05-11T00:00:00.000Z",
          },
        };
      },
      { mutating: true },
    );

    const result = await postTransition("app-1", {
      to: "APPROVED_PENDING_DISBURSAL",
      reason: null,
      idempotencyKey: "key-fixed-123",
    });
    expect(capturedBody?.idempotencyKey).toBe("key-fixed-123");
    expect(capturedHeader).toBe("key-fixed-123");
    expect(result.event.action).toBe("approve");
  });

  it("mints a fresh idempotency key when none is supplied", async () => {
    let captured: string | undefined;
    registerRoute(
      "POST",
      "/api/v1/loan-applications/:id/transitions",
      (req) => {
        captured = (req.body as Record<string, unknown>)?.idempotencyKey as string;
        return {
          application: { id: "app-1" },
          event: {
            id: "evt-x",
            applicationId: "app-1",
            fromStatus: null,
            toStatus: "APPROVED",
            action: "approve",
            actorId: "u",
            actorRole: "OPS_USER",
            channel: "UI",
            correlationId: "c",
            reason: null,
            createdAt: "2026-05-11T00:00:00.000Z",
          },
        };
      },
      { mutating: true },
    );
    await postTransition("app-1", {
      to: "APPROVED",
      reason: null,
      idempotencyKey: "",
    });
    expect(typeof captured).toBe("string");
    expect((captured ?? "").length).toBeGreaterThan(0);
  });
});

describe("postDisbursement", () => {
  it("posts the input and forwards the idempotency key", async () => {
    let capturedHeader: string | undefined;
    registerRoute(
      "POST",
      "/api/v1/loan-applications/:id/disbursement",
      (req) => {
        capturedHeader = req.headers?.["Idempotency-Key"];
        return {
          application: { id: "app-1" },
          events: [],
        };
      },
      { mutating: true },
    );
    const result = await postDisbursement("app-1", {
      note: "manual trigger",
      idempotencyKey: "disb-key-1",
    });
    expect(capturedHeader).toBe("disb-key-1");
    expect(result.events).toHaveLength(0);
  });
});
