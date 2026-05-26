/**
 * HomePage tests — verifies role branching, loading, error and a11y.
 *
 * The card components live in `@/features/home/components` (owned by the
 * cards agent). At authoring time they had not landed, so we mock the
 * barrel with `data-testid` stubs and assert on testids rather than on
 * the cards' real DOM.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { axe } from "vitest-axe";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { MemoryRouter } from "react-router-dom";
import { renderWithProviders } from "@/test/utils";
import { SessionContext, type SessionContextValue } from "@/features/auth/session-context";
import type { Session } from "@/mocks/api/auth";
import type { HomeKpis } from "./types";

// ─── Stub the cards barrel (agent B's territory) ────────────────────────────
vi.mock("@/features/home/components", () => ({
  InternalKpiSummary: ({ kpis }: { kpis: unknown }) => (
    <div data-testid="internal-kpi-summary">{kpis ? "ok" : "no"}</div>
  ),
  LspKpiSummary: ({ kpis }: { kpis: unknown }) => (
    <div data-testid="lsp-kpi-summary">{kpis ? "ok" : "no"}</div>
  ),
  LoansByDpdBucketCard: () => (
    <div data-testid="loans-by-dpd-card">LoansByDpdBucketCard</div>
  ),
  OpenAlertsCard: () => <div data-testid="open-alerts-card">OpenAlertsCard</div>,
  RecentApplicationsCard: () => (
    <div data-testid="recent-applications-card">RecentApplicationsCard</div>
  ),
  LspLinkCardGrid: () => <div data-testid="lsp-link-card-grid">LspLinkCardGrid</div>,
}));

// ─── Stub the query hook ────────────────────────────────────────────────────
const refetchMock = vi.fn();
const useHomeKpisMock = vi.fn();

vi.mock("./hooks/useHomeKpis", () => ({
  useHomeKpis: () => useHomeKpisMock(),
  HOME_KPIS_QUERY_KEY: ["home", "kpis"],
}));

// Import AFTER the vi.mock calls so the page picks up the stubs.
import { HomePage } from "./page";

// ─── Helpers ────────────────────────────────────────────────────────────────
const INTERNAL_FIXTURE: HomeKpis = {
  kind: "internal",
  data: {
    applicationsAwaitingApproval: 12,
    applicationsInDisbursement: 4,
    mtdDisbursedAmount: 4_750_000,
    overdueLoansCount: 3,
    overdueAmount: 125_000,
    avgApprovalTatHours: 18.5,
    applicationsByStatus: [{ status: "AWAITING_APPROVAL", count: 12 }],
    dpdBuckets: [
      { bucket: "B0", count: 20 },
      { bucket: "B1_30", count: 2 },
      { bucket: "B31_60", count: 1 },
      { bucket: "B61_90", count: 0 },
      { bucket: "B90_PLUS", count: 0 },
    ],
    recentApplications: [
      {
        id: "loan-1",
        externalLoanId: "EXT-001",
        borrowerNameMasked: "A•••a Devi",
        lspName: "Acme NBFC",
        productName: "PL-A",
        status: "AWAITING_APPROVAL",
        requestedAmount: 250_000,
        createdAt: "2026-05-10T08:00:00.000Z",
      },
    ],
    openAlerts: [
      {
        id: "a-1",
        severity: "HIGH",
        title: "Webhook delivery failing",
        subjectType: "WEBHOOK_DELIVERY",
        subjectId: "wd-1",
        createdAt: "2026-05-11T07:00:00.000Z",
      },
    ],
  },
};

function makeSession(role: Session["user"]["role"]): SessionContextValue {
  const session: Session = {
    user: {
      id: "00000000-0000-4000-8000-000000000001",
      username: "tester",
      role,
      lspId: role === "LSP_UI_READ" || role === "LSP_UI_WRITE" ? "00000000-0000-4000-8000-000000000099" : null,
      mustChangePassword: false,
    },
    accessToken: "mock.token",
    expiresAt: new Date(Date.now() + 3600_000).toISOString(),
  };
  return {
    session,
    isLoading: false,
    signIn: vi.fn(),
    signOut: vi.fn().mockResolvedValue(undefined),
    refresh: vi.fn().mockResolvedValue(undefined),
  };
}

function renderHome(sessionValue: SessionContextValue) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  function Wrapper({ children }: { children: ReactNode }) {
    return (
      <QueryClientProvider client={client}>
        <SessionContext.Provider value={sessionValue}>
          <MemoryRouter initialEntries={["/home"]}>{children}</MemoryRouter>
        </SessionContext.Provider>
      </QueryClientProvider>
    );
  }
  return renderWithProviders(
    <Wrapper>
      <HomePage />
    </Wrapper>,
  );
}

beforeEach(() => {
  refetchMock.mockReset();
  useHomeKpisMock.mockReset();
});

afterEach(() => {
  vi.clearAllMocks();
});

// ─── Tests ──────────────────────────────────────────────────────────────────

describe("HomePage — internal", () => {
  beforeEach(() => {
    useHomeKpisMock.mockReturnValue({
      data: INTERNAL_FIXTURE,
      isPending: false,
      isError: false,
      isSuccess: true,
      error: null,
      refetch: refetchMock,
    });
  });

  it("renders the internal cards for a SYSTEM_ADMIN session", () => {
    renderHome(makeSession("SYSTEM_ADMIN"));
    expect(screen.getByTestId("home-page-internal")).toBeInTheDocument();
    expect(screen.getByTestId("internal-kpi-summary")).toBeInTheDocument();
    expect(screen.getByTestId("loans-by-dpd-card")).toBeInTheDocument();
    expect(screen.getByTestId("open-alerts-card")).toBeInTheDocument();
    expect(screen.getByTestId("recent-applications-card")).toBeInTheDocument();
    // LSP-only widgets must not appear.
    expect(screen.queryByTestId("lsp-kpi-summary")).not.toBeInTheDocument();
    expect(screen.queryByTestId("lsp-link-card-grid")).not.toBeInTheDocument();
  });

  it("uses the Internal workspace eyebrow + heading for SYSTEM_ADMIN", () => {
    // Gap #8: Home is admin-only. OPS_USER no longer sees it (redirect tested
    // below); the eyebrow assertion lives on the SYSTEM_ADMIN path now.
    renderHome(makeSession("SYSTEM_ADMIN"));
    expect(screen.getByText(/Internal workspace/i)).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 1, name: /Home/ })).toBeInTheDocument();
  });

  it("is axe-clean on the happy path", async () => {
    const { container } = renderHome(makeSession("SYSTEM_ADMIN"));
    expect(await axe(container)).toHaveNoViolations();
  });
});

describe("HomePage — non-admin redirect (Gap #8)", () => {
  beforeEach(() => {
    useHomeKpisMock.mockReturnValue({
      data: INTERNAL_FIXTURE,
      isPending: false,
      isError: false,
      isSuccess: true,
      error: null,
      refetch: refetchMock,
    });
  });

  it("redirects OPS_USER to /loan-applications instead of rendering Home", () => {
    renderHome(makeSession("OPS_USER"));
    // No Home content is rendered for OPS_USER — Navigate replaces the page.
    expect(screen.queryByTestId("home-page-internal")).not.toBeInTheDocument();
    expect(screen.queryByTestId("home-page-lsp")).not.toBeInTheDocument();
    expect(screen.queryByRole("heading", { level: 1, name: /Home/ })).not.toBeInTheDocument();
  });

  it("redirects LSP_UI_WRITE to /my-loans instead of rendering Home", () => {
    renderHome(makeSession("LSP_UI_WRITE"));
    expect(screen.queryByTestId("home-page-internal")).not.toBeInTheDocument();
    expect(screen.queryByTestId("home-page-lsp")).not.toBeInTheDocument();
  });

  it("redirects LSP_UI_READ to /my-loans instead of rendering Home", () => {
    renderHome(makeSession("LSP_UI_READ"));
    expect(screen.queryByTestId("home-page-internal")).not.toBeInTheDocument();
    expect(screen.queryByTestId("home-page-lsp")).not.toBeInTheDocument();
  });

  it("redirects PRODUCT_ADMIN to /products instead of rendering Home", () => {
    renderHome(makeSession("PRODUCT_ADMIN"));
    expect(screen.queryByTestId("home-page-internal")).not.toBeInTheDocument();
    expect(screen.queryByTestId("home-page-lsp")).not.toBeInTheDocument();
  });
});

describe("HomePage — loading state", () => {
  it("renders skeletons while the query is pending", () => {
    useHomeKpisMock.mockReturnValue({
      data: undefined,
      isPending: true,
      isError: false,
      isSuccess: false,
      error: null,
      refetch: refetchMock,
    });
    renderHome(makeSession("SYSTEM_ADMIN"));
    expect(screen.getByTestId("home-page-loading")).toBeInTheDocument();
    // KpiSkeleton + CardSkeleton each render role=status.
    expect(screen.getAllByRole("status").length).toBeGreaterThan(0);
  });
});

describe("HomePage — error state", () => {
  beforeEach(() => {
    useHomeKpisMock.mockReturnValue({
      data: undefined,
      isPending: false,
      isError: true,
      isSuccess: false,
      error: new Error("boom"),
      refetch: refetchMock,
    });
  });

  it("renders an ErrorState with a Retry button", () => {
    renderHome(makeSession("SYSTEM_ADMIN"));
    expect(screen.getByRole("alert")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Retry/i })).toBeInTheDocument();
  });

  it("clicking Retry calls refetch()", async () => {
    const user = userEvent.setup();
    renderHome(makeSession("SYSTEM_ADMIN"));
    await user.click(screen.getByRole("button", { name: /Retry/i }));
    await waitFor(() => {
      expect(refetchMock).toHaveBeenCalledTimes(1);
    });
  });
});
