/**
 * Regression: ordinary transitions must issue exactly ONE command and
 * surface the original 400/403 — even when local session metadata claims
 * SYSTEM_ADMIN. Manual override is a separate, deliberate action.
 *
 * No audit association: mutation responses carry no event (the authoritative
 * timeline refreshes via the invalidated activity query), so an empty,
 * unrelated, or unavailable audit trail can neither fail a committed command
 * nor misattribute a row to it.
 *
 * HTTP-mock boundary: mocks `@/lib/api/http-client` requestJson and
 * `@/lib/api/session-storage` loadStoredSession.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const requestJsonMock = vi.hoisted(() => vi.fn());
const loadStoredSessionMock = vi.hoisted(() => vi.fn());

vi.mock("@/lib/api/http-client", () => ({
  ApiError: class ApiError extends Error {
    status: number;
    body: string;
    code: string | null;
    retryAfterSeconds: number | null = null;
    constructor(message: string, status: number, body: string, code: string | null) {
      super(message);
      this.name = "ApiError";
      this.status = status;
      this.body = body;
      this.code = code;
    }
  },
  requestJson: requestJsonMock,
}));

vi.mock("@/lib/api/session-storage", () => ({
  loadStoredSession: loadStoredSessionMock,
}));

import { ApiError } from "@/lib/api/http-client";
import {
  fetchLoanApplicationDetail,
  isManualOverrideSourceBlocked,
  manualOverrideTargetsFor,
  postManualStatusOverride,
  postTransition,
} from "./api-detail";

const APP_ID = "11111111-1111-4111-8111-111111111111";

function detailPayload(status: string) {
  return {
    id: APP_ID,
    borrowerId: "22222222-2222-4222-8222-222222222222",
    lspId: "33333333-3333-4333-8333-333333333333",
    productId: "44444444-4444-4444-8444-444444444444",
    status,
    createdAt: "2026-09-01T00:00:00.000Z",
    updatedAt: "2026-09-02T00:00:00.000Z",
  };
}

function forgedAdminSession() {
  return {
    user: { role: "SYSTEM_ADMIN" },
    expiresAt: "2099-01-01T00:00:00.000Z",
    accessToken: "",
  };
}

function paths() {
  return requestJsonMock.mock.calls.map(([p]) => String(p));
}

beforeEach(() => {
  requestJsonMock.mockReset();
  loadStoredSessionMock.mockReset();
  loadStoredSessionMock.mockReturnValue(forgedAdminSession());
});

afterEach(() => {
  vi.clearAllMocks();
});

describe("Ordinary transition issues exactly one command", () => {
  it("surfaces the original 400 without falling through to manual-status (forged admin)", async () => {
    const original = new ApiError("illegal transition", 400, "{}", "INVALID_TRANSITION");
    requestJsonMock.mockImplementation((path: string) => {
      if (path.endsWith("/status-transitions")) return Promise.reject(original);
      throw new Error(`unexpected path ${path}`);
    });

    await expect(
      postTransition(APP_ID, {
        to: "APPROVED_PENDING_DISBURSAL",
        reason: null,
        reasonCode: null,
        idempotencyKey: "standard-key-1",
      }),
    ).rejects.toBe(original);

    expect(paths()).toEqual([
      `/api/v1/internal/ops/loan-applications/${APP_ID}/status-transitions`,
    ]);
  });

  it("surfaces the original 403 without falling through to manual-status (forged admin)", async () => {
    const original = new ApiError("forbidden", 403, "{}", "FORBIDDEN");
    requestJsonMock.mockImplementation((path: string) => {
      if (path.endsWith("/status-transitions")) return Promise.reject(original);
      throw new Error(`unexpected path ${path}`);
    });

    await expect(
      postTransition(APP_ID, {
        to: "REJECTED",
        reason: "nope",
        reasonCode: "FAILED_VERIFICATION",
        idempotencyKey: "standard-key-2",
      }),
    ).rejects.toBe(original);

    expect(paths()).toEqual([
      `/api/v1/internal/ops/loan-applications/${APP_ID}/status-transitions`,
    ]);
  });

  it("commits with exactly one network call: no checklist/audit GET, no attributed event", async () => {
    requestJsonMock.mockImplementation((path: string) => {
      if (path.endsWith("/status-transitions"))
        return Promise.resolve(detailPayload("AWAITING_APPROVAL"));
      throw new Error(`unexpected subsequent GET ${path}`);
    });

    const result = await postTransition(APP_ID, {
      to: "AWAITING_APPROVAL",
      reason: null,
      reasonCode: null,
      idempotencyKey: "standard-key-3",
    });

    expect(result.application.status).toBe("AWAITING_APPROVAL");
    expect(result.application.id).toBe(APP_ID);
    expect(result.event).toBeFalsy();
    expect(paths()).toEqual([
      `/api/v1/internal/ops/loan-applications/${APP_ID}/status-transitions`,
    ]);
  });

  it.each([
    ["unavailable KYC checklist (GET would 500)", "/kyc-documents"],
    ["unavailable audit trail (GET would 500)", "/audit-events"],
  ])(
    "committed POST succeeds despite %s: no subsequent GET, application returned",
    async (_label, failingSuffix) => {
      requestJsonMock.mockImplementation((path: string) => {
        if (path.endsWith("/status-transitions"))
          return Promise.resolve(detailPayload("AWAITING_APPROVAL"));
        if (path.endsWith(failingSuffix))
          return Promise.reject(new ApiError("boom", 500, "{}", "INTERNAL"));
        throw new Error(`unexpected subsequent GET ${path}`);
      });

      const result = await postTransition(APP_ID, {
        to: "AWAITING_APPROVAL",
        reason: null,
        reasonCode: null,
        idempotencyKey: "standard-key-4",
      });

      expect(result.application.status).toBe("AWAITING_APPROVAL");
      // No invented association: nothing from an empty/unrelated/unavailable
      // trail is presented as this command's event.
      expect(result.event).toBeFalsy();
      expect(paths()).toEqual([
        `/api/v1/internal/ops/loan-applications/${APP_ID}/status-transitions`,
      ]);
    },
  );
});

describe("Manual override is a separate deliberate action", () => {
  it("posts to manual-status with reason code + note: exactly one call, no follow-up GET", async () => {
    requestJsonMock.mockImplementation(
      (path: string, init?: RequestInit, options?: { idempotencyKey?: string }) => {
        if (path.endsWith("/manual-status")) {
          expect(JSON.parse(String(init?.body)).reasonCode).toBe("MANUAL_ADMIN_OVERRIDE");
          expect(JSON.parse(String(init?.body)).note).toBe("Out-of-band fix, verified by phone");
          expect(options?.idempotencyKey).toBe("override-session-key-9");
          return Promise.resolve(detailPayload("DISBURSEMENT_RETRY"));
        }
        throw new Error(`unexpected subsequent GET ${path}`);
      },
    );

    const result = await postManualStatusOverride(APP_ID, {
      to: "DISBURSEMENT_RETRY",
      reason: "Out-of-band fix, verified by phone",
      reasonCode: "MANUAL_ADMIN_OVERRIDE",
      idempotencyKey: "override-session-key-9",
    });

    expect(result.application.status).toBe("DISBURSEMENT_RETRY");
    expect(result.event).toBeFalsy();
    expect(paths()).toEqual([`/api/v1/internal/ops/loan-applications/${APP_ID}/manual-status`]);
  });

  it("committed override POST succeeds despite unavailable KYC checklist: no subsequent GET", async () => {
    requestJsonMock.mockImplementation((path: string) => {
      if (path.endsWith("/manual-status"))
        return Promise.resolve(detailPayload("DISBURSEMENT_RETRY"));
      if (path.endsWith("/kyc-documents"))
        return Promise.reject(new ApiError("boom", 500, "{}", "INTERNAL"));
      throw new Error(`unexpected subsequent GET ${path}`);
    });

    const result = await postManualStatusOverride(APP_ID, {
      to: "DISBURSEMENT_RETRY",
      reason: "Out-of-band fix, verified by phone",
      reasonCode: "MANUAL_ADMIN_OVERRIDE",
      idempotencyKey: "override-session-key-10",
    });

    expect(result.application.status).toBe("DISBURSEMENT_RETRY");
    expect(result.event).toBeFalsy();
    expect(paths()).toEqual([`/api/v1/internal/ops/loan-applications/${APP_ID}/manual-status`]);
  });

  it("refuses blocked financial targets client-side without sending anything", async () => {
    for (const blocked of [
      "APPROVED_PENDING_DISBURSAL",
      "DISBURSED",
      "CLOSED",
      "FORECLOSED",
    ] as const) {
      requestJsonMock.mockClear();
      requestJsonMock.mockRejectedValue(new Error("must not call network"));
      await expect(
        postManualStatusOverride(APP_ID, {
          to: blocked,
          reason: "x",
          reasonCode: "MANUAL_ADMIN_OVERRIDE",
          idempotencyKey: "k",
        }),
      ).rejects.toThrow();
      expect(requestJsonMock).not.toHaveBeenCalled();
    }
  });

  it("requires a deliberate reason code + explanation", async () => {
    requestJsonMock.mockRejectedValue(new Error("must not call network"));
    await expect(
      postManualStatusOverride(APP_ID, {
        to: "REJECTED",
        reason: null,
        reasonCode: "MANUAL_ADMIN_OVERRIDE",
        idempotencyKey: "k1",
      }),
    ).rejects.toThrow();
    await expect(
      postManualStatusOverride(APP_ID, {
        to: "REJECTED",
        reason: "has note",
        reasonCode: null,
        idempotencyKey: "k2",
      }),
    ).rejects.toThrow();
    expect(requestJsonMock).not.toHaveBeenCalled();
  });
});

describe("Override affordance guards", () => {
  it.each([
    "APPROVED_PENDING_DISBURSAL",
    "INVALID",
    "DISBURSED",
    "UNDER_REPAYMENT",
    "CLOSED",
    "FORECLOSED",
  ])("hides override for backend-blocked source %s", (status) => {
    expect(isManualOverrideSourceBlocked(status as "CLOSED", null)).toBe(true);
  });

  it.each(["INITIALIZED", "AWAITING_APPROVAL", "DISBURSEMENT_RETRY", "REJECTED"])(
    "offers override for recoverable source %s",
    (status) => {
      expect(isManualOverrideSourceBlocked(status as "REJECTED", null)).toBe(false);
    },
  );

  it.each(["DISBURSEMENT_REQUESTED", "DISBURSEMENT_PENDING_RECONCILIATION"])(
    "hides override while account %s is submitted/parked",
    (accountStatus) => {
      expect(isManualOverrideSourceBlocked("AWAITING_APPROVAL", accountStatus)).toBe(true);
    },
  );

  it("excludes the current status from override targets", () => {
    for (const current of [
      "INITIALIZED",
      "AWAITING_APPROVAL",
      "DISBURSEMENT_RETRY",
      "REJECTED",
    ] as const) {
      const targets = manualOverrideTargetsFor(current);
      expect(targets).not.toContain(current);
      expect(targets.length).toBeGreaterThan(0);
      for (const target of targets) {
        expect(["INITIALIZED", "AWAITING_APPROVAL", "DISBURSEMENT_RETRY", "REJECTED"]).toContain(
          target,
        );
      }
    }
  });

  it("hides override for an unrecognized source status", () => {
    expect(isManualOverrideSourceBlocked("UNKNOWN:SOME_FUTURE_STATUS", null)).toBe(true);
  });
});

describe("H28 honest detail projection", () => {
  function detailPayload(overrides: Record<string, unknown> = {}) {
    return {
      id: APP_ID,
      borrowerId: "22222222-2222-4222-8222-222222222222",
      borrowerFullName: "Asha Devi",
      borrowerPan: "ABCDE1234F",
      borrowerMobile: "9001000540",
      borrowerEmail: "asha@example.com",
      borrowerDateOfBirth: "1990-01-01",
      borrowerCity: "Bengaluru",
      borrowerState: "KA",
      borrowerEmploymentType: "SALARIED",
      borrowerMonthlyIncome: 85_000,
      lspId: "33333333-3333-4333-8333-333333333333",
      lspCode: "APEX",
      lspName: "Apex NBFC",
      productId: "44444444-4444-4444-8444-444444444444",
      productCode: "PL-A",
      productName: "Personal Loan A",
      requestedAmount: 250_000,
      tenureMonths: 12,
      status: "UNDER_REPAYMENT",
      sourceChannel: "UI",
      createdAt: "2026-09-01T00:00:00.000Z",
      updatedAt: "2026-09-02T00:00:00.000Z",
      loanAccount: {
        id: "55555555-5555-4555-8555-555555555555",
        accountNumber: "BHAW-1",
        status: "DISBURSED",
        principalAmount: 250_000,
        tenureMonths: 12,
        approvedAt: "2026-09-01T00:00:00.000Z",
        createdAt: "2026-09-01T00:00:00.000Z",
        closedAt: null,
        closureReason: null,
      },
      ...overrides,
    };
  }

  function mockDetail(overrides: Record<string, unknown> = {}) {
    requestJsonMock.mockImplementation((path: string) => {
      if (path.endsWith("/kyc-documents")) return Promise.resolve([]);
      if (path.endsWith(APP_ID)) return Promise.resolve(detailPayload(overrides));
      throw new Error(`unexpected path ${path}`);
    });
  }

  it("projects supplied borrower fields and leaves the rest unknown", async () => {
    mockDetail();
    const detail = await fetchLoanApplicationDetail(APP_ID);
    expect(detail.borrower.fullName).toBe("Asha Devi");
    expect(detail.borrower.pan).toBe("ABCDE1234F");
    expect(detail.borrower.mobile).toBe("9001000540");
    expect(detail.borrower.city).toBe("Bengaluru");
    expect(detail.borrower.employmentType).toBe("SALARIED");
    expect(detail.borrower.monthlyIncome).toBe(85_000);
    expect(detail.borrower.annualIncome).toBe(85_000 * 12);
    // The endpoint never supplies these: null, never M / SINGLE / false.
    expect(detail.borrower.gender).toBeNull();
    expect(detail.borrower.maritalStatus).toBeNull();
    expect(detail.borrower.aadhaar).toBeNull();
    expect(detail.borrower.kycComplete).toBeNull();
    // LSP/product status is unknown, never ACTIVE.
    expect(detail.lsp.status).toBeNull();
    expect(detail.product.status).toBeNull();
  });

  it("preserves UNDER_REPAYMENT and unknown application statuses", async () => {
    mockDetail();
    expect((await fetchLoanApplicationDetail(APP_ID)).application.status).toBe("UNDER_REPAYMENT");

    mockDetail({ status: "SOME_FUTURE_STATUS" });
    expect((await fetchLoanApplicationDetail(APP_ID)).application.status).toBe(
      "UNKNOWN:SOME_FUTURE_STATUS",
    );
  });

  it("keeps account status distinct from application status and preserves unknowns", async () => {
    mockDetail({ status: "DISBURSED" });
    const detail = await fetchLoanApplicationDetail(APP_ID);
    expect(detail.application.status).toBe("DISBURSED");
    expect(detail.account?.accountStatus).toBe("DISBURSED");

    mockDetail({ loanAccount: { ...detailPayload().loanAccount, status: "WEIRD" } });
    const weird = await fetchLoanApplicationDetail(APP_ID);
    expect(weird.account?.accountStatus).toBe("UNKNOWN:WEIRD");
  });

  it("maps missing money to null, never to zero", async () => {
    mockDetail({ requestedAmount: null, borrowerMonthlyIncome: null, tenureMonths: null });
    const detail = await fetchLoanApplicationDetail(APP_ID);
    expect(detail.application.requestedAmount).toBeNull();
    expect(detail.application.tenureMonths).toBeNull();
    expect(detail.borrower.monthlyIncome).toBeNull();
    expect(detail.borrower.annualIncome).toBeNull();
  });
});
