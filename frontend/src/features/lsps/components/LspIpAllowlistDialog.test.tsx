import { beforeEach, describe, expect, it, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/test/utils";
import type { LspRow } from "../types";

const listMock = vi.fn();
const enforcementMock = vi.fn();
const addMock = vi.fn();
const removeMock = vi.fn();
const updateEnforcementMock = vi.fn();

vi.mock("../api", () => ({
  listLspIpAllowlist: (...args: unknown[]) => listMock(...args),
  getLspAllowlistEnforcement: (...args: unknown[]) => enforcementMock(...args),
  addLspIpAllowlistEntry: (...args: unknown[]) => addMock(...args),
  removeLspIpAllowlistEntry: (...args: unknown[]) => removeMock(...args),
  updateLspAllowlistEnforcement: (...args: unknown[]) => updateEnforcementMock(...args),
}));

import { LspIpAllowlistDialog } from "./LspIpAllowlistDialog";

const lsp: LspRow = {
  id: "aaaaaaaa-1111-4aaa-8aaa-aaaaaaaaaaaa",
  code: "ACME",
  name: "Acme Finance",
  status: "ACTIVE",
  createdAt: "2026-01-01T00:00:00.000Z",
  userCount: 2,
  webhookEnabled: false,
};

const uiEntry = {
  id: "entry-ui",
  lspId: lsp.id,
  cidr: "10.0.0.0/8",
  description: null,
  createdAt: "2026-01-01T00:00:00.000Z",
  updatedAt: "2026-01-01T00:00:00.000Z",
};

beforeEach(() => {
  listMock
    .mockReset()
    .mockImplementation(async (_id: string, surface: string) =>
      surface === "ui" ? [uiEntry] : [],
    );
  enforcementMock.mockReset().mockResolvedValue({ enforceUi: true, enforceApi: false });
  addMock.mockReset().mockResolvedValue({ ...uiEntry, id: "new-entry" });
  removeMock.mockReset().mockResolvedValue(undefined);
  updateEnforcementMock.mockReset().mockResolvedValue({ enforceUi: false, enforceApi: false });
});

describe("LspIpAllowlistDialog", () => {
  it("loads, adds, removes, and updates enforcement without losing local drafts", async () => {
    const operator = userEvent.setup();
    renderWithProviders(<LspIpAllowlistDialog open onOpenChange={vi.fn()} lsp={lsp} />);

    expect(await screen.findByText("10.0.0.0/8")).toBeInTheDocument();
    const enforcementCheckboxes = screen.getAllByRole("checkbox", { name: /Enforce allowlist/i });
    expect(enforcementCheckboxes[0]).toBeChecked();

    await operator.click(screen.getAllByRole("button", { name: "Add CIDR" })[1]!);
    await operator.type(screen.getByRole("textbox", { name: "New CIDR entry" }), "203.0.113.7");
    await operator.click(screen.getByRole("button", { name: "Add" }));
    await operator.click(screen.getByRole("button", { name: "Save new entries" }));

    await waitFor(() => {
      expect(addMock).toHaveBeenCalledWith(lsp.id, "api", { cidr: "203.0.113.7" });
    });

    await operator.click(screen.getByRole("button", { name: "Remove 10.0.0.0/8" }));
    await waitFor(() => {
      expect(removeMock).toHaveBeenCalledWith(lsp.id, "ui", "entry-ui");
    });

    await operator.click(enforcementCheckboxes[0]!);
    await waitFor(() => {
      expect(updateEnforcementMock).toHaveBeenCalledWith(lsp.id, { enforceUi: false });
    });
  });

  it("surfaces load errors and retries all three resources", async () => {
    const operator = userEvent.setup();
    listMock.mockRejectedValueOnce(new Error("allowlist unavailable"));
    renderWithProviders(<LspIpAllowlistDialog open onOpenChange={vi.fn()} lsp={lsp} />);

    expect(await screen.findByText("Couldn't load IP allowlists")).toBeInTheDocument();
    await operator.click(screen.getByRole("button", { name: "Retry" }));

    await waitFor(() => expect(listMock.mock.calls.length).toBeGreaterThanOrEqual(4));
    expect(enforcementMock.mock.calls.length).toBeGreaterThanOrEqual(2);
  });

  it("keeps pending entries visible when saving fails", async () => {
    const operator = userEvent.setup();
    addMock.mockRejectedValue(new Error("save failed"));
    renderWithProviders(<LspIpAllowlistDialog open onOpenChange={vi.fn()} lsp={lsp} />);

    await screen.findByText("10.0.0.0/8");
    await operator.click(screen.getAllByRole("button", { name: "Add CIDR" })[1]!);
    await operator.type(screen.getByRole("textbox", { name: "New CIDR entry" }), "203.0.113.7");
    await operator.click(screen.getByRole("button", { name: "Add" }));
    await operator.click(screen.getByRole("button", { name: "Save new entries" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("save failed");
    expect(screen.getByText("203.0.113.7")).toBeInTheDocument();
  });
});
