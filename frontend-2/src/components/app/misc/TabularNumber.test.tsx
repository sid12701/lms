import { describe, it, expect } from "vitest";
import { axe } from "vitest-axe";
import { renderWithProviders } from "@/test/utils";
import { TabularNumber } from "./TabularNumber";

describe("TabularNumber", () => {
  it("formats a number with en-IN grouping by default", () => {
    const { getByText } = renderWithProviders(<TabularNumber value={1234567} />);
    // en-IN renders 12,34,567
    expect(getByText("12,34,567")).toBeInTheDocument();
  });

  it("formats currency with the ₹ symbol", () => {
    const { container } = renderWithProviders(
      <TabularNumber value={1500} variant="currency" decimals={0} />,
    );
    const el = container.querySelector('[data-slot="tabular-number"]');
    expect(el?.textContent).toMatch(/₹\s?1,500/);
  });

  it("formats percent with a % suffix and 2 decimals by default", () => {
    const { getByText } = renderWithProviders(<TabularNumber value={12.345} variant="percent" />);
    expect(getByText(/^12\.35%$/)).toBeInTheDocument();
  });

  it("renders raw using en-IN grouping", () => {
    const { container } = renderWithProviders(<TabularNumber value={50000} variant="raw" />);
    expect(container.querySelector('[data-slot="tabular-number"]')?.textContent).toBe("50,000");
  });

  it("sets the data-tabular attribute", () => {
    const { container } = renderWithProviders(<TabularNumber value={1} />);
    expect(container.querySelector("[data-tabular]")).toBeInTheDocument();
  });

  it("forwards className", () => {
    const { container } = renderWithProviders(<TabularNumber value={1} className="tn-x" />);
    expect(container.querySelector('[data-slot="tabular-number"]')).toHaveClass("tn-x");
  });

  it("has no axe violations", async () => {
    const { container } = renderWithProviders(<TabularNumber value={1234} variant="currency" />);
    expect(await axe(container)).toHaveNoViolations();
  });
});
