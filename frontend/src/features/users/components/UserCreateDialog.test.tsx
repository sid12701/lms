import { describe, expect, it, vi } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "@/test/utils";
import { REPLAYED_CREATE_NOTICE } from "../hooks/useUsersDialogController";
import { UserCreateDialog } from "./UserCreateDialog";

const baseProps = {
  open: true,
  onOpenChange: vi.fn(),
  lspOptions: [] as const,
  onConfirm: vi.fn(),
  onAcknowledgePassword: vi.fn(),
};

describe("UserCreateDialog credential recovery", () => {
  it("shows the recovery notice with no credential value when create replays", () => {
    renderWithProviders(
      <UserCreateDialog {...baseProps} recoveryNotice={REPLAYED_CREATE_NOTICE} />,
    );

    expect(screen.getByRole("status")).toHaveTextContent(/already processed/);
    // No reveal card may mount behind the notice (Radix portals to body).
    expect(document.body.querySelector('[data-slot="temp-password-value"]')).toBeNull();
    expect(document.body.querySelector('[data-slot="user-create-revealed"]')).toBeNull();
  });

  it("reveals the server-minted credential exactly once on first create", () => {
    // The dialog mounts before the mutation resolves (no credential yet),
    // then the parent hands over the one-time server value — the reveal card
    // appears only on that transition.
    const { rerender } = renderWithProviders(
      <UserCreateDialog {...baseProps} temporaryPassword={null} />,
    );
    expect(document.body.querySelector('[data-slot="temp-password-value"]')).toBeNull();

    rerender(
      <UserCreateDialog
        {...baseProps}
        temporaryPassword="server-secret"
        createdUsername="new-user"
      />,
    );
    expect(document.body.querySelector('[data-slot="temp-password-value"]')).toHaveTextContent(
      "server-secret",
    );
    expect(document.body.querySelector('[data-slot="user-create-recovery-notice"]')).toBeNull();
  });
});
