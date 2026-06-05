import { describe, it, expect, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { axe } from "vitest-axe";
import { renderWithProviders } from "@/test/utils";
import { maskAccount } from "@/lib/format";
import { DisbursementInitiateDialog, type DisbursementTarget } from "./DisbursementInitiateDialog";

const TARGET: DisbursementTarget = {
  accountNumber: "112233445566",
  ifsc: "HDFC0001234",
  beneficiaryName: "Asha Devi",
  bankName: "HDFC Bank",
};

describe("<DisbursementInitiateDialog />", () => {
  it("renders title, masked account, IFSC, beneficiary, and the note textarea when open", () => {
    const { getByRole, getByLabelText, getByText } = renderWithProviders(
      <DisbursementInitiateDialog
        open
        onOpenChange={() => {}}
        target={TARGET}
        onConfirm={() => {}}
      />,
    );
    expect(getByRole("heading", { name: /Initiate disbursement/i })).toBeInTheDocument();
    expect(getByText(maskAccount(TARGET.accountNumber))).toBeInTheDocument();
    expect(getByText(TARGET.ifsc)).toBeInTheDocument();
    expect(getByText(TARGET.beneficiaryName)).toBeInTheDocument();
    expect(getByText(TARGET.bankName!)).toBeInTheDocument();
    expect(getByLabelText(/Note/i)).toBeInTheDocument();
  });

  it("does not render the bank name row when bankName is omitted", () => {
    const { queryByText } = renderWithProviders(
      <DisbursementInitiateDialog
        open
        onOpenChange={() => {}}
        target={{ ...TARGET, bankName: undefined }}
        onConfirm={() => {}}
      />,
    );
    expect(queryByText("Bank")).not.toBeInTheDocument();
  });

  it("submits with note=null when the textarea is empty (note is optional)", async () => {
    const onConfirm = vi.fn();
    const { getByRole } = renderWithProviders(
      <DisbursementInitiateDialog
        open
        onOpenChange={() => {}}
        target={TARGET}
        onConfirm={onConfirm}
      />,
    );
    await userEvent.click(getByRole("button", { name: /Initiate disbursement/i }));
    expect(onConfirm).toHaveBeenCalledTimes(1);
    const [args] = onConfirm.mock.calls[0]!;
    expect(args.note).toBeNull();
    expect(typeof args.idempotencyKey).toBe("string");
    expect(args.idempotencyKey.length).toBeGreaterThan(0);
  }, 15_000);

  it("submits with the trimmed note when filled", async () => {
    const onConfirm = vi.fn();
    const { getByRole, getByLabelText } = renderWithProviders(
      <DisbursementInitiateDialog
        open
        onOpenChange={() => {}}
        target={TARGET}
        onConfirm={onConfirm}
      />,
    );
    await userEvent.type(getByLabelText(/Note/i), "  Verified bank details with borrower.  ");
    await userEvent.click(getByRole("button", { name: /Initiate disbursement/i }));
    expect(onConfirm).toHaveBeenCalledTimes(1);
    const [args] = onConfirm.mock.calls[0]!;
    expect(args.note).toBe("Verified bank details with borrower.");
    expect(typeof args.idempotencyKey).toBe("string");
    expect(args.idempotencyKey.length).toBeGreaterThan(0);
  }, 15_000);

  it("invokes onOpenChange(false) when cancel is clicked", async () => {
    const onOpenChange = vi.fn();
    const { getByRole } = renderWithProviders(
      <DisbursementInitiateDialog
        open
        onOpenChange={onOpenChange}
        target={TARGET}
        onConfirm={() => {}}
      />,
    );
    await userEvent.click(getByRole("button", { name: "Cancel" }));
    expect(onOpenChange).toHaveBeenCalledWith(false);
  });

  it("shows a working state and disables the submit button while loading", () => {
    const { getByRole } = renderWithProviders(
      <DisbursementInitiateDialog
        open
        loading
        onOpenChange={() => {}}
        target={TARGET}
        onConfirm={() => {}}
      />,
    );
    const submit = getByRole("button", { name: /Initiating/i });
    expect(submit).toBeDisabled();
    expect(submit.textContent).toMatch(/Initiating/);
  });

  it("has no axe violations when open", async () => {
    const { baseElement } = renderWithProviders(
      <DisbursementInitiateDialog
        open
        onOpenChange={() => {}}
        target={TARGET}
        onConfirm={() => {}}
      />,
    );
    expect(await axe(baseElement)).toHaveNoViolations();
  });
});
