import { describe, it, expect } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { axe } from "vitest-axe";
import { axeBaseElement, renderWithProviders } from "@/test/utils";
import { MultiSelectChip, type MultiSelectChipOption } from "./MultiSelectChip";

/**
 * Accessibility-only coverage. `MultiSelectChip` renders a Radix `Popover`
 * containing a `role="listbox"` — the panel portals out of RTL's `container`
 * into `document.body`, so the open state can only be scanned via
 * `baseElement`. Behavioural coverage for the toggle wiring lives with the
 * filter bars that use it.
 */
const OPTIONS: MultiSelectChipOption<"A" | "B">[] = [
  { value: "A", label: "Alpha" },
  { value: "B", label: "Beta" },
];

describe("MultiSelectChip", () => {
  it("has no axe violations when closed", async () => {
    const { container } = renderWithProviders(
      <MultiSelectChip label="Status" options={OPTIONS} selected={[]} onToggle={() => {}} />,
    );
    expect(await axe(container)).toHaveNoViolations();
  });

  it("has no axe violations with the listbox open (portalled content lives on baseElement)", async () => {
    const user = userEvent.setup();
    const { baseElement } = renderWithProviders(
      <MultiSelectChip label="Status" options={OPTIONS} selected={["A"]} onToggle={() => {}} />,
    );
    await user.click(screen.getByRole("button", { name: /Status/ }));
    await screen.findByRole("listbox");

    expect(await axeBaseElement(baseElement)).toHaveNoViolations();
  });
});
