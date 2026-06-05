import { describe, it, expect } from "vitest";
import { axe } from "vitest-axe";
import { renderWithProviders } from "@/test/utils";
import { KbdHint } from "./KbdHint";

describe("KbdHint", () => {
  it("renders one <kbd> per key", () => {
    const { container } = renderWithProviders(<KbdHint keys={["Ctrl", "K"]} />);
    const kbds = container.querySelectorAll("kbd");
    expect(kbds).toHaveLength(2);
    expect(kbds[0]?.textContent).toBe("Ctrl");
    expect(kbds[1]?.textContent).toBe("K");
  });

  it("renders a separator between keys", () => {
    const { container } = renderWithProviders(<KbdHint keys={["Cmd", "Shift", "P"]} />);
    const separators = container.querySelectorAll('[aria-hidden="true"]');
    // 2 separators between 3 keys
    expect(separators).toHaveLength(2);
  });

  it("forwards className", () => {
    const { container } = renderWithProviders(<KbdHint keys={["A"]} className="kh" />);
    expect(container.querySelector('[data-slot="kbd-hint"]')).toHaveClass("kh");
  });

  it("has no axe violations", async () => {
    const { container } = renderWithProviders(<KbdHint keys={["Ctrl", "K"]} />);
    expect(await axe(container)).toHaveNoViolations();
  });
});
