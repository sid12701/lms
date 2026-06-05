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
import type {
  LoanApplicationActivityResponse,
  LoanApplicationDetail,
  LoanApplicationWebhooksResponse,
} from "../types";

const detailMock = vi.fn<(id: string) => Promise<LoanApplicationDetail>>();
const activityMock = vi.fn<(id: string) => Promise<LoanApplicationActivityResponse>>();
const webhooksMock = vi.fn<(id: string) => Promise<LoanApplicationWebhooksResponse>>();

vi.mock("../api-detail", () => ({
  fetchLoanApplicationDetail: (id: string) => detailMock(id),
  fetchLoanApplicationActivity: (id: string) => activityMock(id),
  fetchLoanApplicationWebhooks: (id: string) => webhooksMock(id),
}));

import {
  loanApplicationDetailQueryKey,
  useLoanApplicationDetail,
} from "./useLoanApplicationDetail";
import { useLoanApplicationActivity } from "./useLoanApplicationActivity";
import { useLoanApplicationWebhooks } from "./useLoanApplicationWebhooks";

const DETAIL_FIXTURE = {
  application: { id: "app-1", status: "INITIATED" },
  borrower: { fullName: "Aanya Devi" },
  lsp: { id: "lsp-1", name: "Acme NBFC" },
  product: { id: "prod-1", name: "PL-A" },
  account: null,
  docsComplete: false,
  scheduleValid: false,
  accountDelinquency: null,
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
  webhooksMock.mockReset();
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

describe("useLoanApplicationWebhooks", () => {
  it("respects the enabled option (paused when false)", () => {
    webhooksMock.mockResolvedValue({ deliveries: [] });
    const { Wrapper } = makeWrapper();
    const { result } = renderHook(() => useLoanApplicationWebhooks("app-1", { enabled: false }), {
      wrapper: Wrapper,
    });
    expect(result.current.fetchStatus).toBe("idle");
    expect(webhooksMock).not.toHaveBeenCalled();
  });

  it("fetches when enabled is true", async () => {
    webhooksMock.mockResolvedValue({ deliveries: [] });
    const { Wrapper } = makeWrapper();
    const { result } = renderHook(() => useLoanApplicationWebhooks("app-1", { enabled: true }), {
      wrapper: Wrapper,
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(webhooksMock).toHaveBeenCalledWith("app-1");
  });
});
