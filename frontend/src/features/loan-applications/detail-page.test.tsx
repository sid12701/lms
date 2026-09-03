import { beforeEach, describe, expect, it, vi } from "vitest";
import type { ReactNode } from "react";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { ApiError } from "@/lib/api/http-client";
import { renderWithProviders } from "@/test/utils";
import type { LoanApplicationDetail } from "./types";

const useLoanApplicationDetailMock = vi.fn();
const useBorrowerDetailMock = vi.fn();
const refetchMock = vi.fn();

vi.mock("./hooks/useLoanApplicationDetail", () => ({
  useLoanApplicationDetail: (...args: unknown[]) => useLoanApplicationDetailMock(...args),
}));
vi.mock("@/features/borrowers/hooks/useBorrowerDetail", () => ({
  useBorrowerDetail: (...args: unknown[]) => useBorrowerDetailMock(...args),
}));
vi.mock("@/features/auth/session-context", () => ({
  useSession: () => ({ session: { user: { role: "SYSTEM_ADMIN" } } }),
}));
vi.mock("./components/DetailHeader", () => ({
  DetailHeader: () => <h1>Application detail</h1>,
}));
vi.mock("./components/DetailTabsShell", () => ({
  DetailTabsShell: (props: {
    activeTab: string;
    onTabChange: (tab: "overview" | "schedule") => void;
    children: ReactNode;
  }) => (
    <section>
      <div data-testid="active-tab">{props.activeTab}</div>
      <button onClick={() => props.onTabChange("overview")}>Overview tab button</button>
      <button onClick={() => props.onTabChange("schedule")}>Schedule tab button</button>
      {props.children}
    </section>
  ),
}));
vi.mock("./components/detail-tabs", () => ({
  OverviewTab: () => <div>overview content</div>,
  ScheduleTab: () => <div>schedule content</div>,
  DocumentsTab: () => <div>documents content</div>,
  RepaymentsTab: () => <div>repayments content</div>,
  ActivityTab: () => <div>activity content</div>,
}));

import { LoanApplicationDetailPage } from "./detail-page";

const detail = {
  application: {
    id: "loan-1",
    status: "UNDER_REPAYMENT",
    requestedAmount: 250_000,
  },
  borrower: { id: "borrower-1" },
  lsp: { name: "Acme Finance" },
  docsComplete: true,
  scheduleValid: true,
  accountDelinquency: null,
  interestRate: null,
} as unknown as LoanApplicationDetail;

function query(overrides: Record<string, unknown> = {}) {
  return {
    data: detail,
    isPending: false,
    isError: false,
    error: null,
    refetch: refetchMock,
    ...overrides,
  };
}

