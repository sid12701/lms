import { describe, expect, it } from "vitest";
import { resolveAlertSubjectHref } from "./alert-links";

describe("resolveAlertSubjectHref", () => {
  it("links LOAN_ACCOUNT alerts to the loan application when context carries applicationId", () => {
    expect(
      resolveAlertSubjectHref(
        "LOAN_ACCOUNT",
        "account-uuid",
        "corr-uuid",
        JSON.stringify({ applicationId: "app-uuid" }),
      ),
    ).toBe("/loan-applications/app-uuid");
  });
});
