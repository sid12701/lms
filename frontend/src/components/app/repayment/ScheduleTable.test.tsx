import { describe, it, expect, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { axe } from "vitest-axe";
import { renderWithProviders } from "@/test/utils";
import type { RepaymentInstallment } from "@/schemas/loan-account";
import { ScheduleTable } from "./ScheduleTable";

function makeInstallment(
  overrides: Partial<RepaymentInstallment> & Pick<RepaymentInstallment, "id" | "number">,
): RepaymentInstallment {
  return {
    accountId: "22222222-2222-4222-8222-222222222222",
    scheduleId: "33333333-3333-4333-8333-333333333333",
    dueDate: "2026-06-15",
    principalDue: 8000,
    interestDue: 2000,
    installmentAmount: 10000,
    paidAmount: 0,
    outstandingAmount: 10000,
    dpd: 0,
    delinquencyBucket: "B0",
    status: "DUE",
    ...overrides,
  };
}

const INSTALLMENTS: RepaymentInstallment[] = [
  makeInstallment({
    id: "11111111-1111-4111-8111-aaaaaaaaaaaa",
    number: 1,
    status: "PAID",
    paidAmount: 10000,
    outstandingAmount: 0,
  }),
  makeInstallment({
    id: "11111111-1111-4111-8111-bbbbbbbbbbbb",
    number: 2,
    status: "DUE",
  }),
  makeInstallment({
    id: "11111111-1111-4111-8111-cccccccccccc",
    number: 3,
    status: "DUE",
    dueDate: "2026-07-15",
  }),
];

describe("ScheduleTable", () => {
  it("renders one row per installment", () => {
    const { getAllByRole, getByText } = renderWithProviders(
      <ScheduleTable installments={INSTALLMENTS} />,
    );
    // header row + body rows
    expect(getAllByRole("row")).toHaveLength(INSTALLMENTS.length + 1);
    expect(getByText("1")).toBeInTheDocument();
    expect(getByText("2")).toBeInTheDocument();
    expect(getByText("3")).toBeInTheDocument();
  });

  it("renders an empty state when there are no installments", () => {
    const { getByText } = renderWithProviders(<ScheduleTable installments={[]} />);
    expect(getByText(/no installments scheduled/i)).toBeInTheDocument();
  });

  it("forwards onRecordPayment from the first non-paid (next-due) row only", async () => {
    const onRecordPayment = vi.fn();
    const { getAllByRole } = renderWithProviders(
      <ScheduleTable installments={INSTALLMENTS} onRecordPayment={onRecordPayment} />,
    );
    const buttons = getAllByRole("button", { name: /record payment/i });
    // Only the next-due (#2) renders an action button.
    expect(buttons).toHaveLength(1);
    await userEvent.click(buttons[0]!);
    expect(onRecordPayment).toHaveBeenCalledTimes(1);
    expect(onRecordPayment.mock.calls[0]![0].number).toBe(2);
  });

  it("respects an explicit nextDueInstallmentId override", () => {
    const { getAllByRole } = renderWithProviders(
      <ScheduleTable
        installments={INSTALLMENTS}
        nextDueInstallmentId={INSTALLMENTS[2]!.id}
        onRecordPayment={() => {}}
      />,
    );
    const buttons = getAllByRole("button", { name: /record payment/i });
    expect(buttons).toHaveLength(1);
    expect(buttons[0]!.getAttribute("aria-label")).toMatch(/installment 3/i);
  });

  it("renders schedule totals above the table", () => {
    const { getByText } = renderWithProviders(<ScheduleTable installments={INSTALLMENTS} />);
    expect(getByText("Total installment amount")).toBeInTheDocument();
    expect(getByText("Outstanding as of today")).toBeInTheDocument();
  });

  it("has no axe violations", async () => {
    const { container } = renderWithProviders(
      <ScheduleTable installments={INSTALLMENTS} onRecordPayment={() => {}} />,
    );
    expect(await axe(container)).toHaveNoViolations();
  });
});
