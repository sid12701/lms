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
  fetchMyLoanPaymentsMock.mockReset().mockResolvedValue([]);
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
});
