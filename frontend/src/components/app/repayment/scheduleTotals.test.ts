import { describe, expect, it, vi } from "vitest";
import type { RepaymentInstallment } from "@/schemas/loan-account";
import { computeScheduleTotals } from "./scheduleTotals";

function installment(
  overrides: Partial<RepaymentInstallment> &
    Pick<RepaymentInstallment, "dueDate" | "outstandingAmount">,
): RepaymentInstallment {
  return {
    id: "11111111-1111-4111-8111-aaaaaaaaaaaa",
    accountId: "22222222-2222-4222-8222-222222222222",
    scheduleId: "33333333-3333-4333-8333-333333333333",
    number: 1,
    principalDue: 8000,
    interestDue: 2000,
    installmentAmount: 10000,
    paidAmount: 0,
    dpd: 0,
    delinquencyBucket: "B0",
    status: "DUE",
    ...overrides,
  };
}

describe("computeScheduleTotals", () => {
  it("separates remaining book from amount due on or before asOf", () => {
    const rows = [
      installment({ dueDate: "2026-05-01", outstandingAmount: 5000, installmentAmount: 10000 }),
      installment({
        id: "11111111-1111-4111-8111-bbbbbbbbbbbb",
        number: 2,
        dueDate: "2026-06-01",
        outstandingAmount: 10000,
        installmentAmount: 10000,
      }),
      installment({
        id: "11111111-1111-4111-8111-cccccccccccc",
        number: 3,
        dueDate: "2026-07-01",
        outstandingAmount: 10000,
        installmentAmount: 10000,
      }),
    ];

    const totals = computeScheduleTotals(rows, "2026-06-15");

    expect(totals.totalInstallmentAmount).toBe(30000);
    expect(totals.totalOutstanding).toBe(25000);
    expect(totals.outstandingAsOfToday).toBe(15000);
  });

  it("counts an installment due exactly on asOf as already due", () => {
    const rows = [installment({ dueDate: "2026-06-15", outstandingAmount: 7000 })];

    expect(computeScheduleTotals(rows, "2026-06-15").outstandingAsOfToday).toBe(7000);
    expect(computeScheduleTotals(rows, "2026-06-14").outstandingAsOfToday).toBe(0);
  });

  it("returns zeroed totals for an empty schedule", () => {
    expect(computeScheduleTotals([], "2026-06-15")).toEqual({
      totalInstallmentAmount: 0,
      totalOutstanding: 0,
      outstandingAsOfToday: 0,
    });
  });

  it("defaults asOf to the viewer's local calendar date, not the UTC date", () => {
    // 02:00 IST is still the previous day in UTC. Pinning the clock there
    // guarantees a regression to `toISOString()` fails this test.
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-08-04T02:00:00+05:30"));
    try {
      const localToday = new Date();
      const iso = `${localToday.getFullYear()}-${String(localToday.getMonth() + 1).padStart(2, "0")}-${String(localToday.getDate()).padStart(2, "0")}`;
      const rows = [installment({ dueDate: iso, outstandingAmount: 4200 })];

      expect(computeScheduleTotals(rows).outstandingAsOfToday).toBe(4200);
    } finally {
      vi.useRealTimers();
    }
  });
});
