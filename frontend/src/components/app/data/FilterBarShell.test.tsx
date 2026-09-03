import { describe, it, expect } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { axe } from "vitest-axe";
import { axeBaseElement, renderWithProviders } from "@/test/utils";
import { FilterBarSingleSelect, type FilterBarSelectOption } from "./FilterBarShell";

/**
 * Accessibility-only coverage for `FilterBarSingleSelect` — the one export of
 * this module that renders a Radix `Select`, which portals its listbox out
 * of RTL's `container` and into `document.body`. `FilterBarShell` and its
 * other exports (`FilterAppliedChips`, `FilterBarClearButton`,
 * `FilterBarStatusTabs`, …) portal nothing and are exercised indirectly by
 * the feature-level filter bars that assemble them.
 */
const OPTIONS: FilterBarSelectOption[] = [
  { value: "A", label: "Alpha" },
  { value: "B", label: "Beta" },
];

describe("FilterBarSingleSelect", () => {
  it("has no axe violations when closed", async () => {
    const { container } = renderWithProviders(
      <FilterBarSingleSelect
        value={undefined}
        onChange={() => {}}
        placeholder="All statuses"
        ariaLabel="Status filter"
        options={OPTIONS}
        dataSlot="status-filter"
      />,
    );
    expect(await axe(container)).toHaveNoViolations();
  });

  it("has no axe violations with the listbox open (portalled content lives on baseElement)", async () => {
    const user = userEvent.setup();
    const { baseElement } = renderWithProviders(
      <FilterBarSingleSelect
        value={undefined}
        onChange={() => {}}
        placeholder="All statuses"
        ariaLabel="Status filter"
        options={OPTIONS}
        dataSlot="status-filter"
      />,
    );
    await user.click(screen.getByRole("combobox", { name: "Status filter" }));
    await screen.findByRole("listbox");

    expect(await axeBaseElement(baseElement)).toHaveNoViolations();
  });
});
