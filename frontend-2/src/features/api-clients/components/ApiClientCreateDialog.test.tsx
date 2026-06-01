import { describe, expect, it, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/test/utils";
import { ApiClientCreateDialog } from "./ApiClientCreateDialog";
import type { ApiClientRow, CreateApiClientResponse } from "../types";

const LSP_ID = "00000000-0000-4000-8000-000000000001";
const CLIENT_SECRET = "sk_live_test_secret_1234567890";

const createdClient: ApiClientRow = {
  id: "00000000-0000-4000-8000-000000000002",
  clientId: "cli_testfreshflow",
  name: "E2E Fresh Flow API Client",
  lspId: LSP_ID,
  lspName: "E2E Fresh Flow",
  status: "ACTIVE",
  createdAt: "2026-05-26T07:15:00.000Z",
  lastUsedAt: null,
  ipAllowList: [],
  ipAllowlistCount: 0,
};

describe("ApiClientCreateDialog", () => {
  it("clears the parent-held reveal when the one-time create secret is acknowledged", async () => {
    const user = userEvent.setup();
    const onCreate = vi
      .fn<
        (input: {
          name: string;
          lspId: string;
          idempotencyKey: string;
        }) => Promise<CreateApiClientResponse>
      >()
      .mockResolvedValue({
        client: createdClient,
        clientSecret: CLIENT_SECRET,
      });
    const onOpenChange = vi.fn();
    const onSecretAcknowledge = vi.fn();

    renderWithProviders(
      <ApiClientCreateDialog
        open
        onOpenChange={onOpenChange}
        lspOptions={[{ id: LSP_ID, name: "E2E Fresh Flow" }]}
        onCreate={onCreate}
        onSecretAcknowledge={onSecretAcknowledge}
      />,
    );

    await user.type(screen.getByRole("textbox", { name: "Name" }), createdClient.name);
    await user.click(screen.getByRole("combobox", { name: "LSP" }));
    await user.click(await screen.findByRole("option", { name: "E2E Fresh Flow" }));
    await user.click(screen.getByRole("button", { name: "Create API client" }));

    expect(await screen.findByText(CLIENT_SECRET)).toBeInTheDocument();
    await waitFor(() => expect(onCreate).toHaveBeenCalledTimes(1));
    expect(onCreate.mock.calls[0]?.[0]).toMatchObject({
      name: createdClient.name,
      lspId: LSP_ID,
    });
    expect(onCreate.mock.calls[0]?.[0].idempotencyKey).toEqual(expect.any(String));

    await user.click(screen.getByRole("button", { name: /I.?ve saved it/i }));

    expect(onSecretAcknowledge).toHaveBeenCalledTimes(1);
    expect(onOpenChange).toHaveBeenCalledWith(false);
    expect(screen.queryByText(CLIENT_SECRET)).not.toBeInTheDocument();
  }, 15_000);
});
