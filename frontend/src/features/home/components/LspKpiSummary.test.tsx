import { describe, it, expect } from "vitest";
import { axe } from "vitest-axe";
import { renderWithProviders } from "@/test/utils";
import { formatINR } from "@/lib/format";
import type { LspHomeKpis } from "../types";
import { LspKpiSummary } from "./LspKpiSummary";

const BASE: LspHomeKpis = {
  myActiveApplications: 24,
  myInDisbursement: 5,
  myMtdDisbursedAmount: 875_000,
  myOverdueLoansCount: 2,
  recentApplications: [],
  openAlerts: [],
};

describe("<LspKpiSummary />", () => {
  it("renders all four labels", () => {
    const { getByText } = renderWithProviders(<LspKpiSummary kpis={BASE} />);
    expect(getByText("My active applications")).toBeInTheDocument();
    expect(getByText("In disbursement")).toBeInTheDocument();
    expect(getByText("MTD disbursed")).toBeInTheDocument();
    expect(getByText("My overdue loans")).toBeInTheDocument();
  });

  it("formats numeric values", () => {
    const { getByText } = renderWithProviders(<LspKpiSummary kpis={BASE} />);
    expect(getByText("24")).toBeInTheDocument();
    expect(getByText("5")).toBeInTheDocument();
    expect(getByText("2")).toBeInTheDocument();
    expect(getByText(formatINR(BASE.myMtdDisbursedAmount))).toBeInTheDocument();
  });

  it("forwards className", () => {
    const { container } = renderWithProviders(
      <LspKpiSummary kpis={BASE} className="extra-class" />,
    );
    const strip = container.querySelector('[data-slot="kpi-strip"]');
    expect(strip).not.toBeNull();
    expect(strip!.className).toContain("extra-class");
  });

  it("applies tabular-nums on every value", () => {
    const { container } = renderWithProviders(<LspKpiSummary kpis={BASE} />);
    const values = container.querySelectorAll('[data-slot="kpi-value"][data-tabular="true"]');
    expect(values.length).toBe(4);
  });

  it("has no axe violations", async () => {
    const { container } = renderWithProviders(<LspKpiSummary kpis={BASE} />);
    expect(await axe(container)).toHaveNoViolations();
  });
});
