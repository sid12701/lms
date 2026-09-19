import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { LoanServicingPanel } from "./LoanServicingPanel";

const fetchMyLoanRepaymentScheduleMock = vi.fn();
const fetchMyLoanPaymentsMock = vi.fn();

vi.mock("../api", () => ({
  fetchMyLoanRepaymentSchedule: (...args: unknown[]) => fetchMyLoanRepaymentScheduleMock(...args),
  fetchMyLoanPayments: (...args: unknown[]) => fetchMyLoanPaymentsMock(...args),
}));

function renderPanel(loanAccountId = "acct-1") {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });

  function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  }

  return render(
    <Wrapper>
      <LoanServicingPanel loanAccountId={loanAccountId} />
    </Wrapper>,
  );
}

beforeEach(() => {
  fetchMyLoanRepaymentScheduleMock.mockReset().mockResolvedValue([]);
  fetchMyLoanPaymentsMock.mockReset().mockResolvedValue({
    items: [],
    totalCount: 0,
    truncated: false,
  });
});

describe("LoanServicingPanel", () => {
  it("loads schedule and payments for the keyed loan account", async () => {
    fetchMyLoanRepaymentScheduleMock.mockResolvedValue([
      {
        id: "inst-1",
        installmentNumber: 1,
        dueDate: "2026-08-01",
        principalDue: 800,
        interestDue: 200,
        installmentAmount: 1000,
        paidAmount: 0,
        outstandingAmount: 1000,
        status: "DUE",
        daysPastDue: null,
        delinquencyBucket: "B0",
      },
    ]);

    renderPanel("acct-42");

    expect(await screen.findByText("Repayment schedule")).toBeInTheDocument();
    expect(fetchMyLoanRepaymentScheduleMock).toHaveBeenCalledWith("acct-42");
    expect(fetchMyLoanPaymentsMock).toHaveBeenCalledWith("acct-42");
    expect(screen.getByText("Due")).toBeInTheDocument();
  });

  it("renders payment rows from the paged history result", async () => {
    fetchMyLoanPaymentsMock.mockResolvedValue({
      items: [
        {
          id: "pay-1",
          amount: 1000,
          paymentDate: "2026-08-05",
          channel: "UPI",
          reference: "REF-1",
          status: "RECEIVED",
          createdAt: "2026-08-05T10:00:00.000Z",
        },
      ],
      totalCount: 1,
      truncated: false,
    });

    renderPanel("acct-7");

    expect(await screen.findByText("REF-1")).toBeInTheDocument();
    expect(screen.queryByText(/most recent of/)).not.toBeInTheDocument();
  });

  it("discloses truncation instead of pretending the list is complete", async () => {
    fetchMyLoanPaymentsMock.mockResolvedValue({
      items: [
        {
          id: "pay-1",
          amount: 1000,
          paymentDate: "2026-08-05",
          channel: "UPI",
          reference: "REF-1",
          status: "RECEIVED",
          createdAt: "2026-08-05T10:00:00.000Z",
        },
      ],
      totalCount: 9999,
      truncated: true,
    });

    renderPanel("acct-8");

    expect(
      await screen.findByText(/Showing the 1 most recent of 9999 payments/),
    ).toBeInTheDocument();
  });
});
