import { describe, it, expect, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { axe } from "vitest-axe";
import { renderWithProviders } from "@/test/utils";
import { Table, TableBody, TableHeader, TableHead, TableRow } from "@/components/ui/table";
import type { RepaymentInstallment, InstallmentStatus } from "@/schemas/loan-account";
import { InstallmentRow } from "./InstallmentRow";

const BASE_INSTALLMENT: RepaymentInstallment = {
  id: "11111111-1111-4111-8111-111111111111",
  accountId: "22222222-2222-4222-8222-222222222222",
  scheduleId: "33333333-3333-4333-8333-333333333333",
  number: 7,
  dueDate: "2026-06-15",
  principalDue: 8000,
  interestDue: 2000,
  installmentAmount: 10000,
  paidAmount: 0,
  outstandingAmount: 10000,
  dpd: 0,
  delinquencyBucket: "B0",
  status: "DUE",
};

/** Wrap the row in a real table so the rendered `<tr>` has a valid parent. */
function renderRow(ui: React.ReactNode) {
  return renderWithProviders(
    <Table>
      <TableHeader>
        <TableRow>
          <TableHead>#</TableHead>
          <TableHead>Due</TableHead>
          <TableHead>Principal</TableHead>
          <TableHead>Interest</TableHead>
          <TableHead>Installment</TableHead>
          <TableHead>Paid</TableHead>
          <TableHead>Outstanding</TableHead>
          <TableHead>Status</TableHead>
          <TableHead>DPD</TableHead>
          <TableHead>Action</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>{ui}</TableBody>
    </Table>,
  );
}

describe("InstallmentRow", () => {
  it("renders all columns with formatted figures and date", () => {
    const { getByText, getAllByText } = renderRow(
      <InstallmentRow
        installment={BASE_INSTALLMENT}
        outstandingAmount={BASE_INSTALLMENT.outstandingAmount}
      />,
    );
    expect(getByText("7")).toBeInTheDocument();
    expect(getByText(/15 Jun 2026/)).toBeInTheDocument();
    // 8000 principal + 2000 interest = 10000 installment & outstanding,
    // so "10,000.00" appears in two cells.
    expect(getAllByText(/10,000\.00/).length).toBeGreaterThanOrEqual(2);
    expect(getByText(/8,000\.00/)).toBeInTheDocument();
    expect(getByText(/2,000\.00/)).toBeInTheDocument();
    // The "Due" status badge is inside the row body (not the header).
    expect(getAllByText("Due").length).toBeGreaterThan(0);
  });

  it("shows 'Record payment' only when isNextDue and not PAID", async () => {
    const onRecordPayment = vi.fn();
    const { getByRole, queryByRole, rerender } = renderRow(
      <InstallmentRow
        installment={BASE_INSTALLMENT}
        outstandingAmount={BASE_INSTALLMENT.outstandingAmount}
        isNextDue
        onRecordPayment={onRecordPayment}
      />,
    );
    const button = getByRole("button", {
      name: /record payment for installment 7/i,
    });
    expect(button).toBeInTheDocument();
    await userEvent.click(button);
    expect(onRecordPayment).toHaveBeenCalledWith(BASE_INSTALLMENT);

    // Not the next-due row → no button.
    rerender(
      <Table>
        <TableBody>
          <InstallmentRow
            installment={BASE_INSTALLMENT}
            outstandingAmount={BASE_INSTALLMENT.outstandingAmount}
            isNextDue={false}
            onRecordPayment={onRecordPayment}
          />
        </TableBody>
      </Table>,
    );
    expect(queryByRole("button")).toBeNull();
  });

  it("hides the action button when status is PAID even if isNextDue", () => {
    const { queryByRole } = renderRow(
      <InstallmentRow
        installment={{ ...BASE_INSTALLMENT, status: "PAID" }}
        outstandingAmount={0}
        isNextDue
        onRecordPayment={() => {}}
      />,
    );
    expect(queryByRole("button")).toBeNull();
  });

  it("renders a DPD value when dpd > 0", () => {
    const { getByLabelText, getByText } = renderRow(
      <InstallmentRow
        installment={{ ...BASE_INSTALLMENT, dpd: 14, status: "OVERDUE" }}
        outstandingAmount={BASE_INSTALLMENT.outstandingAmount}
      />,
    );
    expect(getByLabelText(/14 days past due/i)).toBeInTheDocument();
    expect(getByText("14")).toBeInTheDocument();
    expect(getByText("Overdue")).toBeInTheDocument();
  });

  const STATUSES: InstallmentStatus[] = ["DUE", "PARTIALLY_PAID", "PAID", "OVERDUE"];

  it.each(STATUSES)("renders the %s status branch", (status) => {
    const { container } = renderRow(
      <InstallmentRow
        installment={{ ...BASE_INSTALLMENT, status }}
        outstandingAmount={BASE_INSTALLMENT.outstandingAmount}
      />,
    );
    const row = container.querySelector('[data-slot="installment-row"]');
    expect(row?.getAttribute("data-status")).toBe(status);
  });

  it("has no axe violations", async () => {
    const { container } = renderRow(
      <InstallmentRow
        installment={BASE_INSTALLMENT}
        outstandingAmount={BASE_INSTALLMENT.outstandingAmount}
        isNextDue
        onRecordPayment={() => {}}
      />,
    );
    expect(await axe(container)).toHaveNoViolations();
  });
});
