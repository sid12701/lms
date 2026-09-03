import { describe, expect, it } from "vitest";
import type { RepaymentInstallment } from "@/schemas/loan-account";
import {
  lspScheduleToRepaymentInstallments,
  type LspScheduleInstallmentRow,
} from "./scheduleAdapters";

function row(overrides: Partial<LspScheduleInstallmentRow> = {}): LspScheduleInstallmentRow {
  return {
    id: "inst-1",
    installmentNumber: 1,
    dueDate: "2026-06-01",
    principalDue: 8000,
    interestDue: 2000,
    installmentAmount: 10000,
    paidAmount: 0,
    outstandingAmount: 10000,
    status: "DUE",
    daysPastDue: 0,
    delinquencyBucket: "B0",
    ...overrides,
  };
}

describe("lspScheduleToRepaymentInstallments — status mapping (safeInstallmentStatus)", () => {
  const cases: Array<[string, RepaymentInstallment["status"]]> = [
    ["DUE", "DUE"],
    ["PARTIALLY_PAID", "PARTIALLY_PAID"],
    ["PAID", "PAID"],
    ["OVERDUE", "OVERDUE"],
    ["SCHEDULED", "DUE"],
    ["PENDING", "DUE"],
    ["SETTLED", "PAID"],
    ["SOME_UNRECOGNISED_BACKEND_STATUS", "DUE"],
  ];

  it.each(cases)("maps backend status %s to installment status %s", (backendStatus, expected) => {
    const [mapped] = lspScheduleToRepaymentInstallments([row({ status: backendStatus })], "acct-1");
    expect(mapped!.status).toBe(expected);
  });
});

describe("lspScheduleToRepaymentInstallments — delinquency bucket mapping (safeDelinquencyBucket)", () => {
  const cases: Array<[string | null | undefined, RepaymentInstallment["delinquencyBucket"]]> = [
    ["B0", "B0"],
    ["B1_30", "B1_30"],
    ["B31_60", "B31_60"],
    ["B61_90", "B61_90"],
    ["B90_PLUS", "B90_PLUS"],
    ["NOT_A_REAL_BUCKET", "B0"],
    [null, "B0"],
    [undefined, "B0"],
  ];

  it.each(cases)("maps backend bucket %s to delinquency bucket %s", (backendBucket, expected) => {
    const [mapped] = lspScheduleToRepaymentInstallments(
      [row({ delinquencyBucket: backendBucket })],
      "acct-1",
    );
    expect(mapped!.delinquencyBucket).toBe(expected);
  });
});

describe("lspScheduleToRepaymentInstallments — field passthrough", () => {
  it("carries id, account/schedule linkage, and amounts through unchanged", () => {
    const [mapped] = lspScheduleToRepaymentInstallments(
      [
        row({
          id: "inst-9",
          installmentNumber: 9,
          dueDate: "2026-09-01",
          principalDue: 7000,
          interestDue: 500,
          installmentAmount: 7500,
          paidAmount: 2500,
          outstandingAmount: 5000,
          daysPastDue: 12,
        }),
      ],
      "acct-42",
    );
    expect(mapped).toMatchObject({
      id: "inst-9",
      accountId: "acct-42",
      scheduleId: "acct-42",
      number: 9,
      dueDate: "2026-09-01",
      principalDue: 7000,
      interestDue: 500,
      installmentAmount: 7500,
      paidAmount: 2500,
      outstandingAmount: 5000,
      dpd: 12,
    });
  });

  it("defaults missing principal, interest, and days-past-due to zero", () => {
    const [mapped] = lspScheduleToRepaymentInstallments(
      [row({ principalDue: undefined, interestDue: undefined, daysPastDue: null })],
      "acct-1",
    );
    expect(mapped).toMatchObject({ principalDue: 0, interestDue: 0, dpd: 0 });
  });
});
