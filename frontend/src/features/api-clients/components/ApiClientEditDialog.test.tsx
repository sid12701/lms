import { describe, expect, it, vi } from "vitest";
import { fireEvent, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/test/utils";
import { ApiClientEditDialog } from "./ApiClientEditDialog";
import type { ApiClientRow } from "../types";

const LSP_ID = "00000000-0000-4000-8000-000000000001";

const client: ApiClientRow = {
  id: "00000000-0000-4000-8000-000000000002",
  clientId: "cli_editflow123456",
  name: "Original Name",
  lspId: LSP_ID,
  lspName: "Edit Flow LSP",
  status: "ACTIVE",
  createdAt: "2026-05-26T07:15:00.000Z",
  lastUsedAt: null,
  lastRotatedAt: "2026-06-01T09:30:00.000Z",
  ipAllowList: [],
  ipAllowlistCount: 0,
};

describe("ApiClientEditDialog", () => {
  it("prefills the current values and saves a rename + disable with an idempotency key", async () => {
    const user = userEvent.setup();
    const onSave = vi.fn().mockResolvedValue(undefined);

    const { unmount } = renderWithProviders(
      <ApiClientEditDialog open onOpenChange={vi.fn()} client={client} onSave={onSave} />,
    );

    const nameInput = await screen.findByDisplayValue("Original Name");

    await user.clear(nameInput);
    await user.type(nameInput, "Renamed Client");
    // Radix mirrors Select into a native form control. Drive that supported bridge here so this
    // unit test covers the RHF binding without depending on JSDOM's asynchronous portal timing.
    const nativeSelect = document.querySelector("select");
    expect(nativeSelect).not.toBeNull();
    fireEvent.change(nativeSelect!, { target: { value: "DISABLED" } });
    await user.click(screen.getByRole("button", { name: "Save changes" }));

    await waitFor(() => expect(onSave).toHaveBeenCalledTimes(1));
    expect(onSave.mock.calls[0]?.[0]).toMatchObject({
      name: "Renamed Client",
      status: "DISABLED",
    });
    expect(onSave.mock.calls[0]?.[0].idempotencyKey).toEqual(expect.any(String));
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "Save changes" })).not.toBeDisabled(),
    );
    unmount();
  }, 15_000);

  it("renders the last-rotated timestamp, or 'Never rotated' when null", () => {
    const { rerender } = renderWithProviders(
      <ApiClientEditDialog open onOpenChange={vi.fn()} client={client} onSave={vi.fn()} />,
    );
    expect(screen.queryByText("Never rotated")).not.toBeInTheDocument();

    rerender(
      <ApiClientEditDialog
        open
        onOpenChange={vi.fn()}
        client={{ ...client, lastRotatedAt: null }}
        onSave={vi.fn()}
      />,
    );
    expect(screen.getByText("Never rotated")).toBeInTheDocument();
  });
});
