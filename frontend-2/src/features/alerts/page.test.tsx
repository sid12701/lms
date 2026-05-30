/**
 * AlertsPage smoke tests.
 *
 * The page is wired to the live-backend alerts API module. These tests mock
 * that module directly so the route composition stays deterministic while
 * still exercising filters, table wiring, the acknowledge dialog, and query
 * invalidation behavior.
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
import { SessionProvider } from "@/features/auth/session-context";
import { setLatencyOverride } from "@/mocks/latency";
import { scenario } from "@/mocks/scenarios";
import { resetIdempotency } from "@/mocks/idempotency";
import type { Session } from "@/mocks/api/auth";
import type { AlertRow, AlertsListFilters, AlertsListResponse } from "./types";

const alertsApiMock = vi.hoisted(() => {
  const adminUserId = "11111111-1111-4111-8111-111111111111";
  const openRows = Array.from({ length: 8 }, (_, index) => ({
    id: `aaaaaaaa-aaaa-4aaa-8aaa-${String(index + 1).padStart(12, "0")}`,
    type: "REPORT_REQUEST_STALE",
    severity: index % 2 === 0 ? "HIGH" : "MEDIUM",
    status: "OPEN",
    title: `Open alert ${index + 1}`,
    message: `Open alert message ${index + 1}`,
    subjectType: index % 2 === 0 ? "REPORT_REQUEST" : "SYSTEM",
    subjectId: `subject-open-${index + 1}`,
    correlationId: `bbbbbbbb-bbbb-4bbb-8bbb-${String(index + 1).padStart(12, "0")}`,
    createdAt: `2026-05-26T10:${String(index).padStart(2, "0")}:00.000Z`,
    acknowledgedAt: null,
    acknowledgedBy: null,
    acknowledgmentNote: null,
    acknowledgedByName: null,
  }));
  const ackedRows = Array.from({ length: 4 }, (_, index) => ({
    id: `cccccccc-cccc-4ccc-8ccc-${String(index + 1).padStart(12, "0")}`,
    type: "REPAYMENT_DELAYED",
    severity: "LOW",
    status: "ACKNOWLEDGED",
    title: `Acknowledged alert ${index + 1}`,
    message: `Acknowledged alert message ${index + 1}`,
    subjectType: "LOAN_ACCOUNT",
    subjectId: `subject-acked-${index + 1}`,
    correlationId: `dddddddd-dddd-4ddd-8ddd-${String(index + 1).padStart(12, "0")}`,
    createdAt: `2026-05-26T09:${String(index).padStart(2, "0")}:00.000Z`,
    acknowledgedAt: "2026-05-26T11:00:00.000Z",
    acknowledgedBy: adminUserId,
    acknowledgmentNote: "already reviewed",
    acknowledgedByName: "ops.admin",
  }));
  let rows: AlertRow[] = [...openRows, ...ackedRows] as AlertRow[];

  function resetRows() {
    rows = [...openRows, ...ackedRows].map((row) => ({ ...row })) as AlertRow[];
  }

  function filteredRows(filters = {}) {
    const typedFilters = filters as {
      status?: string;
      severity?: string[];
      subjectType?: string;
      q?: string;
      page?: number;
      pageSize?: number;
    };
    let nextRows = rows;
    if (typedFilters.status) {
      nextRows = nextRows.filter((row) => row.status === typedFilters.status);
    }
    if (typedFilters.severity && typedFilters.severity.length > 0) {
      const severities = new Set(typedFilters.severity);
      nextRows = nextRows.filter((row) => severities.has(row.severity));
    }
    if (typedFilters.subjectType) {
      nextRows = nextRows.filter((row) => row.subjectType === typedFilters.subjectType);
    }
    if (typedFilters.q) {
      const needle = typedFilters.q.toLowerCase();
      nextRows = nextRows.filter(
        (row) =>
          row.title.toLowerCase().includes(needle) || row.message.toLowerCase().includes(needle),
      );
    }
    return nextRows;
  }

  return {
    resetRows,
    listAlerts: vi.fn(async (filters = {}) => {
      const page = typeof filters.page === "number" ? filters.page : 0;
      const pageSize = typeof filters.pageSize === "number" ? filters.pageSize : 25;
      const filtered = filteredRows(filters);
      return {
        items: filtered.slice(page * pageSize, page * pageSize + pageSize),
        total: filtered.length,
        page,
        pageSize,
      };
    }),
    acknowledgeAlert: vi.fn(async (id: string, input: { note: string | null }) => {
      const row = rows.find((candidate) => candidate.id === id);
      if (!row) {
        throw new Error(`alert ${id} not found`);
      }
      const updated: AlertRow = {
        ...row,
        status: "ACKNOWLEDGED",
        acknowledgedAt: "2026-05-26T12:00:00.000Z",
        acknowledgedBy: adminUserId,
        acknowledgmentNote: input.note,
        acknowledgedByName: "ops.admin",
      };
      rows = rows.map((candidate) => (candidate.id === id ? updated : candidate));
      return { alert: updated };
    }),
    listAlertRules: vi.fn(async () => []),
  };
});

vi.mock("./api", () => ({
  listAlerts: alertsApiMock.listAlerts,
  acknowledgeAlert: alertsApiMock.acknowledgeAlert,
  listAlertRules: alertsApiMock.listAlertRules,
}));

// Test shim for AlertsTable. It renders one Acknowledge button per OPEN row
// and an Acknowledged label per already-acked row while preserving the page's
// onAcknowledge contract.
vi.mock("./components/AlertsTable", () => ({
  AlertsTable: (props: {
    data: AlertsListResponse | undefined;
    isLoading: boolean;
    filters: AlertsListFilters;
    onFiltersChange: (next: AlertsListFilters) => void;
    onAcknowledge: (row: AlertRow) => void;
  }) => (
    <div data-testid="alerts-table">
      <span data-testid="alerts-table-loading">{props.isLoading ? "loading" : "idle"}</span>
      <span data-testid="alerts-table-total">{props.data ? String(props.data.total) : "none"}</span>
      <span data-testid="alerts-table-filters">{JSON.stringify(props.filters)}</span>
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

let currentSession: Session | null = null;

function buildAdminSession(): Session {
  return {
    user: {
      id: "11111111-1111-4111-8111-111111111111",
      username: "ops.admin",
      role: "SYSTEM_ADMIN",
      lspId: null,
      mustChangePassword: false,
    },
    accessToken: "test-token",
    expiresAt: "2026-05-26T18:00:00.000Z",
  };
}

function renderPage() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  function Wrapper({ children }: { children: ReactNode }) {
    return (
      <QueryClientProvider client={client}>
        <DensityProvider>
          <SessionProvider skipBootstrap initialSession={currentSession}>
            <MemoryRouter initialEntries={["/alerts"]}>{children}</MemoryRouter>
          </SessionProvider>
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

beforeEach(() => {
  setLatencyOverride(0);
  scenario.reset();
  resetIdempotency();
  alertsApiMock.resetRows();
  vi.clearAllMocks();
  currentSession = buildAdminSession();
});

afterEach(() => {
  currentSession = null;
  scenario.reset();
  setLatencyOverride(null);
});

describe("AlertsPage - load + listing", () => {
  it("renders header, filter bar, and table for an authorised session", async () => {
    renderPage();
    expect(screen.getByTestId("alerts-page")).toBeInTheDocument();
    expect(screen.getByText(/Reporting/i)).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 1, name: /Alerts/i })).toBeInTheDocument();

    expect(await screen.findByRole("group", { name: /Alert filters/i })).toBeInTheDocument();

    await waitFor(() => {
      expect(screen.getByTestId("alerts-table-total")).toHaveTextContent("12");
    });

    const ackBtns = screen.getAllByRole("button", { name: /Acknowledge / });
    expect(ackBtns.length).toBe(8);
  });

  it("is axe-clean on the happy path", async () => {
    const { container } = renderPage();
    await screen.findAllByRole("button", { name: /Acknowledge / });
    expect(await axe(container)).toHaveNoViolations();
  });
});

describe("AlertsPage - acknowledge flow", () => {
  it("opens the dialog, submits a note, and flips the row to Acknowledged", async () => {
    const user = userEvent.setup();
    const { baseElement } = renderPage();

    const ackBtnsBefore = await screen.findAllByRole("button", {
      name: /Acknowledge /,
    });
    const openCountBefore = ackBtnsBefore.length;
    expect(openCountBefore).toBeGreaterThan(0);

    await user.click(ackBtnsBefore[0]!);

    const dialog = await within(baseElement).findByRole("dialog");
    expect(within(dialog).getByRole("heading", { name: /Acknowledge alert/i })).toBeInTheDocument();

    expect(await axe(baseElement)).toHaveNoViolations();

    const note = within(dialog).getByLabelText(/Acknowledgement note/i);
    await user.type(note, "investigated, false alarm");

    const submit = within(dialog).getByRole("button", {
      name: /^Acknowledge$/i,
    });
    await user.click(submit);

    await waitFor(
      () => {
        expect(within(baseElement).queryByRole("dialog")).not.toBeInTheDocument();
      },
      { timeout: 5000 },
    );

    await waitFor(
      () => {
        const after = screen.queryAllByRole("button", {
          name: /Acknowledge /,
        });
        expect(after.length).toBe(openCountBefore - 1);
      },
      { timeout: 5000 },
    );
  }, 15_000);
});

describe("AlertsPage - filter clear", () => {
  it("Clear resets active filters; is disabled when none are active", async () => {
    const user = userEvent.setup();
    renderPage();

    await screen.findAllByRole("button", { name: /Acknowledge / });

    const clearBtn = await screen.findByRole("button", {
      name: /Clear all filters/i,
    });
    expect(clearBtn).toBeDisabled();

    const ackedTab = screen.getByRole("tab", { name: /^Acknowledged$/i });
    await user.click(ackedTab);

    await waitFor(() => expect(clearBtn).not.toBeDisabled());

    await waitFor(() => {
      expect(screen.getByTestId("alerts-table-total")).toHaveTextContent("4");
    });
    expect(screen.queryAllByRole("button", { name: /Acknowledge / })).toHaveLength(0);

    await user.click(clearBtn);
    await waitFor(() => {
      expect(screen.getByTestId("alerts-table-total")).toHaveTextContent("12");
    });
    expect(clearBtn).toBeDisabled();
  });
});
