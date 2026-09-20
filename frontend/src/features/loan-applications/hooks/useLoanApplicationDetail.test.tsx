/**
 * useLoanApplicationDetail tests — verifies the TanStack Query wrapper and
 * the query-key helper. The underlying `api-detail.ts` module is mocked so
 * we only exercise hook plumbing, not the dispatch path (covered by
 * api-detail.test.ts).
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { ReactNode } from "react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderHook, waitFor } from "@testing-library/react";
import type { LoanApplicationActivityResponse, LoanApplicationDetail } from "../types";

const detailMock = vi.fn<(id: string) => Promise<LoanApplicationDetail>>();
const activityMock = vi.fn<(id: string) => Promise<LoanApplicationActivityResponse>>();

vi.mock("../api-detail", () => ({
  fetchLoanApplicationDetail: (id: string) => detailMock(id),
  fetchLoanApplicationActivity: (id: string) => activityMock(id),
}));

import {
  DISBURSEMENT_POLL_INTERVAL_MS,
  DISBURSEMENT_POLL_WINDOW_MS,
  loanApplicationDetailQueryKey,
  resolveDisbursementPollInterval,
  useLoanApplicationDetail,
} from "./useLoanApplicationDetail";
import { useLoanApplicationActivity } from "./useLoanApplicationActivity";

const DETAIL_FIXTURE = {
  application: { id: "app-1", status: "INITIALIZED" },
  borrower: { fullName: "Aanya Devi" },
  lsp: { id: "lsp-1", name: "Acme NBFC" },
  product: { id: "prod-1", name: "PL-A" },
  account: null,
  docsComplete: false,
  scheduleValid: false,
  accountDelinquency: null,
  interestRate: null,
} as unknown as LoanApplicationDetail;

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
  activityMock.mockReset();
});
afterEach(() => vi.clearAllMocks());

describe("useLoanApplicationDetail", () => {
  it("returns the fixture on success", async () => {
    detailMock.mockResolvedValue(DETAIL_FIXTURE);
    const { Wrapper } = makeWrapper();
    const { result } = renderHook(() => useLoanApplicationDetail("app-1"), {
      wrapper: Wrapper,
    });
    expect(result.current.isPending).toBe(true);
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.docsComplete).toBe(false);
    expect(detailMock).toHaveBeenCalledWith("app-1");
  });

  it("uses a stable query key keyed by id", () => {
    expect(loanApplicationDetailQueryKey("a")).toEqual(["loan-application", "a"]);
    expect(loanApplicationDetailQueryKey("b")).toEqual(["loan-application", "b"]);
  });

  it("is disabled when id is empty", async () => {
    detailMock.mockResolvedValue(DETAIL_FIXTURE);
    const { Wrapper } = makeWrapper();
    const { result } = renderHook(() => useLoanApplicationDetail(""), {
      wrapper: Wrapper,
    });
    // disabled queries report fetchStatus === "idle" + isPending === true initially
    expect(result.current.fetchStatus).toBe("idle");
    expect(detailMock).not.toHaveBeenCalled();
  });

  it("surfaces errors as isError", async () => {
    detailMock.mockRejectedValue(new Error("boom"));
    const { Wrapper } = makeWrapper();
    const { result } = renderHook(() => useLoanApplicationDetail("app-1"), {
      wrapper: Wrapper,
    });
    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(result.current.error).toBeInstanceOf(Error);
  });

  // L02 — bounded polling only while a disbursement is genuinely in flight.
  it("polls while a disbursement is in flight and stops at a terminal status", async () => {
    vi.useFakeTimers();
    try {
      detailMock.mockResolvedValue({
        ...DETAIL_FIXTURE,
        application: { ...DETAIL_FIXTURE.application, status: "APPROVED_PENDING_DISBURSAL" },
      });
      const { Wrapper } = makeWrapper();
      renderHook(() => useLoanApplicationDetail("app-1"), { wrapper: Wrapper });

      await vi.advanceTimersByTimeAsync(0);
      expect(detailMock).toHaveBeenCalledTimes(1);

      await vi.advanceTimersByTimeAsync(DISBURSEMENT_POLL_INTERVAL_MS);
      expect(detailMock).toHaveBeenCalledTimes(2);

      // The status flips to terminal on the next response.
      detailMock.mockResolvedValue({
        ...DETAIL_FIXTURE,
        application: { ...DETAIL_FIXTURE.application, status: "DISBURSED" },
      });
      await vi.advanceTimersByTimeAsync(DISBURSEMENT_POLL_INTERVAL_MS);
      expect(detailMock).toHaveBeenCalledTimes(3);

      // Terminal state: polling is off — a long wait fetches nothing more.
      await vi.advanceTimersByTimeAsync(DISBURSEMENT_POLL_INTERVAL_MS * 4);
      expect(detailMock).toHaveBeenCalledTimes(3);
    } finally {
      vi.useRealTimers();
    }
  });

  it("does not poll ordinary statuses", async () => {
    vi.useFakeTimers();
    try {
      detailMock.mockResolvedValue(DETAIL_FIXTURE); // INITIALIZED
      const { Wrapper } = makeWrapper();
      renderHook(() => useLoanApplicationDetail("app-1"), { wrapper: Wrapper });

      await vi.advanceTimersByTimeAsync(0);
      expect(detailMock).toHaveBeenCalledTimes(1);
      await vi.advanceTimersByTimeAsync(DISBURSEMENT_POLL_INTERVAL_MS * 4);
      expect(detailMock).toHaveBeenCalledTimes(1);
    } finally {
      vi.useRealTimers();
    }
  });

  it("keeps polling through a retry state but stops once the window expires", async () => {
    vi.useFakeTimers();
    try {
      detailMock.mockResolvedValue({
        ...DETAIL_FIXTURE,
        application: { ...DETAIL_FIXTURE.application, status: "DISBURSEMENT_RETRY" },
      });
      const { Wrapper } = makeWrapper();
      renderHook(() => useLoanApplicationDetail("app-1"), { wrapper: Wrapper });

      await vi.advanceTimersByTimeAsync(0);
      expect(detailMock).toHaveBeenCalledTimes(1);

      await vi.advanceTimersByTimeAsync(DISBURSEMENT_POLL_INTERVAL_MS);
      expect(detailMock).toHaveBeenCalledTimes(2);

      // Past the polling window the query goes quiet — no unbounded polling.
      await vi.advanceTimersByTimeAsync(
        DISBURSEMENT_POLL_WINDOW_MS + DISBURSEMENT_POLL_INTERVAL_MS,
      );
      const callsAtWindow = detailMock.mock.calls.length;
      expect(callsAtWindow).toBeGreaterThan(2);
      await vi.advanceTimersByTimeAsync(DISBURSEMENT_POLL_INTERVAL_MS * 3);
      expect(detailMock).toHaveBeenCalledTimes(callsAtWindow);
    } finally {
      vi.useRealTimers();
    }
  });
});

describe("resolveDisbursementPollInterval", () => {
  it("returns the interval for in-flight statuses inside the window", () => {
    expect(resolveDisbursementPollInterval("APPROVED_PENDING_DISBURSAL", 0)).toBe(
      DISBURSEMENT_POLL_INTERVAL_MS,
    );
    expect(resolveDisbursementPollInterval("DISBURSEMENT_RETRY", 5_000)).toBe(
      DISBURSEMENT_POLL_INTERVAL_MS,
    );
  });

  it("stops for terminal and historical statuses", () => {
    for (const status of ["DISBURSED", "UNDER_REPAYMENT", "INITIALIZED", "REJECTED", "INVALID"]) {
      expect(resolveDisbursementPollInterval(status, 0)).toBe(false);
    }
  });

  it("stops once the polling window has elapsed", () => {
    expect(resolveDisbursementPollInterval("DISBURSEMENT_RETRY", DISBURSEMENT_POLL_WINDOW_MS)).toBe(
      false,
    );
    expect(
      resolveDisbursementPollInterval(
        "APPROVED_PENDING_DISBURSAL",
        DISBURSEMENT_POLL_WINDOW_MS + 1,
      ),
    ).toBe(false);
  });

  it("stops when no status has loaded yet", () => {
    expect(resolveDisbursementPollInterval(undefined, 0)).toBe(false);
  });
});

describe("useLoanApplicationActivity", () => {
  it("respects the enabled option (paused when false)", () => {
    activityMock.mockResolvedValue({ events: [] });
    const { Wrapper } = makeWrapper();
    const { result } = renderHook(() => useLoanApplicationActivity("app-1", { enabled: false }), {
      wrapper: Wrapper,
    });
    expect(result.current.fetchStatus).toBe("idle");
    expect(activityMock).not.toHaveBeenCalled();
  });

  it("fetches when enabled is true", async () => {
    activityMock.mockResolvedValue({ events: [] });
    const { Wrapper } = makeWrapper();
    const { result } = renderHook(() => useLoanApplicationActivity("app-1", { enabled: true }), {
      wrapper: Wrapper,
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(activityMock).toHaveBeenCalledWith("app-1");
  });
});
