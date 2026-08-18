import { describe, it, expect } from "vitest";
import { axe } from "vitest-axe";
import { MemoryRouter } from "react-router-dom";
import { renderWithProviders } from "@/test/utils";
import type { AlertSeverity } from "@/schemas/alert";
import type { HomeAlertSummary } from "../types";
import { OpenAlertsCard } from "./OpenAlertsCard";
import { SEVERITY_TOKEN } from "./openAlertsSeverity";

const NOW = Date.now();

function mkAlert(overrides: Partial<HomeAlertSummary> = {}): HomeAlertSummary {
  return {
    id: "alert-1",
    severity: "HIGH",
    title: "Disbursement stuck in queue",
    message: null,
    subjectType: "LOAN_APPLICATION",
    subjectId: "11111111-1111-4111-8111-111111111111",
    createdAt: new Date(NOW - 1000 * 60 * 30).toISOString(),
    ...overrides,
  };
}

function wrap(node: React.ReactNode) {
  return <MemoryRouter>{node}</MemoryRouter>;
}

describe("<OpenAlertsCard />", () => {
  it("renders the title", () => {
    const { getByText } = renderWithProviders(wrap(<OpenAlertsCard alerts={[mkAlert()]} />));
    expect(getByText("Open alerts")).toBeInTheDocument();
  });

  it("renders a row per alert", () => {
    const { getByText } = renderWithProviders(
      wrap(
        <OpenAlertsCard
          alerts={[
            mkAlert({ id: "a-1", title: "Rate limit breach", severity: "CRITICAL" }),
            mkAlert({ id: "a-2", title: "KYC document expired", severity: "MEDIUM" }),
          ]}
        />,
      ),
    );
    expect(getByText("Rate limit breach")).toBeInTheDocument();
    expect(getByText("KYC document expired")).toBeInTheDocument();
  });

  it("humanizes delinquency bucket titles", () => {
    const { getByText } = renderWithProviders(
      wrap(
        <OpenAlertsCard
          alerts={[mkAlert({ title: "Delinquency bucket DPD_1_30", severity: "HIGH" })]}
        />,
      ),
    );
    expect(getByText("Delinquency · 1–30 DPD")).toBeInTheDocument();
  });

  it("distinguishes same-titled alerts by their detail message", () => {
    const { getByText } = renderWithProviders(
      wrap(
        <OpenAlertsCard
          alerts={[
            mkAlert({
              id: "a-1",
              title: "Delinquency bucket DPD_1_30",
              message: "Loan APEX-001 is 12 days past due (bucket DPD_1_30, overdue ₹4231.00).",
            }),
            mkAlert({
              id: "a-2",
              title: "Delinquency bucket DPD_1_30",
              message: "Loan APEX-002 is 27 days past due (bucket DPD_1_30, overdue ₹18900.50).",
            }),
          ]}
        />,
      ),
    );
    expect(getByText(/APEX-001 is 12 days past due/)).toBeInTheDocument();
    expect(getByText(/APEX-002 is 27 days past due/)).toBeInTheDocument();
  });

  it("omits the detail line when the alert carries no message", () => {
    const { container } = renderWithProviders(
      wrap(<OpenAlertsCard alerts={[mkAlert({ message: null })]} />),
    );
    expect(container.querySelector('[data-slot="alert-message"]')).toBeNull();
  });

  it("renders an empty state when alerts is empty", () => {
    const { getByText } = renderWithProviders(wrap(<OpenAlertsCard alerts={[]} />));
    expect(getByText("No open alerts")).toBeInTheDocument();
  });

  it.each<[AlertSeverity, string]>([
    ["CRITICAL", "danger"],
    ["HIGH", "warning"],
    ["MEDIUM", "info"],
    ["LOW", "neutral"],
  ])("maps %s severity to the %s token", (severity, token) => {
    const { container } = renderWithProviders(
      wrap(<OpenAlertsCard alerts={[mkAlert({ severity })]} />),
    );
    const row = container.querySelector('[data-slot="alert-row"]');
    expect(row).not.toBeNull();
    expect(row!.getAttribute("data-token")).toBe(token);
    expect(row!.getAttribute("data-severity")).toBe(severity);
    expect(SEVERITY_TOKEN[severity]).toBe(token);
  });

  it("links LOAN_APPLICATION subjects with a readable label", () => {
    const { container, getByText } = renderWithProviders(
      wrap(
        <OpenAlertsCard
          alerts={[mkAlert({ subjectType: "LOAN_APPLICATION", subjectId: "abc-123" })]}
        />,
      ),
    );
    const link = container.querySelector("a");
    expect(link).not.toBeNull();
    expect(link!.getAttribute("href")).toBe("/loan-applications/abc-123");
    expect(getByText(/Loan application · abc-123/)).toBeInTheDocument();
  });

  it("renders a non-link mention for non-application subjects", () => {
    const { container, queryByRole } = renderWithProviders(
      wrap(
        <OpenAlertsCard
          alerts={[
            mkAlert({
              subjectType: "REPORT_REQUEST",
              subjectId: "rpt-xyz-0000",
            }),
          ]}
        />,
      ),
    );
    expect(queryByRole("link")).toBeNull();
    expect(container.textContent).toMatch(/Report request · rpt-xyz-/);
  });

  it("forwards className to the card", () => {
    const { container } = renderWithProviders(
      wrap(<OpenAlertsCard alerts={[]} className="extra-class" />),
    );
    const card = container.querySelector('[data-slot="open-alerts"]');
    expect(card).not.toBeNull();
    expect(card!.className).toContain("extra-class");
  });

  it("has no axe violations", async () => {
    const { container } = renderWithProviders(wrap(<OpenAlertsCard alerts={[mkAlert()]} />));
    expect(await axe(container)).toHaveNoViolations();
  });

  it("has no axe violations when empty", async () => {
    const { container } = renderWithProviders(wrap(<OpenAlertsCard alerts={[]} />));
    expect(await axe(container)).toHaveNoViolations();
  });
});
