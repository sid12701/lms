/**
 * DetailTabsShell tests — verifies the 6-tab layout, controlled value, and
 * the tab-panel data-testid that e2e uses.
 */
import { describe, expect, it, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { axe } from "vitest-axe";
import { renderWithProviders } from "@/test/utils";
import { DetailTabsShell } from "./DetailTabsShell";

describe("DetailTabsShell", () => {
  it("renders all six tab triggers", () => {
    const { getByRole } = renderWithProviders(
      <DetailTabsShell activeTab="overview" onTabChange={() => {}}>
        <p>body</p>
      </DetailTabsShell>,
    );
    for (const label of [
      "Overview",
      "Schedule",
      "Documents",
      "Repayments",
      "Activity",
      "Webhooks",
    ]) {
      expect(getByRole("tab", { name: label })).toBeInTheDocument();
    }
  });

  it("marks the active tab as selected and renders its panel", () => {
    const { getByRole, getByTestId } = renderWithProviders(
      <DetailTabsShell activeTab="activity" onTabChange={() => {}}>
        <p>activity body</p>
      </DetailTabsShell>,
    );
    expect(getByRole("tab", { name: "Activity" })).toHaveAttribute("aria-selected", "true");
    expect(getByTestId("tab-panel-activity")).toHaveTextContent("activity body");
  });

  it("calls onTabChange when a different tab is clicked", async () => {
    const onTabChange = vi.fn();
    const { getByRole } = renderWithProviders(
      <DetailTabsShell activeTab="overview" onTabChange={onTabChange}>
        <p>body</p>
      </DetailTabsShell>,
    );
    await userEvent.click(getByRole("tab", { name: "Webhooks" }));
    expect(onTabChange).toHaveBeenCalledWith("webhooks");
  });

  it("has no axe-detectable a11y violations", async () => {
    const { container } = renderWithProviders(
      <DetailTabsShell activeTab="overview" onTabChange={() => {}}>
        <p>body</p>
      </DetailTabsShell>,
    );
    expect(await axe(container)).toHaveNoViolations();
  });
});
