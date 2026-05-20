/**
 * LoansTab tests — hook module is mocked.
 *
 * Covers: loading / error / empty / populated states, density switch at
 * 12 rows, row click navigation, and axe-clean a11y on the populated
 * state.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { axe } from "vitest-axe";
import userEvent from "@testing-library/user-event";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "@/test/utils";
import type { UseQueryResult } from "@tanstack/react-query";
import type { ReactElement } from "react";
import type { BorrowerLoanRow, BorrowerLoansResponse } from "../../types";

const useLoansMock = vi.fn<[string], UseQueryResult<BorrowerLoansResponse, Error>>();

vi.mock("../../hooks/useBorrowerLoans", () => ({
  useBorrowerLoans: (id: string) => useLoansMock(id),
  borrowerLoansQueryKey: (id: string) => ["borrower", id, "loans"],
}));

import { LoansTab } from "./LoansTab";

function buildResult(
  partial: Partial<UseQueryResult<BorrowerLoansResponse, Error>>,
): UseQueryResult<BorrowerLoansResponse, Error> {
  return {
    isPending: false,
    isError: false,
    isSuccess: false,
    isLoading: false,
    isFetching: false,
    refetch: vi.fn(),
    data: undefined,
    error: null,
    ...partial,
  } as unknown as UseQueryResult<BorrowerLoansResponse, Error>;
}

function row(overrides: Partial<BorrowerLoanRow> = {}): BorrowerLoanRow {
  return {
    applicationId: "11111111-1111-4111-8111-111111111111",
    externalLoanId: null,
    lspId: "lsp-1",
    lspName: "Acme LSP",
    productId: "prod-1",
    productName: "Personal Loan",
    requestedAmount: 50_000,
    tenureMonths: 12,
    status: "DISBURSED",
    createdAt: "2026-05-01T10:00:00.000Z",
    updatedAt: "2026-05-10T10:00:00.000Z",
    ...overrides,
  };
}

function makeRows(n: number): BorrowerLoanRow[] {
  return Array.from({ length: n }, (_, i) =>
    row({
      applicationId: `app-${String(i).padStart(2, "0")}-xxxxxxxx`,
      requestedAmount: 10_000 + i * 1_000,
      updatedAt: new Date(2026, 4, 1 + i).toISOString(),
    }),
  );
}

function renderInRouter(ui: ReactElement, initialEntries: string[] = ["/borrowers/b-1"]) {
  return renderWithProviders(
    <MemoryRouter initialEntries={initialEntries}>
      <Routes>
        <Route path="/borrowers/:id" element={ui} />
        <Route
          path="/loan-applications/:appId"
          element={<div data-testid="app-detail">app-detail</div>}
        />
      </Routes>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  useLoansMock.mockReset();
});

afterEach(() => {
  vi.clearAllMocks();
});

describe("LoansTab", () => {
  it("renders the skeleton while loading", () => {
    useLoansMock.mockReturnValue(buildResult({ isPending: true }));
    const { container } = renderInRouter(<LoansTab borrowerId="b-1" />);
    expect(container.querySelector('[data-slot="loans-tab-loading"]')).not.toBeNull();
  });

  it("renders an error state with retry on failure", async () => {
    const refetch = vi.fn();
    useLoansMock.mockReturnValue(
      buildResult({ isError: true, error: new Error("network blew up"), refetch }),
    );
    renderInRouter(<LoansTab borrowerId="b-1" />);
    expect(screen.getByText("Couldn't load loans")).toBeInTheDocument();
    expect(screen.getByText(/network blew up/i)).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: /retry/i }));
    expect(refetch).toHaveBeenCalled();
  }, 15_000);

  it("renders the empty state when there are no loans", () => {
    useLoansMock.mockReturnValue(
      buildResult({ isSuccess: true, data: { loans: [] } }),
    );
    renderInRouter(<LoansTab borrowerId="b-1" />);
    expect(
      screen.getByText("This borrower has no loan applications."),
    ).toBeInTheDocument();
  });

  it("renders a comfortable table when there are 12 or fewer rows", () => {
    useLoansMock.mockReturnValue(
      buildResult({ isSuccess: true, data: { loans: makeRows(5) } }),
    );
    const { container } = renderInRouter(<LoansTab borrowerId="b-1" />);
    const wrapper = container.querySelector('[data-slot="loans-tab"]') as HTMLElement;
    expect(wrapper).not.toBeNull();
    expect(wrapper.getAttribute("data-density")).toBe("comfortable");
  });

  it("switches to compact density when there are more than 12 rows", () => {
    useLoansMock.mockReturnValue(
      buildResult({ isSuccess: true, data: { loans: makeRows(13) } }),
    );
    const { container } = renderInRouter(<LoansTab borrowerId="b-1" />);
    const wrapper = container.querySelector('[data-slot="loans-tab"]') as HTMLElement;
    expect(wrapper.getAttribute("data-density")).toBe("compact");
  });

  it("renders all required columns including a status badge and amount", () => {
    useLoansMock.mockReturnValue(
      buildResult({
        isSuccess: true,
        data: { loans: [row({ requestedAmount: 75_000, externalLoanId: "EXT-001" })] },
      }),
    );
    renderInRouter(<LoansTab borrowerId="b-1" />);
    expect(screen.getByText("EXT-001")).toBeInTheDocument();
    expect(screen.getByText("Acme LSP")).toBeInTheDocument();
    expect(screen.getByText("Personal Loan")).toBeInTheDocument();
    // formatINR returns "₹75,000" — check for the digits which are stable
    expect(screen.getByText(/75,000/)).toBeInTheDocument();
    expect(screen.getByText("12 mo")).toBeInTheDocument();
  });

  it("renders an em-dash when externalLoanId is null", () => {
    useLoansMock.mockReturnValue(
      buildResult({
        isSuccess: true,
        data: { loans: [row({ externalLoanId: null })] },
      }),
    );
    const { container } = renderInRouter(<LoansTab borrowerId="b-1" />);
    expect(container.textContent).toContain("—");
  });

  it("navigates to the application detail when a row is clicked", async () => {
    const r = row({ applicationId: "app-abcd1234-xxxx" });
    useLoansMock.mockReturnValue(
      buildResult({ isSuccess: true, data: { loans: [r] } }),
    );
    renderInRouter(<LoansTab borrowerId="b-1" />);
    const rowEl = screen.getByTestId(`borrower-loan-row-${r.applicationId}`);
    await userEvent.click(rowEl);
    expect(screen.getByTestId("app-detail")).toBeInTheDocument();
  }, 15_000);

  it("opens the detail surface via keyboard (Enter)", async () => {
    const r = row({ applicationId: "app-keyboard-test" });
    useLoansMock.mockReturnValue(
      buildResult({ isSuccess: true, data: { loans: [r] } }),
    );
    renderInRouter(<LoansTab borrowerId="b-1" />);
    const rowEl = screen.getByTestId(`borrower-loan-row-${r.applicationId}`);
    rowEl.focus();
    await userEvent.keyboard("{Enter}");
    expect(screen.getByTestId("app-detail")).toBeInTheDocument();
  }, 15_000);

  it("has no axe violations on the populated state", async () => {
    useLoansMock.mockReturnValue(
      buildResult({ isSuccess: true, data: { loans: makeRows(3) } }),
    );
    const { container } = renderInRouter(<LoansTab borrowerId="b-1" />);
    expect(await axe(container)).toHaveNoViolations();
  }, 15_000);
});
