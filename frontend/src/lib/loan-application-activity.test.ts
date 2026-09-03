import { describe, expect, it } from "vitest";
import {
  formatActivitySummary,
  formatActivityType,
  loanStatusLabel,
} from "./loan-application-activity";

describe("loan-application-activity", () => {
  describe("loanStatusLabel", () => {
    it("returns lifecycle labels for known statuses", () => {
      expect(loanStatusLabel("INITIALIZED")).toBe("Initialized");
      expect(loanStatusLabel("UNDER_REPAYMENT")).toBe("Under repayment");
    });

    it("surfaces drift as Unknown (raw)", () => {
      expect(loanStatusLabel("KYC_PENDING")).toBe("Unknown (KYC_PENDING)");
    });
  });

  describe("formatActivityType", () => {
    it("maps known activity types", () => {
      expect(formatActivityType("STATUS_TRANSITION")).toBe("Status transition");
      expect(formatActivityType("INTAKE_CAPTURED")).toBe("Intake captured");
    });

    it("surfaces unknown types as Unknown (raw)", () => {
      expect(formatActivityType("MYSTERY_EVENT")).toBe("Unknown (MYSTERY_EVENT)");
    });
  });

  describe("formatActivitySummary", () => {
    it("humanises status-transition summaries", () => {
      expect(
        formatActivitySummary("Moved from INITIALIZED to UNDER_REPAYMENT", "STATUS_TRANSITION"),
      ).toBe("Initialized → Under repayment");
    });

    it("passes through non-transition summaries unchanged", () => {
      expect(formatActivitySummary("Application captured from LSP_API", "INTAKE_CAPTURED")).toBe(
        "Application captured from LSP_API",
      );
    });

    it("falls back to the raw summary when the pattern does not match", () => {
      expect(formatActivitySummary("Custom note", "STATUS_TRANSITION")).toBe("Custom note");
    });
  });
});
