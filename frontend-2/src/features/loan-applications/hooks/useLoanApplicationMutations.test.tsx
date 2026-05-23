/**
 * Mutation-hook tests. Covers:
 *   - postTransition / postDisbursement are invoked with the supplied input
 *   - on success, detail / activity / list query caches are invalidated
 *   - idempotency keys flow through the mutate args unchanged
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { ReactNode } from "react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderHook, waitFor } from "@testing-library/react";
import type {
  DisbursementResponse,
  TransitionResponse,
} from "../api-detail";

const postTransitionMock =
  vi.fn<
    (
      id: string,
      input: { to: string; reason: string | null; idempotencyKey: string },
    ) => Promise<TransitionResponse>
  >();
const postDisbursementMock =
  vi.fn<
    (
      id: string,
      input: { note: string | null; idempotencyKey: string },
    ) => Promise<DisbursementResponse>
  >();

vi.mock("../api-detail", () => ({
  postTransition: (id: string, input: { to: string; reason: string | null; idempotencyKey: string }) =>
    postTransitionMock(id, input),
  postDisbursement: (id: string, input: { note: string | null; idempotencyKey: string }) =>
    postDisbursementMock(id, input),
}));

import {
  useInitiateDisbursement,
  useTransitionStatus,
} from "./useLoanApplicationMutations";

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

const FAKE_TRANSITION_RESPONSE = {
  application: { id: "app-1", status: "APPROVED" },
  event: {
    id: "evt-1",
    applicationId: "app-1",
    fromStatus: "AWAITING_APPROVAL",
    toStatus: "APPROVED",
    action: "approve",
    actorId: "u",
    actorRole: "OPS_USER",
    channel: "UI",
    correlationId: "c",
    reason: null,
    createdAt: "2026-05-11T00:00:00.000Z",
  },
} as unknown as TransitionResponse;

const FAKE_DISBURSEMENT_RESPONSE = {
  application: { id: "app-1" },
  events: [],
} as unknown as DisbursementResponse;

beforeEach(() => {
  postTransitionMock.mockReset();
  postDisbursementMock.mockReset();
});
afterEach(() => vi.clearAllMocks());

describe("useTransitionStatus", () => {
  it(
    "calls postTransition with the args and forwards the idempotency key",
    { timeout: 15_000 },
    async () => {
      postTransitionMock.mockResolvedValue(FAKE_TRANSITION_RESPONSE);
      const { Wrapper } = makeWrapper();
      const { result } = renderHook(() => useTransitionStatus("app-1"), {
        wrapper: Wrapper,
      });

      const args = {
        to: "APPROVED" as const,
        reason: "Looks good",
        idempotencyKey: "key-123",
      };
      await result.current.mutateAsync(args);

      expect(postTransitionMock).toHaveBeenCalledWith("app-1", args);
      expect(typeof args.idempotencyKey).toBe("string");
      expect(args.idempotencyKey.length).toBeGreaterThan(0);
    },
  );

  it("invalidates detail + activity + list caches on success", { timeout: 15_000 }, async () => {
    postTransitionMock.mockResolvedValue(FAKE_TRANSITION_RESPONSE);
    const { client, Wrapper } = makeWrapper();
    const invalidateSpy = vi.spyOn(client, "invalidateQueries");

    const { result } = renderHook(() => useTransitionStatus("app-1"), {
      wrapper: Wrapper,
    });

    await result.current.mutateAsync({
      to: "APPROVED",
      reason: null,
      idempotencyKey: "k",
    });

    await waitFor(() => expect(invalidateSpy).toHaveBeenCalled());

    const calledKeys = invalidateSpy.mock.calls.map(
      ([arg]) => (arg as { queryKey: unknown[] }).queryKey,
    );
    // Detail key
    expect(calledKeys).toContainEqual(["loan-application", "app-1"]);
    // Activity key
    expect(calledKeys).toContainEqual(["loan-application", "app-1", "activity"]);
    // List key
    expect(calledKeys).toContainEqual(["loan-applications", "list"]);
  });

  it("surfaces errors through mutateAsync", { timeout: 15_000 }, async () => {
    postTransitionMock.mockRejectedValue(new Error("nope"));
    const { Wrapper } = makeWrapper();
    const { result } = renderHook(() => useTransitionStatus("app-1"), {
      wrapper: Wrapper,
    });
    await expect(
      result.current.mutateAsync({ to: "APPROVED", reason: null, idempotencyKey: "k" }),
    ).rejects.toThrow("nope");
  });
});

describe("useInitiateDisbursement", () => {
  it("calls postDisbursement and invalidates the same set", { timeout: 15_000 }, async () => {
    postDisbursementMock.mockResolvedValue(FAKE_DISBURSEMENT_RESPONSE);
    const { client, Wrapper } = makeWrapper();
    const invalidateSpy = vi.spyOn(client, "invalidateQueries");

    const { result } = renderHook(() => useInitiateDisbursement("app-1"), {
      wrapper: Wrapper,
    });

    await result.current.mutateAsync({ note: null, idempotencyKey: "disb-key" });

    expect(postDisbursementMock).toHaveBeenCalledWith("app-1", {
      note: null,
      idempotencyKey: "disb-key",
    });
    await waitFor(() => expect(invalidateSpy).toHaveBeenCalled());
  });
});
