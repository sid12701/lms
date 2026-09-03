import { describe, it, expect } from "vitest";
import { axe } from "vitest-axe";
import { renderWithProviders } from "@/test/utils";
import { PermissionDeniedState } from "./PermissionDeniedState";

describe("PermissionDeniedState", () => {
  it("renders the default title and lock variant", () => {
    const { getByText, container } = renderWithProviders(<PermissionDeniedState />);
    expect(getByText("You don't have access to this page")).toBeInTheDocument();
    expect(container.querySelector('[data-variant="no-permission"]')).toBeInTheDocument();
  });

  it("composes a description from currentRole and missingPermission", () => {
    const { getByText } = renderWithProviders(
      <PermissionDeniedState currentRole="OPS_USER" missingPermission="LOAN_STATUS_UPDATE" />,
    );
    expect(getByText(/Signed in as OPS_USER/)).toBeInTheDocument();
    expect(getByText(/LOAN_STATUS_UPDATE/)).toBeInTheDocument();
  });

  it("uses the explicit description when provided", () => {
    const { getByText, queryByText } = renderWithProviders(
      <PermissionDeniedState
        currentRole="OPS_USER"
        missingPermission="LOAN_STATUS_UPDATE"
        description="Custom"
      />,
    );
    expect(getByText("Custom")).toBeInTheDocument();
    expect(queryByText(/Signed in as/)).not.toBeInTheDocument();
  });

  it("forwards className", () => {
    const { container } = renderWithProviders(<PermissionDeniedState className="denied-x" />);
    expect(container.querySelector('[data-slot="empty-state"]')).toHaveClass("denied-x");
  });

  it("renders title as h1 for permission-denied routes", () => {
    const { getByRole } = renderWithProviders(<PermissionDeniedState />);
    expect(
      getByRole("heading", { level: 1, name: "You don't have access to this page" }),
    ).toBeInTheDocument();
  });

  it("has no axe violations", async () => {
    const { container } = renderWithProviders(
      <PermissionDeniedState currentRole="OPS_USER" missingPermission="LOAN_STATUS_UPDATE" />,
    );
    expect(await axe(container)).toHaveNoViolations();
  });
});
