/**
 * AlertsPage smoke tests.
 *
 * Composition target: the page wires the (already-shipped) filter bar,
 * server-paged table, and acknowledge dialog into the route shell. These
 * tests assert that wiring end-to-end against the REAL mock router + seed.
 *
 *   1. Loads + shows seeded alerts for an authorised SYSTEM_ADMIN session.
 *   2. Clicking Acknowledge opens the dialog; submitting flips the row.
 *   3. Axe-clean on the happy path (container) + the open dialog
 *      (baseElement, per binding rule #1).
 *   4. The filter bar's Clear button resets active filters.
 *
 * Note: `AlertsTable` is rendered with a thin testbed shim — the shipped
 * `AlertsTable.tsx` renders `DataTablePagination` with a stale prop shape
 * (`page` / `pageSize` / `total` / `onPageChange` / `onPageSizeChange`) and
 * crashes at runtime because `DataTablePagination` now requires a TanStack
 * `table` ref. See "INTEGRATION NOTE for orchestrator" in the report —
 * Agent A is not allowed to edit that file. The shim keeps the page-level
 * wiring assertions honest (filters in, rows out, ack → mutation) while
 * leaving the broken primitive surfaced to the orchestrator.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { axe } from "vitest-axe";
import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { renderWithProviders } from "@/test/utils";
import { DensityProvider } from "@/app/providers";
import { setLatencyOverride } from "@/mocks/latency";
import { scenario } from "@/mocks/scenarios";
import { resetIdempotency } from "@/mocks/idempotency";
import { resetMockApi, auth } from "@/mocks/api";
// Side-effect: register /api/v1/alerts routes.
import "@/features/alerts/api";
import type {
  AlertRow,
  AlertsListFilters,
  AlertsListResponse,
} from "./types";

// ─── Test shim for the broken AlertsTable ───────────────────────────────────
// Renders one Acknowledge button per OPEN row + an Acknowledged label per
// already-acked row. Wired to the page's `onAcknowledge` so the dialog flow
// is exercised end-to-end.
vi.mock("./components/AlertsTable", () => ({
  AlertsTable: (props: {
    data: AlertsListResponse | undefined;
    isLoading: boolean;
    filters: AlertsListFilters;
    onFiltersChange: (next: AlertsListFilters) => void;
    onAcknowledge: (row: AlertRow) => void;
  }) => (
    <div data-testid="alerts-table">
      <span data-testid="alerts-table-loading">
        {props.isLoading ? "loading" : "idle"}
      </span>
      <span data-testid="alerts-table-total">
        {props.data ? String(props.data.total) : "none"}
      </span>
      <span data-testid="alerts-table-filters">
        {JSON.stringify(props.filters)}
      </span>
      <ul>
        {(props.data?.items ?? []).map((row) => (
          <li key={row.id} data-testid={`alerts-row-${row.id}`} data-status={row.status}>
            <span>{row.title}</span>
            {row.status === "OPEN" ? (
              <button
                type="button"
                onClick={() => props.onAcknowledge(row)}
                aria-label={`Acknowledge ${row.title}`}
              >
                Acknowledge
              </button>
            ) : (
              <span data-testid={`alerts-acked-${row.id}`}>Acknowledged</span>
            )}
          </li>
        ))}
      </ul>
    </div>
  ),
}));

import { AlertsPage } from "./page";

function renderPage() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  function Wrapper({ children }: { children: ReactNode }) {
    return (
      <QueryClientProvider client={client}>
        <DensityProvider>
          <MemoryRouter initialEntries={["/alerts"]}>{children}</MemoryRouter>
        </DensityProvider>
      </QueryClientProvider>
    );
  }
  return renderWithProviders(
    <Wrapper>
      <AlertsPage />
    </Wrapper>,
  );
}

beforeEach(async () => {
  setLatencyOverride(0);
  scenario.reset();
  resetIdempotency();
  resetMockApi();
  await auth.login({ username: "ops.admin", password: "any" });
});

afterEach(() => {
  scenario.reset();
  setLatencyOverride(null);
});

describe("AlertsPage — load + listing", () => {
  it("renders header, filter bar, and table for an authorised session", async () => {
    renderPage();
    expect(screen.getByTestId("alerts-page")).toBeInTheDocument();
    expect(screen.getByText(/Reporting/i)).toBeInTheDocument();
    expect(
      screen.getByRole("heading", { level: 1, name: /Alerts/i }),
    ).toBeInTheDocument();

    expect(
      await screen.findByRole("group", { name: /Alert filters/i }),
    ).toBeInTheDocument();

    // The dashboard seed ships 12 alerts (page size 25 → all visible).
    await waitFor(() => {
      expect(screen.getByTestId("alerts-table-total")).toHaveTextContent("12");
    });

    // 8 are OPEN → 8 Acknowledge buttons rendered by the shim.
    const ackBtns = screen.getAllByRole("button", { name: /Acknowledge / });
    expect(ackBtns.length).toBe(8);
  });

  it("is axe-clean on the happy path", async () => {
    const { container } = renderPage();
    await screen.findAllByRole("button", { name: /Acknowledge / });
    expect(await axe(container)).toHaveNoViolations();
  });
});

describe("AlertsPage — acknowledge flow", () => {
  it(
    "opens the dialog, submits a note, and flips the row to Acknowledged",
    async () => {
      const user = userEvent.setup();
      const { baseElement } = renderPage();

      const ackBtnsBefore = await screen.findAllByRole("button", {
        name: /Acknowledge /,
      });
      const openCountBefore = ackBtnsBefore.length;
      expect(openCountBefore).toBeGreaterThan(0);

      // Click the first OPEN row's Acknowledge action.
      await user.click(ackBtnsBefore[0]!);

      // Radix portals the dialog to body — query via baseElement.
      const dialog = await within(baseElement).findByRole("dialog");
      expect(
        within(dialog).getByRole("heading", { name: /Acknowledge alert/i }),
      ).toBeInTheDocument();

      // Axe on the open dialog → baseElement (binding rule #1).
      expect(await axe(baseElement)).toHaveNoViolations();

      // Fill the optional note + submit.
      const note = within(dialog).getByLabelText(/Acknowledgement note/i);
      await user.type(note, "investigated, false alarm");

      // FormShell mirrors errors into both an aria-live <ul><li> and inline
      // <FormMessage>, so a "Acknowledge" lookup must be scoped to the
      // submit button (it's unique by role within the dialog).
      const submit = within(dialog).getByRole("button", {
        name: /^Acknowledge$/i,
      });
      await user.click(submit);

      // Dialog closes once the mutation resolves and the list invalidates.
      await waitFor(
        () => {
          expect(
            within(baseElement).queryByRole("dialog"),
          ).not.toBeInTheDocument();
        },
        { timeout: 5000 },
      );

      // List invalidation triggers a refetch — the row's status flipped, so
      // there is now exactly one fewer Acknowledge button.
      await waitFor(
        () => {
          const after = screen.queryAllByRole("button", {
            name: /Acknowledge /,
          });
          expect(after.length).toBe(openCountBefore - 1);
        },
        { timeout: 5000 },
      );
    },
    15_000,
  );
});

describe("AlertsPage — filter clear", () => {
  it("Clear resets active filters; is disabled when none are active", async () => {
    const user = userEvent.setup();
    renderPage();

    await screen.findAllByRole("button", { name: /Acknowledge / });

    const clearBtn = await screen.findByRole("button", {
      name: /Clear all filters/i,
    });
    expect(clearBtn).toBeDisabled();

    // Apply the Acknowledged status filter via the tablist.
    const ackedTab = screen.getByRole("tab", { name: /^Acknowledged$/i });
    await user.click(ackedTab);

    // Once a filter is active the Clear button enables.
    await waitFor(() => expect(clearBtn).not.toBeDisabled());

    // The list now narrows to the 4 ACKED rows.
    await waitFor(() => {
      expect(screen.getByTestId("alerts-table-total")).toHaveTextContent("4");
    });
    expect(
      screen.queryAllByRole("button", { name: /Acknowledge / }),
    ).toHaveLength(0);

    // Click Clear → filters reset, OPEN rows return.
    await user.click(clearBtn);
    await waitFor(() => {
      expect(screen.getByTestId("alerts-table-total")).toHaveTextContent("12");
    });
    expect(clearBtn).toBeDisabled();
  });
});
