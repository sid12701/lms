import { describe, it, expect } from "vitest";
import { axe } from "vitest-axe";
import { renderWithProviders } from "@/test/utils";
import { DpdBadge, type DpdBucketKey } from "./DpdBadge";

describe("DpdBadge", () => {
  it("renders bucket label", () => {
    const { getByText } = renderWithProviders(<DpdBadge bucket="B0" />);
    expect(getByText(/Current/)).toBeInTheDocument();
  });

  it("appends day count when days prop is provided", () => {
    const { container } = renderWithProviders(<DpdBadge bucket="B31_60" days={47} />);
    const badge = container.querySelector('[data-slot="dpd-badge"]');
    expect(badge?.textContent).toMatch(/31–60 DPD/);
    expect(badge?.textContent).toMatch(/47d/);
  });

  it("forwards data-bucket attribute", () => {
    const { container } = renderWithProviders(<DpdBadge bucket="B90_PLUS" />);
    expect(container.querySelector('[data-slot="dpd-badge"]')?.getAttribute("data-bucket")).toBe(
      "B90_PLUS",
    );
  });

  it("renders every canonical bucket without throwing", () => {
    const buckets: DpdBucketKey[] = ["B0", "B1_30", "B31_60", "B61_90", "B90_PLUS", "NPA"];
    for (const b of buckets) {
      const { container, unmount } = renderWithProviders(<DpdBadge bucket={b} />);
      expect(container.querySelector('[data-slot="dpd-badge"]')).toBeInTheDocument();
      unmount();
    }
  });

  it("has no axe violations", async () => {
    const { container } = renderWithProviders(<DpdBadge bucket="B0" />);
    expect(await axe(container)).toHaveNoViolations();
  });
});
