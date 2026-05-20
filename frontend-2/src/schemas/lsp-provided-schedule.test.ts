import { describe, expect, it } from "vitest";
import {
  LspProvidedInstallment,
  LspProvidedSchedule,
  RECONCILE_TOLERANCE,
} from "./lsp-provided-schedule";

const UUID = "550e8400-e29b-41d4-a716-446655440000";

function row(n: number, dueDate: string, principal: number, interest: number) {
  return {
    number: n,
    dueDate,
    principalDue: principal,
    interestDue: interest,
    installmentAmount: principal + interest,
  };
}

function threeMonths() {
  return {
    accountId: UUID,
    approvedPrincipal: 30000,
    approvedTenureMonths: 3,
    installments: [
      row(1, "2026-06-01", 10000, 500),
      row(2, "2026-07-01", 10000, 500),
      row(3, "2026-08-01", 10000, 500),
    ],
  };
}

describe("LspProvidedInstallment", () => {
  it("accepts a row", () => {
    expect(LspProvidedInstallment.safeParse(row(1, "2026-06-01", 1, 0)).success).toBe(true);
  });

  it("rejects negative principalDue", () => {
    expect(
      LspProvidedInstallment.safeParse({
        number: 1,
        dueDate: "2026-06-01",
        principalDue: -1,
        interestDue: 0,
        installmentAmount: -1,
      }).success,
    ).toBe(false);
  });
});

describe("LspProvidedSchedule (BR-11)", () => {
  it("RECONCILE_TOLERANCE is exactly 0.01", () => {
    expect(RECONCILE_TOLERANCE).toBe(0.01);
  });

  it("accepts a fully-balanced 3-month schedule", () => {
    expect(LspProvidedSchedule.safeParse(threeMonths()).success).toBe(true);
  });

  it("BR-11(1): rejects installment count != approvedTenureMonths", () => {
    const s = threeMonths();
    s.installments.pop();
    const r = LspProvidedSchedule.safeParse(s);
    expect(r.success).toBe(false);
    if (!r.success) {
      expect(r.error.issues.some((i) => /expected 3 installments/.test(i.message))).toBe(true);
    }
  });

  it("BR-11(2a): rejects gap in numbering (1, 3, ...)", () => {
    const s = threeMonths();
    s.installments[1] = { ...s.installments[1]!, number: 3 };
    const r = LspProvidedSchedule.safeParse(s);
    expect(r.success).toBe(false);
  });

  it("BR-11(2b): rejects duplicate numbering (1, 1, 3)", () => {
    const s = threeMonths();
    s.installments[1] = { ...s.installments[1]!, number: 1 };
    expect(LspProvidedSchedule.safeParse(s).success).toBe(false);
  });

  it("BR-11(3a): rejects equal due dates", () => {
    const s = threeMonths();
    s.installments[1] = { ...s.installments[1]!, dueDate: "2026-06-01" };
    expect(LspProvidedSchedule.safeParse(s).success).toBe(false);
  });

  it("BR-11(3b): rejects reversed due dates", () => {
    const s = threeMonths();
    s.installments[2] = { ...s.installments[2]!, dueDate: "2026-05-01" };
    expect(LspProvidedSchedule.safeParse(s).success).toBe(false);
  });

  it("BR-11(4-over): rejects per-row sum exceeding tolerance (over)", () => {
    const s = threeMonths();
    s.installments[0] = { ...s.installments[0]!, installmentAmount: 10500 + 0.02 };
    expect(LspProvidedSchedule.safeParse(s).success).toBe(false);
  });

  it("BR-11(4-under): rejects per-row sum under tolerance", () => {
    const s = threeMonths();
    s.installments[0] = { ...s.installments[0]!, installmentAmount: 10500 - 0.02 };
    expect(LspProvidedSchedule.safeParse(s).success).toBe(false);
  });

  it("BR-11(4-boundary-within): accepts row diff exactly at ±0.01", () => {
    const s = threeMonths();
    s.installments[0] = { ...s.installments[0]!, installmentAmount: 10500 + 0.01 };
    expect(LspProvidedSchedule.safeParse(s).success).toBe(true);
  });

  it("BR-11(5-over): rejects sum(principalDue) > approvedPrincipal beyond tolerance", () => {
    const s = threeMonths();
    s.installments[0] = {
      ...s.installments[0]!,
      principalDue: 10000.5,
      installmentAmount: 10500.5,
    };
    expect(LspProvidedSchedule.safeParse(s).success).toBe(false);
  });

  it("BR-11(5-under): rejects sum(principalDue) < approvedPrincipal beyond tolerance", () => {
    const s = threeMonths();
    s.installments[0] = {
      ...s.installments[0]!,
      principalDue: 9999.5,
      installmentAmount: 10499.5,
    };
    expect(LspProvidedSchedule.safeParse(s).success).toBe(false);
  });

  it("BR-11(5-boundary): accepts when total off by ≤ 0.01", () => {
    const s = threeMonths();
    s.installments[0] = {
      ...s.installments[0]!,
      principalDue: 10000.01,
      installmentAmount: 10500.01,
    };
    expect(LspProvidedSchedule.safeParse(s).success).toBe(true);
  });

  it("rejects empty installments", () => {
    expect(
      LspProvidedSchedule.safeParse({
        accountId: UUID,
        approvedPrincipal: 1000,
        approvedTenureMonths: 1,
        installments: [],
      }).success,
    ).toBe(false);
  });
});
