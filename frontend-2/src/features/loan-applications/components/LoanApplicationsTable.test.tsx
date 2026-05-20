/**
 * LoanApplicationsTable tests — verifies the triage table contract:
 * rendering, sorting wiring, pagination wiring, row navigation, the
 * loading skeleton, the empty state, and axe cleanliness.
 */
import { describe, expect, it, vi } from "vitest";
import { axe } from "vitest-axe";
import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Routes, Route } from "react-router-dom";
import { renderWithProviders } from "@/test/utils";
import { DensityProvider } from "@/app/providers";
import { LoanApplicationsTable } from "./LoanApplicationsTable";
import type {
  LoanApplicationListFilters,
  LoanApplicationListItem,
  LoanApplicationListResponse,
} from "../types";

const ROW_A: LoanApplicationListItem = {
  id: "11111111-1111-4111-8111-111111111111",
  externalLoanId: "EXT-001",
  borrowerId: "22222222-2222-4222-8222-222222222222",
  borrowerNameMasked: "A•••a Devi",
  lspId: "33333333-3333-4333-8333-333333333333",
  lspName: "Acme NBFC",
  productId: "44444444-4444-4444-8444-444444444444",
  productName: "Personal Loan A",
  requestedAmount: 250_000,
  tenureMonths: 24,
  status: "AWAITING_APPROVAL",
  assignedTo: "55555555-5555-4555-8555-555555555555",
  assignedToName: "Ops User",
  createdAt: "2026-04-10T08:00:00.000Z",
  updatedAt: "2026-05-10T08:00:00.000Z",
};

const ROW_B: LoanApplicationListItem = {
  ...ROW_A,
  id: "99999999-9999-4999-8999-999999999999",
  externalLoanId: null,
  borrowerNameMasked: "B•••b Singh",
  requestedAmount: 75_000,
  status: "DISBURSED",
  assignedTo: null,
  assignedToName: null,
};

const RESPONSE: LoanApplicationListResponse = {
  items: [ROW_A, ROW_B],
  total: 42,
  page: 0,
  pageSize: 25,
};

function renderTable(props: {
  data?: LoanApplicationListResponse | undefined;
  isLoading?: boolean;
  filters?: LoanApplicationListFilters;
  onFiltersChange?: (next: LoanApplicationListFilters) => void;
  initialPath?: string;
}) {
  const onFiltersChange = props.onFiltersChange ?? vi.fn();
  const filters = props.filters ?? {};
  const route = (
    <DensityProvider>
      <LoanApplicationsTable
        data={props.data}
        isLoading={props.isLoading ?? false}
        filters={filters}
        onFiltersChange={onFiltersChange}
      />
    </DensityProvider>
  );
  const detailMarker = (
    <div data-testid="detail-route">detail</div>
  );
  return renderWithProviders(
    <MemoryRouter initialEntries={[props.initialPath ?? "/loan-applications"]}>
      <Routes>
        <Route path="/loan-applications" element={route} />
        <Route path="/loan-applications/:id" element={detailMarker} />
      </Routes>
    </MemoryRouter>,
  );
}

