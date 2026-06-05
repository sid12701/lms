import { describe, expect, it, vi } from "vitest";
import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/test/utils";
import { LspMultiSelect } from "./LspMultiSelect";

const CHOICES = [
  { id: "lsp-1", name: "Apex Finance", code: "APEX", status: "ACTIVE" },
  { id: "lsp-2", name: "North Finance", code: "NORTH", status: "ACTIVE" },
];

describe("LspMultiSelect", () => {
  it("opens as a dropdown checklist and emits checked selections", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    renderWithProviders(
      <LspMultiSelect choices={CHOICES} selected={["lsp-1"]} onChange={onChange} />,
    );

    const trigger = screen.getByRole("button", { name: /1 LSP selected/i });
    await user.click(trigger);

    const listbox = await screen.findByRole("listbox", { name: /LSP selection/i });
    const apex = within(listbox).getByRole("option", { name: /Apex Finance/i });
    const north = within(listbox).getByRole("option", { name: /North Finance/i });

    expect(apex).toHaveAttribute("aria-selected", "true");
    expect(north).toHaveAttribute("aria-selected", "false");

    await user.click(north);
    expect(onChange).toHaveBeenCalledWith(["lsp-1", "lsp-2"]);
  });
});
