import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { axe } from "vitest-axe";
import { render } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { TooltipProvider } from "@/components/ui/tooltip";
import { DensityProvider } from "@/app/providers";
import { ComponentsSandboxPage } from "./components-sandbox";

function renderPage() {
  return render(
    <MemoryRouter initialEntries={["/dev/components"]}>
      <DensityProvider>
        <TooltipProvider delayDuration={0}>
          <ComponentsSandboxPage />
        </TooltipProvider>
      </DensityProvider>
    </MemoryRouter>,
  );
}

describe("ComponentsSandboxPage", () => {
  // Captures any React warnings (act, key, lazy-mount, etc.) so the test can
  // assert nothing logged on first paint — covers punch list item 5.
  const consoleErrorSpy = vi.spyOn(console, "error");
  const consoleWarnSpy = vi.spyOn(console, "warn");

  beforeEach(() => {
    consoleErrorSpy.mockClear();
    consoleWarnSpy.mockClear();
  });

  afterEach(() => {
    consoleErrorSpy.mockReset();
    consoleWarnSpy.mockReset();
  });

  it("renders a back-link to /home (punch list item 9)", () => {
    const { getByRole } = renderPage();
    const link = getByRole("link", { name: /back to home/i });
    expect(link).toBeInTheDocument();
    expect(link.getAttribute("href")).toBe("/home");
  });

  it("renders without console errors or React warnings (punch list item 5)", () => {
    renderPage();
    expect(consoleErrorSpy).not.toHaveBeenCalled();
    expect(consoleWarnSpy).not.toHaveBeenCalled();
  });

  it("renders the PageHeader eyebrow + h1 synchronously on first paint", () => {
    const { getByText, getByRole } = renderPage();
    // Eyebrow + heading exist on first render — no async wait — confirms the
    // page state is initialised synchronously (item 5 stable-skeleton fix).
    expect(getByText("Dev")).toBeInTheDocument();
    expect(getByRole("heading", { level: 1, name: /components sandbox/i })).toBeInTheDocument();
  });

  it("is axe-clean", async () => {
    const { container } = renderPage();
    expect(await axe(container)).toHaveNoViolations();
  });
});
