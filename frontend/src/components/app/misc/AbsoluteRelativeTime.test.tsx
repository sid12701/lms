import { describe, expect, it } from "vitest";
import { renderWithProviders } from "@/test/utils";
import { AbsoluteRelativeTime, TimeLayoutProvider } from "./AbsoluteRelativeTime";

const NOW = new Date("2026-07-13T00:38:00.000Z");

describe("AbsoluteRelativeTime", () => {
  it("renders absolute as primary and relative as secondary", () => {
    const { container } = renderWithProviders(
      <AbsoluteRelativeTime iso="2026-06-21T10:00:00.000Z" now={NOW} />,
    );
    const root = container.querySelector('[data-slot="absolute-relative-time"]');
    expect(root?.getAttribute("dateTime")).toBe("2026-06-21T10:00:00.000Z");
    expect(
      container.querySelector('[data-slot="absolute-relative-absolute"]')?.textContent,
    ).toMatch(/21 Jun 2026/);
    expect(
      container.querySelector('[data-slot="absolute-relative-relative"]')?.textContent,
    ).toMatch(/days? ago/i);
  });

  /*
   * The dense-column variant. F-13's requirement is not "always show the
   * absolute" — it is that no timestamp is relative-only with no path to the
   * instant. Here that path is `dateTime` plus `title`, so the assertion is on
   * those, not on visible text.
   */
  describe('variant="relative"', () => {
    it("shows only the relative reading", () => {
      const { container } = renderWithProviders(
        <AbsoluteRelativeTime iso="2026-06-21T10:00:00.000Z" now={NOW} variant="relative" />,
      );
      expect(
        container.querySelector('[data-slot="absolute-relative-absolute"]'),
      ).not.toBeInTheDocument();
      expect(
        container.querySelector('[data-slot="absolute-relative-relative"]')?.textContent,
      ).toMatch(/days? ago/i);
    });

    it("still carries the absolute instant on the element", () => {
      const { container } = renderWithProviders(
        <AbsoluteRelativeTime iso="2026-06-21T10:00:00.000Z" now={NOW} variant="relative" />,
      );
      const root = container.querySelector('[data-slot="absolute-relative-time"]');
      expect(root?.getAttribute("dateTime")).toBe("2026-06-21T10:00:00.000Z");
      expect(root?.getAttribute("title")).toMatch(/21 Jun 2026/);
    });

    it("falls back to an em dash for implausible instants like the full variant", () => {
      const { container } = renderWithProviders(
        <AbsoluteRelativeTime iso="1970-01-01T00:00:00.000Z" now={NOW} variant="relative" />,
      );
      expect(container.querySelector('[data-slot="absolute-relative-time"]')?.textContent).toBe(
        "—",
      );
    });
  });

  /*
   * A roomy layout overrides the dense-column request. `title` only surfaces on
   * hover, which touch devices do not have, so a relative-only reading inside a
   * stacked card would leave no path at all to the instant — the one thing
   * F-13's policy rules out. Measured live at 390px on the loan-applications
   * mobile cards, which reuse the table's cell renderer verbatim.
   */
  describe("inside a roomy layout", () => {
    it('renders the full reading even when variant="relative" was requested', () => {
      const { container } = renderWithProviders(
        <TimeLayoutProvider dense={false}>
          <AbsoluteRelativeTime iso="2026-06-21T10:00:00.000Z" now={NOW} variant="relative" />
        </TimeLayoutProvider>,
      );
      expect(
        container.querySelector('[data-slot="absolute-relative-absolute"]')?.textContent,
      ).toMatch(/21 Jun 2026/);
      expect(
        container.querySelector('[data-slot="absolute-relative-relative"]')?.textContent,
      ).toMatch(/days? ago/i);
    });

    it("keeps the dense reading when the layout declares itself dense", () => {
      const { container } = renderWithProviders(
        <TimeLayoutProvider dense>
          <AbsoluteRelativeTime iso="2026-06-21T10:00:00.000Z" now={NOW} variant="relative" />
        </TimeLayoutProvider>,
      );
      expect(
        container.querySelector('[data-slot="absolute-relative-absolute"]'),
      ).not.toBeInTheDocument();
    });
  });

  it("renders an em dash for implausible instants", () => {
    const { container } = renderWithProviders(
      <AbsoluteRelativeTime iso="1970-01-01T00:00:00.000Z" now={NOW} />,
    );
    expect(container.querySelector('[data-slot="absolute-relative-time"]')?.textContent).toBe("—");
  });
});