function renderPage(path = "/loan-applications/loan-1", route = "/loan-applications/:id") {
  return renderWithProviders(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path={route} element={<LoanApplicationDetailPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  refetchMock.mockReset();
  useLoanApplicationDetailMock.mockReset().mockReturnValue(query());
  useBorrowerDetailMock.mockReset().mockReturnValue({ data: undefined });
});

describe("LoanApplicationDetailPage", () => {
  it("does not query an absent route identifier and renders the missing-id state", () => {
    renderPage("/loan-applications", "/loan-applications");

    expect(useLoanApplicationDetailMock).toHaveBeenCalledWith("");
    expect(screen.getByText("No application id")).toBeInTheDocument();
  });

  it("renders a skeleton while initial detail is loading", () => {
    useLoanApplicationDetailMock.mockReturnValue(query({ data: undefined, isPending: true }));
    renderPage();

    expect(screen.getByTestId("loan-application-detail")).toHaveAttribute("data-loading", "true");
  });

  it("distinguishes forbidden and missing applications", () => {
    useLoanApplicationDetailMock.mockReturnValue(
      query({
        data: undefined,
        isError: true,
        error: new ApiError("forbidden", 403, "", "FORBIDDEN"),
      }),
    );
    const forbidden = renderPage();
    expect(screen.getByText("No access to this application")).toBeInTheDocument();
    forbidden.unmount();

    useLoanApplicationDetailMock.mockReturnValue(
      query({
        data: undefined,
        isError: true,
        error: new ApiError("missing", 404, "", "NOT_FOUND"),
      }),
    );
    renderPage();
    expect(screen.getByText("Application not found")).toBeInTheDocument();
  });

  it("renders a retryable state for other failures", async () => {
    const operator = userEvent.setup();
    useLoanApplicationDetailMock.mockReturnValue(
      query({ data: undefined, isError: true, error: new Error("backend unavailable") }),
    );
    renderPage();

    expect(screen.getByText("Couldn't load this application")).toBeInTheDocument();
    await operator.click(screen.getByRole("button", { name: "Retry" }));
    expect(refetchMock).toHaveBeenCalledTimes(1);
  });

  it("renders successful detail and keeps the selected tab in URL state", async () => {
    const operator = userEvent.setup();
    renderPage();

    expect(screen.getByRole("heading", { name: "Application detail" })).toBeInTheDocument();
    expect(screen.getByText("overview content")).toBeInTheDocument();
    expect(useBorrowerDetailMock).toHaveBeenCalledWith("borrower-1");

    await operator.click(screen.getByRole("button", { name: "Schedule tab button" }));
    expect(screen.getByTestId("active-tab")).toHaveTextContent("schedule");
    expect(screen.getByText("schedule content")).toBeInTheDocument();
  });

  it("singularises 'day' when a delinquent loan is exactly 1 day past due", () => {
    useLoanApplicationDetailMock.mockReturnValue(
      query({
        data: {
          ...detail,
          accountDelinquency: { maxDaysPastDue: 1, overdueInstallmentCount: 1 },
        },
      }),
    );
    renderPage();

    expect(screen.getByText("1 day past due")).toBeInTheDocument();
    expect(screen.queryByText("1 days past due")).not.toBeInTheDocument();
    expect(screen.getByText("1 overdue installment")).toBeInTheDocument();
  });

  it("keeps the plural 'days' when more than one day past due", () => {
    useLoanApplicationDetailMock.mockReturnValue(
      query({
        data: {
          ...detail,
          accountDelinquency: { maxDaysPastDue: 5, overdueInstallmentCount: 2 },
        },
      }),
    );
    renderPage();

    expect(screen.getByText("5 days past due")).toBeInTheDocument();
    expect(screen.getByText("2 overdue installments")).toBeInTheDocument();
  });

  /*
   * "At a glance" fell through to "Repayment · On track" whenever a loan had no
   * delinquency — including loans where no money has been lent yet. It read as
   * reassurance on the stuck-disbursement screen itself.
   */
  describe("at-a-glance repayment line before disbursement", () => {
    const preDisbursement = [
      "INITIALIZED",
      "AWAITING_APPROVAL",
      "APPROVED_PENDING_DISBURSAL",
      "DISBURSEMENT_RETRY",
    ] as const;

    it.each(preDisbursement)("reads 'Awaiting disbursement' for %s", (status) => {
      useLoanApplicationDetailMock.mockReturnValue(
        query({ data: { ...detail, application: { ...detail.application, status } } }),
      );
      renderPage();

      expect(screen.getByText("Awaiting disbursement")).toBeInTheDocument();
      expect(screen.queryByText("On track")).not.toBeInTheDocument();
    });

    it("still reads 'On track' once the loan is under repayment and current", () => {
      useLoanApplicationDetailMock.mockReturnValue(query());
      renderPage();

      expect(screen.getByText("On track")).toBeInTheDocument();
      expect(screen.queryByText("Awaiting disbursement")).not.toBeInTheDocument();
    });

    it("does not claim a disbursement is coming for a rejected loan", () => {
      useLoanApplicationDetailMock.mockReturnValue(
        query({ data: { ...detail, application: { ...detail.application, status: "REJECTED" } } }),
      );
      renderPage();

      expect(screen.queryByText("Awaiting disbursement")).not.toBeInTheDocument();
    });
  });
});
