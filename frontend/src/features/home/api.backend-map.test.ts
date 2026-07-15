import { describe, expect, it } from "vitest";
import { mapBackendHomeOverviewToInternalKpis, type BackendHomeOverview } from "./api";

const OVERVIEW: BackendHomeOverview = {
  totalDisbursedAmount: 1_000_000,
  totalOutstandingAmount: 800_000,
  dpd90PlusAmount: 50_000,
  dpd90PlusLoanCount: 2,
  applicationsAwaitingApproval: 3,
  applicationsInDisbursement: 4,
  avgApprovalTatHours: 12.5,
  applicationsByStatus: [
    { status: "AWAITING_APPROVAL", count: 3 },
    { status: "DISBURSED", count: 10 },
  ],
  dpdBuckets: [
    { bucket: "CURRENT", count: 8 },
    { bucket: "DPD_90_PLUS", count: 2 },
  ],
  openAlerts: 5,
  openAlertSummaries: [
    {
      id: "alert-1",
      severity: "HIGH",
      title: "Webhook failing",
      subjectType: "WEBHOOK_DELIVERY",
      subjectId: "wd-1",
      createdAt: "2026-05-11T07:00:00.000Z",
    },
  ],
  priorityAccounts: [
    {
      applicationId: "app-1",
      externalLoanId: "EXT-1",
      customerName: "A•••a Devi",
      lspCode: "APEX",
      principalAmount: 250_000,
      overdueAmount: 10_000,
      daysPastDue: 95,
      loanStatusDisplay: "UNDER_REPAYMENT",
    },
  ],
  recentApplications: [
    {
      id: "app-recent-1",
      externalLoanId: "EXT-RECENT-1",
      borrowerNameMasked: "Anika Sharma",
      lspName: "Apex Finance",
      productName: "Flex Cash",
      status: "INITIALIZED",
      requestedAmount: 150_000,
      createdAt: "2026-06-18T18:47:44.810085Z",
    },
  ],
  dataAsOf: "2026-07-06T04:30:00.000Z",
};

describe("mapBackendHomeOverviewToInternalKpis (Gap #7)", () => {
  it("maps all six KPI fields from the backend overview", () => {
    const kpis = mapBackendHomeOverviewToInternalKpis(OVERVIEW);
    expect(kpis.applicationsAwaitingApproval).toBe(3);
    expect(kpis.applicationsInDisbursement).toBe(4);
    expect(kpis.avgApprovalTatHours).toBe(12.5);
    expect(kpis.applicationsByStatus).toEqual([
      { status: "AWAITING_APPROVAL", count: 3 },
      { status: "DISBURSED", count: 10 },
    ]);
    expect(kpis.dpdBuckets).toEqual([
      { bucket: "B0", count: 8 },
      { bucket: "B1_30", count: 0 },
      { bucket: "B31_60", count: 0 },
      { bucket: "B61_90", count: 0 },
      { bucket: "B90_PLUS", count: 2 },
    ]);
    expect(kpis.openAlerts).toHaveLength(1);
    expect(kpis.openAlerts[0]?.title).toBe("Webhook failing");
    expect(kpis.recentApplications[0]?.status).toBe("INITIALIZED");
    expect(kpis.recentApplications[0]?.borrowerNameMasked).toBe("Anika Sharma");
  });

  it("tolerates older backends that omit recentApplications", () => {
    const { recentApplications: _ignored, ...legacyOverview } = OVERVIEW;
    const kpis = mapBackendHomeOverviewToInternalKpis(legacyOverview as BackendHomeOverview);
    expect(kpis.recentApplications).toEqual([]);
  });
});
