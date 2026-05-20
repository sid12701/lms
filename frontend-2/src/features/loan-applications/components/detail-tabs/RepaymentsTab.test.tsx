/**
 * RepaymentsTab tests — hook module is mocked.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { axe } from "vitest-axe";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/test/utils";
import { DensityProvider } from "@/app/providers";
import type { ReactElement } from "react";
import type { PaymentTransaction } from "@/types";

function renderWithDensity(ui: ReactElement) {
  return renderWithProviders(<DensityProvider>{ui}</DensityProvider>);
}

const useRepaymentsMock = vi.fn();

vi.mock("../../hooks/useLoanApplicationRepayments", () => ({
  useLoanApplicationRepayments: () => useRepaymentsMock(),
  loanApplicationRepaymentsQueryKey: (id: string) => ["loan-application", id, "repayments"],
}));

import { RepaymentsTab } from "./RepaymentsTab";

function payment(overrides: Partial<PaymentTransaction> = {}): PaymentTransaction {
  return {
    id: "00000000-0000-4000-8000-000000000001",
    accountId: "11111111-1111-4111-8111-111111111111",
    installmentId: "22222222-2222-4222-8222-222222222222",
    channel: "BANK_TRANSFER",
    amount: 12_500,
    postedAt: "2026-05-08T10:00:00.000Z",
    postedBy: "33333333-3333-4333-8333-333333333333",
    idempotencyKey: "idem-1",
    allocation: [],
    ...overrides,
  };
}

beforeEach(() => {
  useRepaymentsMock.mockReset();
});

afterEach(() => {
  vi.clearAllMocks();
});

describe("RepaymentsTab", () => {
  it("renders the skeleton while pending", () => {
    useRepaymentsMock.mockReturnValue({
      isPending: true,
      isError: false,
      data: undefined,
      refetch: vi.fn(),
    });
    const { container } = renderWithDensity(<RepaymentsTab applicationId="app-1" />);
    expect(container.querySelector('[data-slot="table-skeleton"]')).not.toBeNull();
  });

  it("renders the empty state when no payments are posted", () => {
    useRepaymentsMock.mockReturnValue({
      isPending: false,
      isError: false,
      data: { payments: [] },
      refetch: vi.fn(),
    });
    renderWithDensity(<RepaymentsTab applicationId="app-1" />);
    expect(screen.getByText(/No payments posted yet/i)).toBeInTheDocument();
  });

  it("renders payment rows with amount + channel", () => {
    useRepaymentsMock.mockReturnValue({
      isPending: false,
      isError: false,
      data: {
        payments: [
          payment({ id: "p-1", amount: 12_500, channel: "BANK_TRANSFER" }),
          payment({ id: "p-2", amount: 9_750, channel: "UPI" }),
        ],
      },
      refetch: vi.fn(),
    });
    renderWithDensity(<RepaymentsTab applicationId="app-1" />);
    // Use getAllByText to be robust to formatting prefixes / unicode.
    expect(screen.getAllByText(/12,500/).length).toBeGreaterThan(0);
    expect(screen.getAllByText(/9,750/).length).toBeGreaterThan(0);
    expect(screen.getByText(/BANK_TRANSFER/)).toBeInTheDocument();
    expect(screen.getByText(/^UPI$/)).toBeInTheDocument();
  });

  it("renders the error state with retry", async () => {
    const refetch = vi.fn();
    useRepaymentsMock.mockReturnValue({
      isPending: false,
      isError: true,
      error: new Error("boom"),
      data: undefined,
      refetch,
    });
    const user = userEvent.setup();
    renderWithDensity(<RepaymentsTab applicationId="app-1" />);
    expect(screen.getByText(/Couldn't load repayments/i)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: /try again/i }));
    expect(refetch).toHaveBeenCalledTimes(1);
  });

  it("is axe-clean when populated", async () => {
    useRepaymentsMock.mockReturnValue({
      isPending: false,
      isError: false,
      data: {
        payments: [payment({ id: "p-1" })],
      },
      refetch: vi.fn(),
    });
    const { container } = renderWithDensity(<RepaymentsTab applicationId="app-1" />);
    expect(await axe(container)).toHaveNoViolations();
  });
});
