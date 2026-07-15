/**
 * ReportsPage smoke tests.
 *
 * Composition target: the page wires the filter bar, KPI summary cards, the
 * MIS preview table, the requests list (with 5s polling), the create dialog,
 * and download mutation. Hooks are mocked so tests stay offline (the live
 * API module targets `/api/v1/internal/reports` on the Spring backend).
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { axe } from "vitest-axe";
import { act, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { renderWithProviders } from "@/test/utils";
import { pickDateInField } from "@/test/date-picker";
import { DensityProvider } from "@/app/providers";
import type { MisPreviewResponseDto, MisSummary, ReportRequest } from "./types";

const SUMMARY_FIXTURE: MisSummary = {
  totalDisbursedMtd: 12_500_000,
  activeLoanCount: 42,
  weightedAvgYieldPct: 14.2,
  portfolioAtRisk30Pct: 3.1,
  totalLoanCount: 53,
};

const PREVIEW_FIXTURE: MisPreviewResponseDto = {
  items: [
    {
      loanId: "00000000-0000-4000-8000-000000000101",
      externalLoanId: "EXT-1",
      borrowerName: "A•••a Devi",
      borrowerId: "00000000-0000-4000-8000-000000000201",
      lspCode: "APEX",
      lspName: "Apex NBFC",
      productCode: "PL-A",
      productName: "Personal Loan A",
      accountNumber: "BHAW-000101",
      amount: 250_000,
      status: "UNDER_REPAYMENT",
      loanStatusDisplay: "UNDER_REPAYMENT",
      disbursalDate: "2026-01-15",
      applicationCreatedAt: "2026-01-01",
      dpd: 0,
      delinquencyBucket: "B0",
      year: 2026,
      processingFee: 2_500,
      disbursalAmount: 247_500,
      interestPct: 14.5,
      tenureMonths: 12,
      emiAmount: 22_500,
      overdueAmount: 0,
      closureDate: null,
      closureReason: null,
      foreclosureDate: null,
      foreclosedAmount: null,
      address: null,
      pan: null,
      aadhaar: "XXXXXXXX1234",
      gender: null,
      state: null,
      zip: null,
      ifsc: null,
      bankAccount: null,
      profession: null,
      income: null,
    },
  ],
  total: 1,
  page: 0,
  pageSize: 25,
};

const createRequestMock = vi.fn();
const downloadRequestMock = vi.fn();

vi.mock("./hooks/useMisSummary", () => ({
  useMisSummary: () => ({
    data: SUMMARY_FIXTURE,
    isLoading: false,
    isError: false,
    error: null,
    refetch: vi.fn(),
  }),
  MIS_SUMMARY_QUERY_KEY: ["reports", "mis-summary"],
}));

vi.mock("./hooks/useMisPreview", () => ({
  useMisPreview: () => ({
    data: PREVIEW_FIXTURE,
    isLoading: false,
    isError: false,
    error: null,
    refetch: vi.fn(),
  }),
  MIS_PREVIEW_QUERY_KEY: ["reports", "mis-preview"],
}));

vi.mock("./hooks/useReportRequests", () => ({
  useReportRequests: () => ({
    data: { items: [] as ReportRequest[] },
    isLoading: false,
    isError: false,
    error: null,
    refetch: vi.fn(),
  }),
  REPORT_REQUESTS_QUERY_KEY: ["reports", "requests"],
}));

vi.mock("./hooks/useCreateReportRequest", () => ({
  useCreateReportRequest: () => ({
    mutateAsync: createRequestMock,
    isPending: false,
  }),
}));

vi.mock("./hooks/useDownloadReportRequest", () => ({
  useDownloadReportRequest: () => ({
    mutateAsync: downloadRequestMock,
    isPending: false,
  }),
}));

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

beforeEach(() => {
  createRequestMock.mockReset();
  downloadRequestMock.mockReset();
  createRequestMock.mockResolvedValue({
    id: "report-new-1",
    type: "PORTFOLIO_MIS",
    status: "QUEUED",
    requestedBy: "ops.admin",
    lspId: null,
    dateFrom: null,
    dateTo: null,
    notificationEmail: null,
    fileMeta: null,
    errorMessage: null,
    queuedAt: "2026-05-25T12:00:00.000Z",
    completedAt: null,
  } satisfies ReportRequest);
});

afterEach(() => {
  vi.clearAllMocks();
});

describe("ReportsPage — load + listing", () => {
  it("renders header, KPI cards, and preview rows for an authorised session", async () => {
    renderPage();
    expect(screen.getByTestId("reports-page")).toBeInTheDocument();
    expect(screen.getByText(/Reporting/i)).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 1, name: /^Reports$/i })).toBeInTheDocument();

    expect(await screen.findByRole("group", { name: /Report filters/i })).toBeInTheDocument();

    expect(screen.getByTestId("mis-kpi-disbursed-mtd")).toBeInTheDocument();
    expect(screen.getByTestId("mis-kpi-active-loans")).toBeInTheDocument();
    expect(screen.getByTestId("mis-kpi-total-loans")).toBeInTheDocument();
    expect(screen.getByTestId("mis-kpi-weighted-yield")).toBeInTheDocument();
    expect(screen.getByTestId("mis-kpi-par-30")).toBeInTheDocument();

    expect(
      screen.getByTestId("mis-preview-row-00000000-0000-4000-8000-000000000101"),
    ).toBeInTheDocument();
  });

  it("is axe-clean on the happy path", async () => {
    const { container } = renderPage();
    await screen.findByTestId("mis-kpi-total-loans");
    expect(
      screen.getByTestId("mis-preview-row-00000000-0000-4000-8000-000000000101"),
    ).toBeInTheDocument();
    await act(async () => {
      expect(await axe(container)).toHaveNoViolations();
    });
  });
});

describe("ReportsPage — create flow", () => {
  it("opens the dialog, submits the form, and the new QUEUED request appears in the list", async () => {
    const user = userEvent.setup();
    const { baseElement } = renderPage();

    await screen.findByTestId("mis-kpi-total-loans");

    const generateBtn = screen.getByRole("button", {
      name: /Generate report/i,
    });
    await user.click(generateBtn);

    const dialog = await within(baseElement).findByRole("dialog");
    expect(
      within(dialog).getByRole("heading", {
        name: /Generate portfolio MIS report/i,
      }),
    ).toBeInTheDocument();

    expect(await axe(baseElement)).toHaveNoViolations();

    const submit = within(dialog).getByRole("button", {
      name: /^Queue report$/i,
    });
    await user.click(submit);

    await waitFor(() => {
      expect(createRequestMock).toHaveBeenCalled();
    });

    await waitFor(
      () => {
        expect(within(baseElement).queryByRole("dialog")).not.toBeInTheDocument();
      },
      { timeout: 5000 },
    );
  }, 15_000);
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

    await pickDateInField(user, /Disbursed from/i, "2026-01-15");

    await waitFor(() => expect(clearBtn).not.toBeDisabled());

    await user.click(clearBtn);
    await waitFor(() => expect(clearBtn).toBeDisabled());
  });
});
