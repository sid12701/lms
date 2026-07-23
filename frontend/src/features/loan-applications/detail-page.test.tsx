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
  WebhooksTab: () => <div>webhooks content</div>,
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
});
