/**
 * BorrowersPage tests — verifies error/retry wiring and 403 empty state.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Routes, Route } from "react-router-dom";
import type { ReactNode } from "react";
import { renderWithProviders } from "@/test/utils";
import { ApiError } from "@/lib/api/http-client";
import type { BorrowerListResponse } from "./list-types";

const refetchMock = vi.fn();
const useBorrowersListMock = vi.fn();

vi.mock("./hooks/useBorrowersList", () => ({
  useBorrowersList: (...args: unknown[]) => useBorrowersListMock(...args),
}));

vi.mock("./components/BorrowersFilterBar", () => ({
  BorrowersFilterBar: () => <div data-testid="filter-bar">FilterBar</div>,
}));

vi.mock("./components/BorrowersTable", () => ({
  BorrowersTable: (props: { data: BorrowerListResponse | undefined; isLoading: boolean }) => (
    <div data-testid="borrowers-table">
      <span data-testid="borrowers-table-loading">{props.isLoading ? "loading" : "idle"}</span>
      <span data-testid="borrowers-table-total">
        {props.data ? String(props.data.total) : "none"}
      </span>
    </div>
  ),
}));

import { BorrowersPage } from "./page";

const FIXTURE: BorrowerListResponse = {
  items: [],
  total: 3,
  page: 0,
  pageSize: 25,
};

function renderPage() {
  const ui: ReactNode = (
    <MemoryRouter initialEntries={["/borrowers"]}>
      <Routes>
        <Route path="/borrowers" element={<BorrowersPage />} />
      </Routes>
    </MemoryRouter>
  );
  return renderWithProviders(ui);
}

beforeEach(() => {
  refetchMock.mockReset();
  useBorrowersListMock.mockReset();
});

afterEach(() => {
  vi.clearAllMocks();
});

describe("BorrowersPage", () => {
  it("renders a no-permission EmptyState when the query returns 403", () => {
    useBorrowersListMock.mockReturnValue({
      data: undefined,
      isPending: false,
      isError: true,
      isSuccess: false,
      error: new ApiError("Access denied", 403, "", "FORBIDDEN"),
      refetch: refetchMock,
    });
    renderPage();
    expect(screen.getByText(/No access to borrowers/i)).toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(screen.queryByTestId("borrowers-table")).not.toBeInTheDocument();
  });

  it("renders an ErrorState with retry when the query errors", () => {
    useBorrowersListMock.mockReturnValue({
      data: undefined,
      isPending: false,
      isError: true,
      isSuccess: false,
      error: new Error("boom"),
      refetch: refetchMock,
    });
    renderPage();
    expect(screen.getByRole("alert")).toBeInTheDocument();
    expect(screen.queryByTestId("borrowers-table")).not.toBeInTheDocument();
  });

  it("clicking Retry calls refetch()", async () => {
    const user = userEvent.setup();
    useBorrowersListMock.mockReturnValue({
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

  it("renders the table on the happy path", () => {
    useBorrowersListMock.mockReturnValue({
      data: FIXTURE,
      isPending: false,
      isError: false,
      isSuccess: true,
      error: null,
      refetch: refetchMock,
    });
    renderPage();
    expect(screen.getByTestId("borrowers-table-total")).toHaveTextContent("3");
  });
});
