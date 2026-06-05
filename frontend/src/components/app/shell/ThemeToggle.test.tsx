import { describe, it, expect, beforeEach, afterEach } from "vitest";
import { axe } from "vitest-axe";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ThemeProvider } from "@/app/providers";
import { ThemeToggle } from "./ThemeToggle";

const STORAGE_KEY = "bhawana-lms-theme";

function renderWithTheme() {
  return render(
    <ThemeProvider>
      <ThemeToggle />
    </ThemeProvider>,
  );
}

describe("ThemeToggle", () => {
  beforeEach(() => {
    window.localStorage.removeItem(STORAGE_KEY);
    document.documentElement.classList.remove("dark");
  });

  afterEach(() => {
    window.localStorage.removeItem(STORAGE_KEY);
    document.documentElement.classList.remove("dark");
  });

  it("renders the moon icon in light mode and labels itself for switching to dark", () => {
    renderWithTheme();
    const btn = screen.getByRole("button", { name: "Switch to dark theme" });
    expect(btn).toBeInTheDocument();
    // Lucide renders an inline SVG; Moon is the only icon present.
    expect(btn.querySelector("svg")).toBeInTheDocument();
  });

  it("flips icon + aria-label when clicked", async () => {
    const user = userEvent.setup();
    renderWithTheme();
    const initial = screen.getByRole("button", { name: "Switch to dark theme" });
    await user.click(initial);
    const flipped = await screen.findByRole("button", { name: "Switch to light theme" });
    expect(flipped).toBeInTheDocument();
    expect(document.documentElement.classList.contains("dark")).toBe(true);
  });

  it("calls toggle and persists the new theme to localStorage", async () => {
    const user = userEvent.setup();
    renderWithTheme();
    await user.click(screen.getByRole("button", { name: "Switch to dark theme" }));
    expect(window.localStorage.getItem(STORAGE_KEY)).toBe("dark");
    await user.click(screen.getByRole("button", { name: "Switch to light theme" }));
    expect(window.localStorage.getItem(STORAGE_KEY)).toBe("light");
  });

  it("has no axe violations", async () => {
    const { container } = renderWithTheme();
    expect(await axe(container)).toHaveNoViolations();
  });
});
