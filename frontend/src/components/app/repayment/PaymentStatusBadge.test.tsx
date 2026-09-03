import { describe, it, expect } from "vitest";
import { axe } from "vitest-axe";
import { renderWithProviders } from "@/test/utils";
import { PaymentStatusBadge } from "./PaymentStatusBadge";

describe("PaymentStatusBadge", () => {
  it("renders the Posted label with the success tone and icon for POSTED", () => {
    const { getByText, container } = renderWithProviders(<PaymentStatusBadge status="POSTED" />);
    expect(getByText("Posted")).toBeInTheDocument();
    const badge = container.querySelector('[data-slot="payment-status-badge"]');
    expect(badge?.getAttribute("data-intent")).toBe("success");
    expect(badge?.className).toContain("text-success");
    expect(badge?.querySelector("svg")?.getAttribute("class")).toContain("lucide-circle-check");
  });

  it("renders an Unknown (raw) label with the neutral tone and icon for an unrecognised status", () => {
    const { getByText, container } = renderWithProviders(<PaymentStatusBadge status="SETTLED" />);
    expect(getByText("Unknown (SETTLED)")).toBeInTheDocument();
    const badge = container.querySelector('[data-slot="payment-status-badge"]');
    expect(badge?.getAttribute("data-intent")).toBe("neutral");
    expect(badge?.className).toContain("text-foreground-muted");
    expect(badge?.querySelector("svg")?.getAttribute("class")).toContain("lucide-circle-dot");
  });

  it("hides the icon when hideIcon is true", () => {
    const { container } = renderWithProviders(<PaymentStatusBadge status="POSTED" hideIcon />);
    const badge = container.querySelector('[data-slot="payment-status-badge"]');
    expect(badge?.querySelector("svg")).toBeNull();
    expect(badge).toHaveTextContent("Posted");
  });

  it("forwards data-status for the raw value, independent of the resolved label", () => {
    const { container } = renderWithProviders(<PaymentStatusBadge status="SETTLED" />);
    const badge = container.querySelector('[data-slot="payment-status-badge"]');
    expect(badge?.getAttribute("data-status")).toBe("SETTLED");
  });

  it("has no axe violations", async () => {
    const { container } = renderWithProviders(<PaymentStatusBadge status="POSTED" />);
    expect(await axe(container)).toHaveNoViolations();
  });
});
