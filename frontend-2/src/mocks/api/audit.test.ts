/**
 * Audit explorer handler tests.
 *
 * Covers:
 *   - role gate (SYSTEM_ADMIN only — every other role is rejected)
 *   - happy list across every stream (newest first)
 *   - single-stream + multi-stream filtering
 *   - actorId / correlationId / date-range filters
 *   - free-text `q` over headline + actorName
 *   - pagination
 *   - subject deep-link projection (subjectType + subjectId)
 *
 * Seed primer: `seedDashboardFixtures` only writes ApplicationAuditEvent
 * rows. The remaining four streams are empty out of the box. To exercise
 * every projection, each test that needs a row primes the db directly via
 * `getDb()`. The audit page is append-only by design — there is no public
 * mutation surface.
 */
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { setLatencyOverride } from "../latency";
import { scenario } from "../scenarios";
import { resetIdempotency } from "../idempotency";
import { getDb } from "../db/state";
import {
  USER_LSP_READ,
  USER_LSP_WRITE,
  USER_OPS_ADMIN,
  USER_OPS_USER,
  USER_PRODUCT_ADMIN,
} from "../db/seed";
import { resetMockApi, auth } from "./index";
import * as audit from "./audit";
import type {
  DocumentAccessEvent,
  IntakeAuditEvent,
  PiiRevealEvent,
  ProductAuditEvent,
} from "@/types";

// ─── Helpers ─────────────────────────────────────────────────────────────────

function uuid(prefix: string, suffix: string): string {
  const p = prefix.padEnd(8, "0").slice(0, 8);
  const s = suffix.padEnd(12, "0").slice(0, 12);
  return `${p}-1111-4111-8111-${s}`;
}

const APP_ID = "a0000000-0001-4000-8000-000000000000";
const BORROWER_ID = "b0000000-0001-4000-8000-000000000000";

function seedAuxiliaryAuditRows(): void {
  const db = getDb();
  const intake: IntakeAuditEvent = {
    id: uuid("ce000001", "intake0000aa"),
    applicationId: APP_ID,
    snapshotJson: { borrowerPan: "ABCDE1234F" },
    actorId: USER_OPS_USER,
    channel: "UI",
    correlationId: "co000001-1111-4111-8111-intake0000aa",
    createdAt: "2026-04-30T11:00:00.000Z",
  };
  db.auditIntake.push(intake);

  const pii: PiiRevealEvent = {
    id: uuid("ce000002", "pii00000000a"),
    subjectType: "BORROWER",
    subjectId: BORROWER_ID,
    fieldName: "PAN",
    actorId: USER_OPS_ADMIN,
    actorRole: "SYSTEM_ADMIN",
    reason: "Verifying KYC mismatch flagged by ops",
    correlationId: "co000002-1111-4111-8111-pii00000000a",
    revealedAt: "2026-05-01T08:30:00.000Z",
  };
  db.auditPiiReveal.push(pii);

  const docAccess: DocumentAccessEvent = {
    id: uuid("ce000003", "doc0000000a"),
    documentId: uuid("d0000001", "documentaaaa"),
    applicationId: APP_ID,
    action: "VIEWED",
    actorId: USER_OPS_USER,
    actorRole: "OPS_USER",
    correlationId: "co000003-1111-4111-8111-doc0000000a",
    accessedAt: "2026-05-02T09:15:00.000Z",
  };
  db.auditDocumentAccess.push(docAccess);

  const productAudit: ProductAuditEvent = {
    id: uuid("ce000004", "product0000a"),
    productId: "bbbbbbbb-1111-4bbb-8bbb-bbbbbbbbbbbb",
    action: "UPDATED",
    before: { interestRatePct: 14.0 },
    after: { interestRatePct: 14.5 },
    actorId: USER_PRODUCT_ADMIN,
    actorRole: "PRODUCT_ADMIN",
    correlationId: "co000004-1111-4111-8111-product0000a",
    createdAt: "2026-05-03T10:00:00.000Z",
  };
  db.auditProduct.push(productAudit);
}

// ─── Setup ───────────────────────────────────────────────────────────────────

beforeEach(() => {
  setLatencyOverride(0);
  scenario.reset();
  resetIdempotency();
  resetMockApi();
});

afterEach(() => {
  scenario.reset();
  setLatencyOverride(null);
});

// ─── Auth + role gate ────────────────────────────────────────────────────────

describe("audit.events — role gate", () => {
  it("UNAUTHORIZED when no session is active", async () => {
    await expect(audit.events()).rejects.toMatchObject({
      code: "UNAUTHORIZED",
      httpStatus: 401,
    });
  });

  it.each([
    ["ops.user", USER_OPS_USER],
    ["product.admin", USER_PRODUCT_ADMIN],
    ["lsp.read", USER_LSP_READ],
    ["lsp.write", USER_LSP_WRITE],
  ])("UNAUTHORIZED for non-SYSTEM_ADMIN role %s", async (username, _id) => {
    await auth.login({ username, password: "any" });
    await expect(audit.events()).rejects.toMatchObject({
      code: "UNAUTHORIZED",
      httpStatus: 401,
    });
  });

  it("SYSTEM_ADMIN is admitted", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const res = await audit.events();
    expect(res.items.length).toBeGreaterThan(0);
  });
});

// ─── Happy path / projections ────────────────────────────────────────────────

