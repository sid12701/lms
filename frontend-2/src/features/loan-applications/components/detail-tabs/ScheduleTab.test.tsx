/**
 * ScheduleTab tests — hook modules are mocked so the test exercises only
 * the tab's composition + dialog wiring.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { axe } from "vitest-axe";
import { waitFor, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/test/utils";
import type { RepaymentInstallment } from "@/types";

const useScheduleMock = vi.fn();
const usePostRepaymentMock = vi.fn();
const mutateAsyncMock = vi.fn();
const toastSuccess = vi.fn();
const toastError = vi.fn();

vi.mock("sonner", () => ({
  toast: {
    success: (...args: unknown[]) => toastSuccess(...args),
    error: (...args: unknown[]) => toastError(...args),
  },
}));

vi.mock("../../hooks/useLoanApplicationSchedule", () => ({
  useLoanApplicationSchedule: () => useScheduleMock(),
  loanApplicationScheduleQueryKey: (id: string) => ["loan-application", id, "schedule"],
}));
vi.mock("../../hooks/usePostRepayment", () => ({
  usePostRepayment: () => usePostRepaymentMock(),
}));

// Import after mocks.
import { ScheduleTab } from "./ScheduleTab";

function inst(overrides: Partial<RepaymentInstallment> = {}): RepaymentInstallment {
  return {
    id: "00000000-0000-4000-8000-000000000001",
    accountId: "11111111-1111-4111-8111-111111111111",
    scheduleId: "22222222-2222-4222-8222-222222222222",
    number: 1,
    dueDate: "2026-06-01",
    principalDue: 1000,
    interestDue: 200,
    installmentAmount: 1200,
    paidAmount: 0,
    outstandingAmount: 1200,
    dpd: 0,
    delinquencyBucket: "B0",
    status: "DUE",
    ...overrides,
  };
}

beforeEach(() => {
  useScheduleMock.mockReset();
  usePostRepaymentMock.mockReset();
  mutateAsyncMock.mockReset();
  toastSuccess.mockReset();
  toastError.mockReset();
  usePostRepaymentMock.mockReturnValue({
    mutateAsync: mutateAsyncMock,
    isPending: false,
  });
});

afterEach(() => {
  vi.clearAllMocks();
});

describe("ScheduleTab", () => {
  it("renders the skeleton while pending", () => {
    useScheduleMock.mockReturnValue({
      isPending: true,
      isError: false,
      data: undefined,
      refetch: vi.fn(),
    });
    const { container } = renderWithProviders(
      <ScheduleTab
        applicationId="app-1"
        status="DISBURSED"
        canPost
        docsComplete
        scheduleValid
      />,
    );
    expect(container.querySelector('[data-slot="table-skeleton"]')).not.toBeNull();
  });

  it("renders the empty state when no installments are present", () => {
    useScheduleMock.mockReturnValue({
      isPending: false,
      isError: false,
      data: { schedule: null, installments: [] },
      refetch: vi.fn(),
    });
    renderWithProviders(
      <ScheduleTab
        applicationId="app-1"
        status="DISBURSED"
        canPost={false}
        docsComplete
        scheduleValid
      />,
    );
    expect(screen.getByText(/No repayment schedule yet/i)).toBeInTheDocument();
  });

  it("renders the gate banner pre-disbursal when gates fail", () => {
    useScheduleMock.mockReturnValue({
      isPending: false,
      isError: false,
      data: { schedule: null, installments: [inst()] },
      refetch: vi.fn(),
    });
    renderWithProviders(
      <ScheduleTab
        applicationId="app-1"
        status="APPROVED_PENDING_DISBURSAL"
        canPost={false}
        docsComplete={false}
        scheduleValid={false}
      />,
    );
    expect(screen.getByText(/Disbursement blocked/i)).toBeInTheDocument();
  });

  it("hides the gate banner once disbursed", () => {
    useScheduleMock.mockReturnValue({
      isPending: false,
      isError: false,
      data: { schedule: null, installments: [inst()] },
      refetch: vi.fn(),
    });
    renderWithProviders(
      <ScheduleTab
        applicationId="app-1"
        status="DISBURSED"
        canPost={false}
        docsComplete={false}
        scheduleValid={false}
      />,
    );
    expect(screen.queryByText(/Disbursement blocked/i)).toBeNull();
  });

  it("opens the dialog + posts a repayment when canPost is true", async () => {
    const next = inst({ status: "DUE", outstandingAmount: 1200, number: 2 });
    const paid = inst({
      id: "00000000-0000-4000-8000-000000000002",
      number: 1,
      status: "PAID",
      paidAmount: 1200,
      outstandingAmount: 0,
    });
    useScheduleMock.mockReturnValue({
      isPending: false,
      isError: false,
      data: { schedule: { id: "s-1" }, installments: [paid, next] },
      refetch: vi.fn(),
    });
    mutateAsyncMock.mockResolvedValue(undefined);

    const user = userEvent.setup();
    renderWithProviders(
      <ScheduleTab
        applicationId="app-1"
        status="DISBURSED"
        canPost
        docsComplete
        scheduleValid
      />,
    );

    // Click the record-payment trigger on the next-due row.
    const trigger = await screen.findByRole("button", { name: /record/i });
    await user.click(trigger);

    // Dialog mounts in a portal — find via baseElement
    const submit = await screen.findByRole("button", { name: /post repayment/i });
    await user.click(submit);

    await waitFor(() => {
      expect(mutateAsyncMock).toHaveBeenCalledTimes(1);
    });
    const [args] = mutateAsyncMock.mock.calls[0] ?? [];
    expect(args).toMatchObject({
      installmentId: next.id,
      amount: 1200,
      mode: "BANK_TRANSFER",
    });
    expect(typeof (args as { idempotencyKey: string }).idempotencyKey).toBe("string");
    expect((args as { idempotencyKey: string }).idempotencyKey.length).toBeGreaterThan(0);
    expect(toastSuccess).toHaveBeenCalled();
  }, 15_000);

  it("shows an error toast when the mutation rejects", async () => {
    const next = inst({ status: "DUE", outstandingAmount: 500, number: 1 });
    useScheduleMock.mockReturnValue({
      isPending: false,
      isError: false,
      data: { schedule: { id: "s-1" }, installments: [next] },
      refetch: vi.fn(),
    });
    mutateAsyncMock.mockRejectedValue(new Error("BR-13 mismatch"));

    const user = userEvent.setup();
    renderWithProviders(
      <ScheduleTab
        applicationId="app-1"
        status="DISBURSED"
        canPost
        docsComplete
        scheduleValid
      />,
    );

    const trigger = await screen.findByRole("button", { name: /record/i });
    await user.click(trigger);
    const submit = await screen.findByRole("button", { name: /post repayment/i });
    await user.click(submit);

    await waitFor(() => {
      expect(toastError).toHaveBeenCalled();
    });
  }, 15_000);

  it("renders an error state + retry when the query fails", async () => {
    const refetch = vi.fn();
    useScheduleMock.mockReturnValue({
      isPending: false,
      isError: true,
      error: new Error("network down"),
      data: undefined,
      refetch,
    });
    const user = userEvent.setup();
    renderWithProviders(
      <ScheduleTab
        applicationId="app-1"
        status="DISBURSED"
        canPost
        docsComplete
        scheduleValid
      />,
    );
    expect(screen.getByText(/Couldn't load repayment schedule/i)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: /try again/i }));
    expect(refetch).toHaveBeenCalledTimes(1);
  });

  it("renders the awaiting-schedule banner when approved but no installments are present", () => {
    useScheduleMock.mockReturnValue({
      isPending: false,
      isError: false,
      data: { schedule: null, installments: [] },
      refetch: vi.fn(),
    });
    const { container } = renderWithProviders(
      <ScheduleTab
        applicationId="app-1"
        status="APPROVED_PENDING_DISBURSAL"
        canPost={false}
        docsComplete
        scheduleValid={false}
      />,
    );
    expect(
      container.querySelector('[data-slot="awaiting-schedule-banner"]'),
    ).not.toBeNull();
    // The generic empty state should NOT also render — avoid double-emptiness.
    expect(screen.queryByText(/No repayment schedule yet/i)).toBeNull();
  });

  it("renders the invalid-schedule banner above the table when scheduleValid is false", () => {
    useScheduleMock.mockReturnValue({
      isPending: false,
      isError: false,
      data: { schedule: { id: "s-1" }, installments: [inst()] },
      refetch: vi.fn(),
    });
    const { container } = renderWithProviders(
      <ScheduleTab
        applicationId="app-1"
        status="DISBURSED"
        canPost={false}
        docsComplete
        scheduleValid={false}
      />,
    );
    expect(
      container.querySelector('[data-slot="invalid-schedule-banner"]'),
    ).not.toBeNull();
    expect(screen.getByText(/Schedule validation failed/i)).toBeInTheDocument();
  });

  it("is axe-clean on the awaiting-schedule banner render", async () => {
    useScheduleMock.mockReturnValue({
      isPending: false,
      isError: false,
      data: { schedule: null, installments: [] },
      refetch: vi.fn(),
    });
    const { container } = renderWithProviders(
      <ScheduleTab
        applicationId="app-1"
        status="APPROVED_PENDING_DISBURSAL"
        canPost={false}
        docsComplete
        scheduleValid={false}
      />,
    );
    expect(await axe(container)).toHaveNoViolations();
  });

  it("is axe-clean when rendered with installments", async () => {
    useScheduleMock.mockReturnValue({
      isPending: false,
      isError: false,
      data: { schedule: { id: "s-1" }, installments: [inst()] },
      refetch: vi.fn(),
    });
    const { container } = renderWithProviders(
      <ScheduleTab
        applicationId="app-1"
        status="DISBURSED"
        canPost
        docsComplete
        scheduleValid
      />,
    );
    expect(await axe(container)).toHaveNoViolations();
  });
});
