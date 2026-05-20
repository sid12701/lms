import { describe, it, expect } from "vitest";
import { axe } from "vitest-axe";
import { renderWithProviders } from "@/test/utils";
import { KpiStrip } from "./KpiStrip";

describe("KpiStrip", () => {
  it("renders children", () => {
    const { getByText } = renderWithProviders(
      <KpiStrip>
        <div>One</div>
        <div>Two</div>
      </KpiStrip>,
    );
    expect(getByText("One")).toBeInTheDocument();
    expect(getByText("Two")).toBeInTheDocument();
  });

  it("applies the responsive grid utilities", () => {
    const { container } = renderWithProviders(
      <KpiStrip>
        <div>x</div>
      </KpiStrip>,
    );
    const root = container.querySelector('[data-slot="kpi-strip"]');
    expect(root).toHaveClass("grid", "grid-cols-1", "md:grid-cols-2", "xl:grid-cols-4");
  });

  it("forwards className", () => {
    const { container } = renderWithProviders(
      <KpiStrip className="extra">
        <div>x</div>
      </KpiStrip>,
    );
    expect(container.querySelector('[data-slot="kpi-strip"]')).toHaveClass("extra");
  });

  it("has no axe violations", async () => {
    const { container } = renderWithProviders(
      <KpiStrip>
        <div>x</div>
      </KpiStrip>,
    );
    expect(await axe(container)).toHaveNoViolations();
  });
});