describe("audit.events — listing", () => {
  it("returns rows from every populated stream, newest first", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    seedAuxiliaryAuditRows();
    const res = await audit.events({ pageSize: 200 });

    const streams = new Set(res.items.map((r) => r.stream));
    expect(streams).toContain("APPLICATION");
    expect(streams).toContain("INTAKE");
    expect(streams).toContain("PII_REVEAL");
    expect(streams).toContain("DOCUMENT_ACCESS");
    expect(streams).toContain("PRODUCT");

    for (let i = 1; i < res.items.length; i++) {
      expect(
        res.items[i - 1]!.createdAt >= res.items[i]!.createdAt,
      ).toBe(true);
    }
  });

  it("PII_REVEAL row exposes the field NAME but never the value (BR-7)", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    seedAuxiliaryAuditRows();
    const res = await audit.events({ streams: ["PII_REVEAL"] });
    expect(res.items.length).toBe(1);
    const row = res.items[0]!;
    expect(row.headline).toContain("PAN");
    expect(row.headline).not.toMatch(/ABCDE/i);
    expect(row.subjectType).toBe("BORROWER");
    expect(row.subjectId).toBe(BORROWER_ID);
  });

  it("APPLICATION row carries subjectType=LOAN_APPLICATION + subjectId", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const res = await audit.events({ streams: ["APPLICATION"], pageSize: 5 });
    expect(res.items.length).toBeGreaterThan(0);
    for (const row of res.items) {
      expect(row.stream).toBe("APPLICATION");
      expect(row.subjectType).toBe("LOAN_APPLICATION");
      expect(row.subjectId).not.toBeNull();
      expect(row.actorName).not.toBe("");
    }
  });
});

// ─── Filters ─────────────────────────────────────────────────────────────────

describe("audit.events — filters", () => {
  it("single-stream filter (PRODUCT) narrows the result set", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    seedAuxiliaryAuditRows();
    const res = await audit.events({ streams: ["PRODUCT"] });
    expect(res.total).toBe(1);
    expect(res.items[0]!.stream).toBe("PRODUCT");
  });

  it("multi-stream filter accepts comma-joined values", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    seedAuxiliaryAuditRows();
    const res = await audit.events({ streams: ["PII_REVEAL", "DOCUMENT_ACCESS"] });
    expect(res.total).toBe(2);
    const streams = new Set(res.items.map((r) => r.stream));
    expect(streams).toEqual(new Set(["PII_REVEAL", "DOCUMENT_ACCESS"]));
  });

  it("actorId filter scopes rows to one actor", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    seedAuxiliaryAuditRows();
    const res = await audit.events({ actorId: USER_PRODUCT_ADMIN });
    expect(res.total).toBe(1);
    expect(res.items[0]!.actorId).toBe(USER_PRODUCT_ADMIN);
  });

  it("correlationId substring match finds the right row", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    seedAuxiliaryAuditRows();
    const res = await audit.events({ correlationId: "pii00000000a" });
    expect(res.total).toBe(1);
    expect(res.items[0]!.stream).toBe("PII_REVEAL");
  });

  it("dateFrom + dateTo clip the result range", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    seedAuxiliaryAuditRows();
    const res = await audit.events({
      dateFrom: "2026-05-01",
      dateTo: "2026-05-02",
      pageSize: 200,
    });
    for (const row of res.items) {
      expect(row.createdAt.slice(0, 10) >= "2026-05-01").toBe(true);
      expect(row.createdAt.slice(0, 10) <= "2026-05-02").toBe(true);
    }
    // The PII reveal (05-01) and document access (05-02) seeds fall inside.
    const streams = new Set(res.items.map((r) => r.stream));
    expect(streams).toContain("PII_REVEAL");
    expect(streams).toContain("DOCUMENT_ACCESS");
    expect(streams.has("PRODUCT")).toBe(false);
  });

  it("free-text `q` matches against headline (PAN reveal)", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    seedAuxiliaryAuditRows();
    const res = await audit.events({ q: "PAN" });
    expect(res.items.length).toBeGreaterThan(0);
    for (const row of res.items) {
      expect(row.headline.toLowerCase()).toContain("pan");
    }
  });

  it("free-text `q` matches against actorName", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    seedAuxiliaryAuditRows();
    const res = await audit.events({ q: "product.admin", pageSize: 200 });
    expect(res.items.length).toBeGreaterThan(0);
    for (const row of res.items) {
      expect(row.actorName.toLowerCase()).toContain("product.admin");
    }
  });
});

// ─── Pagination ──────────────────────────────────────────────────────────────

describe("audit.events — pagination", () => {
  it("page=0 + page=1 yield disjoint slices", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const page0 = await audit.events({ page: 0, pageSize: 5 });
    const page1 = await audit.events({ page: 1, pageSize: 5 });
    expect(page0.items.length).toBe(5);
    expect(page1.items.length).toBeGreaterThan(0);
    const seen = new Set(page0.items.map((r) => r.id));
    for (const row of page1.items) {
      expect(seen.has(row.id)).toBe(false);
    }
    expect(page0.page).toBe(0);
    expect(page1.page).toBe(1);
    expect(page0.pageSize).toBe(5);
  });

  it("default page size is 50", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const res = await audit.events();
    expect(res.pageSize).toBe(50);
    expect(res.page).toBe(0);
  });
});
