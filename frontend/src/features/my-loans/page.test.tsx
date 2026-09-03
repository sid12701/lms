import { beforeEach, describe, expect, it, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { DensityProvider } from "@/app/providers";
import { renderWithProviders } from "@/test/utils";
import type { MyLoanListPage } from "./api";

const fetchMyLoansPageMock = vi.hoisted(() => vi.fn());

vi.mock("./api", () => ({
  fetchMyLoansPage: (...args: unknown[]) => fetchMyLoansPageMock(...args),
}));

import { MyLoansPage } from "./page";

const PAGE: MyLoanListPage = {
  items: [
    {
      id: "loan-1",
      externalLoanId: "EXT-001",
      borrowerFullName: "Aarav Singh",
      productName: "Personal Loan",
      requestedAmount: 250_000,
      status: "AWAITING_APPROVAL",
      createdAt: "2026-08-01T00:00:00.000Z",
    },
  ],
  totalCount: 1,
  offset: 0,
  limit: 25,
};

function renderPage(entry = "/my-loans") {
  return renderWithProviders(
    <MemoryRouter initialEntries={[entry]}>
      <DensityProvider>
        <MyLoansPage />
      </DensityProvider>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  fetchMyLoansPageMock.mockReset();
});

describe("MyLoansPage", () => {
  it("loads the first page and renders the returned loan", async () => {
    fetchMyLoansPageMock.mockResolvedValue(PAGE);

    renderPage();

    expect(await screen.findByText("EXT-001")).toBeInTheDocument();
    expect(screen.getByText("Aarav Singh")).toBeInTheDocument();
    expect(fetchMyLoansPageMock).toHaveBeenCalledWith({
      offset: 0,
      limit: 25,
      q: undefined,
      status: undefined,
    });
  });

  /**
   * The partner surface previously had no way to find one loan among hundreds.
   * Filtering is server-side — the LSP list endpoint already accepted `q` and
   * `status` — so it must reach the request, not just the local page.
   */
  it("passes URL filters through to the list request", async () => {
    fetchMyLoansPageMock.mockResolvedValue(PAGE);

    renderPage("/my-loans?q=Aarav&status=UNDER_REPAYMENT");

    await screen.findByText("EXT-001");
    expect(fetchMyLoansPageMock).toHaveBeenCalledWith({
      offset: 0,
      limit: 25,
      q: "Aarav",
      status: "UNDER_REPAYMENT",
    });
  });

  it("lets the operator filter by status and resets to the first page", async () => {
    fetchMyLoansPageMock.mockResolvedValue(PAGE);
    const operator = userEvent.setup();

    renderPage("/my-loans?page=3");
    await screen.findByText("EXT-001");

    await operator.click(screen.getByRole("combobox", { name: "Status filter" }));
    await operator.click(await screen.findByRole("option", { name: "Under repayment" }));

    await waitFor(() =>
      expect(fetchMyLoansPageMock).toHaveBeenLastCalledWith({
        offset: 0,
        limit: 25,
        q: undefined,
        status: "UNDER_REPAYMENT",
      }),
    );
  });

  it("distinguishes an over-filtered list from an empty book", async () => {
    fetchMyLoansPageMock.mockResolvedValue({ ...PAGE, items: [], totalCount: 0 });

    renderPage("/my-loans?q=nobody");

    expect(await screen.findByText("No loans match these filters")).toBeInTheDocument();
    expect(screen.queryByText("No loans yet")).not.toBeInTheDocument();
  });

  it("keeps retry in the page when the list request fails", async () => {
    fetchMyLoansPageMock.mockRejectedValueOnce(new Error("service unavailable"));
    fetchMyLoansPageMock.mockResolvedValueOnce(PAGE);

    renderPage();

    expect(await screen.findByText("Couldn't load loans")).toBeInTheDocument();
    await userEvent.setup().click(screen.getByRole("button", { name: "Retry" }));

    await waitFor(() => expect(screen.getByText("EXT-001")).toBeInTheDocument());
    expect(fetchMyLoansPageMock).toHaveBeenCalledTimes(2);
  });

  it("exposes loading while the keyed page query is pending", () => {
    fetchMyLoansPageMock.mockReturnValue(new Promise(() => undefined));

    renderPage();

    expect(screen.getByText("Loading…")).toBeInTheDocument();
  });
});
