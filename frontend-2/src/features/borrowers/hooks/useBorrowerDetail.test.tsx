/**
 * useBorrowerDetail tests — verifies the TanStack Query wrapper and the
 * query-key helper. The underlying `api.ts` module is mocked so we only
 * exercise hook plumbing, not the dispatch path (covered by api.test.ts).
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { ReactNode } from "react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderHook, waitFor } from "@testing-library/react";
import type { BorrowerDetail } from "../types";

const detailMock = vi.fn<[string], Promise<BorrowerDetail>>();

vi.mock("../api", () => ({
  fetchBorrowerDetail: (id: string) => detailMock(id),
}));

import {
  borrowerDetailQueryKey,
  useBorrowerDetail,
} from "./useBorrowerDetail";

const DETAIL_FIXTURE = {
  borrower: { id: "bor-1", fullName: "Aanya Devi" },
  visibleLsps: [{ id: "lsp-1", name: "Acme NBFC" }],
  totals: {
    openApplicationsCount: 1,
    closedApplicationsCount: 0,
    lifetimeDisbursedAmount: 0,
    activeOverdueAmount: 0,
  },
} as unknown as BorrowerDetail;

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
  detailMock.mockReset();
});
afterEach(() => vi.clearAllMocks());

describe("useBorrowerDetail", () => {
  it("returns the fixture on success", async () => {
    detailMock.mockResolvedValue(DETAIL_FIXTURE);
    const { Wrapper } = makeWrapper();
    const { result } = renderHook(() => useBorrowerDetail("bor-1"), {
      wrapper: Wrapper,
    });
    expect(result.current.isPending).toBe(true);
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.totals.openApplicationsCount).toBe(1);
    expect(detailMock).toHaveBeenCalledWith("bor-1");
  });

  it("uses a stable query key keyed by id", () => {
    expect(borrowerDetailQueryKey("a")).toEqual(["borrower", "a"]);
    expect(borrowerDetailQueryKey("b")).toEqual(["borrower", "b"]);
  });

  it("is disabled when id is empty", () => {
    detailMock.mockResolvedValue(DETAIL_FIXTURE);
    const { Wrapper } = makeWrapper();
    const { result } = renderHook(() => useBorrowerDetail(""), {
      wrapper: Wrapper,
    });
    expect(result.current.fetchStatus).toBe("idle");
    expect(detailMock).not.toHaveBeenCalled();
  });

  it("surfaces errors as isError", async () => {
    detailMock.mockRejectedValue(new Error("boom"));
    const { Wrapper } = makeWrapper();
    const { result } = renderHook(() => useBorrowerDetail("bor-1"), {
      wrapper: Wrapper,
    });
    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(result.current.error).toBeInstanceOf(Error);
  });
});
