/**
 * DetailTabsShell tests — verifies the 5-tab layout, controlled value, and
 * the tab-panel data-testid that e2e uses.
 */
import { afterEach, describe, expect, it, vi } from "vitest";
import { axe } from "vitest-axe";
import { renderWithProviders } from "@/test/utils";
import { DetailTabsShell } from "./DetailTabsShell";

const realMatchMedia = window.matchMedia;

function stubMotionPreference(matches: boolean) {
  Object.defineProperty(window, "matchMedia", {
    configurable: true,
    writable: true,
    value: vi.fn().mockReturnValue({
      matches,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      addListener: vi.fn(),
      removeListener: vi.fn(),
      dispatchEvent: vi.fn(),
    }),
  });
}

// `matchMedia` is global; leaving a stub in place would hand the next test a
// reduced-motion preference it never asked for.
afterEach(() => {
  Object.defineProperty(window, "matchMedia", {
    configurable: true,
    writable: true,
    value: realMatchMedia,
  });
});

describe("DetailTabsShell", () => {
  it("renders all five tab triggers", () => {
    const { getByRole } = renderWithProviders(
      <DetailTabsShell activeTab="overview" onTabChange={() => {}}>
        <p>body</p>
      </DetailTabsShell>,
    );
    for (const label of ["Overview", "Schedule", "Documents", "Repayments", "Activity"]) {
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

  /*
   * The audit sanctions motion on exactly two surfaces, this being one: a tab
   * swap is a state change where continuity aids comprehension. Both halves
   * matter — that the entrance is there, and that it is gone entirely when the
   * viewer has asked for reduced motion.
   */
  it("fades the panel in on a tab swap, and remounts it so the fade replays", () => {
    stubMotionPreference(false);
    const { getByTestId, rerender } = renderWithProviders(
      <DetailTabsShell activeTab="overview" onTabChange={() => {}}>
        <p>body</p>
      </DetailTabsShell>,
    );
    const first = getByTestId("tab-panel-overview");
    expect(first.className).toContain("animate-in");
    expect(first.className).toContain("duration-150");

    rerender(
      <DetailTabsShell activeTab="schedule" onTabChange={() => {}}>
        <p>body</p>
      </DetailTabsShell>,
    );
    // A fresh node, not the same one reused — otherwise the entrance would
    // only ever play on first paint.
    expect(getByTestId("tab-panel-schedule")).not.toBe(first);
  });

  it("drops the panel animation entirely under prefers-reduced-motion", () => {
    stubMotionPreference(true);
    const { getByTestId } = renderWithProviders(
      <DetailTabsShell activeTab="overview" onTabChange={() => {}}>
        <p>body</p>
      </DetailTabsShell>,
    );
    const panel = getByTestId("tab-panel-overview");
    expect(panel).toHaveAttribute("data-reduced-motion", "true");
    expect(panel.className).not.toContain("animate-in");
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
