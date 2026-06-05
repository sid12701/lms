import { describe, it, expect } from "vitest";
import { axe } from "vitest-axe";
import { renderWithProviders } from "@/test/utils";
import { AccountStatusBadge } from "./AccountStatusBadge";

describe("AccountStatusBadge", () => {
  it("renders the account-status label", () => {
    const { getByText } = renderWithProviders(<AccountStatusBadge status="ACTIVE" />);
    expect(getByText("Active")).toBeInTheDocument();
  });

  it("forwards variant and emits data-status", () => {
    const { container } = renderWithProviders(
      <AccountStatusBadge status="PENDING_DISBURSEMENT" variant="default" />,
    );
    const badge = container.querySelector('[data-slot="status-badge"]');
    expect(badge?.getAttribute("data-status")).toBe("PENDING_DISBURSEMENT");
    expect(badge?.getAttribute("data-variant")).toBe("default");
  });

  it("has no axe violations", async () => {
    const { container } = renderWithProviders(<AccountStatusBadge status="CLOSED" />);
    expect(await axe(container)).toHaveNoViolations();
  });
});
