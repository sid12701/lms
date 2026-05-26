import { describe, it, expect, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/test/utils";
import { EscalateToAdminDialog } from "./EscalateToAdminDialog";

describe("<EscalateToAdminDialog />", () => {
  function renderDialog(overrides: Partial<Parameters<typeof EscalateToAdminDialog>[0]> = {}) {
    const onConfirm = vi.fn().mockResolvedValue(undefined);
    const onOpenChange = vi.fn();
    const utils = renderWithProviders(
      <EscalateToAdminDialog
        open
        onOpenChange={onOpenChange}
        subjectType="LOAN_APPLICATION"
        subjectId="11111111-1111-1111-1111-111111111111"
        onConfirm={onConfirm}
        {...overrides}
      />,
    );
    return { ...utils, onConfirm, onOpenChange };
  }

  it("submits trimmed title + message and forwards a fresh idempotency key", async () => {
    const user = userEvent.setup({ delay: null });
    const { findByLabelText, findByRole, onConfirm } = renderDialog();

    // Wait for the dialog to mount and focus to land on the title input.
    await findByRole("dialog");

    const titleInput = await findByLabelText("Title");
    const messageInput = await findByLabelText("Message");
    await user.click(titleInput);
    await user.paste("  Loan stuck in DISBURSEMENT_RETRY ");
    await user.click(messageInput);
    await user.paste(
      "  Disbursement adapter has failed repeatedly; please intervene.  ",
    );
    await user.click(await findByRole("button", { name: "Send escalation" }));

    expect(onConfirm).toHaveBeenCalledTimes(1);
    const args = onConfirm.mock.calls[0]![0] as {
      title: string;
      message: string;
      idempotencyKey: string;
    };
    expect(args.title).toBe("Loan stuck in DISBURSEMENT_RETRY");
    expect(args.message).toBe(
      "Disbursement adapter has failed repeatedly; please intervene.",
    );
    expect(args.idempotencyKey).toMatch(/^[0-9a-f-]{36}$/);
  });

  it("blocks submit and surfaces an error when title is blank", async () => {
    const user = userEvent.setup({ delay: null });
    const { findByLabelText, findByRole, findByText, onConfirm } = renderDialog();

    await findByRole("dialog");
    const messageInput = await findByLabelText("Message");
    await user.click(messageInput);
    await user.paste("Body only, no title");
    await user.click(await findByRole("button", { name: "Send escalation" }));

    expect(onConfirm).not.toHaveBeenCalled();
    await findByText("Title is required.");
  });

  it("blocks submit and surfaces an error when message is blank", async () => {
    const user = userEvent.setup({ delay: null });
    const { findByLabelText, findByRole, findByText, onConfirm } = renderDialog();

    await findByRole("dialog");
    const titleInput = await findByLabelText("Title");
    await user.click(titleInput);
    await user.paste("Title only");
    await user.click(await findByRole("button", { name: "Send escalation" }));

    expect(onConfirm).not.toHaveBeenCalled();
    await findByText("Message is required.");
  });
});
