import { describe, it, expect } from "vitest";
import { axe } from "vitest-axe";
import { renderWithProviders } from "@/test/utils";
import { formatINR } from "@/lib/format";
import type { InternalHomeKpis } from "../types";
import { InternalKpiSummary } from "./InternalKpiSummary";

const BASE: InternalHomeKpis = {
  applicationsAwaitingApproval: 12,
  applicationsInDisbursement: 3,
  mtdDisbursedAmount: 1_250_000,
  overdueLoansCount: 7,
  overdueAmount: 480_000,
  avgApprovalTatHours: 8.45,
  applicationsByStatus: [],
  dpdBuckets: [],
  recentApplications: [],
  openAlerts: [],
};

describe("<InternalKpiSummary />", () => {
  it("renders all five KPI labels", () => {
    const { getByText } = renderWithProviders(<InternalKpiSummary kpis={BASE} />);
    expect(getByText("Awaiting approval")).toBeInTheDocument();
    expect(getByText("In disbursement")).toBeInTheDocument();
    expect(getByText("MTD disbursed")).toBeInTheDocument();
    expect(getByText("Overdue loans")).toBeInTheDocument();
    expect(getByText("Avg approval TAT")).toBeInTheDocument();
  });

  it("formats numeric values", () => {
    const { getByText } = renderWithProviders(<InternalKpiSummary kpis={BASE} />);
    expect(getByText("12")).toBeInTheDocument();
    expect(getByText("3")).toBeInTheDocument();
    expect(getByText("7")).toBeInTheDocument();
    expect(getByText(formatINR(BASE.mtdDisbursedAmount))).toBeInTheDocument();
    expect(getByText(`${formatINR(BASE.overdueAmount)} outstanding`)).toBeInTheDocument();
    expect(getByText("8.4 h")).toBeInTheDocument();
  });

  it('renders "—" when avgApprovalTatHours is null', () => {
    const { getByText } = renderWithProviders(
      <InternalKpiSummary kpis={{ ...BASE, avgApprovalTatHours: null }} />,
    );
    expect(getByText("—")).toBeInTheDocument();
  });

  it("forwards className to the strip", () => {
    const { container } = renderWithProviders(
      <InternalKpiSummary kpis={BASE} className="extra-class" />,
    );
    const strip = container.querySelector('[data-slot="kpi-strip"]');
    expect(strip).not.toBeNull();
    expect(strip!.className).toContain("extra-class");
  });

  it("applies tabular-nums to numeric values", () => {
    const { container } = renderWithProviders(<InternalKpiSummary kpis={BASE} />);
    const tabulars = container.querySelectorAll('[data-tabular="true"]');
    expect(tabulars.length).toBeGreaterThan(0);
  });

  it("has no axe violations", async () => {
    const { container } = renderWithProviders(<InternalKpiSummary kpis={BASE} />);
    expect(await axe(container)).toHaveNoViolations();
  });
});
