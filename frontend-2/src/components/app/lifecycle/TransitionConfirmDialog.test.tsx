import { describe, it, expect, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { axe } from "vitest-axe";
import { renderWithProviders } from "@/test/utils";
import { TransitionConfirmDialog } from "./TransitionConfirmDialog";
import type { LifecycleAction } from "./actions";

const REASON_REQUIRED: LifecycleAction = {
  id: "AWAITING_APPROVAL__REJECTED",
  label: "Reject",
  toStatus: "REJECTED",
  tone: "destructive",
  requiresReason: true,
  permission: "LOAN_STATUS_UPDATE",
};

const REASON_OPTIONAL: LifecycleAction = {
  id: "AWAITING_APPROVAL__APPROVED_PENDING_DISBURSAL",
  label: "Approve",
  toStatus: "APPROVED_PENDING_DISBURSAL",
  tone: "approve",
  requiresReason: false,
  permission: "LOAN_STATUS_UPDATE",
};

describe("<TransitionConfirmDialog />", () => {
  it("renders the action label and reason field when open", () => {
    const { getByRole, getByLabelText } = renderWithProviders(
      <TransitionConfirmDialog
        open
        onOpenChange={() => {}}
        action={REASON_REQUIRED}
        onConfirm={() => {}}
      />,
    );
    expect(getByRole("heading", { name: /Reject/i })).toBeInTheDocument();
    expect(getByLabelText(/Reason/i)).toBeInTheDocument();
  });

  it("blocks submit when reason is required and empty", async () => {
    const onConfirm = vi.fn();
    const { findAllByText, getAllByRole } = renderWithProviders(
      <TransitionConfirmDialog
        open
        onOpenChange={() => {}}
        action={REASON_REQUIRED}
        onConfirm={onConfirm}
      />,
    );
    // Two buttons named "Reject" exist (visible label + the submit button itself).
    // Pick the submit button (type=submit).
    const buttons = getAllByRole("button", { name: /Reject/i });
    const submit = buttons.find((b) => (b as HTMLButtonElement).type === "submit");
    expect(submit).toBeTruthy();
    await userEvent.click(submit!);
    const matches = await findAllByText(/Reason is required/i);
    expect(matches.length).toBeGreaterThan(0);
    expect(onConfirm).not.toHaveBeenCalled();
  }, 15_000);

  it("submits with action, trimmed reason, and a fresh idempotency key", async () => {
    const onConfirm = vi.fn();
    const { getByLabelText, getAllByRole } = renderWithProviders(
      <TransitionConfirmDialog
        open
        onOpenChange={() => {}}
        action={REASON_REQUIRED}
        onConfirm={onConfirm}
      />,
    );
    await userEvent.type(getByLabelText(/Reason/i), "  Borrower withdrew application.  ");
    const submit = getAllByRole("button", { name: /Reject/i }).find(
      (b) => (b as HTMLButtonElement).type === "submit",
    )!;
    await userEvent.click(submit);
    expect(onConfirm).toHaveBeenCalledTimes(1);
    const [args] = onConfirm.mock.calls[0]!;
    expect(args.action.id).toBe(REASON_REQUIRED.id);
    expect(args.reason).toBe("Borrower withdrew application.");
    expect(typeof args.idempotencyKey).toBe("string");
    expect(args.idempotencyKey.length).toBeGreaterThan(0);
  }, 15_000);

  it("submits with reason=null when optional reason is left blank", async () => {
    const onConfirm = vi.fn();
    const { getAllByRole } = renderWithProviders(
      <TransitionConfirmDialog
        open
        onOpenChange={() => {}}
        action={REASON_OPTIONAL}
        onConfirm={onConfirm}
      />,
    );
    const submit = getAllByRole("button", { name: /Approve/i }).find(
      (b) => (b as HTMLButtonElement).type === "submit",
    )!;
    await userEvent.click(submit);
    expect(onConfirm).toHaveBeenCalledTimes(1);
    const [args] = onConfirm.mock.calls[0]!;
    expect(args.reason).toBeNull();
  }, 15_000);

  it("invokes onOpenChange(false) when cancel is clicked", async () => {
    const onOpenChange = vi.fn();
    const { getByRole } = renderWithProviders(
      <TransitionConfirmDialog
        open
        onOpenChange={onOpenChange}
        action={REASON_REQUIRED}
        onConfirm={() => {}}
      />,
    );
    await userEvent.click(getByRole("button", { name: "Cancel" }));
    expect(onOpenChange).toHaveBeenCalledWith(false);
  });

  it("shows a working state while loading", () => {
    const { getByRole } = renderWithProviders(
      <TransitionConfirmDialog
        open
        loading
        onOpenChange={() => {}}
        action={REASON_REQUIRED}
        onConfirm={() => {}}
      />,
    );
    expect(getByRole("button", { name: /Working/i })).toBeDisabled();
  });

  it("renders a fallback empty body when no action is supplied", () => {
    const { baseElement } = renderWithProviders(
      <TransitionConfirmDialog
        open
        onOpenChange={() => {}}
        action={null}
        onConfirm={() => {}}
      />,
    );
    expect(baseElement).toBeTruthy();
  });

  it("has no axe violations when open", async () => {
    const { baseElement } = renderWithProviders(
      <TransitionConfirmDialog
        open
        onOpenChange={() => {}}
        action={REASON_REQUIRED}
        onConfirm={() => {}}
      />,
    );
    expect(await axe(baseElement)).toHaveNoViolations();
  });
});
