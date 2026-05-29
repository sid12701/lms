/**
 * useLoanApplications tests — verifies the TanStack Query wrapper.
 *
 * We stub `fetchLoanApplications` so the test exercises only the query
 * shape and cache-key choice, not the dispatch pipeline.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderHook, waitFor } from "@testing-library/react";
import type { ReactNode } from "react";
import type { LoanApplicationListFilters, LoanApplicationListResponse } from "../types";

const FIXTURE: LoanApplicationListResponse = {
  items: [
    {
      id: "11111111-1111-4111-8111-111111111111",
      externalLoanId: "EXT-001",
      accountNumber: null,
      borrowerId: "22222222-2222-4222-8222-222222222222",
      borrowerNameMasked: "A•••a Devi",
      lspId: "33333333-3333-4333-8333-333333333333",
      lspName: "Acme NBFC",
      productId: "44444444-4444-4444-8444-444444444444",
      productName: "Personal Loan A",
      requestedAmount: 250_000,
      tenureMonths: 24,
      status: "AWAITING_APPROVAL",
      createdAt: "2026-05-10T08:00:00.000Z",
      updatedAt: "2026-05-11T08:00:00.000Z",
    },
  ],
  total: 1,
  page: 0,
  pageSize: 25,
};

const fetchLoanApplicationsMock =
  vi.fn<(f: LoanApplicationListFilters) => Promise<LoanApplicationListResponse>>();

vi.mock("../api", () => ({
  fetchLoanApplications: (f: LoanApplicationListFilters) => fetchLoanApplicationsMock(f),
}));

// Import AFTER vi.mock so the hook picks up the mocked module.
import { useLoanApplications, LOAN_APPLICATIONS_LIST_QUERY_KEY } from "./useLoanApplications";

function makeWrapper() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, staleTime: 0 } },
  });
  function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  }
  return { client, Wrapper };
}

beforeEach(() => {
  fetchLoanApplicationsMock.mockReset();
});

afterEach(() => {
  vi.clearAllMocks();
});

describe("useLoanApplications", () => {
  it("exports a stable base query key", () => {
    expect(LOAN_APPLICATIONS_LIST_QUERY_KEY).toEqual(["loan-applications", "list"]);
  });

  it("transitions isPending → isSuccess and returns the fixture", async () => {
    fetchLoanApplicationsMock.mockResolvedValue(FIXTURE);
    const { Wrapper } = makeWrapper();
    const { result } = renderHook(() => useLoanApplications({}), {
      wrapper: Wrapper,
    });

    expect(result.current.isPending).toBe(true);
    await waitFor(() => {
      expect(result.current.isSuccess).toBe(true);
    });
    expect(result.current.data).toEqual(FIXTURE);
  });

  it("passes filters through to fetchLoanApplications", async () => {
    fetchLoanApplicationsMock.mockResolvedValue(FIXTURE);
    const { Wrapper } = makeWrapper();
    const filters: LoanApplicationListFilters = {
      q: "demo",
      page: 2,
      pageSize: 10,
    };
    renderHook(() => useLoanApplications(filters), { wrapper: Wrapper });
    await waitFor(() => {
      expect(fetchLoanApplicationsMock).toHaveBeenCalledWith(filters);
    });
  });

  it("differentiates cache entries by filter snapshot", async () => {
    fetchLoanApplicationsMock.mockResolvedValue(FIXTURE);
    const { Wrapper } = makeWrapper();
    renderHook(() => useLoanApplications({ page: 0 }), { wrapper: Wrapper });
    renderHook(() => useLoanApplications({ page: 1 }), { wrapper: Wrapper });
    await waitFor(() => {
      expect(fetchLoanApplicationsMock).toHaveBeenCalledTimes(2);
    });
  });

  it("surfaces errors as isError", async () => {
    fetchLoanApplicationsMock.mockRejectedValue(new Error("boom"));
    const { Wrapper } = makeWrapper();
    const { result } = renderHook(() => useLoanApplications({}), {
      wrapper: Wrapper,
    });
    await waitFor(() => {
      expect(result.current.isError).toBe(true);
    });
    expect(result.current.error).toBeInstanceOf(Error);
  });
});
