/**
 * OverviewTab tests — verifies the read-only sections render PII as masked
 * by default (BR-7) and surface the docs / schedule gate state.
 */
import { describe, expect, it } from "vitest";
import { axe } from "vitest-axe";
import { renderWithProviders } from "@/test/utils";
import { OverviewTab } from "./OverviewTab";
import type { LoanApplicationDetail } from "../../types";

const DETAIL: LoanApplicationDetail = {
  application: {
    id: "app-1",
    externalLoanId: "EXT-001",
    borrowerId: "bor-1",
    lspId: "lsp-1",
    productId: "prod-1",
    requestedAmount: 250_000,
    tenureMonths: 12,
    status: "AWAITING_APPROVAL",
    sourceChannel: "UI",
    assignedTo: "user-1",
    createdAt: "2026-05-10T08:00:00.000Z",
    updatedAt: "2026-05-10T09:00:00.000Z",
    invalidatedAt: null,
    invalidReason: null,
  } as unknown as LoanApplicationDetail["application"],
  borrower: {
    id: "bor-1",
    fullName: "Aanya Devi",
    pan: "ABCDE1234F",
    aadhaar: "123456789012",
    mobile: "9876543210",
  } as unknown as LoanApplicationDetail["borrower"],
  lsp: { id: "lsp-1", name: "Acme NBFC" } as unknown as LoanApplicationDetail["lsp"],
  product: { id: "prod-1", name: "Personal Loan A" } as unknown as LoanApplicationDetail["product"],
  account: null,
  docsComplete: false,
  scheduleValid: false,
};

describe("OverviewTab", () => {
  it("renders the loan terms section", () => {
    const { getByText } = renderWithProviders(<OverviewTab detail={DETAIL} />);
    expect(getByText("Loan terms")).toBeInTheDocument();
    expect(getByText(/₹\s*2,50,000/)).toBeInTheDocument();
    expect(getByText("12 months")).toBeInTheDocument();
    expect(getByText("Personal Loan A")).toBeInTheDocument();
  });

  it("masks PII fields by default (BR-7)", () => {
    const { queryByText, getAllByLabelText } = renderWithProviders(
      <OverviewTab detail={DETAIL} />,
    );
    // Cleartext PAN must not be on screen by default
    expect(queryByText("ABCDE1234F")).toBeNull();
    // Each PII field renders a Reveal control
    expect(getAllByLabelText(/Reveal/).length).toBeGreaterThanOrEqual(3);
  });

  it("surfaces both gates with the right state", () => {
    const { getByText, getAllByText } = renderWithProviders(
      <OverviewTab
        detail={{ ...DETAIL, docsComplete: true, scheduleValid: false }}
      />,
    );
    expect(getByText(/Docs complete/)).toBeInTheDocument();
    expect(getAllByText(/Schedule missing/)[0]).toBeInTheDocument();
  });

  it("shows 'Unassigned' when assignedTo is null", () => {
    const detail = {
      ...DETAIL,
      application: { ...DETAIL.application, assignedTo: null },
    };
    const { getByText } = renderWithProviders(<OverviewTab detail={detail} />);
    expect(getByText("Unassigned")).toBeInTheDocument();
  });

  it("has no axe-detectable a11y violations", async () => {
    const { container } = renderWithProviders(<OverviewTab detail={DETAIL} />);
    expect(await axe(container)).toHaveNoViolations();
  });
});
