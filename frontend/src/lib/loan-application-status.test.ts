import { describe, expect, it } from "vitest";
import {
  BACKEND_ALLOWED_TRANSITIONS,
  LOAN_APPLICATION_STATUSES,
  isLoanApplicationStatus,
  parseLoanApplicationStatus,
  unknownLoanApplicationStatusLabel,
} from "@/lib/loan-application-status";

describe("loan-application-status", () => {
  it("defines exactly 10 canonical statuses", () => {
    expect(LOAN_APPLICATION_STATUSES).toHaveLength(10);
  });

  it("recognises backend enum values", () => {
    expect(isLoanApplicationStatus("INITIALIZED")).toBe(true);
    expect(isLoanApplicationStatus("KYC_PENDING")).toBe(false);
  });

  it("returns null for unknown API values instead of folding", () => {
    expect(parseLoanApplicationStatus("KYC_PENDING")).toBeNull();
    expect(parseLoanApplicationStatus("AWAITING_APPROVAL")).toBe("AWAITING_APPROVAL");
  });

  it("formats unknown status labels for loud badges", () => {
    expect(unknownLoanApplicationStatusLabel("KYC_PENDING")).toBe("Unknown (KYC_PENDING)");
  });

  it("matches backend transition matrix shape", () => {
    expect(BACKEND_ALLOWED_TRANSITIONS.INITIALIZED).toEqual(["AWAITING_APPROVAL", "INVALID"]);
    expect(BACKEND_ALLOWED_TRANSITIONS.REJECTED).toEqual([]);
  });
});
