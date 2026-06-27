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
  it("renders the loan applications link without disabled placeholder cards", () => {
    const { container } = renderWithProviders(wrap(<LspLinkCardGrid />));
    const cards = container.querySelectorAll('[data-slot="lsp-link-card"]');
    expect(cards.length).toBe(1);

    const links = Array.from(container.querySelectorAll("a"));
    expect(links.map((a) => a.getAttribute("href"))).toEqual(["/my-loans"]);

    const disabled = container.querySelectorAll(
      '[data-slot="lsp-link-card"][data-disabled="true"]',
    );
    expect(disabled.length).toBe(0);
  });

  it("renders only the supported LSP workspace destination", () => {
    const { getByText, queryByText } = renderWithProviders(wrap(<LspLinkCardGrid />));
    expect(getByText("Loan applications")).toBeInTheDocument();
    expect(queryByText("Submit new loan")).not.toBeInTheDocument();
    expect(queryByText("Help & docs")).not.toBeInTheDocument();
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
