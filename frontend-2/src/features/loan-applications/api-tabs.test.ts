/**
 * api-tabs.ts tests — verifies the per-tab transport contract.
 *
 * Like api.test.ts, we register routes directly against the mock router
 * rather than booting agent A's full handler module, so the tests
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
import { clearStoredSession, saveStoredSession } from "@/lib/api/session-storage";
import {
  fetchLoanApplicationDocuments,
  fetchLoanApplicationRepayments,
  fetchLoanApplicationSchedule,
  postRepayment,
} from "./api-tabs";

const APPLICATION_ID = "11111111-1111-4111-8111-111111111111";

beforeEach(() => {
  clearStoredSession();
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
  vi.clearAllMocks();
});

describe("fetchLoanApplicationSchedule", () => {
  it("dispatches GET to the schedule endpoint", async () => {
    let capturedPath: string | null = null;
    registerRoute("GET", "/api/v1/loan-applications/:id/schedule", (req: MockRequest) => {
      capturedPath = req.path;
      return { schedule: null, installments: [] };
    });
    const result = await fetchLoanApplicationSchedule(APPLICATION_ID);
    expect(capturedPath).toBe(`/api/v1/loan-applications/${APPLICATION_ID}/schedule`);
    expect(result).toEqual({ schedule: null, installments: [] });
  });

  it("returns installments when present", async () => {
    const inst = { id: "i-1", number: 1 };
    registerRoute("GET", "/api/v1/loan-applications/:id/schedule", () => ({
      schedule: { id: "s-1" },
      installments: [inst],
    }));
    const result = await fetchLoanApplicationSchedule(APPLICATION_ID);
    expect(result.installments).toHaveLength(1);
  });

  it("surfaces NOT_FOUND when no route is registered", async () => {
    await expect(fetchLoanApplicationSchedule(APPLICATION_ID)).rejects.toMatchObject({
      code: "NOT_FOUND",
    });
  });
});

describe("fetchLoanApplicationDocuments", () => {
  it("dispatches GET to the documents endpoint", async () => {
    let capturedPath: string | null = null;
    registerRoute("GET", "/api/v1/loan-applications/:id/documents", (req: MockRequest) => {
      capturedPath = req.path;
      return { documents: [] };
    });
    const result = await fetchLoanApplicationDocuments(APPLICATION_ID);
    expect(capturedPath).toBe(`/api/v1/loan-applications/${APPLICATION_ID}/documents`);
    expect(result).toEqual({ documents: [] });
  });

  it("rejects when the handler returns a drift-shaped payload", async () => {
    registerRoute("GET", "/api/v1/loan-applications/:id/documents", () => ({}));
    await expect(fetchLoanApplicationDocuments(APPLICATION_ID)).rejects.toBeDefined();
  });

  it("maps live backend required document types without collapsing them to OTHER", async () => {
    saveStoredSession({
      user: {
        id: "22222222-2222-4222-8222-222222222222",
        username: "ops.admin",
        role: "SYSTEM_ADMIN",
        lspId: null,
        mustChangePassword: false,
      },
      accessToken: "access-token",
      expiresAt: "2026-05-26T10:00:00.000Z",
    });
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify([
            {
              id: "00000000-0000-4000-8000-000000000001",
              loanApplicationId: APPLICATION_ID,
              documentType: "ADDRESS_PROOF",
              documentDisplayName: "Address Proof",
              required: true,
              status: "SUBMITTED",
              note: null,
              fileName: "address-proof.pdf",
              fileReference: "lms-doc://address-proof.pdf",
              contentType: "application/pdf",
              sourceReference: null,
              lmsManagedContent: true,
              storageKey: "loan/app/address/address-proof.pdf",
              fileChecksum: "sha256:address",
              fileSizeBytes: 1024,
              uploadedAt: "2026-05-26T10:00:00.000Z",
              uploadedByUsername: "lsp.client",
              updatedByUsername: "lsp.client",
              createdAt: "2026-05-26T10:00:00.000Z",
              updatedAt: "2026-05-26T10:00:00.000Z",
            },
            {
              id: "00000000-0000-4000-8000-000000000002",
              loanApplicationId: APPLICATION_ID,
              documentType: "KFS",
              documentDisplayName: "KFS",
              required: true,
              status: "SUBMITTED",
              note: null,
              fileName: "signed-kfs.pdf",
              fileReference: "lms-doc://signed-kfs.pdf",
              contentType: "application/pdf",
              sourceReference: null,
              lmsManagedContent: true,
              storageKey: "loan/app/kfs/signed-kfs.pdf",
              fileChecksum: "sha256:kfs",
              fileSizeBytes: 2048,
              uploadedAt: "2026-05-26T10:00:00.000Z",
              uploadedByUsername: "lsp.client",
              updatedByUsername: "lsp.client",
              createdAt: "2026-05-26T10:00:00.000Z",
              updatedAt: "2026-05-26T10:00:00.000Z",
            },
          ]),
          { status: 200, headers: { "content-type": "application/json" } },
        ),
      ),
    );

    const result = await fetchLoanApplicationDocuments(APPLICATION_ID);

    expect(result.documents.map((doc) => doc.type)).toEqual(["ADDRESS_PROOF", "KFS"]);
    expect(result.documents[1]?.fileMeta?.fileName).toBe("signed-kfs.pdf");
  });
});

describe("fetchLoanApplicationRepayments", () => {
  it("dispatches GET to the repayments endpoint", async () => {
    let capturedPath: string | null = null;
    registerRoute("GET", "/api/v1/loan-applications/:id/repayments", (req: MockRequest) => {
      capturedPath = req.path;
      return { payments: [] };
    });
    const result = await fetchLoanApplicationRepayments(APPLICATION_ID);
    expect(capturedPath).toBe(`/api/v1/loan-applications/${APPLICATION_ID}/repayments`);
    expect(result).toEqual({ payments: [] });
  });
});

describe("postRepayment", () => {
  it("dispatches POST with the idempotency-key header + body", async () => {
    let captured: MockRequest | null = null;
    registerRoute(
      "POST",
      "/api/v1/loan-applications/:id/repayments",
      (req: MockRequest) => {
        captured = req;
        return {
          payment: { id: "pay-1" },
          application: { id: APPLICATION_ID },
          autoAdvanced: false,
          autoClosed: false,
        };
      },
      { mutating: true },
    );
    await postRepayment(APPLICATION_ID, {
      installmentId: "i-1",
      amount: 5_000,
      postedAt: "2026-05-11T10:00:00.000Z",
      mode: "BANK_TRANSFER",
      idempotencyKey: "idem-key-1",
    });
    expect(captured).not.toBeNull();
    const req = captured as unknown as MockRequest;
    expect(req.method).toBe("POST");
    expect(req.path).toBe(`/api/v1/loan-applications/${APPLICATION_ID}/repayments`);
    expect(req.headers?.["Idempotency-Key"]).toBe("idem-key-1");
    const body = req.body as { idempotencyKey: string; amount: number };
    expect(typeof body.idempotencyKey).toBe("string");
    expect(body.idempotencyKey.length).toBeGreaterThan(0);
    expect(body.amount).toBe(5_000);
  });

  it("resolves to void when the server responds successfully", async () => {
    registerRoute(
      "POST",
      "/api/v1/loan-applications/:id/repayments",
      () => ({
        payment: { id: "pay-1" },
        application: { id: APPLICATION_ID },
      }),
      { mutating: true },
    );
    const result = await postRepayment(APPLICATION_ID, {
      installmentId: "i-1",
      amount: 1,
      postedAt: "2026-05-11T10:00:00.000Z",
      mode: "UPI",
      idempotencyKey: "idem-key-2",
    });
    expect(result).toBeUndefined();
  });

  it("propagates handler errors", async () => {
    registerRoute(
      "POST",
      "/api/v1/loan-applications/:id/repayments",
      () => {
        throw new Error("boom");
      },
      { mutating: true },
    );
    await expect(
      postRepayment(APPLICATION_ID, {
        installmentId: "i-1",
        amount: 1,
        postedAt: "2026-05-11T10:00:00.000Z",
        mode: "UPI",
        idempotencyKey: "idem-key-3",
      }),
    ).rejects.toBeDefined();
  });
});
