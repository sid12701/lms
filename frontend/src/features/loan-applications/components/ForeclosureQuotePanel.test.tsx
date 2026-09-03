import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { renderWithProviders } from "@/test/utils";
import type { LoanApplicationDetail, LoanForeclosureQuote } from "../types";
import { ForeclosureQuotePanel } from "./ForeclosureQuotePanel";

const apiMocks = vi.hoisted(() => ({
  fetchForeclosureQuotes: vi.fn(),
  requestForeclosureQuote: vi.fn(),
  executeForeclosureQuote: vi.fn(),
}));

vi.mock("sonner", () => ({
  toast: {
    success: vi.fn(),
    error: vi.fn(),
  },
}));

vi.mock("../api-detail", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../api-detail")>()),
  fetchForeclosureQuotes: apiMocks.fetchForeclosureQuotes,
  requestForeclosureQuote: apiMocks.requestForeclosureQuote,
  executeForeclosureQuote: apiMocks.executeForeclosureQuote,
}));

const DETAIL: LoanApplicationDetail = {
  application: {
    id: "app-1",
    externalLoanId: "EXT-1",
    borrowerId: "borrower-1",
    lspId: "lsp-1",
    productId: "product-1",
    requestedAmount: 100_000,
    tenureMonths: 12,
    status: "UNDER_REPAYMENT",
    sourceChannel: "API",
    createdAt: "2026-06-01T00:00:00.000Z",
    updatedAt: "2026-06-01T00:00:00.000Z",
    invalidatedAt: null,
    invalidReason: null,
  } as LoanApplicationDetail["application"],
  borrower: { id: "borrower-1", fullName: "Asha Devi" } as LoanApplicationDetail["borrower"],
  lsp: {
    id: "lsp-1",
    code: "APEX",
    name: "Apex Finance",
    status: "ACTIVE",
    createdAt: "2026-06-01T00:00:00.000Z",
  },
  product: {
    id: "product-1",
    code: "PL",
    name: "Personal Loan",
    status: "ACTIVE",
    createdAt: "2026-06-01T00:00:00.000Z",
    principalMin: 10_000,
    principalMax: 500_000,
    interestRatePct: 18,
    processingFeePct: 2,
    tenureMinMonths: 6,
    tenureMaxMonths: 24,
  },
  account: {
    id: "account-1",
    applicationId: "app-1",
    accountNumber: "LN-1",
    accountStatus: "DISBURSED",
    principal: 100_000,
    tenureMonths: 12,
    approvedAt: "2026-06-01T00:00:00.000Z",
    createdAt: "2026-06-01T00:00:00.000Z",
    closedAt: null,
    closureReason: null,
  },
  docsComplete: true,
  scheduleValid: true,
  accountDelinquency: null,
  interestRate: null,
};

const QUOTE: LoanForeclosureQuote = {
  id: "quote-1",
  loanAccountId: "account-1",
  version: 3,
  requestedByUsername: "ops.admin",
  executedByUsername: null,
  effectiveDate: "2026-06-22",
  outstandingPrincipal: 90_000,
  outstandingInterest: 1_250,
  settlementAmount: 91_250,
  status: "ACTIVE",
  executedAt: null,
  createdAt: "2026-06-22T08:00:00.000Z",
  updatedAt: "2026-06-22T08:00:00.000Z",
};

describe("<ForeclosureQuotePanel />", () => {
  beforeEach(() => {
    apiMocks.fetchForeclosureQuotes.mockReset();
    apiMocks.requestForeclosureQuote.mockReset();
    apiMocks.executeForeclosureQuote.mockReset();
  });

  it("requests a quote for the selected effective date", async () => {
    apiMocks.fetchForeclosureQuotes.mockResolvedValue([]);
    apiMocks.requestForeclosureQuote.mockResolvedValue(QUOTE);
    const user = userEvent.setup();

    renderWithProviders(<ForeclosureQuotePanel detail={DETAIL} />);

    expect(screen.getByRole("button", { name: /foreclosure/i })).toHaveAttribute(
      "aria-expanded",
      "false",
    );
    expect(screen.queryByLabelText("Settlement reference")).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: /foreclosure/i }));

    await screen.findByText("No foreclosure quote has been generated for this loan yet.");
    await user.click(screen.getByRole("button", { name: "Request quote" }));

    await waitFor(() => {
      expect(apiMocks.requestForeclosureQuote).toHaveBeenCalledWith(
        "app-1",
        expect.objectContaining({ effectiveDate: expect.stringMatching(/^\d{4}-\d{2}-\d{2}$/) }),
      );
    });
  });

  it("renders the latest active quote expanded and executes it with a settlement reference", async () => {
    apiMocks.fetchForeclosureQuotes.mockResolvedValue([QUOTE]);
    apiMocks.executeForeclosureQuote.mockResolvedValue({ ...QUOTE, status: "EXECUTED" });
    const onExecuted = vi.fn();
    const user = userEvent.setup();

    renderWithProviders(<ForeclosureQuotePanel detail={DETAIL} onExecuted={onExecuted} />);

    expect(await screen.findByText("Quote version 3")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /foreclosure/i })).toHaveAttribute(
      "aria-expanded",
      "true",
    );
    const execute = screen.getByRole("button", { name: "Execute quote" });
    expect(execute).toBeDisabled();

    await user.type(screen.getByLabelText("Settlement reference"), "UTR123");
    await user.click(execute);

    await waitFor(() => {
      expect(apiMocks.executeForeclosureQuote).toHaveBeenCalledWith(
        "app-1",
        expect.objectContaining({
          quoteId: "quote-1",
          settlementDate: "2026-06-22",
          reference: "UTR123",
        }),
      );
    });
    expect(onExecuted).toHaveBeenCalledTimes(1);
  });
});
