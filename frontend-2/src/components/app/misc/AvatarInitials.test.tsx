import { describe, it, expect } from "vitest";
import { axe } from "vitest-axe";
import { renderWithProviders } from "@/test/utils";
import { AvatarInitials } from "./AvatarInitials";

describe("AvatarInitials", () => {
  it("derives 2 uppercase initials from a multi-word name", () => {
    const { getByRole } = renderWithProviders(<AvatarInitials name="Sid Daryanani" />);
    const el = getByRole("img");
    expect(el.textContent).toBe("SD");
    expect(el.getAttribute("aria-label")).toBe("Sid Daryanani");
  });

  it("derives up to 2 letters for a single-word name", () => {
    const { getByRole } = renderWithProviders(<AvatarInitials name="Cher" />);
    expect(getByRole("img").textContent).toBe("CH");
  });

  it("falls back to '?' for empty input", () => {
    const { getByRole } = renderWithProviders(<AvatarInitials name="   " />);
    expect(getByRole("img").textContent).toBe("?");
  });

  it("applies size and tone variants", () => {
    const { container } = renderWithProviders(
      <AvatarInitials name="X Y" size="lg" tone="accent" />,
    );
    const el = container.querySelector('[data-slot="avatar-initials"]');
    expect(el).toHaveClass("h-10", "w-10", "bg-accent-500");
  });

  it("forwards className", () => {
    const { container } = renderWithProviders(<AvatarInitials name="X" className="ai-x" />);
    expect(container.querySelector('[data-slot="avatar-initials"]')).toHaveClass("ai-x");
  });

  it("has no axe violations", async () => {
    const { container } = renderWithProviders(<AvatarInitials name="Sid Daryanani" />);
    expect(await axe(container)).toHaveNoViolations();
  });
});
