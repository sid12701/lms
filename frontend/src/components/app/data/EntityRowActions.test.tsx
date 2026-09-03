import { describe, it, expect } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { axe } from "vitest-axe";
import { axeBaseElement, renderWithProviders } from "@/test/utils";
import { EntityRowActions, type EntityRowActionItem } from "./EntityRowActions";

/**
 * Accessibility-only coverage. `EntityRowActions` (menu mode, the default)
 * renders a Radix `Popover` — its panel portals out of RTL's `container`
 * into `document.body`, so the open state can only be scanned via
 * `baseElement`. Behavioural coverage for the row-action wiring itself lives
 * with the tables that use it.
 */
const ITEMS: EntityRowActionItem[] = [
  { id: "edit", label: "Edit", onSelect: () => {} },
  { id: "delete", label: "Delete", onSelect: () => {} },
];

describe("EntityRowActions", () => {
  it("has no axe violations when closed", async () => {
    const { container } = renderWithProviders(<EntityRowActions items={ITEMS} />);
    expect(await axe(container)).toHaveNoViolations();
  });

  it("has no axe violations with the menu open (portalled content lives on baseElement)", async () => {
    const user = userEvent.setup();
    const { baseElement } = renderWithProviders(
      <EntityRowActions items={ITEMS} ariaLabel="Row actions" />,
    );
    await user.click(screen.getByRole("button", { name: "Row actions" }));
    await screen.findByRole("button", { name: "Edit" });

    expect(await axeBaseElement(baseElement)).toHaveNoViolations();
  });
});
