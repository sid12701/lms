import { beforeEach, describe, expect, it, vi } from "vitest";
import type { ReactNode } from "react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderHook, waitFor } from "@testing-library/react";
import { makeMyLoanDetail } from "../test-utils";

const fetchMyLoanDetailMock = vi.fn();
const fetchInvalidReasonsMock = vi.fn();

vi.mock("../api", () => ({
  fetchMyLoanDetail: (...args: unknown[]) => fetchMyLoanDetailMock(...args),
  fetchInvalidReasons: (...args: unknown[]) => fetchInvalidReasonsMock(...args),
}));

import { myLoanDetailQueryKey, useMyLoanDetail } from "./useMyLoanDetail";
import { INVALID_REASONS_QUERY_KEY, useInvalidReasons } from "./useInvalidReasons";

function makeWrapper() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 } },
  });
  function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  }
  return Wrapper;
}

beforeEach(() => {
  fetchMyLoanDetailMock.mockReset();
  fetchInvalidReasonsMock.mockReset();
});

describe("My Loan query hooks", () => {
  it("fetches detail with an ID-specific cache key", async () => {
    const detail = makeMyLoanDetail();
    fetchMyLoanDetailMock.mockResolvedValue(detail);
    const { result } = renderHook(() => useMyLoanDetail(detail.id), { wrapper: makeWrapper() });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toEqual(detail);
    expect(fetchMyLoanDetailMock).toHaveBeenCalledWith(detail.id);
    expect(myLoanDetailQueryKey(detail.id)).toEqual(["my-loans", "detail", detail.id]);
  });

  it("does not fetch detail without a route ID", () => {
    const { result } = renderHook(() => useMyLoanDetail(""), { wrapper: makeWrapper() });

    expect(result.current.fetchStatus).toBe("idle");
    expect(fetchMyLoanDetailMock).not.toHaveBeenCalled();
  });

  it("loads invalid reasons only after the workflow is enabled", async () => {
    fetchInvalidReasonsMock.mockResolvedValue([
      { code: "DUPLICATE", label: "Duplicate", requiresText: false },
    ]);
    const { result, rerender } = renderHook(({ enabled }) => useInvalidReasons(enabled), {
      initialProps: { enabled: false },
      wrapper: makeWrapper(),
    });

    expect(result.current.fetchStatus).toBe("idle");
    expect(fetchInvalidReasonsMock).not.toHaveBeenCalled();

    rerender({ enabled: true });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.[0]?.code).toBe("DUPLICATE");
    expect(fetchInvalidReasonsMock).toHaveBeenCalledTimes(1);
    expect(INVALID_REASONS_QUERY_KEY).toEqual(["my-loans", "invalid-reasons"]);
  });

  it("surfaces detail and invalid-reason failures through query state", async () => {
    fetchMyLoanDetailMock.mockRejectedValue(new Error("detail failed"));
    fetchInvalidReasonsMock.mockRejectedValue(new Error("reasons failed"));
    const Wrapper = makeWrapper();
    const detail = renderHook(() => useMyLoanDetail("loan-1"), { wrapper: Wrapper });
    const reasons = renderHook(() => useInvalidReasons(true), { wrapper: Wrapper });

    await waitFor(() => expect(detail.result.current.isError).toBe(true));
    await waitFor(() => expect(reasons.result.current.isError).toBe(true));
    expect(detail.result.current.error?.message).toBe("detail failed");
    expect(reasons.result.current.error?.message).toBe("reasons failed");
  });
});
