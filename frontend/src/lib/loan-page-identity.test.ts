import { describe, it, expect } from "vitest";
import { formatLoanDocumentTitle, resolveLoanPageIdentity } from "@/lib/loan-page-identity";

describe("loan-page-identity", () => {
  it("prefers external loan id, then borrower name, then short id", () => {
    expect(
      resolveLoanPageIdentity({
        applicationId: "11111111-1111-4111-8111-111111111111",
        externalLoanId: "LSP-9001",
        borrowerName: "A•••a Devi",
      }),
    ).toBe("LSP-9001");

    expect(
      resolveLoanPageIdentity({
        applicationId: "11111111-1111-4111-8111-111111111111",
        externalLoanId: null,
        borrowerName: "A•••a Devi",
      }),
    ).toBe("A•••a Devi");

    expect(
      resolveLoanPageIdentity({
        applicationId: "11111111-1111-4111-8111-111111111111",
      }),
    ).toBe("11111111");
  });

  it("formats document titles with the product organization name", () => {
    expect(formatLoanDocumentTitle("LSP-9001")).toBe("LSP-9001 · Bhawana Capital");
  });
});
