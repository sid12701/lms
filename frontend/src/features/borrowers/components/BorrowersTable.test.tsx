/**
 * BorrowersTable tests — verifies the directory table contract:
 * rendering each row's identity columns, the loading skeleton, the
 * empty state, and click-to-open navigation to /borrowers/:id.
 */
import { describe, expect, it, vi } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Routes, Route } from "react-router-dom";
import { renderWithProviders } from "@/test/utils";
import { DensityProvider } from "@/app/providers";
import { BorrowersTable } from "./BorrowersTable";
import type { BorrowerListFilters, BorrowerListResponse, BorrowerSummary } from "../list-types";

const ROW_A: BorrowerSummary = {
  id: "11111111-1111-4111-8111-111111111111",
  fullName: "Anika Sharma",
  pan: "ABCDE1234F",
  mobile: "9999999991",
  email: "anika@example.com",
  city: "Bengaluru",
  state: "Karnataka",
  aadharNumberMasked: "XXXXXXXX9012",
  visibleLspIds: [],
};

const ROW_B: BorrowerSummary = {
  id: "99999999-9999-4999-8999-999999999999",
  fullName: "Rahul Shah",
  pan: "ZXCVB1234N",
  mobile: "9876543210",
  email: null,
  city: null,
  state: null,
  aadharNumberMasked: null,
  visibleLspIds: [],
};

const RESPONSE: BorrowerListResponse = {
  items: [ROW_A, ROW_B],
  total: 2,
  page: 0,
  pageSize: 25,
};

function renderTable(props: {
  data?: BorrowerListResponse | undefined;
  isLoading?: boolean;
  filters?: BorrowerListFilters;
  onFiltersChange?: (next: BorrowerListFilters) => void;
}) {
  const onFiltersChange = props.onFiltersChange ?? vi.fn();
  const filters = props.filters ?? {};
  const route = (
    <DensityProvider>
      <BorrowersTable
        data={props.data}
        isLoading={props.isLoading ?? false}
        filters={filters}
        onFiltersChange={onFiltersChange}
      />
    </DensityProvider>
  );
  const detailMarker = <div data-testid="detail-route">detail</div>;
  return renderWithProviders(
    <MemoryRouter initialEntries={["/borrowers"]}>
      <Routes>
        <Route path="/borrowers" element={route} />
        <Route path="/borrowers/:id" element={detailMarker} />
      </Routes>
    </MemoryRouter>,
  );
}

describe("BorrowersTable", () => {
  it("renders one body row per borrower with identity columns", () => {
    const { container } = renderTable({ data: RESPONSE });
    expect(container.querySelectorAll('[data-slot="data-table"] tbody tr')).toHaveLength(2);
    expect(screen.getByText("Anika Sharma")).toBeInTheDocument();
    expect(screen.getByText("Rahul Shah")).toBeInTheDocument();
    expect(screen.getByText("ABCDE1234F")).toBeInTheDocument();
    expect(screen.getByText("9876543210")).toBeInTheDocument();
    expect(screen.getByText("anika@example.com")).toBeInTheDocument();
    expect(screen.getByText("Bengaluru, Karnataka")).toBeInTheDocument();
  });

  it("falls back to an em-dash when optional fields are null", () => {
    renderTable({ data: RESPONSE });
    // ROW_B has null email, city, state — 2 em-dashes (email + location)
    expect(screen.getAllByText("—").length).toBeGreaterThanOrEqual(2);
  });

  it("shows a skeleton when loading with no prior data", () => {
    const { container } = renderTable({ isLoading: true });
    expect(container.querySelector('[data-slot="borrowers-table"]')).toBeTruthy();
    expect(container.querySelector('[data-slot="data-table"] .animate-pulse')).toBeTruthy();
  });

  it("shows an empty-state message when the post-fetch list is empty", () => {
    renderTable({ data: { items: [], total: 0, page: 0, pageSize: 25 } });
    expect(screen.getByText(/no borrowers/i)).toBeInTheDocument();
  });

  it("navigates to /borrowers/:id when a row is clicked", async () => {
    const user = userEvent.setup();
    renderTable({ data: RESPONSE });
    const row = screen.getByTestId(`borrowers-row-${ROW_A.id}`);
    await user.click(row);
    expect(await screen.findByTestId("detail-route")).toBeInTheDocument();
  });

  it("navigates to /borrowers/:id when Enter is pressed on a focused row", async () => {
    const user = userEvent.setup();
    renderTable({ data: RESPONSE });
    const row = screen.getByTestId(`borrowers-row-${ROW_B.id}`);
    row.focus();
    await user.keyboard("{Enter}");
    expect(await screen.findByTestId("detail-route")).toBeInTheDocument();
  });
});
