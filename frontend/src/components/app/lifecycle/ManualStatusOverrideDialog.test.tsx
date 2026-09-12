import { describe, expect, it, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/test/utils";
import { ManualStatusOverrideDialog } from "./ManualStatusOverrideDialog";

describe("<ManualStatusOverrideDialog />", () => {
  it("offers only backend-permitted non-financial targets", async () => {
    const { getByLabelText, findByRole, queryByRole } = renderWithProviders(
      <ManualStatusOverrideDialog open onOpenChange={() => {}} onConfirm={() => {}} />,
    );
    await userEvent.click(getByLabelText("Target status"));
    for (const allowed of ["INITIALIZED", "AWAITING_APPROVAL", "DISBURSEMENT_RETRY", "REJECTED"]) {
      expect(await findByRole("option", { name: allowed })).toBeInTheDocument();
    }
    for (const blocked of ["APPROVED_PENDING_DISBURSAL", "DISBURSED", "CLOSED", "FORECLOSED"]) {
      expect(queryByRole("option", { name: blocked })).not.toBeInTheDocument();
    }
  }, 15_000);

  it("blocks submit until target, reason code, and explanation are entered", async () => {
    const onConfirm = vi.fn();
    const { getAllByRole, findAllByText } = renderWithProviders(
      <ManualStatusOverrideDialog open onOpenChange={() => {}} onConfirm={onConfirm} />,
    );
    const submit = getAllByRole("button", { name: /Confirm override/i }).find(
      (b) => (b as HTMLButtonElement).type === "submit",
    )!;
    await userEvent.click(submit);
    const errors = await findAllByText(/Select|required/i);
    expect(errors.length).toBeGreaterThan(0);
    expect(onConfirm).not.toHaveBeenCalled();
  }, 15_000);

  it("submits a deliberate reason + fresh key distinct from any failed standard key", async () => {
    const onConfirm = vi.fn();
    const { getByLabelText, getAllByRole, findByRole } = renderWithProviders(
      <ManualStatusOverrideDialog open onOpenChange={() => {}} onConfirm={onConfirm} />,
    );
    await userEvent.click(getByLabelText("Target status"));
    await userEvent.click(await findByRole("option", { name: "DISBURSEMENT_RETRY" }));
    await userEvent.click(getByLabelText("Reason code"));
    await userEvent.click(await findByRole("option", { name: /Manual admin override/i }));
    await userEvent.type(
      getByLabelText(/Explanation/i),
      "  Verified by phone; moving back for re-review.  ",
    );
    const submit = getAllByRole("button", { name: /Confirm override/i }).find(
      (b) => (b as HTMLButtonElement).type === "submit",
    )!;
    await userEvent.click(submit);
    expect(onConfirm).toHaveBeenCalledTimes(1);
    const [args] = onConfirm.mock.calls[0]!;
    expect(args.toStatus).toBe("DISBURSEMENT_RETRY");
    expect(args.reason).toBe("Verified by phone; moving back for re-review.");
    expect(args.reasonCode).toBe("MANUAL_ADMIN_OVERRIDE");
    expect(typeof args.idempotencyKey).toBe("string");
    expect(args.idempotencyKey.length).toBeGreaterThan(0);
    expect(args.idempotencyKey).not.toBe("standard-key-1");
  }, 15_000);

  it("cancel/dismiss sends nothing", async () => {
    const onConfirm = vi.fn();
    const onOpenChange = vi.fn();
    const { getByRole } = renderWithProviders(
      <ManualStatusOverrideDialog open onOpenChange={onOpenChange} onConfirm={onConfirm} />,
    );
    await userEvent.click(getByRole("button", { name: /Cancel/i }));
    expect(onConfirm).not.toHaveBeenCalled();
    expect(onOpenChange).toHaveBeenCalledWith(false);
  }, 15_000);

  it("explains the resulting status and reason plainly, without key internals", async () => {
    const { getByText, queryByText } = renderWithProviders(
      <ManualStatusOverrideDialog open onOpenChange={() => {}} onConfirm={() => {}} />,
    );
    expect(getByText(/recorded in the audit log/i)).toBeInTheDocument();
    expect(queryByText(/idempotency/i)).not.toBeInTheDocument();
  }, 15_000);

  async function fillValidPayload(
    ui: ReturnType<typeof renderWithProviders>,
    explanation: string,
  ): Promise<HTMLButtonElement> {
    await userEvent.click(ui.getByLabelText("Target status"));
    await userEvent.click(await ui.findByRole("option", { name: "DISBURSEMENT_RETRY" }));
    await userEvent.click(ui.getByLabelText("Reason code"));
    await userEvent.click(await ui.findByRole("option", { name: /Manual admin override/i }));
    await userEvent.clear(ui.getByLabelText(/Explanation/i));
    await userEvent.type(ui.getByLabelText(/Explanation/i), explanation);
    return ui
      .getAllByRole("button", { name: /Confirm override/i })
      .find((b) => (b as HTMLButtonElement).type === "submit")! as HTMLButtonElement;
  }

  it("reuses the key when the identical payload is retried after an uncertain failure", async () => {
    const onConfirm = vi
      .fn()
      .mockRejectedValueOnce(new Error("uncertain response"))
      .mockResolvedValue(undefined);
    const ui = renderWithProviders(
      <ManualStatusOverrideDialog open onOpenChange={() => {}} onConfirm={onConfirm} />,
    );
    const submit = await fillValidPayload(ui, "Same explanation twice.");
    await userEvent.click(submit);
    await userEvent.click(submit);
    expect(onConfirm).toHaveBeenCalledTimes(2);
    const [first, second] = onConfirm.mock.calls.map(
      ([args]) => args as { idempotencyKey: string },
    );
    expect(first!.idempotencyKey).not.toBe("standard-key-1");
    expect(second!.idempotencyKey).toBe(first!.idempotencyKey);
  }, 15_000);

  it("mints a new key for a changed payload and for a new dialog session", async () => {
    const onConfirm = vi.fn().mockResolvedValue(undefined);
    const ui = renderWithProviders(
      <ManualStatusOverrideDialog open onOpenChange={() => {}} onConfirm={onConfirm} />,
    );
    let submit = await fillValidPayload(ui, "First explanation.");
    await userEvent.click(submit);
    submit = await fillValidPayload(ui, "Changed explanation.");
    await userEvent.click(submit);
    expect(onConfirm).toHaveBeenCalledTimes(2);
    const [first, second] = onConfirm.mock.calls.map(
      ([args]) => args as { idempotencyKey: string },
    );
    expect(second!.idempotencyKey).not.toBe(first!.idempotencyKey);

    // New dialog session: same deliberate payload still gets a new key.
    ui.unmount();
    const fresh = renderWithProviders(
      <ManualStatusOverrideDialog open onOpenChange={() => {}} onConfirm={onConfirm} />,
    );
    const freshSubmit = await fillValidPayload(fresh, "Changed explanation.");
    await userEvent.click(freshSubmit);
    const third = onConfirm.mock.calls[2]![0] as { idempotencyKey: string };
    expect(third.idempotencyKey).not.toBe(first!.idempotencyKey);
    expect(third.idempotencyKey).not.toBe(second!.idempotencyKey);
  }, 15_000);
});
