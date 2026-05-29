/**
 * LoanApplicationsPage tests — verifies layout composition, error/retry
 * behaviour, and that filter state flows from the URL into the table.
 *
 * The hook + table + filter bar are mocked because they're unit-tested in
 * their own files; here we only assert the page's wiring contract.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { axe } from "vitest-axe";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Routes, Route } from "react-router-dom";
import type { ReactNode } from "react";
import { renderWithProviders } from "@/test/utils";
import type { LoanApplicationListResponse } from "./types";

const refetchMock = vi.fn();
const useLoanApplicationsMock = vi.fn();

vi.mock("./hooks/useLoanApplications", () => ({
  useLoanApplications: (...args: unknown[]) => useLoanApplicationsMock(...args),
  LOAN_APPLICATIONS_LIST_QUERY_KEY: ["loan-applications", "list"],
}));

vi.mock("./components", () => ({
  LoanApplicationsFilterBar: () => <div data-testid="filter-bar">FilterBar</div>,
  LoanApplicationsTable: (props: {
    data: LoanApplicationListResponse | undefined;
    isLoading: boolean;
    filters: Record<string, unknown>;
  }) => (
    <div data-testid="triage-table">
      <span data-testid="triage-table-loading">{props.isLoading ? "loading" : "idle"}</span>
      <span data-testid="triage-table-total">{props.data ? String(props.data.total) : "none"}</span>
      <span data-testid="triage-table-filters">{JSON.stringify(props.filters)}</span>
    </div>
  ),
}));

// Import after the mocks.
import { LoanApplicationsPage } from "./page";

const FIXTURE: LoanApplicationListResponse = {
  items: [],
  total: 7,
  page: 0,
  pageSize: 25,
};

function renderPage(initialPath = "/loan-applications") {
  const ui: ReactNode = (
    <MemoryRouter initialEntries={[initialPath]}>
      <Routes>
        <Route path="/loan-applications" element={<LoanApplicationsPage />} />
      </Routes>
    </MemoryRouter>
  );
  return renderWithProviders(ui);
}

beforeEach(() => {
  refetchMock.mockReset();
  useLoanApplicationsMock.mockReset();
});

afterEach(() => {
  vi.clearAllMocks();
});

describe("LoanApplicationsPage", () => {
  it("renders the page header with the expected copy", () => {
    useLoanApplicationsMock.mockReturnValue({
      data: FIXTURE,
      isPending: false,
      isError: false,
      isSuccess: true,
      error: null,
      refetch: refetchMock,
    });
    renderPage();
    expect(screen.getByTestId("loan-applications-page")).toBeInTheDocument();
    expect(screen.getByText(/Workspace/)).toBeInTheDocument();
    expect(
      screen.getByRole("heading", { level: 1, name: /Loan applications/i }),
    ).toBeInTheDocument();
    expect(
      screen.getByText(/Browse, filter, and act on every loan application/i),
    ).toBeInTheDocument();
  });

  it("renders the filter bar and the table on the happy path", () => {
    useLoanApplicationsMock.mockReturnValue({
      data: FIXTURE,
      isPending: false,
      isError: false,
      isSuccess: true,
      error: null,
      refetch: refetchMock,
    });
    renderPage();
    expect(screen.getByTestId("filter-bar")).toBeInTheDocument();
    expect(screen.getByTestId("triage-table")).toBeInTheDocument();
    expect(screen.getByTestId("triage-table-total")).toHaveTextContent("7");
    expect(screen.getByTestId("triage-table-loading")).toHaveTextContent("idle");
  });

  it("forwards isPending to the table while the query is pending", () => {
    useLoanApplicationsMock.mockReturnValue({
      data: undefined,
      isPending: true,
      isError: false,
      isSuccess: false,
      error: null,
      refetch: refetchMock,
    });
    renderPage();
    expect(screen.getByTestId("triage-table-loading")).toHaveTextContent("loading");
  });

  it("renders an ErrorState with retry when the query errors", () => {
    useLoanApplicationsMock.mockReturnValue({
      data: undefined,
      isPending: false,
      isError: true,
      isSuccess: false,
      error: new Error("boom"),
      refetch: refetchMock,
    });
    renderPage();
    expect(screen.getByRole("alert")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Retry/i })).toBeInTheDocument();
    // Table is replaced, not rendered alongside the error.
    expect(screen.queryByTestId("triage-table")).not.toBeInTheDocument();
  });

  it("clicking Retry calls refetch()", async () => {
    const user = userEvent.setup();
    useLoanApplicationsMock.mockReturnValue({
      data: undefined,
      isPending: false,
      isError: true,
      isSuccess: false,
      error: new Error("boom"),
      refetch: refetchMock,
    });
    renderPage();
    await user.click(screen.getByRole("button", { name: /Retry/i }));
    await waitFor(() => {
      expect(refetchMock).toHaveBeenCalledTimes(1);
    });
  });

  it("hydrates filters from the URL", () => {
    useLoanApplicationsMock.mockReturnValue({
      data: FIXTURE,
      isPending: false,
      isError: false,
      isSuccess: true,
      error: null,
      refetch: refetchMock,
    });
    renderPage("/loan-applications?q=demo&page=2&pageSize=50");
    const filtersJson = screen.getByTestId("triage-table-filters").textContent ?? "";
    const parsed = JSON.parse(filtersJson) as Record<string, unknown>;
    expect(parsed.q).toBe("demo");
    expect(parsed.page).toBe(2);
    expect(parsed.pageSize).toBe(50);
    // The hook was called with the parsed filters.
    expect(useLoanApplicationsMock).toHaveBeenCalledWith(
      expect.objectContaining({ q: "demo", page: 2, pageSize: 50 }),
    );
  });

  it("is axe-clean on the happy path", async () => {
    useLoanApplicationsMock.mockReturnValue({
      data: FIXTURE,
      isPending: false,
      isError: false,
      isSuccess: true,
      error: null,
      refetch: refetchMock,
    });
    const { container } = renderPage();
    expect(await axe(container)).toHaveNoViolations();
  });
});
