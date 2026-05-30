/**
 * usePostRepayment tests — verifies the mutation invalidates the four
 * documented query keys + propagates errors from the api wrapper.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderHook, waitFor, act } from "@testing-library/react";
import type { ReactNode } from "react";
import type { PostRepaymentInput } from "../types";

const postRepaymentMock = vi.fn<(id: string, input: PostRepaymentInput) => Promise<void>>();

vi.mock("../api-tabs", () => ({
  postRepayment: (id: string, input: PostRepaymentInput) => postRepaymentMock(id, input),
}));

// Import AFTER vi.mock so the hook picks up the mocked module.
import { usePostRepayment } from "./usePostRepayment";
import { loanApplicationRepaymentsQueryKey } from "./useLoanApplicationRepayments";
import { loanApplicationScheduleQueryKey } from "./useLoanApplicationSchedule";
import { LOAN_APPLICATIONS_LIST_QUERY_KEY } from "./useLoanApplications";

const APPLICATION_ID = "11111111-1111-4111-8111-111111111111";

function makeWrapper() {
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false, staleTime: 0 },
      mutations: { retry: false },
    },
  });
  function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  }
  return { client, Wrapper };
}

beforeEach(() => {
  postRepaymentMock.mockReset();
});

afterEach(() => {
  vi.clearAllMocks();
});

describe("usePostRepayment", () => {
  it("forwards the input to postRepayment with the application id", async () => {
    postRepaymentMock.mockResolvedValue(undefined);
    const { Wrapper } = makeWrapper();
    const { result } = renderHook(() => usePostRepayment(APPLICATION_ID), {
      wrapper: Wrapper,
    });

    await act(async () => {
      await result.current.mutateAsync({
        installmentId: "i-1",
        amount: 5_000,
        postedAt: "2026-05-11T10:00:00.000Z",
        mode: "BANK_TRANSFER",
        idempotencyKey: "idem-key-1",
      });
    });

    expect(postRepaymentMock).toHaveBeenCalledTimes(1);
    const [calledId, calledInput] = postRepaymentMock.mock.calls[0] ?? [];
    expect(calledId).toBe(APPLICATION_ID);
    expect(calledInput).toBeDefined();
    expect(typeof calledInput!.idempotencyKey).toBe("string");
    expect(calledInput!.idempotencyKey.length).toBeGreaterThan(0);
  });

  it("invalidates schedule, repayments, detail, and list keys on success", async () => {
    postRepaymentMock.mockResolvedValue(undefined);
    const { client, Wrapper } = makeWrapper();
    const invalidate = vi.spyOn(client, "invalidateQueries");

    const { result } = renderHook(() => usePostRepayment(APPLICATION_ID), {
      wrapper: Wrapper,
    });

    await act(async () => {
      await result.current.mutateAsync({
        installmentId: "i-1",
        amount: 5_000,
        postedAt: "2026-05-11T10:00:00.000Z",
        mode: "UPI",
        idempotencyKey: "idem-key-2",
      });
    });

    await waitFor(() => {
      expect(result.current.isSuccess).toBe(true);
    });

    const calledKeys = invalidate.mock.calls.map(([arg]) =>
      JSON.stringify((arg as { queryKey: unknown[] }).queryKey),
    );
    expect(calledKeys).toContain(JSON.stringify(loanApplicationScheduleQueryKey(APPLICATION_ID)));
    expect(calledKeys).toContain(JSON.stringify(loanApplicationRepaymentsQueryKey(APPLICATION_ID)));
    expect(calledKeys).toContain(JSON.stringify(["loan-application", APPLICATION_ID]));
    expect(calledKeys).toContain(JSON.stringify([...LOAN_APPLICATIONS_LIST_QUERY_KEY]));
  });

  it("surfaces errors via isError + does not invalidate", async () => {
    postRepaymentMock.mockRejectedValue(new Error("boom"));
    const { client, Wrapper } = makeWrapper();
    const invalidate = vi.spyOn(client, "invalidateQueries");

    const { result } = renderHook(() => usePostRepayment(APPLICATION_ID), {
      wrapper: Wrapper,
    });

    await act(async () => {
      await expect(
        result.current.mutateAsync({
          installmentId: "i-1",
          amount: 5_000,
          postedAt: "2026-05-11T10:00:00.000Z",
          mode: "CASH",
          idempotencyKey: "idem-key-3",
        }),
      ).rejects.toThrow("boom");
    });

    await waitFor(() => {
      expect(result.current.isError).toBe(true);
    });
    expect(invalidate).not.toHaveBeenCalled();
  });
});
