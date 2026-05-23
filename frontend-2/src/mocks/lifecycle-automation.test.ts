/**
 * Tests for the lifecycle-automation helpers.
 *
 * The whole `@/mocks/api/loan-applications` namespace is mocked so these
 * tests don't depend on Agent A1/A2's `submitSchedule` / `completeForeclosure`
 * implementations landing first.
 */
import { describe, it, expect, vi, beforeEach } from "vitest";
import { LspProvidedSchedule } from "@/schemas/lsp-provided-schedule";

vi.mock("@/mocks/api/loan-applications", () => ({
  detail: vi.fn(),
  transition: vi.fn(),
  submitSchedule: vi.fn(),
  initiateDisbursement: vi.fn(),
  postRepayment: vi.fn(),
  completeForeclosure: vi.fn(),
  schedule: vi.fn(),
}));

import * as loanApi from "@/mocks/api/loan-applications";
import {
  runFullLifecycle,
  submitGeneratedSchedule,
  completeForeclosureForApplication,
} from "./lifecycle-automation";

// ─── Helpers ─────────────────────────────────────────────────────────────────

interface MockInstallment {
  id: string;
  number: number;
  principalDue: number;
  interestDue: number;
  installmentAmount: number;
  outstandingAmount: number;
  paidAmount: number;
  status: "DUE" | "PAID" | "OVERDUE";
}

function makeInstallments(count: number, amount: number): MockInstallment[] {
  return Array.from({ length: count }, (_, idx) => ({
    id: `i${idx + 1}`,
    number: idx + 1,
    principalDue: amount,
    interestDue: 0,
    installmentAmount: amount,
    outstandingAmount: amount,
    paidAmount: 0,
    status: "DUE" as const,
  }));
}

function detailFor(status: string, requestedAmount = 480000, tenureMonths = 4) {
  return {
    application: {
      id: "app-1",
      requestedAmount,
      tenureMonths,
      status,
    },
  };
}

beforeEach(() => {
  // resetAllMocks also clears mockResolvedValueOnce queues so tests don't bleed.
  vi.resetAllMocks();
});

// ─── Test cases ──────────────────────────────────────────────────────────────

