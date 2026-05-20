/**
 * ActivityTab tests — verifies loading / error / empty / populated states
 * across all three audit kinds the borrower-activity endpoint surfaces.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { axe } from "vitest-axe";
import userEvent from "@testing-library/user-event";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "@/test/utils";
import type { UseQueryResult } from "@tanstack/react-query";
import type { BorrowerActivityResponse } from "../../types";

const useActivityMock =
  vi.fn<[string], UseQueryResult<BorrowerActivityResponse, Error>>();

vi.mock("../../hooks/useBorrowerActivity", () => ({
  useBorrowerActivity: (id: string) => useActivityMock(id),
  borrowerActivityQueryKey: (id: string) => ["borrower", id, "activity"],
}));

import { ActivityTab } from "./ActivityTab";

function buildResult(
  partial: Partial<UseQueryResult<BorrowerActivityResponse, Error>>,
): UseQueryResult<BorrowerActivityResponse, Error> {
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
  } as unknown as UseQueryResult<BorrowerActivityResponse, Error>;
}

const APPLICATION_ENTRY = {
  kind: "APPLICATION",
  event: {
    id: "evt-app-1",
    applicationId: "app-1",
    fromStatus: "UNDER_REVIEW",
    toStatus: "APPROVED",
    action: "approve",
    actorId: "user-1",
    actorRole: "OPS_USER",
    channel: "UI",
    correlationId: "corr-app-1",
    reason: null,
    createdAt: "2026-05-10T11:30:00.000Z",
  },
} as const;

const PII_REVEAL_ENTRY = {
  kind: "PII_REVEAL",
  event: {
    id: "evt-pii-1",
    subjectBorrowerId: "b-1",
    subjectApplicationId: null,
    fieldName: "PAN",
    reason: "Verification before disbursement",
    actorId: "user-1",
    actorRole: "OPS_USER",
    correlationId: "corr-pii-1",
    revealedAt: "2026-05-09T11:30:00.000Z",
  },
} as const;

const DOCUMENT_ACCESS_ENTRY = {
  kind: "DOCUMENT_ACCESS",
  event: {
    id: "evt-doc-1",
    documentId: "doc-1",
    applicationId: "app-1",
    action: "PREVIEW",
    actorId: "user-1",
    actorRole: "OPS_USER",
    correlationId: "corr-doc-1",
    accessedAt: "2026-05-08T11:30:00.000Z",
  },
} as const;

afterEach(() => {
  vi.clearAllMocks();
});

beforeEach(() => {
  useActivityMock.mockReset();
});

describe("ActivityTab", () => {
  it("renders skeletons while loading", () => {
    useActivityMock.mockReturnValue(buildResult({ isPending: true }));
    const { container } = renderWithProviders(<ActivityTab borrowerId="b-1" />);
    expect(container.querySelector('[data-slot="activity-tab-loading"]')).not.toBeNull();
  });

  it("renders an error state with retry on failure", async () => {
    const refetch = vi.fn();
    useActivityMock.mockReturnValue(
      buildResult({ isError: true, error: new Error("boom"), refetch }),
    );
    renderWithProviders(<ActivityTab borrowerId="b-1" />);
    expect(screen.getByText("Couldn't load activity")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: /retry/i }));
    expect(refetch).toHaveBeenCalled();
  }, 15_000);

  it("renders an empty timeline when there is no activity", () => {
    useActivityMock.mockReturnValue(
      buildResult({ isSuccess: true, data: { entries: [] } }),
    );
    renderWithProviders(<ActivityTab borrowerId="b-1" />);
    expect(screen.getByText("No activity yet")).toBeInTheDocument();
  });

  it("renders all three audit kinds in the timeline", () => {
    useActivityMock.mockReturnValue(
      buildResult({
        isSuccess: true,
        data: {
          entries: [APPLICATION_ENTRY, PII_REVEAL_ENTRY, DOCUMENT_ACCESS_ENTRY],
        } as BorrowerActivityResponse,
      }),
    );
    const { container } = renderWithProviders(<ActivityTab borrowerId="b-1" />);
    expect(container.querySelector('[data-slot="audit-timeline"]')).not.toBeNull();

    const kindNodes = container.querySelectorAll('[data-slot="audit-event-node"]');
    expect(kindNodes.length).toBe(3);

    const kinds = Array.from(kindNodes).map((n) => n.getAttribute("data-kind"));
    expect(kinds).toContain("APPLICATION");
    expect(kinds).toContain("PII_REVEAL");
    expect(kinds).toContain("DOCUMENT_ACCESS");
  });

  it("surfaces the PII reveal reason on the row", () => {
    useActivityMock.mockReturnValue(
      buildResult({
        isSuccess: true,
        data: { entries: [PII_REVEAL_ENTRY] } as BorrowerActivityResponse,
      }),
    );
    renderWithProviders(<ActivityTab borrowerId="b-1" />);
    expect(
      screen.getByText(/Verification before disbursement/),
    ).toBeInTheDocument();
  });

  it("renders the application transition headline for APPLICATION entries", () => {
    useActivityMock.mockReturnValue(
      buildResult({
        isSuccess: true,
        data: { entries: [APPLICATION_ENTRY] } as BorrowerActivityResponse,
      }),
    );
    const { getAllByText } = renderWithProviders(
      <ActivityTab borrowerId="b-1" />,
    );
    // AuditEventNode renders the verb in both the headline (UNDER_REVIEW → APPROVED)
    // and the detail body — `getAllByText` handles the dual match.
    expect(getAllByText(/approve/i).length).toBeGreaterThan(0);
  });

  it("has no axe violations on the populated state", async () => {
    useActivityMock.mockReturnValue(
      buildResult({
        isSuccess: true,
        data: {
          entries: [APPLICATION_ENTRY, PII_REVEAL_ENTRY, DOCUMENT_ACCESS_ENTRY],
        } as BorrowerActivityResponse,
      }),
    );
    const { container } = renderWithProviders(<ActivityTab borrowerId="b-1" />);
    expect(await axe(container)).toHaveNoViolations();
  }, 15_000);
});
