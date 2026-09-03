import { describe, it, expect } from "vitest";
import { axe } from "vitest-axe";
import { renderWithProviders } from "@/test/utils";
import { PageEyebrow } from "./PageEyebrow";

describe("PageEyebrow", () => {
  it("renders text content inside a paragraph by default", () => {
    const { getByText, container } = renderWithProviders(<PageEyebrow>Administration</PageEyebrow>);
    const node = getByText("Administration");
    expect(node).toBeInTheDocument();
    expect(node.tagName).toBe("P");
    expect(container.querySelector('[data-slot="page-eyebrow"]')).not.toBeNull();
  });

  it('renders as a span when as="span"', () => {
    const { getByText } = renderWithProviders(<PageEyebrow as="span">Inline</PageEyebrow>);
    const node = getByText("Inline");
    expect(node.tagName).toBe("SPAN");
    expect(node).toHaveAttribute("data-slot", "page-eyebrow");
  });

  it("merges a custom className with the canonical typography classes", () => {
    const { getByText } = renderWithProviders(
      <PageEyebrow className="mb-1">Workspace</PageEyebrow>,
    );
    const node = getByText("Workspace");
    // Size, line-height, weight and tracking now come from the `--text-eyebrow`
    // token rather than four arbitrary utilities repeated at every call site,
    // so the single class *is* the typography contract.
    expect(node.className).toMatch(/text-eyebrow/);
    expect(node.className).toMatch(/uppercase/);
    expect(node.className).toMatch(/mb-1/);
  });

  it("has no axe violations", async () => {
    const { container } = renderWithProviders(<PageEyebrow>Reporting</PageEyebrow>);
    expect(await axe(container)).toHaveNoViolations();
  });
});
