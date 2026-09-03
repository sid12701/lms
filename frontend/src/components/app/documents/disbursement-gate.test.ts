import { describe, expect, it } from "vitest";
import { isDisbursementGatePassed } from "./disbursement-gate";

describe("isDisbursementGatePassed", () => {
  it.each(["DISBURSED", "UNDER_REPAYMENT", "CLOSED", "FORECLOSED"] as const)(
    "reports the gate as passed for %s",
    (status) => {
      expect(isDisbursementGatePassed(status)).toBe(true);
    },
  );

  it.each([
    "INITIALIZED",
    "AWAITING_APPROVAL",
    "APPROVED_PENDING_DISBURSAL",
    "DISBURSEMENT_RETRY",
    "REJECTED",
    "INVALID",
  ] as const)("reports the gate as still ahead for %s", (status) => {
    expect(isDisbursementGatePassed(status)).toBe(false);
  });

  /**
   * Callers without a status must get the pre-disbursement wording, which
   * describes a real gate, rather than asserting one has been cleared.
   */
  it("defaults to not-passed when the status is unknown", () => {
    expect(isDisbursementGatePassed(undefined)).toBe(false);
  });
});
