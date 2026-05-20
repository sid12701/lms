import { describe, it, expect, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { axe } from "vitest-axe";
import { renderWithProviders } from "@/test/utils";
import { PiiRevealDialog } from "./PiiRevealDialog";

describe("PiiRevealDialog", () => {
  it("renders title and reason field when open", () => {
    const { getByRole, getByLabelText } = renderWithProviders(
      <PiiRevealDialog
        open
        onOpenChange={() => {}}
        fieldName="PAN"
        onConfirm={() => {}}
      />,
    );
    expect(getByRole("heading", { name: /Reveal PAN/i })).toBeInTheDocument();
    expect(getByLabelText(/Reason for revealing/i)).toBeInTheDocument();
  });

  it("includes the subjectLabel in the description when provided", () => {
    const { getByText } = renderWithProviders(
      <PiiRevealDialog
        open
        onOpenChange={() => {}}
        fieldName="MOBILE"
        subjectLabel="Asha Devi"
        onConfirm={() => {}}
      />,
    );
    expect(getByText(/for Asha Devi/i)).toBeInTheDocument();
  });

  it("blocks submit when reason is empty (Zod min(1))", async () => {
    const onConfirm = vi.fn();
    const { getByRole, findAllByText } = renderWithProviders(
      <PiiRevealDialog
        open
        onOpenChange={() => {}}
        fieldName="PAN"
        onConfirm={onConfirm}
      />,
    );
    await userEvent.click(getByRole("button", { name: "Reveal" }));
    // FormShell mirrors the inline FormMessage into an aria-live summary,
    // so the message appears twice in the DOM (li + p).
    const matches = await findAllByText(/Reason is required/i);
    expect(matches.length).toBeGreaterThan(0);
    expect(onConfirm).not.toHaveBeenCalled();
  });

  it("calls onConfirm with the trimmed reason and a fresh idempotency key", async () => {
    const onConfirm = vi.fn();
    const { getByRole, getByLabelText } = renderWithProviders(
      <PiiRevealDialog
        open
        onOpenChange={() => {}}
        fieldName="AADHAAR"
        onConfirm={onConfirm}
      />,
    );
    await userEvent.type(
      getByLabelText(/Reason for revealing/i),
      "  Verifying borrower repayment query.  ",
    );
    await userEvent.click(getByRole("button", { name: "Reveal" }));
    expect(onConfirm).toHaveBeenCalledTimes(1);
    const [args] = onConfirm.mock.calls[0]!;
    expect(args.reason).toBe("Verifying borrower repayment query.");
    expect(typeof args.idempotencyKey).toBe("string");
    expect(args.idempotencyKey.length).toBeGreaterThan(0);
  });

  it("invokes onOpenChange(false) when cancel is clicked", async () => {
    const onOpenChange = vi.fn();
    const { getByRole } = renderWithProviders(
      <PiiRevealDialog
        open
        onOpenChange={onOpenChange}
        fieldName="EMAIL"
        onConfirm={() => {}}
      />,
    );
    await userEvent.click(getByRole("button", { name: "Cancel" }));
    expect(onOpenChange).toHaveBeenCalledWith(false);
  });

  it("shows a working state while loading", () => {
    const { getByRole } = renderWithProviders(
      <PiiRevealDialog
        open
        loading
        onOpenChange={() => {}}
        fieldName="PAN"
        onConfirm={() => {}}
      />,
    );
    expect(getByRole("button", { name: /Revealing/ })).toBeDisabled();
  });

  it("has no axe violations when open", async () => {
    const { baseElement } = renderWithProviders(
      <PiiRevealDialog
        open
        onOpenChange={() => {}}
        fieldName="PAN"
        onConfirm={() => {}}
      />,
    );
    expect(await axe(baseElement)).toHaveNoViolations();
  });
});