describe("LoanApplicationsTable", () => {
  it("renders one body row per item with all required columns", () => {
    renderTable({ data: RESPONSE });
    // Header row + 2 data rows
    expect(screen.getAllByRole("row")).toHaveLength(3);
    expect(screen.getByText(ROW_A.borrowerNameMasked)).toBeInTheDocument();
    expect(screen.getByText(ROW_B.borrowerNameMasked)).toBeInTheDocument();
    // External ID fallback
    expect(screen.getByText("—")).toBeInTheDocument();
    // Assignee fallback
    expect(screen.getByText("Unassigned")).toBeInTheDocument();
    // Tenure formatting
    expect(screen.getAllByText("24 mo")).toHaveLength(2);
    // Short id with mono font
    expect(screen.getByText("11111111…")).toBeInTheDocument();
  });

  it("renders the loading skeleton when isLoading and no data", () => {
    renderTable({ data: undefined, isLoading: true });
    expect(screen.getByRole("status", { name: /loading/i })).toBeInTheDocument();
  });

  it("renders the empty state when the response has no items", () => {
    renderTable({
      data: { items: [], total: 0, page: 0, pageSize: 25 },
    });
    expect(
      screen.getByText(/No applications match these filters/i),
    ).toBeInTheDocument();
  });

  it("navigates to the detail route on row click", async () => {
    const user = userEvent.setup();
    renderTable({ data: RESPONSE });
    const row = screen.getByTestId(`loan-applications-row-${ROW_A.id}`);
    await user.click(row);
    expect(screen.getByTestId("detail-route")).toBeInTheDocument();
  });

  it("navigates to the detail route on Enter keypress", async () => {
    const user = userEvent.setup();
    renderTable({ data: RESPONSE });
    const row = screen.getByTestId(`loan-applications-row-${ROW_A.id}`);
    row.focus();
    await user.keyboard("{Enter}");
    expect(screen.getByTestId("detail-route")).toBeInTheDocument();
  });

  it("emits sortBy/sortDir when a sortable header is clicked", async () => {
    const user = userEvent.setup();
    const onFiltersChange = vi.fn();
    renderTable({ data: RESPONSE, onFiltersChange });
    // Find the Amount sort button specifically by text content + label.
    const sortButtons = screen
      .getAllByRole("button")
      .filter((b) => /Activate to sort/i.test(b.getAttribute("aria-label") ?? ""));
    const amountBtn = sortButtons.find((b) => /Amount/.test(b.textContent ?? ""));
    expect(amountBtn).toBeDefined();
    await user.click(amountBtn!);
    expect(onFiltersChange).toHaveBeenCalledTimes(1);
    const patch = onFiltersChange.mock.calls[0]![0];
    expect(patch.sortBy).toBe("requestedAmount");
    expect(patch.sortDir).toBe("asc");
    expect(patch.page).toBe(0);
  });

  it("flips sortDir to desc on a second click of the same column", async () => {
    const user = userEvent.setup();
    const onFiltersChange = vi.fn();
    renderTable({
      data: RESPONSE,
      filters: { sortBy: "requestedAmount", sortDir: "asc" },
      onFiltersChange,
    });
    const sortButtons = screen
      .getAllByRole("button")
      .filter((b) => /Sorted ascending/i.test(b.getAttribute("aria-label") ?? ""));
    expect(sortButtons.length).toBeGreaterThan(0);
    const amountBtn = sortButtons.find((b) => /Amount/.test(b.textContent ?? ""));
    expect(amountBtn).toBeDefined();
    await user.click(amountBtn!);
    const patch = onFiltersChange.mock.calls[0]![0];
    expect(patch.sortDir).toBe("desc");
  });

  it("emits a pagination patch when the next-page button is clicked", async () => {
    const user = userEvent.setup();
    const onFiltersChange = vi.fn();
    renderTable({
      data: { ...RESPONSE, total: 100 },
      filters: { page: 0, pageSize: 25 },
      onFiltersChange,
    });
    await user.click(screen.getByRole("button", { name: /Go to next page/i }));
    const patch = onFiltersChange.mock.calls[0]![0];
    expect(patch.page).toBe(1);
    expect(patch.pageSize).toBe(25);
  });

  it("renders the status badge for each row", () => {
    renderTable({ data: RESPONSE });
    const badges = document.querySelectorAll('[data-slot="status-badge"]');
    expect(badges.length).toBe(2);
  });

  it("renders amount cells with tabular alignment", () => {
    const { container } = renderTable({ data: RESPONSE });
    const tabularCells = container.querySelectorAll("td[data-tabular]");
    expect(tabularCells.length).toBeGreaterThan(0);
  });

  it("has no axe violations on the happy path", async () => {
    const { container } = renderTable({ data: RESPONSE });
    expect(await axe(container)).toHaveNoViolations();
  }, 15000);

  it("has no axe violations in the empty state", async () => {
    const { container } = renderTable({
      data: { items: [], total: 0, page: 0, pageSize: 25 },
    });
    expect(await axe(container)).toHaveNoViolations();
  }, 15000);

  it("falls back to non-empty header content for visible columns", () => {
    renderTable({ data: RESPONSE });
    expect(screen.getByText("Borrower")).toBeInTheDocument();
    expect(screen.getByText("LSP")).toBeInTheDocument();
    expect(screen.getByText("Product")).toBeInTheDocument();
    // Two amount labels (header button + sort variant) — just check presence.
    const firstRow = screen.getAllByRole("row")[1]!;
    expect(within(firstRow).getAllByRole("cell").length).toBe(10);
  });
});
