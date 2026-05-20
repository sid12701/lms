import { describe, it, expect, vi, beforeEach } from "vitest";
import { axe } from "vitest-axe";
import { MemoryRouter } from "react-router-dom";
import { renderWithProviders } from "@/test/utils";
import { LspLinkCardGrid } from "./LspLinkCardGrid";

function wrap(node: React.ReactNode) {
  return <MemoryRouter>{node}</MemoryRouter>;
}

beforeEach(() => {
  Object.defineProperty(window, "matchMedia", {
    configurable: true,
    writable: true,
    value: vi.fn().mockReturnValue({
      matches: false,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      addListener: vi.fn(),
      removeListener: vi.fn(),
      dispatchEvent: vi.fn(),
    }),
  });
});

describe("<LspLinkCardGrid />", () => {
  it("renders three cards with correct hrefs", () => {
    const { container } = renderWithProviders(wrap(<LspLinkCardGrid />));
    const cards = container.querySelectorAll('[data-slot="lsp-link-card"]');
    expect(cards.length).toBe(3);

    const links = Array.from(container.querySelectorAll("a"));
    expect(links.map((a) => a.getAttribute("href"))).toEqual(["/my-loans/new", "/my-loans"]);
  });

  it("renders Submit new loan and My loans titles", () => {
    const { getByText } = renderWithProviders(wrap(<LspLinkCardGrid />));
    expect(getByText("Submit new loan")).toBeInTheDocument();
    expect(getByText("My loans")).toBeInTheDocument();
    expect(getByText("Help & docs")).toBeInTheDocument();
  });

  it("marks the Help & docs card as aria-disabled", () => {
    const { container } = renderWithProviders(wrap(<LspLinkCardGrid />));
    const disabled = container.querySelector('[data-slot="lsp-link-card"][data-disabled="true"]');
    expect(disabled).not.toBeNull();
    expect(disabled!.getAttribute("aria-disabled")).toBe("true");
    // It is not a real link.
    expect(disabled!.tagName).not.toBe("A");
  });

  it("disables entrance animation under prefers-reduced-motion", () => {
    Object.defineProperty(window, "matchMedia", {
      configurable: true,
      writable: true,
      value: vi.fn().mockReturnValue({
        matches: true,
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
        addListener: vi.fn(),
        removeListener: vi.fn(),
        dispatchEvent: vi.fn(),
      }),
    });
    const { container } = renderWithProviders(wrap(<LspLinkCardGrid />));
    const grid = container.querySelector('[data-slot="lsp-link-card-grid"]');
    expect(grid).not.toBeNull();
    expect(grid!.getAttribute("data-reduced-motion")).toBe("true");
    const card = container.querySelector('[data-slot="lsp-link-card"]') as HTMLElement;
    // Animation classes should not be present.
    expect(card.className).not.toMatch(/animate-in/);
  });

  it("forwards className", () => {
    const { container } = renderWithProviders(wrap(<LspLinkCardGrid className="extra-class" />));
    const grid = container.querySelector('[data-slot="lsp-link-card-grid"]');
    expect(grid).not.toBeNull();
    expect(grid!.className).toContain("extra-class");
  });

  it("has no axe violations", async () => {
    const { container } = renderWithProviders(wrap(<LspLinkCardGrid />));
    expect(await axe(container)).toHaveNoViolations();
  });
});
