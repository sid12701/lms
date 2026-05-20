import { describe, it, expect } from "vitest";
import { axe } from "vitest-axe";
import { renderWithProviders } from "@/test/utils";
import { formatINR, formatDate } from "@/lib/format";
import { DisbursementSummaryCard } from "./DisbursementSummaryCard";

const FIXTURE = {
  approvedPrincipal: 250_000,
  tenureMonths: 24,
  interestRatePercent: 14.5,
  scheduledOn: "2026-05-12",
  firstEmiDate: "2026-06-12",
} as const;

describe("<DisbursementSummaryCard />", () => {
  it("renders the card title", () => {
    const { getByText } = renderWithProviders(<DisbursementSummaryCard {...FIXTURE} />);
    expect(getByText("Disbursement summary")).toBeInTheDocument();
  });

  it("renders all five fields", () => {
    const { getByText } = renderWithProviders(<DisbursementSummaryCard {...FIXTURE} />);
    expect(getByText("Approved principal")).toBeInTheDocument();
    expect(getByText("Tenure")).toBeInTheDocument();
    expect(getByText("Interest rate")).toBeInTheDocument();
    expect(getByText("Scheduled disbursement")).toBeInTheDocument();
    expect(getByText("First EMI due")).toBeInTheDocument();
  });

  it("formats currency via formatINR", () => {
    const { getByText } = renderWithProviders(<DisbursementSummaryCard {...FIXTURE} />);
    expect(getByText(formatINR(FIXTURE.approvedPrincipal))).toBeInTheDocument();
  });

  it("formats both dates via formatDate", () => {
    const { getByText } = renderWithProviders(<DisbursementSummaryCard {...FIXTURE} />);
    expect(getByText(formatDate(FIXTURE.scheduledOn))).toBeInTheDocument();
    expect(getByText(formatDate(FIXTURE.firstEmiDate))).toBeInTheDocument();
  });

  it("renders tenure in plural for >1 month", () => {
    const { getByText } = renderWithProviders(<DisbursementSummaryCard {...FIXTURE} />);
    expect(getByText("24 months")).toBeInTheDocument();
  });

  it("renders tenure in singular for exactly 1 month", () => {
    const { getByText } = renderWithProviders(
      <DisbursementSummaryCard {...FIXTURE} tenureMonths={1} />,
    );
    expect(getByText("1 month")).toBeInTheDocument();
  });

  it("formats the interest rate to two decimals with the suffix", () => {
    const { getByText } = renderWithProviders(<DisbursementSummaryCard {...FIXTURE} />);
    expect(getByText("14.50% p.a.")).toBeInTheDocument();
  });

  it("applies the tabular-nums attribute to numeric values", () => {
    const { getByText } = renderWithProviders(<DisbursementSummaryCard {...FIXTURE} />);
    const principal = getByText(formatINR(FIXTURE.approvedPrincipal));
    expect(principal.getAttribute("data-tabular")).toBe("true");
  });

  it("uses dl/dt/dd semantics for label/value pairs", () => {
    const { getByText } = renderWithProviders(<DisbursementSummaryCard {...FIXTURE} />);
    expect(getByText("Approved principal").tagName).toBe("DT");
    expect(getByText(formatINR(FIXTURE.approvedPrincipal)).tagName).toBe("DD");
  });

  it("forwards a custom className", () => {
    const { container } = renderWithProviders(
      <DisbursementSummaryCard {...FIXTURE} className="extra-class" />,
    );
    const card = container.querySelector('[data-slot="disbursement-summary"]');
    expect(card).not.toBeNull();
    expect(card!.className).toContain("extra-class");
  });

  it("has no axe violations", async () => {
    const { container } = renderWithProviders(<DisbursementSummaryCard {...FIXTURE} />);
    expect(await axe(container)).toHaveNoViolations();
  });
});
