import { describe, it, expect, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { axe } from "vitest-axe";
import { renderWithProviders } from "@/test/utils";
import { RotateSecretDialog } from "./RotateSecretDialog";

describe("RotateSecretDialog", () => {
  it("renders the title, warning, and reason textarea when open", () => {
    const { getByRole, getByLabelText, getByText } = renderWithProviders(
      <RotateSecretDialog open onOpenChange={() => {}} onConfirm={() => {}} />,
    );
    expect(getByRole("heading", { name: /Rotate API client secret/i })).toBeInTheDocument();
    expect(getByText(/Rotating invalidates the current secret immediately/i)).toBeInTheDocument();
    expect(getByLabelText(/Reason for rotation/i)).toBeInTheDocument();
  });

  it("includes the clientLabel in the title when provided", () => {
    const { getByRole } = renderWithProviders(
      <RotateSecretDialog
        open
        onOpenChange={() => {}}
        clientLabel="Acme NBFC"
        onConfirm={() => {}}
      />,
    );
    expect(
      getByRole("heading", { name: /Rotate API client secret for Acme NBFC/i }),
    ).toBeInTheDocument();
  });

  it("blocks submit when reason is empty", async () => {
    const onConfirm = vi.fn();
    const { getByRole, findAllByText } = renderWithProviders(
      <RotateSecretDialog open onOpenChange={() => {}} onConfirm={onConfirm} />,
    );
    await userEvent.click(getByRole("button", { name: "Rotate secret" }));
    const matches = await findAllByText(/Reason is required/i);
    expect(matches.length).toBeGreaterThan(0);
    expect(onConfirm).not.toHaveBeenCalled();
  }, 15_000);

  it("calls onConfirm with the trimmed reason and a fresh idempotency key", async () => {
    const onConfirm = vi.fn();
    const { getByRole, getByLabelText } = renderWithProviders(
      <RotateSecretDialog open onOpenChange={() => {}} onConfirm={onConfirm} />,
    );
    await userEvent.type(
      getByLabelText(/Reason for rotation/i),
      "  Suspected leak in build pipeline.  ",
    );
    await userEvent.click(getByRole("button", { name: "Rotate secret" }));
    expect(onConfirm).toHaveBeenCalledTimes(1);
    const [args] = onConfirm.mock.calls[0]!;
    expect(args.reason).toBe("Suspected leak in build pipeline.");
    expect(typeof args.idempotencyKey).toBe("string");
    expect(args.idempotencyKey.length).toBeGreaterThan(0);
  }, 15_000);

  it("invokes onOpenChange(false) when cancel is clicked", async () => {
    const onOpenChange = vi.fn();
    const { getByRole } = renderWithProviders(
      <RotateSecretDialog open onOpenChange={onOpenChange} onConfirm={() => {}} />,
    );
    await userEvent.click(getByRole("button", { name: "Cancel" }));
    expect(onOpenChange).toHaveBeenCalledWith(false);
  }, 15_000);

  it("disables submit + cancel and switches to a working label when loading", () => {
    const { getByRole } = renderWithProviders(
      <RotateSecretDialog open loading onOpenChange={() => {}} onConfirm={() => {}} />,
    );
    const submit = getByRole("button", { name: /Rotating/i });
    expect(submit).toBeDisabled();
    expect(getByRole("button", { name: "Cancel" })).toBeDisabled();
  });

  it("surfaces a server error message when provided", () => {
    const { getByRole } = renderWithProviders(
      <RotateSecretDialog
        open
        onOpenChange={() => {}}
        onConfirm={() => {}}
        errorMessage="Rotation is temporarily unavailable."
      />,
    );
    expect(getByRole("alert")).toHaveTextContent("Rotation is temporarily unavailable.");
  });

  it("has no axe violations when open", async () => {
    const { baseElement } = renderWithProviders(
      <RotateSecretDialog open onOpenChange={() => {}} onConfirm={() => {}} />,
    );
    expect(await axe(baseElement)).toHaveNoViolations();
  });
});
