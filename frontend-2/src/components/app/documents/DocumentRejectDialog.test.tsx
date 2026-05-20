import { describe, it, expect, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { axe } from "vitest-axe";
import { renderWithProviders } from "@/test/utils";
import { DocumentRejectDialog } from "./DocumentRejectDialog";

describe("DocumentRejectDialog", () => {
  it("renders title, description, and the textarea when open", () => {
    const { getByRole, getByLabelText, getByText } = renderWithProviders(
      <DocumentRejectDialog
        open
        onOpenChange={() => {}}
        kind="PAN_CARD"
        fileName="pan.pdf"
        onConfirm={() => {}}
      />,
    );
    expect(getByRole("heading", { name: /Reject document/i })).toBeInTheDocument();
    expect(getByText(/PAN card/)).toBeInTheDocument();
    expect(getByText(/pan\.pdf/)).toBeInTheDocument();
    expect(getByLabelText(/Reason for rejection/i)).toBeInTheDocument();
  });

  it("falls back to the generic word 'document' when no kind is provided", () => {
    const { getByText } = renderWithProviders(
      <DocumentRejectDialog open onOpenChange={() => {}} onConfirm={() => {}} />,
    );
    expect(getByText(/Rejecting this document/i)).toBeInTheDocument();
  });

  it(
    "blocks submit when the reason is empty (Zod min(1))",
    async () => {
      const onConfirm = vi.fn();
      const { getByRole, findAllByText } = renderWithProviders(
        <DocumentRejectDialog
          open
          onOpenChange={() => {}}
          kind="AADHAAR_CARD"
          onConfirm={onConfirm}
        />,
      );
      await userEvent.click(getByRole("button", { name: "Reject" }));
      // FormShell mirrors errors into both <FormMessage> and the aria-live <ul>.
      const matches = await findAllByText(/Rejection reason is required/i);
      expect(matches.length).toBeGreaterThan(0);
      expect(onConfirm).not.toHaveBeenCalled();
    },
    15_000,
  );

  it(
    "submits the trimmed reason with a fresh idempotency key",
    async () => {
      const onConfirm = vi.fn();
      const { getByRole, getByLabelText } = renderWithProviders(
        <DocumentRejectDialog
          open
          onOpenChange={() => {}}
          kind="BANK_STATEMENT"
          onConfirm={onConfirm}
        />,
      );
      await userEvent.type(
        getByLabelText(/Reason for rejection/i),
        "  Statement is illegible. Please re-upload.  ",
      );
      await userEvent.click(getByRole("button", { name: "Reject" }));
      expect(onConfirm).toHaveBeenCalledTimes(1);
      const [args] = onConfirm.mock.calls[0]!;
      expect(args.rejectionReason).toBe("Statement is illegible. Please re-upload.");
      expect(typeof args.idempotencyKey).toBe("string");
      expect(args.idempotencyKey.length).toBeGreaterThan(0);
    },
    15_000,
  );

  it("invokes onOpenChange(false) when cancel is clicked", async () => {
    const onOpenChange = vi.fn();
    const { getByRole } = renderWithProviders(
      <DocumentRejectDialog
        open
        onOpenChange={onOpenChange}
        kind="INCOME_PROOF"
        onConfirm={() => {}}
      />,
    );
    await userEvent.click(getByRole("button", { name: "Cancel" }));
    expect(onOpenChange).toHaveBeenCalledWith(false);
  });

  it("shows a working state and disables both buttons while loading", () => {
    const { getByRole } = renderWithProviders(
      <DocumentRejectDialog
        open
        loading
        onOpenChange={() => {}}
        kind="PAN_CARD"
        onConfirm={() => {}}
      />,
    );
    expect(getByRole("button", { name: /Rejecting/ })).toBeDisabled();
    expect(getByRole("button", { name: "Cancel" })).toBeDisabled();
  });

  it("has no axe violations when open", async () => {
    const { baseElement } = renderWithProviders(
      <DocumentRejectDialog
        open
        onOpenChange={() => {}}
        kind="PAN_CARD"
        onConfirm={() => {}}
      />,
    );
    expect(await axe(baseElement)).toHaveNoViolations();
  });
});
