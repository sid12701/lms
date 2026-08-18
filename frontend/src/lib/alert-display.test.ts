import { describe, it, expect } from "vitest";
import {
  humanizeAlertTitle,
  alertSubjectTypeLabel,
  lspBoundViolationLabel,
} from "@/lib/alert-display";

describe("alert-display", () => {
  it("humanizes backend delinquency bucket titles", () => {
    expect(humanizeAlertTitle("Delinquency bucket DPD_1_30")).toBe("Delinquency · 1–30 DPD");
    expect(humanizeAlertTitle("Delinquency bucket DPD_90_PLUS")).toBe("Delinquency · 90+ DPD");
  });

  it("passes through non-delinquency, non-violation titles unchanged", () => {
    expect(humanizeAlertTitle("Rate limit breach")).toBe("Rate limit breach");
  });

  it("labels alert subject types for display", () => {
    expect(alertSubjectTypeLabel("LOAN_APPLICATION")).toBe("Loan application");
    expect(alertSubjectTypeLabel("REPORT_REQUEST")).toBe("Report request");
  });

  it("humanizes LSP bound violation titles for every backend source", () => {
    // ScheduleViolationType
    expect(humanizeAlertTitle("LSP bound violation: SCHEDULE_CHAIN_BROKEN")).toBe(
      "LSP bound violation · Schedule chain broken",
    );
    expect(humanizeAlertTitle("LSP bound violation: SCHEDULE_GENERIC")).toBe(
      "LSP bound violation · Schedule validation failed",
    );
    // ForeclosureViolationType
    expect(humanizeAlertTitle("LSP bound violation: QUOTE_NOT_ACTIVE")).toBe(
      "LSP bound violation · Foreclosure quote not active",
    );
    // Ad-hoc (not backed by an enum)
    expect(humanizeAlertTitle("LSP bound violation: MISSING_LOAN_ACCOUNT")).toBe(
      "LSP bound violation · Loan account not available for disbursement",
    );
    expect(humanizeAlertTitle("LSP bound violation: HOLDER_NAME_SOFT_MISMATCH")).toBe(
      "LSP bound violation · Account holder name mismatch",
    );
  });

  it("degrades an unmapped violation code to Unknown (raw) rather than raw prose", () => {
    // Regression guard for F-07/14/19: a violation type this map has not
    // caught up with must stay visibly unmapped, never read as if the raw
    // enum spelling were normal copy.
    expect(humanizeAlertTitle("LSP bound violation: SOME_FUTURE_VIOLATION")).toBe(
      "LSP bound violation · Unknown (SOME_FUTURE_VIOLATION)",
    );
    expect(lspBoundViolationLabel("SOME_FUTURE_VIOLATION")).toBe("Unknown (SOME_FUTURE_VIOLATION)");
  });

  it("covers every ScheduleViolationType and ForeclosureViolationType member with a mapped label", () => {
    // Kept in lockstep with the backend enums (ScheduleViolationType,
    // ForeclosureViolationType) so a member missing here fails loudly instead
    // of silently degrading to "Unknown" in production.
    const scheduleViolationTypes = [
      "SCHEDULE_OPENING_MISMATCH",
      "SCHEDULE_CHAIN_BROKEN",
      "SCHEDULE_FINAL_NONZERO",
      "SCHEDULE_ROW_RECONCILE_FAILED",
      "SCHEDULE_PRINCIPAL_NOT_CLOSED",
      "SCHEDULE_INSTALLMENT_COUNT_MISMATCH",
      "SCHEDULE_FIRST_DUE_OUT_OF_WINDOW",
      "SCHEDULE_CADENCE_VIOLATION",
      "SCHEDULE_HORIZON_EXCEEDED",
      "SCHEDULE_INTEREST_ROW_MISMATCH",
      "SCHEDULE_INTEREST_TOTAL_MISMATCH",
      "SCHEDULE_GENERIC",
    ];
    const foreclosureViolationTypes = [
      "QUOTE_NOT_ACTIVE",
      "QUOTE_OWNERSHIP_MISMATCH",
      "SETTLEMENT_DATE_MISMATCH",
      "LOAN_ACCOUNT_NOT_DISBURSED",
      "SETTLEMENT_INCOMPLETE",
      "REFERENCE_MISSING",
      "FORECLOSURE_GENERIC",
    ];
    const adHocViolationTypes = [
      "MISSING_LOAN_ACCOUNT",
      "PROVIDER_BUSINESS_DECLINE",
      "DISBURSEMENT_PENDING_RECONCILIATION",
      "BANK_DETAIL_MISMATCH",
      "HOLDER_NAME_SOFT_MISMATCH",
    ];
    for (const code of [
      ...scheduleViolationTypes,
      ...foreclosureViolationTypes,
      ...adHocViolationTypes,
    ]) {
      expect(lspBoundViolationLabel(code)).not.toMatch(/^Unknown/);
      expect(lspBoundViolationLabel(code)).not.toBe(code);
    }
  });
});
