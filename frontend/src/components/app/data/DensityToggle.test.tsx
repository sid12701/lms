import { describe, it, expect } from "vitest";
import userEvent from "@testing-library/user-event";
import { axe } from "vitest-axe";
import { renderWithProviders } from "@/test/utils";
import { DensityProvider } from "@/app/providers";
import { DensityToggle } from "./DensityToggle";

function renderToggle() {
  window.localStorage.removeItem("bhawana-lms-density");
  return renderWithProviders(
    <DensityProvider>
      <DensityToggle />
    </DensityProvider>,
  );
}

describe("DensityToggle", () => {
  // `compact` is the first-run default: the product's north star is instrument
  // density, and shipping `comfortable` put 6 of 25 rows on a 1280x800 screen.
  it("starts with compact selected", () => {
    const { getByRole } = renderToggle();
    expect(getByRole("radio", { name: /compact/i }).getAttribute("aria-checked")).toBe("true");
    expect(getByRole("radio", { name: /comfortable/i }).getAttribute("aria-checked")).toBe("false");
  });

  it("switches to comfortable on click", async () => {
    const { getByRole } = renderToggle();
    const comfortable = getByRole("radio", { name: /comfortable/i });
    await userEvent.click(comfortable);
    expect(comfortable.getAttribute("aria-checked")).toBe("true");
    expect(getByRole("radio", { name: /compact/i }).getAttribute("aria-checked")).toBe("false");
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

  it("marks the selected option with border and semibold weight for non-color state", () => {
    const { getByRole } = renderToggle();
    const compact = getByRole("radio", { name: /compact/i });
    expect(compact.getAttribute("data-state")).toBe("on");
    expect(compact.className).toContain("data-[state=on]:bg-surface-muted");
    expect(compact.className).toContain("data-[state=on]:border");
    expect(compact.className).toContain("data-[state=on]:!font-semibold");
    expect(getByRole("radio", { name: /comfortable/i }).getAttribute("data-state")).toBe("off");
  });

  it("has no axe violations", async () => {
    const { container } = renderToggle();
    expect(await axe(container)).toHaveNoViolations();
  });
});
