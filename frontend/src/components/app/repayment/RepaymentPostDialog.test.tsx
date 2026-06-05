import { describe, it, expect, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { axe } from "vitest-axe";
import { renderWithProviders } from "@/test/utils";
import { RepaymentPostDialog } from "./RepaymentPostDialog";

const OUTSTANDING = 10000;

describe("RepaymentPostDialog", () => {
  it("renders the title and amount input when open", () => {
    const { getByRole, getByLabelText } = renderWithProviders(
      <RepaymentPostDialog
        open
        onOpenChange={() => {}}
        outstandingAmount={OUTSTANDING}
        onConfirm={() => {}}
      />,
    );
    expect(getByRole("heading", { name: /record repayment/i })).toBeInTheDocument();
    expect(getByLabelText(/amount/i)).toBeInTheDocument();
  });

  it("shows the outstanding amount as a read-only field (BR-13)", () => {
    const { getByLabelText } = renderWithProviders(
      <RepaymentPostDialog
        open
        onOpenChange={() => {}}
        outstandingAmount={OUTSTANDING}
        onConfirm={() => {}}
      />,
    );
    const amount = getByLabelText(/amount/i) as HTMLInputElement;
    expect(amount).toHaveAttribute("readonly");
    expect(amount.value).toContain("10");
  });

  it("calls onConfirm with amount, postedAt, mode, and a fresh idempotency key", async () => {
    const onConfirm = vi.fn();
    const { getByRole } = renderWithProviders(
      <RepaymentPostDialog
        open
        onOpenChange={() => {}}
        outstandingAmount={OUTSTANDING}
        defaultPostedAt="2026-05-11T10:30"
        defaultMode="UPI"
        onConfirm={onConfirm}
      />,
    );
    // Default amount equals outstanding, so submitting straight away should
    // pass BR-13 with no edits.
    await userEvent.click(getByRole("button", { name: /post repayment/i }));
    expect(onConfirm).toHaveBeenCalledTimes(1);
    const [args] = onConfirm.mock.calls[0]!;
    expect(args.amount).toBe(OUTSTANDING);
    // The dialog normalises the datetime-local value into a full ISO 8601
    // string (with offset) so the backend's `Iso8601` schema accepts it.
    expect(args.postedAt).toBe(new Date("2026-05-11T10:30").toISOString());
    expect(args.mode).toBe("UPI");
    expect(typeof args.idempotencyKey).toBe("string");
    expect(args.idempotencyKey.length).toBeGreaterThan(0);
  }, 15_000);

  it("invokes onOpenChange(false) when cancel is clicked", async () => {
    const onOpenChange = vi.fn();
    const { getByRole } = renderWithProviders(
      <RepaymentPostDialog
        open
        onOpenChange={onOpenChange}
        outstandingAmount={OUTSTANDING}
        onConfirm={() => {}}
      />,
    );
    await userEvent.click(getByRole("button", { name: "Cancel" }));
    expect(onOpenChange).toHaveBeenCalledWith(false);
  });

  it("shows a working state while loading", () => {
    const { getByRole } = renderWithProviders(
      <RepaymentPostDialog
        open
        loading
        onOpenChange={() => {}}
        outstandingAmount={OUTSTANDING}
        onConfirm={() => {}}
      />,
    );
    expect(getByRole("button", { name: /Posting/i })).toBeDisabled();
    expect(getByRole("button", { name: /Cancel/i })).toBeDisabled();
  });

  it("surfaces the installment label in the dialog copy", () => {
    const { getByText } = renderWithProviders(
      <RepaymentPostDialog
        open
        onOpenChange={() => {}}
        outstandingAmount={OUTSTANDING}
        installmentLabel="Installment 7"
        onConfirm={() => {}}
      />,
    );
    expect(getByText(/for Installment 7/)).toBeInTheDocument();
  });

  it("has no axe violations when open", async () => {
    const { baseElement } = renderWithProviders(
      <RepaymentPostDialog
        open
        onOpenChange={() => {}}
        outstandingAmount={OUTSTANDING}
        onConfirm={() => {}}
      />,
    );
    expect(await axe(baseElement)).toHaveNoViolations();
  }, 15_000);
});
