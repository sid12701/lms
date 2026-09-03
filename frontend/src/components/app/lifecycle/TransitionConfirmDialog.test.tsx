import { describe, it, expect, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { waitFor } from "@testing-library/react";
import { axeBaseElement, renderWithProviders } from "@/test/utils";
import { TransitionConfirmDialog } from "./TransitionConfirmDialog";
import type { LifecycleAction } from "./actions";

const REASON_REQUIRED: LifecycleAction = {
  id: "AWAITING_APPROVAL__REJECTED",
  label: "Reject",
  toStatus: "REJECTED",
  tone: "destructive",
  requiresReason: true,
  requiresReasonCode: false,
  permission: "LOAN_STATUS_UPDATE",
};

const REASON_CODE_REQUIRED: LifecycleAction = {
  ...REASON_REQUIRED,
  requiresReasonCode: true,
};

const REASON_OPTIONAL: LifecycleAction = {
  id: "AWAITING_APPROVAL__APPROVED_PENDING_DISBURSAL",
  label: "Approve",
  toStatus: "APPROVED_PENDING_DISBURSAL",
  tone: "approve",
  requiresReason: false,
  requiresReasonCode: false,
  permission: "LOAN_STATUS_UPDATE",
};

const DISBURSEMENT_ACTION: LifecycleAction = {
  id: "APPROVED_PENDING_DISBURSAL__DISBURSED",
  label: "Initiate disbursement",
  toStatus: "DISBURSED",
  tone: "approve",
  requiresReason: false,
  requiresReasonCode: false,
  permission: "DISBURSEMENT_TRIGGER",
};

const PREVIEW = {
  principal: 45000,
  processingFee: 1012.5,
  netDisbursalAmount: 43987.5,
  paymentMode: "IMPS",
  beneficiaryAccountHolderName: "Preview Borrower",
  beneficiaryBankName: "Preview Bank",
  beneficiaryIfsc: "HDFC0001234",
  maskedBeneficiaryAccountNumber: "XXXXXXXX9012",
  externalLoanId: "EXT-123",
  loanAccountNumber: "LMS-LN-1",
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

  /*
   * F-31: the required marker has to follow the action, not the field. The
   * same textarea is mandatory for a rejection and genuinely optional for an
   * approval, so a hardcoded marker would be wrong half the time — and a
   * marker that overstates the constraint is worse than none.
   */
  it("marks the reason field required only when the action requires one", () => {
    const required = renderWithProviders(
      <TransitionConfirmDialog
        open
        onOpenChange={() => {}}
        action={REASON_REQUIRED}
        onConfirm={() => {}}
      />,
    );
    expect(required.getByLabelText(/^Reason/)).toHaveAttribute("aria-required", "true");
    required.unmount();

    const optional = renderWithProviders(
      <TransitionConfirmDialog
        open
        onOpenChange={() => {}}
        action={REASON_OPTIONAL}
        onConfirm={() => {}}
      />,
    );
    expect(optional.getByLabelText(/^Reason/)).not.toHaveAttribute("aria-required", "true");
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

  it("requires a structured reason code when the action demands one (audit F2)", async () => {
    const onConfirm = vi.fn();
    const { getByLabelText, getAllByRole, findAllByText, findByRole } = renderWithProviders(
      <TransitionConfirmDialog
        open
        onOpenChange={() => {}}
        action={REASON_CODE_REQUIRED}
        onConfirm={onConfirm}
      />,
    );
    await userEvent.type(getByLabelText(/Details/i), "Income docs below policy.");
    const submit = getAllByRole("button", { name: /Reject/i }).find(
      (b) => (b as HTMLButtonElement).type === "submit",
    )!;
    await userEvent.click(submit);
    const matches = await findAllByText(/Select a reason/i);
    expect(matches.length).toBeGreaterThan(0);
    expect(onConfirm).not.toHaveBeenCalled();

    await userEvent.click(getByLabelText("Reason code"));
    await userEvent.click(await findByRole("option", { name: /Failed verification/i }));
    await userEvent.click(submit);
    expect(onConfirm).toHaveBeenCalledTimes(1);
    const [args] = onConfirm.mock.calls[0]!;
    expect(args.reasonCode).toBe("FAILED_VERIFICATION");
    expect(args.reason).toBe("Income docs below policy.");
  }, 15_000);

  it("renders the submit failure inside the dialog", () => {
    const { getByText } = renderWithProviders(
      <TransitionConfirmDialog
        open
        onOpenChange={() => {}}
        action={REASON_REQUIRED}
        onConfirm={() => {}}
        errorMessage="Reason code is required when a loan application is moved to REJECTED."
      />,
    );
    expect(getByText(/Reason code is required/i)).toBeInTheDocument();
  });

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

  it("offers nothing to confirm when no action is supplied", () => {
    const { baseElement, queryByRole, getAllByRole } = renderWithProviders(
      <TransitionConfirmDialog open onOpenChange={() => {}} action={null} onConfirm={() => {}} />,
    );
    // The dialog shell still mounts (the caller controls `open`), but with an
    // empty body — no headline, no reason field, and no submit path, so a null
    // action can never be transitioned by accident.
    expect(baseElement.querySelector('[data-slot="dialog-content"]')).not.toBeNull();
    expect(queryByRole("heading")).toBeNull();
    expect(queryByRole("textbox")).toBeNull();
    expect(getAllByRole("button").map((b) => b.textContent)).toEqual(["Close dialog"]);
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
    expect(await axeBaseElement(baseElement)).toHaveNoViolations();
  });

  /**
   * The reason-field variant above scans the shell, not the money-critical
   * path this conversion touches. A dialog with a spinner or an empty error
   * region passes axe almost by default — the assertion that actually
   * exercises `DisbursementPreviewSummary`'s markup (table semantics, the
   * populated `aria-label`) needs the preview loaded first.
   */
  it("has no axe violations with a loaded disbursement preview", async () => {
    const loadDisbursementPreview = vi.fn(() => Promise.resolve(PREVIEW));
    const { baseElement, findByText } = renderWithProviders(
      <TransitionConfirmDialog
        open
        applicationId="app-1"
        onOpenChange={() => {}}
        action={DISBURSEMENT_ACTION}
        loadDisbursementPreview={loadDisbursementPreview}
        onConfirm={() => {}}
      />,
    );

    expect(await findByText(/Net disbursal/i)).toBeInTheDocument();
    expect(await axeBaseElement(baseElement)).toHaveNoViolations();
  }, 15_000);

  it("renders disbursement money fields and keeps confirm disabled while preview loads", async () => {
    let resolvePreview: ((value: typeof PREVIEW) => void) | undefined;
    const loadDisbursementPreview = vi.fn(
      () =>
        new Promise<typeof PREVIEW>((resolve) => {
          resolvePreview = resolve;
        }),
    );

    const { getByRole, getByText, findByText } = renderWithProviders(
      <TransitionConfirmDialog
        open
        applicationId="app-1"
        onOpenChange={() => {}}
        action={DISBURSEMENT_ACTION}
        loadDisbursementPreview={loadDisbursementPreview}
        onConfirm={() => {}}
      />,
    );

    expect(getByText(/Loading disbursement summary/i)).toBeInTheDocument();
    const submit = getByRole("button", { name: /Initiate disbursement/i });
    expect(submit).toBeDisabled();

    resolvePreview?.(PREVIEW);

    expect(await findByText(/Principal/i)).toBeInTheDocument();
    expect(getByText(/Processing fee/i)).toBeInTheDocument();
    expect(getByText(/Net disbursal/i)).toBeInTheDocument();
    expect(getByText("IMPS")).toBeInTheDocument();
    expect(getByText(PREVIEW.maskedBeneficiaryAccountNumber)).toBeInTheDocument();
    expect(submit).not.toBeDisabled();
  }, 15_000);

  it("keeps confirm disabled when disbursement preview fails", async () => {
    const loadDisbursementPreview = vi.fn(() => Promise.reject(new Error("Preview unavailable")));

    const { getByRole, findByText } = renderWithProviders(
      <TransitionConfirmDialog
        open
        applicationId="app-1"
        onOpenChange={() => {}}
        action={DISBURSEMENT_ACTION}
        loadDisbursementPreview={loadDisbursementPreview}
        onConfirm={() => {}}
      />,
    );

    expect(await findByText(/Preview unavailable/i)).toBeInTheDocument();
    expect(getByRole("button", { name: /Initiate disbursement/i })).toBeDisabled();
  }, 15_000);

  it("does not fetch the disbursement preview while the dialog is closed", () => {
    const loadDisbursementPreview = vi.fn(() => Promise.resolve(PREVIEW));

    renderWithProviders(
      <TransitionConfirmDialog
        open={false}
        applicationId="app-1"
        onOpenChange={() => {}}
        action={DISBURSEMENT_ACTION}
        loadDisbursementPreview={loadDisbursementPreview}
        onConfirm={() => {}}
      />,
    );

    expect(loadDisbursementPreview).not.toHaveBeenCalled();
  });

  /**
   * The old hand-rolled hook always fetched fresh on every open — the
   * `request` object was recreated by identity whenever `enabled` flipped
   * true again, so a re-open could never be served a cached figure. Moving
   * to `useQuery` keeps that: the dialog drops its cached preview on close
   * (see `useDisbursementPreview`'s comment), so re-opening for the same
   * application fetches again rather than flashing yesterday's number.
   */
  it("refetches the disbursement preview on every open instead of reusing a cached one", async () => {
    const loadDisbursementPreview = vi
      .fn<(id: string) => Promise<typeof PREVIEW>>()
      .mockResolvedValueOnce(PREVIEW)
      .mockResolvedValueOnce({ ...PREVIEW, netDisbursalAmount: 50_000 });

    function Wrapper({ open }: { open: boolean }) {
      return (
        <TransitionConfirmDialog
          open={open}
          applicationId="app-1"
          onOpenChange={() => {}}
          action={DISBURSEMENT_ACTION}
          loadDisbursementPreview={loadDisbursementPreview}
          onConfirm={() => {}}
        />
      );
    }

    const { rerender, findByText } = renderWithProviders(<Wrapper open />);
    expect(await findByText(/Net disbursal/i)).toBeInTheDocument();
    expect(loadDisbursementPreview).toHaveBeenCalledTimes(1);

    rerender(<Wrapper open={false} />);
    rerender(<Wrapper open />);

    await waitFor(() => expect(loadDisbursementPreview).toHaveBeenCalledTimes(2));
  }, 15_000);

  /**
   * The audit's P1: the irreversible action was styled and worded exactly like
   * every reversible one. Both halves of the fix are asserted here so a future
   * refactor cannot quietly drop them.
   */
  it("states that disbursement cannot be undone, and names the amount on the confirm", async () => {
    const loadDisbursementPreview = vi.fn(() => Promise.resolve(PREVIEW));

    const { getByRole, findByText } = renderWithProviders(
      <TransitionConfirmDialog
        open
        applicationId="app-1"
        onOpenChange={() => {}}
        action={DISBURSEMENT_ACTION}
        loadDisbursementPreview={loadDisbursementPreview}
        onConfirm={() => {}}
      />,
    );

    expect(await findByText(/This cannot be undone/i)).toBeInTheDocument();
    expect(await findByText(/cannot be recalled or re-initiated/i)).toBeInTheDocument();

    // The operator's last commitment is to a figure, not to a verb.
    const submit = getByRole("button", { name: /Initiate disbursement/i });
    expect(submit).toHaveTextContent("43,987.50");
    expect(submit).toHaveAttribute("data-irreversible", "true");
  }, 15_000);
});
