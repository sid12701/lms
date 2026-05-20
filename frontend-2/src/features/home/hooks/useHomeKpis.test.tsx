/**
 * useHomeKpis tests — verifies the TanStack Query wrapper.
 *
 * We stub `fetchHomeKpis` so the test exercises only the query shape /
 * cache-key choice, not the underlying transport.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderHook, waitFor } from "@testing-library/react";
import type { ReactNode } from "react";
import type { HomeKpis } from "../types";

const FIXTURE: HomeKpis = {
  kind: "internal",
  data: {
    applicationsAwaitingApproval: 5,
    applicationsInDisbursement: 2,
    mtdDisbursedAmount: 1_000_000,
    overdueLoansCount: 1,
    overdueAmount: 25_000,
    avgApprovalTatHours: 9,
    applicationsByStatus: [{ status: "AWAITING_APPROVAL", count: 5 }],
    dpdBuckets: [
      { bucket: "B0", count: 9 },
      { bucket: "B1_30", count: 1 },
      { bucket: "B31_60", count: 0 },
      { bucket: "B61_90", count: 0 },
      { bucket: "B90_PLUS", count: 0 },
    ],
    recentApplications: [],
    openAlerts: [],
  },
};

const fetchHomeKpisMock = vi.fn<[], Promise<HomeKpis>>();

vi.mock("../api", () => ({
  fetchHomeKpis: () => fetchHomeKpisMock(),
}));

// Importing AFTER vi.mock so the hook picks up the mocked module.
import { useHomeKpis, HOME_KPIS_QUERY_KEY } from "./useHomeKpis";

function makeWrapper() {
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false, staleTime: 0 },
    },
  });
  function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  }
  return { client, Wrapper };
}

beforeEach(() => {
  fetchHomeKpisMock.mockReset();
});

afterEach(() => {
  vi.clearAllMocks();
});

describe("useHomeKpis", () => {
  it("transitions isPending → isSuccess with the fixture", async () => {
    fetchHomeKpisMock.mockResolvedValue(FIXTURE);
    const { Wrapper } = makeWrapper();
    const { result } = renderHook(() => useHomeKpis(), { wrapper: Wrapper });

    expect(result.current.isPending).toBe(true);

    await waitFor(() => {
      expect(result.current.isSuccess).toBe(true);
    });
    expect(result.current.data).toEqual(FIXTURE);
  });

  it("uses the stable query key", () => {
    expect(HOME_KPIS_QUERY_KEY).toEqual(["home", "kpis"]);
  });

  it("surfaces errors as isError", async () => {
    fetchHomeKpisMock.mockRejectedValue(new Error("boom"));
    const { Wrapper } = makeWrapper();
    const { result } = renderHook(() => useHomeKpis(), { wrapper: Wrapper });

    await waitFor(() => {
      expect(result.current.isError).toBe(true);
    });
    expect(result.current.error).toBeInstanceOf(Error);
  });
});
