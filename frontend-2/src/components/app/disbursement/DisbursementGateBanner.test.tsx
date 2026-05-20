import { describe, it, expect } from "vitest";
import { axe } from "vitest-axe";
import { renderWithProviders } from "@/test/utils";
import { DisbursementGateBanner } from "./DisbursementGateBanner";

describe("<DisbursementGateBanner />", () => {
  it("returns null when both gates pass", () => {
    const { container } = renderWithProviders(
      <DisbursementGateBanner docsComplete scheduleValid />,
    );
    expect(container.firstChild).toBeNull();
  });

  it("shows the BR-3 message when docs are incomplete", () => {
    const { getByText, queryByText } = renderWithProviders(
      <DisbursementGateBanner docsComplete={false} scheduleValid />,
    );
    expect(getByText(/Required documents pending verification \(BR-3\)/i)).toBeInTheDocument();
    expect(queryByText(/Repayment schedule not generated/i)).not.toBeInTheDocument();
  });

  it("shows the BR-10 message when the schedule is invalid", () => {
    const { getByText, queryByText } = renderWithProviders(
      <DisbursementGateBanner docsComplete scheduleValid={false} />,
    );
    expect(getByText(/Repayment schedule not generated \(BR-10\)/i)).toBeInTheDocument();
    expect(queryByText(/Required documents pending verification/i)).not.toBeInTheDocument();
  });

  it("shows BOTH messages when both gates fail", () => {
    const { getByText } = renderWithProviders(
      <DisbursementGateBanner docsComplete={false} scheduleValid={false} />,
    );
    expect(getByText(/Required documents pending verification \(BR-3\)/i)).toBeInTheDocument();
    expect(getByText(/Repayment schedule not generated \(BR-10\)/i)).toBeInTheDocument();
  });

  it("renders the alert role + headline when blocked", () => {
    const { getByRole, getByText } = renderWithProviders(
      <DisbursementGateBanner docsComplete={false} scheduleValid={false} />,
    );
    expect(getByRole("alert")).toBeInTheDocument();
    expect(getByText("Disbursement blocked")).toBeInTheDocument();
  });

  it("forwards a custom className", () => {
    const { getByRole } = renderWithProviders(
      <DisbursementGateBanner
        docsComplete={false}
        scheduleValid={false}
        className="my-custom-cls"
      />,
    );
    expect(getByRole("alert").className).toContain("my-custom-cls");
  });

  it("has no axe violations in the error state", async () => {
    const { container } = renderWithProviders(
      <DisbursementGateBanner docsComplete={false} scheduleValid={false} />,
    );
    expect(await axe(container)).toHaveNoViolations();
  });
});
