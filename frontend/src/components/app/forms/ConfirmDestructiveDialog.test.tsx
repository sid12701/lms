import { describe, it, expect, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { axeBaseElement, renderWithProviders } from "@/test/utils";
import { ConfirmDestructiveDialog } from "./ConfirmDestructiveDialog";

describe("ConfirmDestructiveDialog", () => {
  it("renders title, description, and the confirm button when open", () => {
    const { getByText, getByRole } = renderWithProviders(
      <ConfirmDestructiveDialog
        open
        onOpenChange={() => {}}
        title="Invalidate loan"
        description="This cannot be undone."
        confirmLabel="Invalidate"
        onConfirm={() => {}}
      />,
    );
    expect(getByText("Invalidate loan")).toBeInTheDocument();
    expect(getByText("This cannot be undone.")).toBeInTheDocument();
    expect(getByRole("button", { name: "Invalidate" })).toBeEnabled();
  });

  it("disables confirm until typed confirmation matches", async () => {
    const onConfirm = vi.fn();
    const { getByRole, getByLabelText } = renderWithProviders(
      <ConfirmDestructiveDialog
        open
        onOpenChange={() => {}}
        title="Invalidate"
        description="Type INVALIDATE."
        confirmLabel="Invalidate"
        typedConfirmation="INVALIDATE"
        onConfirm={onConfirm}
      />,
    );
    const button = getByRole("button", { name: "Invalidate" });
    expect(button).toBeDisabled();
    const input = getByLabelText(/Type/i);
    await userEvent.type(input, "INVALIDATE");
    expect(button).toBeEnabled();
    await userEvent.click(button);
    expect(onConfirm).toHaveBeenCalledOnce();
  });

  it("invokes onOpenChange(false) when cancel is clicked", async () => {
    const onOpenChange = vi.fn();
    const { getByRole } = renderWithProviders(
      <ConfirmDestructiveDialog
        open
        onOpenChange={onOpenChange}
        title="x"
        description="y"
        confirmLabel="Go"
        onConfirm={() => {}}
      />,
    );
    await userEvent.click(getByRole("button", { name: "Cancel" }));
    expect(onOpenChange).toHaveBeenCalledWith(false);
  });

  it("shows a working state while loading", () => {
    const { getByRole } = renderWithProviders(
      <ConfirmDestructiveDialog
        open
        loading
        onOpenChange={() => {}}
        title="x"
        description="y"
        confirmLabel="Go"
        onConfirm={() => {}}
      />,
    );
    expect(getByRole("button", { name: /Working/ })).toBeDisabled();
  });

  it("has no axe violations when open", async () => {
    const { baseElement } = renderWithProviders(
      <ConfirmDestructiveDialog
        open
        onOpenChange={() => {}}
        title="Invalidate"
        description="Confirm to proceed."
        confirmLabel="Invalidate"
        onConfirm={() => {}}
      />,
    );
    expect(await axeBaseElement(baseElement)).toHaveNoViolations();
  });
});
