import { describe, it, expect, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { axe } from "vitest-axe";
import { renderWithProviders } from "@/test/utils";
import { ForeclosureRequestDialog } from "./ForeclosureRequestDialog";

describe("<ForeclosureRequestDialog />", () => {
  it("renders title and reason textarea when open", () => {
    const { getByRole, getByLabelText } = renderWithProviders(
      <ForeclosureRequestDialog open onOpenChange={() => {}} onConfirm={() => {}} />,
    );
    expect(
      getByRole("heading", { name: /Request foreclosure/i }),
    ).toBeInTheDocument();
    expect(getByLabelText("Reason")).toBeInTheDocument();
    expect(getByLabelText(/Note \(optional\)/i)).toBeInTheDocument();
  });

  it("blocks submit when reason is empty", async () => {
    const onConfirm = vi.fn();
    const { findAllByText, getAllByRole } = renderWithProviders(
      <ForeclosureRequestDialog open onOpenChange={() => {}} onConfirm={onConfirm} />,
    );
    const submit = getAllByRole("button", { name: /Request foreclosure/i }).find(
      (b) => (b as HTMLButtonElement).type === "submit",
    );
    expect(submit).toBeTruthy();
    await userEvent.click(submit!);
    const matches = await findAllByText(/Reason is required/i);
    expect(matches.length).toBeGreaterThan(0);
    expect(onConfirm).not.toHaveBeenCalled();
  }, 15_000);

  it("submits with trimmed reason, trimmed note, and a fresh idempotency key", async () => {
    const onConfirm = vi.fn();
    const { getByLabelText, getAllByRole } = renderWithProviders(
      <ForeclosureRequestDialog open onOpenChange={() => {}} onConfirm={onConfirm} />,
    );
    await userEvent.type(
      getByLabelText("Reason"),
      "  Borrower requested closure.  ",
    );
    await userEvent.type(
      getByLabelText(/Note \(optional\)/i),
      "  Sales-ops escalation 2391.  ",
    );
    const submit = getAllByRole("button", { name: /Request foreclosure/i }).find(
      (b) => (b as HTMLButtonElement).type === "submit",
    )!;
    await userEvent.click(submit);
    expect(onConfirm).toHaveBeenCalledTimes(1);
    const [args] = onConfirm.mock.calls[0]!;
    expect(args.reason).toBe("Borrower requested closure.");
    expect(args.note).toBe("Sales-ops escalation 2391.");
    expect(typeof args.idempotencyKey).toBe("string");
    expect(args.idempotencyKey.length).toBeGreaterThan(0);
  }, 15_000);

  // The dialog normalises blank/whitespace notes to `null` so the audit row
  // does not store an empty string. This is the documented contract used by
  // upstream consumers (ActionBar wiring).
  it("submits with note=null when the optional note is left blank", async () => {
    const onConfirm = vi.fn();
    const { getByLabelText, getAllByRole } = renderWithProviders(
      <ForeclosureRequestDialog open onOpenChange={() => {}} onConfirm={onConfirm} />,
    );
    await userEvent.type(getByLabelText("Reason"), "Borrower requested closure.");
    const submit = getAllByRole("button", { name: /Request foreclosure/i }).find(
      (b) => (b as HTMLButtonElement).type === "submit",
    )!;
    await userEvent.click(submit);
    expect(onConfirm).toHaveBeenCalledTimes(1);
    const [args] = onConfirm.mock.calls[0]!;
    expect(args.note).toBeNull();
  }, 15_000);

  it("invokes onOpenChange(false) when cancel is clicked", async () => {
    const onOpenChange = vi.fn();
    const { getByRole } = renderWithProviders(
      <ForeclosureRequestDialog
        open
        onOpenChange={onOpenChange}
        onConfirm={() => {}}
      />,
    );
    await userEvent.click(getByRole("button", { name: "Cancel" }));
    expect(onOpenChange).toHaveBeenCalledWith(false);
  });

  it("shows a submitting state while loading", () => {
    const { getByRole } = renderWithProviders(
      <ForeclosureRequestDialog
        open
        loading
        onOpenChange={() => {}}
        onConfirm={() => {}}
      />,
    );
    expect(getByRole("button", { name: /Submitting/i })).toBeDisabled();
    expect(getByRole("button", { name: "Cancel" })).toBeDisabled();
  });

  it("resets the form when re-opened after close", async () => {
    const onConfirm = vi.fn();
    const { getByLabelText, getAllByRole, rerender } = renderWithProviders(
      <ForeclosureRequestDialog open onOpenChange={() => {}} onConfirm={onConfirm} />,
    );
    await userEvent.type(getByLabelText("Reason"), "Stale draft");
    rerender(
      <ForeclosureRequestDialog
        open={false}
        onOpenChange={() => {}}
        onConfirm={onConfirm}
      />,
    );
    rerender(
      <ForeclosureRequestDialog open onOpenChange={() => {}} onConfirm={onConfirm} />,
    );
    // After reopen, the reason textarea should be empty — submitting fails on
    // the required-min check rather than carrying over "Stale draft".
    const submit = getAllByRole("button", { name: /Request foreclosure/i }).find(
      (b) => (b as HTMLButtonElement).type === "submit",
    )!;
    await userEvent.click(submit);
    expect(onConfirm).not.toHaveBeenCalled();
  }, 15_000);

  it("has no axe violations when open", async () => {
    const { baseElement } = renderWithProviders(
      <ForeclosureRequestDialog open onOpenChange={() => {}} onConfirm={() => {}} />,
    );
    expect(await axe(baseElement)).toHaveNoViolations();
  });
});
