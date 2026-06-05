/**
 * OverviewTab tests — verifies the read-only sections render the backend's
 * pre-masked PII values directly (no reveal-on-demand flow) and surface
 * the docs / schedule gate state. See `docs/gap-fixes.md` § Gap #1.
 */
import { describe, expect, it } from "vitest";
import { axe } from "vitest-axe";
import { renderWithProviders } from "@/test/utils";
import { OverviewTab } from "./OverviewTab";
import type { LoanApplicationDetail } from "../../types";
import type { BorrowerDetail } from "@/features/borrowers/types";

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
    createdAt: "2026-05-10T08:00:00.000Z",
    updatedAt: "2026-05-10T09:00:00.000Z",
    invalidatedAt: null,
    invalidReason: null,
  } as unknown as LoanApplicationDetail["application"],
  borrower: {
    id: "bor-1",
    fullName: "Aanya Devi",
    pan: "ABXXXXX34F",
    aadhaar: "XXXXXXXX9012",
    mobile: "9876543210",
  } as unknown as LoanApplicationDetail["borrower"],
  lsp: { id: "lsp-1", name: "Acme NBFC" } as unknown as LoanApplicationDetail["lsp"],
  product: { id: "prod-1", name: "Personal Loan A" } as unknown as LoanApplicationDetail["product"],
  account: null,
  docsComplete: false,
  scheduleValid: false,
  accountDelinquency: null,
};

describe("OverviewTab", () => {
  it("renders the loan terms section", () => {
    const { getByText } = renderWithProviders(<OverviewTab detail={DETAIL} />);
    expect(getByText("Loan terms")).toBeInTheDocument();
    expect(getByText(/₹\s*2,50,000/)).toBeInTheDocument();
    expect(getByText("12 months")).toBeInTheDocument();
    expect(getByText("Personal Loan A")).toBeInTheDocument();
  });

  it("renders PII as the backend-supplied masked values (no reveal control)", () => {
    const { queryByText, getByText, container } = renderWithProviders(
      <OverviewTab detail={DETAIL} />,
    );
    // Cleartext aadhaar/PAN must never appear — only the masked forms.
    expect(queryByText("123412341234")).toBeNull();
    expect(queryByText("ABCDE1234F")).toBeNull();
    // The masked values surface in the borrower section.
    expect(getByText("ABXXXXX34F")).toBeInTheDocument();
    expect(getByText("XXXXXXXX9012")).toBeInTheDocument();
    // No reveal-on-demand controls remain in the DOM.
    expect(container.querySelector("[data-slot='pii-reveal-trigger']")).toBeNull();
  });

  it("surfaces both gates with the right state", () => {
    const { getByText, getAllByText } = renderWithProviders(
      <OverviewTab detail={{ ...DETAIL, docsComplete: true, scheduleValid: false }} />,
    );
    expect(getByText(/Docs complete/)).toBeInTheDocument();
    expect(getAllByText(/Schedule missing/)[0]).toBeInTheDocument();
  });

  it("has no axe-detectable a11y violations", async () => {
    const { container } = renderWithProviders(<OverviewTab detail={DETAIL} />);
    expect(await axe(container)).toHaveNoViolations();
  });

  // Gap #20: when the borrower-admin projection is available, OverviewTab
  // augments the borrower section with totals (delinquency + lifetime
  // disbursed) and a Borrower visibility section listing every LSP that
  // can see this borrower. Aadhaar is still rendered from the backend's
  // masked value.
  describe("with parallel borrower-admin projection (Gap #20)", () => {
    const BORROWER_DETAIL: BorrowerDetail = {
      borrower: {
        ...DETAIL.borrower,
        fullName: "Aanya Devi (admin projection)",
      } as unknown as BorrowerDetail["borrower"],
      visibleLsps: [
        { id: "lsp-1", name: "Acme NBFC" },
        { id: "lsp-2", name: "Beta NBFC" },
      ],
      totals: {
        openApplicationsCount: 2,
        closedApplicationsCount: 1,
        lifetimeDisbursedAmount: 480_000,
        activeOverdueAmount: 12_500,
      },
    };

    it("prefers the fuller borrower projection when supplied", () => {
      const { getByText, queryByText } = renderWithProviders(
        <OverviewTab detail={DETAIL} borrowerDetail={BORROWER_DETAIL} />,
      );
      expect(getByText("Aanya Devi (admin projection)")).toBeInTheDocument();
      // Embedded thin name is replaced.
      expect(queryByText("Aanya Devi")).toBeNull();
    });

    it("renders activeOverdueAmount + open/closed/lifetime totals", () => {
      const { getByText } = renderWithProviders(
        <OverviewTab detail={DETAIL} borrowerDetail={BORROWER_DETAIL} />,
      );
      expect(getByText(/₹\s*12,500/)).toBeInTheDocument();
      expect(getByText("2")).toBeInTheDocument(); // openApplicationsCount
      expect(getByText("1")).toBeInTheDocument(); // closedApplicationsCount
      expect(getByText(/₹\s*4,80,000/)).toBeInTheDocument();
    });

    it("lists every visible LSP", () => {
      const { getByText } = renderWithProviders(
        <OverviewTab detail={DETAIL} borrowerDetail={BORROWER_DETAIL} />,
      );
      expect(getByText("Borrower visibility")).toBeInTheDocument();
      expect(getByText("Beta NBFC")).toBeInTheDocument();
    });

    it("falls back to the embedded thin projection when borrowerDetail is null", () => {
      const { getByText } = renderWithProviders(
        <OverviewTab detail={DETAIL} borrowerDetail={null} />,
      );
      expect(getByText("Aanya Devi")).toBeInTheDocument();
    });
  });
});
