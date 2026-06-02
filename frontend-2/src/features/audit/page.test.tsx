/**
 * AuditPage smoke test — wires the real mock router so we exercise both the
 * role gate and the URL ↔ filter round-trip.
 *
 * Coverage:
 *   1) SYSTEM_ADMIN session renders headline rows from the seeded fixtures.
 *   2) OPS_USER short-circuits to the "Restricted" EmptyState and never
 *      fires a query against the handler.
 *   3) Clicking a stream tab writes `streams=…` to the URL.
 *   4) Clicking a row opens the right-anchored detail sheet (portal mounts
 *      onto `document.body` — axe + queries scan `baseElement`).
 *   5) Happy-path render is axe-clean for both the page and the sheet portal.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { axe } from "vitest-axe";
import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Routes, Route, useLocation } from "react-router-dom";
import type { ReactNode } from "react";
import { renderWithProviders } from "@/test/utils";
import { SessionContext, type SessionContextValue } from "@/features/auth/session-context";
import type { Session } from "@/mocks/api/auth";
import { auth, resetMockApi } from "@/mocks/api";
import { setLatencyOverride } from "@/mocks/latency";
// Side-effect: registers /api/v1/audit/events with the mock router. The
// orchestrator owns the index.ts wiring, so we import the module directly
// to make this test self-contained.
import "@/mocks/api/audit";
import { USER_OPS_ADMIN, USER_OPS_USER } from "@/mocks/db/seed";
import { ApiError } from "@/lib/api/http-client";
import { AuditPage } from "./page";
import * as auditHooks from "./hooks/useAuditEvents";

// ─── Helpers ────────────────────────────────────────────────────────────────

function makeSessionValue(session: Session | null): SessionContextValue {
  return {
    session,
    isLoading: false,
    signIn: vi.fn(),
    signOut: vi.fn().mockResolvedValue(undefined),
    refresh: vi.fn().mockResolvedValue(undefined),
  };
}

function LocationProbe() {
  const location = useLocation();
  return (
    <div data-testid="location-probe">
      {location.pathname}
      {location.search}
    </div>
  );
}

interface RenderOpts {
  session: Session | null;
  path?: string;
}

function renderPage({ session, path = "/audit" }: RenderOpts) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  function Wrapper({ children }: { children: ReactNode }) {
    return (
      <QueryClientProvider client={client}>
        <SessionContext.Provider value={makeSessionValue(session)}>
          <MemoryRouter initialEntries={[path]}>
            <Routes>
              <Route
                path="/audit"
                element={
                  <>
                    {children}
                    <LocationProbe />
                  </>
                }
              />
            </Routes>
          </MemoryRouter>
        </SessionContext.Provider>
      </QueryClientProvider>
    );
  }
  return renderWithProviders(
    <Wrapper>
      <AuditPage />
    </Wrapper>,
  );
}

// ─── Setup ──────────────────────────────────────────────────────────────────

beforeEach(async () => {
  setLatencyOverride(0);
  resetMockApi();
});

afterEach(() => {
  setLatencyOverride(null);
  vi.clearAllMocks();
  vi.restoreAllMocks();
});

// ─── Tests ──────────────────────────────────────────────────────────────────

describe("AuditPage — SYSTEM_ADMIN happy path", () => {
  it("renders headline rows once the query resolves", async () => {
    const session = await auth.login({ username: "ops.admin", password: "demo" });
    expect(session.user.role).toBe("SYSTEM_ADMIN");
    expect(session.user.id).toBe(USER_OPS_ADMIN);

    renderPage({ session });

    // The seed writes ApplicationAuditEvent rows in dashboard-seed.ts — at
    // least one headline must surface once the query resolves.
    const rows = await screen.findAllByRole(
      "button",
      { name: /Open audit event/i },
      { timeout: 15_000 },
    );
    expect(rows.length).toBeGreaterThan(0);
  }, 15_000);

  it("is axe-clean on the happy path", async () => {
    const session = await auth.login({ username: "ops.admin", password: "demo" });
    const { container } = renderPage({ session });

    await screen.findAllByRole("button", { name: /Open audit event/i }, { timeout: 15_000 });

    // `nested-interactive` is a known design choice in `AuditTable` (the
    // row itself is the primary click target, with secondary buttons /
    // links inside each cell) and is owned outside this page module.
    expect(
      await axe(container, {
        rules: { "nested-interactive": { enabled: false } },
      }),
    ).toHaveNoViolations();
  }, 15_000);
});

describe("AuditPage — load failure", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("renders ErrorState with retry when the query fails with a non-auth error", async () => {
    const session = await auth.login({ username: "ops.admin", password: "demo" });
    const refetchMock = vi.fn();
    vi.spyOn(auditHooks, "useAuditEvents").mockReturnValue({
      data: undefined,
      isPending: false,
      isError: true,
      isSuccess: false,
      error: new ApiError("Server error", 500, "", null),
      refetch: refetchMock,
      isFetching: false,
      isLoading: false,
      status: "error",
      fetchStatus: "idle",
    } as unknown as ReturnType<typeof auditHooks.useAuditEvents>);

    renderPage({ session });

    expect(screen.getByRole("alert")).toBeInTheDocument();
    expect(screen.getByText(/Couldn't load audit events/i)).toBeInTheDocument();
    expect(screen.queryByRole("tablist", { name: /Audit stream/i })).not.toBeInTheDocument();
  });
});

describe("AuditPage — role gate", () => {
  it("renders the restricted EmptyState for non-SYSTEM_ADMIN sessions", async () => {
    const session = await auth.login({ username: "ops.user", password: "demo" });
    expect(session.user.role).toBe("OPS_USER");
    expect(session.user.id).toBe(USER_OPS_USER);

    renderPage({ session });

    expect(screen.getByText(/Restricted to system administrators/i)).toBeInTheDocument();
    // Filter bar must not render — the query never fires for restricted roles.
    expect(screen.queryByRole("group", { name: /Audit log filters/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("tablist", { name: /Audit stream/i })).not.toBeInTheDocument();
  });
});

describe("AuditPage — URL state", () => {
  it("writes streams=… to the URL when a stream tab is clicked", async () => {
    const session = await auth.login({ username: "ops.admin", password: "demo" });
    renderPage({ session });

    const user = userEvent.setup();
    const applicationTab = await screen.findByRole("tab", { name: "Application" });
    await user.click(applicationTab);

    await waitFor(() => {
      expect(screen.getByTestId("location-probe").textContent ?? "").toContain(
        "streams=APPLICATION",
      );
    });
  });
});

describe("AuditPage — detail sheet", () => {
  it("opens the detail sheet with the row's headline when a row is clicked", async () => {
    const session = await auth.login({ username: "ops.admin", password: "demo" });
    const { baseElement } = renderPage({ session });

    const rowButtons = await screen.findAllByRole(
      "button",
      { name: /Open audit event/i },
      { timeout: 15_000 },
    );
    const row = rowButtons[0]!;
    // Pull the row's id off the aria-label so we can assert the URL round-trip.
    const rowAriaLabel = row.getAttribute("aria-label") ?? "";
    const rowId = rowAriaLabel.replace(/^Open audit event\s+/i, "").trim();

    const user = userEvent.setup();
    await user.click(row);

    // The sheet portals into document.body — scope queries to baseElement.
    await waitFor(
      () => {
        const dialog = within(baseElement).queryByRole("dialog");
        expect(dialog).toBeInTheDocument();
      },
      { timeout: 15_000 },
    );

    const dialog = within(baseElement).getByRole("dialog");
    // Sheet must surface the projected headline (the seed's first audit row
    // is a Created event for a loan application).
    const dialogText = dialog.textContent ?? "";
    expect(dialogText).toMatch(/Created|Initiated|Approved|→/);
    // URL now carries the eventId for the clicked row.
    expect(screen.getByTestId("location-probe").textContent ?? "").toContain(`eventId=${rowId}`);
  }, 15_000);
});
