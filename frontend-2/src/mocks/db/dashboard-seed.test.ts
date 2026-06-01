/**
 * Dashboard-seed invariants.
 *
 * These tests pin down the shape of the seed so downstream KPI assertions
 * stay stable as the fixture evolves.
 */
import { describe, it, expect, beforeEach } from "vitest";
import { createEmptyDb } from "./state";
import { seedAuthFixtures } from "./seed";
import { seedDashboardFixtures } from "./dashboard-seed";
import { MOCK_REFERENCE_NOW } from "./reference-now";
import type { MockDb } from "./state";

let db: MockDb;

beforeEach(() => {
  db = createEmptyDb();
  seedAuthFixtures(db);
  seedDashboardFixtures(db);
});

describe("seedDashboardFixtures", () => {
  it("is idempotent", () => {
    const appsBefore = db.applications.size;
    seedDashboardFixtures(db);
    expect(db.applications.size).toBe(appsBefore);
  });

  it("seeds the planned number of applications across every status group", () => {
    expect(db.applications.size).toBeGreaterThanOrEqual(40);
    expect(db.applications.size).toBeLessThanOrEqual(60);
    const statuses = new Set(Array.from(db.applications.values()).map((a) => a.status));
    expect(statuses.has("AWAITING_APPROVAL")).toBe(true);
    expect(statuses.has("APPROVED_PENDING_DISBURSAL")).toBe(true);
    expect(statuses.has("DISBURSEMENT_IN_PROGRESS")).toBe(true);
    expect(statuses.has("UNDER_REPAYMENT")).toBe(true);
    expect(statuses.has("DELINQUENT")).toBe(true);
    expect(statuses.has("CLOSED")).toBe(true);
    expect(statuses.has("REJECTED")).toBe(true);
  });

  it("spreads applications across all four LSPs", () => {
    const lspIds = new Set(Array.from(db.applications.values()).map((a) => a.lspId));
    expect(lspIds.size).toBe(4);
  });

  it("seeds 30 borrowers", () => {
    expect(db.borrowers.size).toBe(30);
  });

  it("seeds loan accounts for the disbursed / repayment cohort", () => {
    expect(db.accounts.size).toBeGreaterThanOrEqual(15);
    for (const acct of db.accounts.values()) {
      const app = db.applications.get(acct.applicationId);
      expect(app).toBeDefined();
    }
  });

  it("seeds installments and includes some OVERDUE rows", () => {
    let overdueCount = 0;
    for (const list of db.installments.values()) {
      for (const inst of list) {
        if (inst.status === "OVERDUE") overdueCount++;
      }
    }
    expect(overdueCount).toBeGreaterThan(0);
  });

  it("seeds payments — some this month, some prior", () => {
    expect(db.payments.length).toBeGreaterThanOrEqual(20);
    const thisMonth = db.payments.filter((p) => {
      const d = new Date(p.postedAt);
      return (
        d.getUTCFullYear() === MOCK_REFERENCE_NOW.getUTCFullYear() &&
        d.getUTCMonth() === MOCK_REFERENCE_NOW.getUTCMonth()
      );
    });
    expect(thisMonth.length).toBeGreaterThan(0);
  });

  it("seeds alerts with a mix of open and acknowledged states", () => {
    expect(db.alerts.size).toBe(12);
    const open = Array.from(db.alerts.values()).filter((a) => a.status === "OPEN");
    const ack = Array.from(db.alerts.values()).filter((a) => a.status === "ACKNOWLEDGED");
    expect(open.length).toBeGreaterThan(0);
    expect(ack.length).toBeGreaterThan(0);
  });

  it("seeds approval audit pairs for TAT computation", () => {
    const awaiting = db.auditApplication.filter((e) => e.toStatus === "AWAITING_APPROVAL");
    const approved = db.auditApplication.filter((e) => e.toStatus === "APPROVED_PENDING_DISBURSAL");
    expect(awaiting.length).toBeGreaterThan(0);
    expect(approved.length).toBeGreaterThan(0);
  });

  it("does NOT mutate the auth seed (LSPs, products, users intact)", () => {
    // The auth seed now chains `seedAdminLspFixtures` (Phase 9) for 4 extra
    // LSPs — that's a fixture-layer change, not a dashboard-seed regression.
    expect(db.lsps.size).toBe(8);
    expect(db.products.size).toBe(5);
    expect(db.users.size).toBeGreaterThanOrEqual(6);
  });
});
