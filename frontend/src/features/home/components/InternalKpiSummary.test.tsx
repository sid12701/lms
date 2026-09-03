import { describe, it, expect } from "vitest";
import { axe } from "vitest-axe";
import { renderWithProviders } from "@/test/utils";
import { formatINR } from "@/lib/format";
import type { InternalHomeKpis } from "../types";
import { InternalKpiSummary } from "./InternalKpiSummary";

const BASE: InternalHomeKpis = {
  applicationsAwaitingApproval: 12,
  applicationsInDisbursement: 3,
  totalDisbursedAmount: 1_250_000,
  overdueLoansCount: 7,
  overdueAmount: 480_000,
  applicationsByStatus: [],
  dpdBuckets: [],
  recentApplications: [],
  openAlerts: [],
  dataAsOf: "2026-08-01T12:00:00.000Z",
};

describe("<InternalKpiSummary />", () => {
  it("renders all four KPI labels", () => {
    const { getByText } = renderWithProviders(<InternalKpiSummary kpis={BASE} />);
    expect(getByText("Awaiting approval")).toBeInTheDocument();
    expect(getByText("In disbursement")).toBeInTheDocument();
    expect(getByText("Total disbursed")).toBeInTheDocument();
    expect(getByText("Overdue loans")).toBeInTheDocument();
  });

  it("formats numeric values", () => {
    const { getByText } = renderWithProviders(<InternalKpiSummary kpis={BASE} />);
    expect(getByText("12")).toBeInTheDocument();
    expect(getByText("3")).toBeInTheDocument();
    expect(getByText("7")).toBeInTheDocument();
    expect(getByText(formatINR(BASE.totalDisbursedAmount))).toBeInTheDocument();
    expect(getByText(`${formatINR(BASE.overdueAmount)} outstanding`)).toBeInTheDocument();
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

  /*
   * Found by a live browser pass, not by this suite: a fifth tile squeezed each
   * one to ~142px at xl, while a full-notation crore figure needs ~168px and the
   * tile clips its overflow — so ₹12,30,00,000 silently lost its last digits at
   * 1280 and 1366. Four tiles on the default four-up grid clear it.
   *
   * jsdom has no layout engine, so this cannot assert the measurement that
   * actually matters. It pins the constraint that keeps the row safe: four
   * tiles, no five-up override. The real check is a browser at 1280.
   */
  it("keeps the row to four tiles so crore figures are not clipped at xl", () => {
    const { container } = renderWithProviders(<InternalKpiSummary kpis={BASE} />);
    expect(container.querySelectorAll('[data-slot="kpi-tile"]')).toHaveLength(4);
    expect(container.querySelector('[data-slot="kpi-strip"]')!.className).not.toContain(
      "grid-cols-5",
    );
  });

  it("has no axe violations", async () => {
    const { container } = renderWithProviders(<InternalKpiSummary kpis={BASE} />);
    expect(await axe(container)).toHaveNoViolations();
  });
});
