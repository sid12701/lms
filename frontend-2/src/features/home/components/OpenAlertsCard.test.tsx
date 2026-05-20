import { describe, it, expect } from "vitest";
import { axe } from "vitest-axe";
import { MemoryRouter } from "react-router-dom";
import { renderWithProviders } from "@/test/utils";
import type { AlertSeverity } from "@/schemas/alert";
import type { HomeAlertSummary } from "../types";
import { OpenAlertsCard, SEVERITY_TOKEN } from "./OpenAlertsCard";

const NOW = Date.now();

function mkAlert(overrides: Partial<HomeAlertSummary> = {}): HomeAlertSummary {
  return {
    id: "alert-1",
    severity: "HIGH",
    title: "Disbursement stuck in queue",
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
            mkAlert({ id: "a-1", title: "Webhook retry exhausted", severity: "CRITICAL" }),
            mkAlert({ id: "a-2", title: "KYC document expired", severity: "MEDIUM" }),
          ]}
        />,
      ),
    );
    expect(getByText("Webhook retry exhausted")).toBeInTheDocument();
    expect(getByText("KYC document expired")).toBeInTheDocument();
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

  it("links LOAN_APPLICATION subjects to the detail page", () => {
    const { container } = renderWithProviders(
      wrap(
        <OpenAlertsCard
          alerts={[mkAlert({ subjectType: "LOAN_APPLICATION", subjectId: "abc-123" })]}
        />,
      ),
    );
    const link = container.querySelector("a");
    expect(link).not.toBeNull();
    expect(link!.getAttribute("href")).toBe("/loan-applications/abc-123");
  });

  it("renders a non-link mention for non-application subjects", () => {
    const { container, queryByRole } = renderWithProviders(
      wrap(
        <OpenAlertsCard
          alerts={[
            mkAlert({
              subjectType: "WEBHOOK_DELIVERY",
              subjectId: "wh-xyz-0000",
            }),
          ]}
        />,
      ),
    );
    expect(queryByRole("link")).toBeNull();
    expect(container.textContent).toMatch(/webhook_delivery/);
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
    const { container } = renderWithProviders(
      wrap(<OpenAlertsCard alerts={[mkAlert()]} />),
    );
    expect(await axe(container)).toHaveNoViolations();
  });

  it("has no axe violations when empty", async () => {
    const { container } = renderWithProviders(wrap(<OpenAlertsCard alerts={[]} />));
    expect(await axe(container)).toHaveNoViolations();
  });
});
