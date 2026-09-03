import { describe, it, expect, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { axeBaseElement, renderWithProviders } from "@/test/utils";
import { EscalateToAdminDialog } from "./EscalateToAdminDialog";

/**
 * Text of everything a control points at with `aria-describedby`. Asserting
 * through the association — rather than "some element on the page has this
 * text" — is what actually pins WCAG 3.3.1: the message must reach a screen
 * reader *via the control*. It also tolerates the message legitimately
 * appearing twice, inline and in the form-level summary.
 */
function describedByText(el: HTMLElement): string {
  return (el.getAttribute("aria-describedby") ?? "")
    .split(/\s+/u)
    .filter(Boolean)
    .map((id) => document.getElementById(id)?.textContent ?? "")
    .join(" ");
}

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

    const titleInput = await findByLabelText(/^title/i);
    const messageInput = await findByLabelText(/^message/i);
    await user.click(titleInput);
    await user.paste("  Loan stuck in DISBURSEMENT_RETRY ");
    await user.click(messageInput);
    await user.paste("  Disbursement adapter has failed repeatedly; please intervene.  ");
    await user.click(await findByRole("button", { name: "Send escalation" }));

    expect(onConfirm).toHaveBeenCalledTimes(1);
    const args = onConfirm.mock.calls[0]![0] as {
      title: string;
      message: string;
      idempotencyKey: string;
    };
    expect(args.title).toBe("Loan stuck in DISBURSEMENT_RETRY");
    expect(args.message).toBe("Disbursement adapter has failed repeatedly; please intervene.");
    expect(args.idempotencyKey).toMatch(/^[0-9a-f-]{36}$/);
  });

  it("blocks submit and attaches the error to the title field when it is blank", async () => {
    const user = userEvent.setup({ delay: null });
    const { findByLabelText, findByRole, onConfirm } = renderDialog();

    await findByRole("dialog");
    const messageInput = await findByLabelText(/^message/i);
    await user.click(messageInput);
    await user.paste("Body only, no title");
    await user.click(await findByRole("button", { name: "Send escalation" }));

    expect(onConfirm).not.toHaveBeenCalled();
    const titleInput = await findByLabelText(/^title/i);
    expect(titleInput).toHaveAttribute("aria-invalid", "true");
    expect(describedByText(titleInput)).toContain("Title is required.");
  });

  it("blocks submit and attaches the error to the message field when it is blank", async () => {
    const user = userEvent.setup({ delay: null });
    const { findByLabelText, findByRole, onConfirm } = renderDialog();

    await findByRole("dialog");
    const titleInput = await findByLabelText(/^title/i);
    await user.click(titleInput);
    await user.paste("Title only");
    await user.click(await findByRole("button", { name: "Send escalation" }));

    expect(onConfirm).not.toHaveBeenCalled();
    const messageInput = await findByLabelText(/^message/i);
    expect(messageInput).toHaveAttribute("aria-invalid", "true");
    expect(describedByText(messageInput)).toContain("Message is required.");
  });

  /*
   * F-22: this dialog hand-rolled its validation state and so never got the
   * announce-and-focus guarantees every other form in the product has. Both
   * halves are asserted — a live region that names the failures is useless if
   * focus is left on the submit button, and vice versa.
   */
  it("announces validation failures in a live region on submit", async () => {
    const user = userEvent.setup({ delay: null });
    const { findByRole } = renderDialog();

    await findByRole("dialog");
    await user.click(await findByRole("button", { name: "Send escalation" }));

    const summary = await findByRole("alert");
    expect(summary).toHaveTextContent("There are errors in this form");
    expect(summary).toHaveTextContent("Title is required.");
    expect(summary).toHaveTextContent("Message is required.");
  });

  it("moves focus to the first invalid control on submit", async () => {
    const user = userEvent.setup({ delay: null });
    const { findByLabelText, findByRole } = renderDialog();

    await findByRole("dialog");
    // Title is valid, so the first invalid control is Message — the assertion
    // would pass by accident if it were merely "focus the first field".
    const titleInput = await findByLabelText(/^title/i);
    await user.click(titleInput);
    await user.paste("Title only");
    await user.click(await findByRole("button", { name: "Send escalation" }));

    expect(await findByLabelText(/^message/i)).toHaveFocus();
  });

  it("has no axe violations when open (portalled content lives on baseElement)", async () => {
    const { findByRole, baseElement } = renderDialog();
    await findByRole("dialog");

    expect(await axeBaseElement(baseElement)).toHaveNoViolations();
  });
});
