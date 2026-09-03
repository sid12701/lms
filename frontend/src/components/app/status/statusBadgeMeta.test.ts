import { describe, expect, it } from "vitest";
import { resolveStatusMeta } from "./statusBadgeMeta";

describe("delinquency is carried by text, not colour alone", () => {
  /**
   * DESIGN.md's "Never Colour Alone" rule: colour + icon + text. The tone flip
   * to danger is colour; without a label change a screen-reader user hears the
   * same thing for a current loan and one well past due.
   */
  it("names the days past due when a loan under repayment is delinquent", () => {
    const meta = resolveStatusMeta("UNDER_REPAYMENT", {
      delinquency: { maxDaysPastDue: 25, overdueInstallmentCount: 1 },
    });
    expect(meta.intent).toBe("danger");
    expect(meta.label).toBe("Under repayment · 25d past due");
  });

  it("falls back to the overdue count when days past due is absent", () => {
    const meta = resolveStatusMeta("UNDER_REPAYMENT", {
      delinquency: { maxDaysPastDue: 0, overdueInstallmentCount: 2 },
    });
    expect(meta.intent).toBe("danger");
    expect(meta.label).toBe("Under repayment · 2 overdue");
  });

  it("leaves a healthy loan's label untouched", () => {
    expect(resolveStatusMeta("UNDER_REPAYMENT", { delinquency: null })).toEqual({
      label: "Under repayment",
      intent: "success",
    });
    expect(resolveStatusMeta("UNDER_REPAYMENT").label).toBe("Under repayment");
  });
});
