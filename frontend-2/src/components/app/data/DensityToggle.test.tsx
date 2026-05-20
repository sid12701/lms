import { describe, it, expect } from "vitest";
import userEvent from "@testing-library/user-event";
import { axe } from "vitest-axe";
import { renderWithProviders } from "@/test/utils";
import { DensityProvider } from "@/app/providers";
import { DensityToggle } from "./DensityToggle";

function renderToggle() {
  return renderWithProviders(
    <DensityProvider>
      <DensityToggle />
    </DensityProvider>,
  );
}

describe("DensityToggle", () => {
  it("starts with comfortable selected", () => {
    const { getByRole } = renderToggle();
    expect(getByRole("radio", { name: /comfortable/i }).getAttribute("aria-checked")).toBe("true");
    expect(getByRole("radio", { name: /compact/i }).getAttribute("aria-checked")).toBe("false");
  });

  it("switches to compact on click", async () => {
    const { getByRole } = renderToggle();
    const compact = getByRole("radio", { name: /compact/i });
    await userEvent.click(compact);
    expect(compact.getAttribute("aria-checked")).toBe("true");
    expect(getByRole("radio", { name: /comfortable/i }).getAttribute("aria-checked")).toBe("false");
  });

  it("groups the radios inside a <fieldset> with an sr-only <legend>", () => {
    const { container } = renderToggle();
    const fieldset = container.querySelector('fieldset[data-slot="density-toggle"]');
    expect(fieldset).not.toBeNull();
    const legend = fieldset!.querySelector("legend");
    expect(legend).not.toBeNull();
    expect(legend!.tagName).toBe("LEGEND");
    expect(legend!.className).toContain("sr-only");
    expect(legend!.textContent).toBe("Row density");
  });

  it("respects a custom label prop on both the fieldset aria and legend text", () => {
    const { container } = renderWithProviders(
      <DensityProvider>
        <DensityToggle label="Table density" />
      </DensityProvider>,
    );
    const fieldset = container.querySelector('fieldset[data-slot="density-toggle"]');
    expect(fieldset?.getAttribute("aria-label")).toBe("Table density");
    expect(fieldset?.querySelector("legend")?.textContent).toBe("Table density");
  });

  it("has no axe violations", async () => {
    const { container } = renderToggle();
    expect(await axe(container)).toHaveNoViolations();
  });
});
