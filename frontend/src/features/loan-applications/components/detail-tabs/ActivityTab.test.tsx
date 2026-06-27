/**
 * ActivityTab tests — verifies loading / error / empty / populated states
 * and that activity events are wrapped in the `APPLICATION` kind before
 * reaching the timeline.
 */
import { describe, expect, it, vi } from "vitest";
import type { UseQueryResult } from "@tanstack/react-query";
import { axe } from "vitest-axe";
import { renderWithProviders } from "@/test/utils";
import type { LoanApplicationActivityResponse } from "../../types";

const activityHookMock =
  vi.fn<(id: string) => UseQueryResult<LoanApplicationActivityResponse, Error>>();

vi.mock("../../hooks/useLoanApplicationActivity", () => ({
  useLoanApplicationActivity: (id: string) => activityHookMock(id),
  loanApplicationActivityQueryKey: (id: string) => ["loan-application", id, "activity"],
}));

import { ActivityTab } from "./ActivityTab";

function buildResult(
  partial: Partial<UseQueryResult<LoanApplicationActivityResponse, Error>>,
): UseQueryResult<LoanApplicationActivityResponse, Error> {
  return {
    isPending: false,
    isError: false,
    isSuccess: false,
    isLoading: false,
    isFetching: false,
    refetch: vi.fn(),
    data: undefined,
    error: null,
    ...partial,
  } as unknown as UseQueryResult<LoanApplicationActivityResponse, Error>;
}

describe("ActivityTab", () => {
  it("renders skeletons while loading", () => {
    activityHookMock.mockReturnValue(buildResult({ isPending: true }));
    const { container } = renderWithProviders(<ActivityTab applicationId="app-1" />);
    expect(container.querySelector('[data-slot="activity-tab-loading"]')).not.toBeNull();
  });

  it("renders an error state with retry on failure", async () => {
    const refetch = vi.fn();
    activityHookMock.mockReturnValue(
      buildResult({ isError: true, error: new Error("boom"), refetch }),
    );
    const { getByRole, getByText } = renderWithProviders(<ActivityTab applicationId="app-1" />);
    expect(getByText("Couldn't load activity")).toBeInTheDocument();
    getByRole("button", { name: /retry/i }).click();
    expect(refetch).toHaveBeenCalled();
  });

  it("renders an empty timeline when there are no events", () => {
    activityHookMock.mockReturnValue(buildResult({ isSuccess: true, data: { events: [] } }));
    const { getByText } = renderWithProviders(<ActivityTab applicationId="app-1" />);
    expect(getByText("No activity yet")).toBeInTheDocument();
  });

  it("wraps events as APPLICATION kind for the timeline", () => {
    activityHookMock.mockReturnValue(
      buildResult({
        isSuccess: true,
        data: {
          events: [
            {
              id: "evt-1",
              applicationId: "app-1",
              fromStatus: "AWAITING_APPROVAL",
              toStatus: "APPROVED_PENDING_DISBURSAL",
              action: "approve",
              actorId: "user-1",
              actorRole: "OPS_USER",
              channel: "UI",
              correlationId: "corr-1",
              reason: null,
              createdAt: "2026-05-10T11:30:00.000Z",
            },
          ],
        } as LoanApplicationActivityResponse,
      }),
    );
    const { container, getAllByText } = renderWithProviders(<ActivityTab applicationId="app-1" />);
    expect(container.querySelector('[data-slot="audit-timeline"]')).not.toBeNull();
    // AuditEventNode renders humanized status labels and action text.
    expect(getAllByText(/Awaiting approval/i).length).toBeGreaterThan(0);
    expect(getAllByText(/Approve/i).length).toBeGreaterThan(0);
  });

  it("has no axe violations on the populated state", async () => {
    activityHookMock.mockReturnValue(
      buildResult({
        isSuccess: true,
        data: {
          events: [
            {
              id: "evt-1",
              applicationId: "app-1",
              fromStatus: "AWAITING_APPROVAL",
              toStatus: "APPROVED_PENDING_DISBURSAL",
              action: "approve",
              actorId: "user-1",
              actorRole: "OPS_USER",
              channel: "UI",
              correlationId: "corr-1",
              reason: null,
              createdAt: "2026-05-10T11:30:00.000Z",
            },
          ],
        } as LoanApplicationActivityResponse,
      }),
    );
    const { container } = renderWithProviders(<ActivityTab applicationId="app-1" />);
    expect(await axe(container)).toHaveNoViolations();
  });
});