describe("runFullLifecycle", () => {
  it("APPROVED_PENDING_DISBURSAL happy path → CLOSED", async () => {
    const detailMock = vi.mocked(loanApi.detail);
    // 1) initial fetch (APD)
    // 2) after submitSchedule+initiateDisbursement → DISBURSED
    // 3..6) after each of 4 repayments → DISBURSED, DISBURSED, DISBURSED, CLOSED
    detailMock
      .mockResolvedValueOnce(detailFor("APPROVED_PENDING_DISBURSAL") as never)
      .mockResolvedValueOnce(detailFor("DISBURSED") as never)
      .mockResolvedValueOnce(detailFor("DISBURSED") as never)
      .mockResolvedValueOnce(detailFor("DISBURSED") as never)
      .mockResolvedValueOnce(detailFor("DISBURSED") as never)
      .mockResolvedValueOnce(detailFor("CLOSED") as never);

    // Progressively-paid schedule: each call marks one more installment PAID.
    let paidCount = 0;
    vi.mocked(loanApi.schedule).mockImplementation(async () => {
      const installments = makeInstallments(4, 120000).map((i) =>
        i.number <= paidCount
          ? { ...i, status: "PAID" as const, paidAmount: i.installmentAmount, outstandingAmount: 0 }
          : i,
      );
      return { schedule: { id: "s1" }, installments } as never;
    });
    vi.mocked(loanApi.postRepayment).mockImplementation(async () => {
      paidCount += 1;
      return {} as never;
    });
    vi.mocked(loanApi.submitSchedule).mockResolvedValue({} as never);
    vi.mocked(loanApi.initiateDisbursement).mockResolvedValue({} as never);

    const result = await runFullLifecycle("app-1");

    expect(result.finalStatus).toBe("CLOSED");
    expect(vi.mocked(loanApi.submitSchedule)).toHaveBeenCalledTimes(1);
    const submitCall = vi.mocked(loanApi.submitSchedule).mock.calls[0]!;
    const payload = submitCall[1] as unknown as { installments: unknown[] };
    expect(payload.installments).toHaveLength(4);
    expect(vi.mocked(loanApi.initiateDisbursement)).toHaveBeenCalledTimes(1);
    expect(vi.mocked(loanApi.postRepayment)).toHaveBeenCalledTimes(4);
  });

  it("closeViaForeclosure path calls completeForeclosure once and never postRepayment", async () => {
    vi.mocked(loanApi.detail)
      .mockResolvedValueOnce(detailFor("APPROVED_PENDING_DISBURSAL") as never)
      .mockResolvedValueOnce(detailFor("DISBURSED") as never)
      .mockResolvedValueOnce(detailFor("CLOSED") as never);

    const installments = makeInstallments(4, 120000);
    vi.mocked(loanApi.schedule).mockResolvedValue({
      schedule: { id: "s1" },
      installments,
    } as never);
    vi.mocked(loanApi.submitSchedule).mockResolvedValue({} as never);
    vi.mocked(loanApi.initiateDisbursement).mockResolvedValue({} as never);
    vi.mocked(loanApi.completeForeclosure).mockResolvedValue({} as never);

    const result = await runFullLifecycle("app-1", { closeViaForeclosure: true });

    expect(result.finalStatus).toBe("CLOSED");
    expect(vi.mocked(loanApi.submitSchedule)).toHaveBeenCalledTimes(1);
    expect(vi.mocked(loanApi.initiateDisbursement)).toHaveBeenCalledTimes(1);
    expect(vi.mocked(loanApi.completeForeclosure)).toHaveBeenCalledTimes(1);
    expect(vi.mocked(loanApi.postRepayment)).not.toHaveBeenCalled();

    const expectedOutstanding = installments.reduce((s, i) => s + i.principalDue, 0);
    const fcCall = vi.mocked(loanApi.completeForeclosure).mock.calls[0]!;
    const fcPayload = fcCall[1] as { outstandingPrincipal: number };
    expect(fcPayload.outstandingPrincipal).toBe(expectedOutstanding);
  });

  it("mid-flow failure (initiateDisbursement rejects) returns lastStepError", async () => {
    vi.mocked(loanApi.detail)
      .mockResolvedValueOnce(detailFor("APPROVED_PENDING_DISBURSAL") as never)
      // best-effort final-status fetch after failure
      .mockResolvedValueOnce(detailFor("APPROVED_PENDING_DISBURSAL") as never);

    vi.mocked(loanApi.submitSchedule).mockResolvedValue({} as never);
    vi.mocked(loanApi.initiateDisbursement).mockRejectedValue(
      new Error("disbursement failed"),
    );

    const result = await runFullLifecycle("app-1");
    const last = result.steps[result.steps.length - 1]!;
    expect(last.ok).toBe(false);
    expect(result.lastStepError).toBe("disbursement failed");
    expect(result.finalStatus).toBe("APPROVED_PENDING_DISBURSAL");
  });

  it("safety cap terminates the repayment loop with a synthetic failure step", async () => {
    // detail keeps reporting UNDER_REPAYMENT forever (need plenty of values).
    const detailMock = vi.mocked(loanApi.detail);
    detailMock.mockResolvedValueOnce(detailFor("APPROVED_PENDING_DISBURSAL") as never);
    detailMock.mockResolvedValueOnce(detailFor("DISBURSED") as never);
    for (let i = 0; i < 100; i++) {
      detailMock.mockResolvedValueOnce(detailFor("UNDER_REPAYMENT") as never);
    }

    // Schedule always returns 1 DUE installment that never advances.
    vi.mocked(loanApi.schedule).mockResolvedValue({
      schedule: { id: "s1" },
      installments: makeInstallments(1, 50000),
    } as never);
    vi.mocked(loanApi.submitSchedule).mockResolvedValue({} as never);
    vi.mocked(loanApi.initiateDisbursement).mockResolvedValue({} as never);
    vi.mocked(loanApi.postRepayment).mockResolvedValue({} as never);

    const result = await runFullLifecycle("app-1");

    const synthetic = result.steps.find((s) => s.step === "post-repayment-loop");
    expect(synthetic).toBeDefined();
    expect(synthetic?.ok).toBe(false);
    expect(synthetic?.error).toMatch(/safety/);
    expect(result.finalStatus).toBe("UNDER_REPAYMENT");
  });

  it("synthesised schedule passes BR-11 (LspProvidedSchedule.safeParse)", async () => {
    vi.mocked(loanApi.detail).mockResolvedValue(
      detailFor("APPROVED_PENDING_DISBURSAL", 360000, 12) as never,
    );
    vi.mocked(loanApi.submitSchedule).mockResolvedValue({} as never);

    await submitGeneratedSchedule("app-1");

    const captured = vi.mocked(loanApi.submitSchedule).mock.calls[0]![1] as unknown as {
      installments: unknown[];
    };
    const parsed = LspProvidedSchedule.safeParse({
      accountId: "00000000-0000-4000-8000-000000000001",
      approvedPrincipal: 360000,
      approvedTenureMonths: 12,
      installments: captured.installments,
    });
    expect(parsed.success, parsed.success ? "" : JSON.stringify(parsed.error.flatten())).toBe(true);
  });

  it("onStep fires per step in the documented order", async () => {
    const detailMock = vi.mocked(loanApi.detail);
    detailMock
      .mockResolvedValueOnce(detailFor("APPROVED_PENDING_DISBURSAL") as never)
      .mockResolvedValueOnce(detailFor("DISBURSED") as never)
      .mockResolvedValueOnce(detailFor("DISBURSED") as never)
      .mockResolvedValueOnce(detailFor("DISBURSED") as never)
      .mockResolvedValueOnce(detailFor("DISBURSED") as never)
      .mockResolvedValueOnce(detailFor("CLOSED") as never);

    let paidCount = 0;
    vi.mocked(loanApi.schedule).mockImplementation(async () => {
      const installments = makeInstallments(4, 120000).map((i) =>
        i.number <= paidCount
          ? { ...i, status: "PAID" as const, paidAmount: i.installmentAmount, outstandingAmount: 0 }
          : i,
      );
      return { schedule: { id: "s1" }, installments } as never;
    });
    vi.mocked(loanApi.postRepayment).mockImplementation(async () => {
      paidCount += 1;
      return {} as never;
    });
    vi.mocked(loanApi.submitSchedule).mockResolvedValue({} as never);
    vi.mocked(loanApi.initiateDisbursement).mockResolvedValue({} as never);

    const labels: string[] = [];
    await runFullLifecycle("app-1", { onStep: (l) => labels.push(l) });

    expect(labels[0]).toBe("fetch-detail");
    expect(labels).toContain("submit-schedule");
    expect(labels).toContain("initiate-disbursement");
    expect(labels.indexOf("submit-schedule")).toBeLessThan(
      labels.indexOf("initiate-disbursement"),
    );
    expect(labels).toContain("post-repayment-1");
    expect(labels.indexOf("initiate-disbursement")).toBeLessThan(
      labels.indexOf("post-repayment-1"),
    );
  });
});

describe("completeForeclosureForApplication", () => {
  it("returns a LifecycleStepResult on success", async () => {
    vi.mocked(loanApi.detail).mockResolvedValue(detailFor("UNDER_REPAYMENT") as never);
    vi.mocked(loanApi.schedule).mockResolvedValue({
      schedule: { id: "s1" },
      installments: makeInstallments(2, 60000),
    } as never);
    vi.mocked(loanApi.completeForeclosure).mockResolvedValue({} as never);

    const result = await completeForeclosureForApplication("app-1");
    expect(result.step).toBe("complete-foreclosure");
    expect(result.ok).toBe(true);
  });
});
