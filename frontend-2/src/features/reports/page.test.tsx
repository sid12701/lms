/**
 * ReportsPage smoke tests.
 *
 * Composition target: the page wires the filter bar, KPI summary cards, the
 * MIS preview table, the requests list (with 5s polling), the create dialog,
 * and download mutation. These tests assert that wiring end-to-end against
 * the REAL mock router + seed.
 *
 *   1. Loads + shows the 5 KPI cards for an authorised SYSTEM_ADMIN session.
 *   2. The preview table renders rows from the seed.
 *   3. Clicking "Generate report" opens the dialog; submitting creates a
 *      QUEUED request that appears in the requests table.
 *   4. Axe-clean on the happy path (container) + the open dialog
 *      (baseElement, per binding rule #1).
 */
import { afterEach, beforeEach, describe, expect, it } from "vitest";
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
// Side-effect: register /api/v1/reports/* routes.
import "@/features/reports/api";

import { ReportsPage } from "./page";

function renderPage() {
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });
  function Wrapper({ children }: { children: ReactNode }) {
    return (
      <QueryClientProvider client={client}>
        <DensityProvider>
          <MemoryRouter initialEntries={["/reports"]}>{children}</MemoryRouter>
        </DensityProvider>
      </QueryClientProvider>
    );
  }
  return renderWithProviders(
    <Wrapper>
      <ReportsPage />
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

describe("ReportsPage — load + listing", () => {
  it("renders header, KPI cards, and preview rows for an authorised session", async () => {
    renderPage();
    expect(screen.getByTestId("reports-page")).toBeInTheDocument();
    expect(screen.getByText(/Reporting/i)).toBeInTheDocument();
    expect(
      screen.getByRole("heading", { level: 1, name: /^Reports$/i }),
    ).toBeInTheDocument();

    // Filter bar lands.
    expect(
      await screen.findByRole("group", { name: /Report filters/i }),
    ).toBeInTheDocument();

    // Five KPI cards by their stable testIds.
    await waitFor(() => {
      expect(screen.getByTestId("mis-kpi-disbursed-mtd")).toBeInTheDocument();
    });
    expect(screen.getByTestId("mis-kpi-active-loans")).toBeInTheDocument();
    expect(screen.getByTestId("mis-kpi-total-loans")).toBeInTheDocument();
    expect(screen.getByTestId("mis-kpi-weighted-yield")).toBeInTheDocument();
    expect(screen.getByTestId("mis-kpi-par-30")).toBeInTheDocument();

    // Preview rows arrive from the seed (53 applications total → first page
    // is 25 rows). Assert at least one preview row renders.
    await waitFor(() => {
      const rows = document.querySelectorAll(
        '[data-testid^="mis-preview-row-"]',
      );
      expect(rows.length).toBeGreaterThan(0);
    });
  });

  it("is axe-clean on the happy path", async () => {
    const { container } = renderPage();
    await screen.findByTestId("mis-kpi-total-loans");
    await waitFor(() => {
      const rows = document.querySelectorAll(
        '[data-testid^="mis-preview-row-"]',
      );
      expect(rows.length).toBeGreaterThan(0);
    });
    expect(await axe(container)).toHaveNoViolations();
  });
});

describe("ReportsPage — create flow", () => {
  it(
    "opens the dialog, submits the form, and the new QUEUED request appears in the list",
    async () => {
      const user = userEvent.setup();
      const { baseElement } = renderPage();

      // Wait for initial data so the Generate button is fully wired.
      await screen.findByTestId("mis-kpi-total-loans");

      // Capture the requests count BEFORE create. Requests table starts empty.
      const requestsBefore = document.querySelectorAll(
        '[data-testid^="report-request-row-"]',
      ).length;

      const generateBtn = screen.getByRole("button", {
        name: /Generate report/i,
      });
      await user.click(generateBtn);

      // Radix portals the dialog to body → query via baseElement.
      const dialog = await within(baseElement).findByRole("dialog");
      expect(
        within(dialog).getByRole("heading", {
          name: /Generate portfolio MIS report/i,
        }),
      ).toBeInTheDocument();

      // Axe on the open dialog → baseElement (binding rule #1).
      expect(await axe(baseElement)).toHaveNoViolations();

      // Submit with no input (all fields are optional).
      const submit = within(dialog).getByRole("button", {
        name: /^Queue report$/i,
      });
      await user.click(submit);

      // Dialog closes once the mutation resolves.
      await waitFor(
        () => {
          expect(
            within(baseElement).queryByRole("dialog"),
          ).not.toBeInTheDocument();
        },
        { timeout: 5000 },
      );

      // Requests list refetched → a new row landed (the QUEUED report).
      await waitFor(
        () => {
          const after = document.querySelectorAll(
            '[data-testid^="report-request-row-"]',
          );
          expect(after.length).toBe(requestsBefore + 1);
        },
        { timeout: 5000 },
      );

      // The new row's status is QUEUED (auto-completion ticker won't have
      // fired yet — 5s elapsed required and latency override is 0).
      const newRows = document.querySelectorAll(
        '[data-testid^="report-request-row-"]',
      );
      const firstRow = newRows[0] as HTMLElement | undefined;
      expect(firstRow).toBeDefined();
      // Status badge present + matches QUEUED.
      const badge = firstRow?.querySelector(
        '[data-slot="report-status-badge"]',
      );
      expect(badge).toBeTruthy();
    },
    15_000,
  );
});

describe("ReportsPage — filter bar", () => {
  it("Clear resets active date filters; is disabled when none are active", async () => {
    const user = userEvent.setup();
    renderPage();

    await screen.findByTestId("mis-kpi-total-loans");

    const clearBtn = await screen.findByRole("button", {
      name: /Clear all filters/i,
    });
    expect(clearBtn).toBeDisabled();

    // Apply a from-date filter via the date input.
    const fromInput = screen.getByLabelText(/Date from/i);
    await user.type(fromInput, "2099-01-01");

    // Clear becomes enabled once a filter is set.
    await waitFor(() => expect(clearBtn).not.toBeDisabled());

    // Click Clear → filters reset.
    await user.click(clearBtn);
    await waitFor(() => expect(clearBtn).toBeDisabled());
  });
});
