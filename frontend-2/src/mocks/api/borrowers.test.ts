/**
 * Borrowers handler tests (Phase 6).
 *
 * Uses the same setup harness as `loan-applications.test.ts`:
 * `resetMockApi()` re-seeds the deterministic dashboard fixtures so we can
 * address borrowers + applications by index.
 *
 * Seed cheatsheet (see `dashboard-seed.ts` for the source of truth):
 *
 *   Borrower #i visibleLspIds  = [LSPS[(i-1) % 4]]
 *     LSPS = [BHAW_DEMO, SOUTH, NORTH, EAST]
 *   Borrower #1  → BHAW_DEMO
 *   Borrower #2  → SOUTH
 *   Borrower #16 → EAST       (apps #16 APPROVED + #46 CLOSED)
 *   Borrower #6  → SOUTH      (apps #6  KYC_PENDING + #36 DELINQUENT)
 *
 *   Application borrowerIndex = ((appCounter-1) % 30) + 1, so borrower #N
 *   owns applications #N and #N+30 (when N+30 ≤ 53).
 *
 *   STATUSES_NEEDING_ACCOUNT accounts are minted in plan order starting at 1:
 *     DISBURSED (21-25)            → accounts 1-5
 *     UNDER_REPAYMENT (26-35)      → accounts 6-15
 *     DELINQUENT (36-41)           → accounts 16-21
 *     FORECLOSURE_REQUESTED (42-43)→ accounts 22-23
 *     CLOSED (44-48)               → accounts 24-28
 */
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { setLatencyOverride } from "../latency";
import { scenario } from "../scenarios";
import { resetIdempotency } from "../idempotency";
import { getDb } from "../db/state";
import {
  LSP_BHAW_DEMO,
  LSP_EAST,
  LSP_SOUTH,
} from "../db/seed";
import { resetMockApi, auth, borrowers } from "./index";
import type { LoanDocument } from "@/types";

// ─── Id helpers (mirror dashboard-seed shapes) ───────────────────────────────

function pad4(n: number): string {
  return n.toString(16).padStart(4, "0");
}
function borrowerId(i: number): string {
  return `b0000000-${pad4(i)}-4000-8000-000000000000`;
}
function applicationId(i: number): string {
  return `a0000000-${pad4(i)}-4000-8000-000000000000`;
}

// ─── Stable borrower IDs used across the suite ───────────────────────────────

// Borrower 1: visible to BHAW_DEMO. Apps #1 (INITIATED, SOUTH) + #31
// (UNDER_REPAYMENT, EAST). Note: neither app is on BHAW_DEMO LSP, so an
// LSP_UI_READ user (lsp.read = BHAW_DEMO) sees this borrower but its
// loans-tab returns an empty list.
const BORROWER_1_BHAW = borrowerId(1);

// Borrower 2: visible to SOUTH only. Useful for cross-tenant FORBIDDEN tests
// when authed as the BHAW_DEMO LSP user.
const BORROWER_2_SOUTH = borrowerId(2);

// Borrower 16: visible to EAST. Apps #16 (APPROVED_PENDING_DISBURSAL — open,
// no account) and #46 (CLOSED — closed, has account, all installments PAID).
// Yields deterministic totals: open=1, closed=1, lifetimeDisbursed=app#46
// principal, activeOverdue=0.
const BORROWER_16_EAST = borrowerId(16);

// Borrower 6: visible to SOUTH. App #6 (KYC_PENDING — open, no account) +
// app #36 (DELINQUENT — open, has account #16 with OVERDUE installments).
const BORROWER_6_SOUTH = borrowerId(6);

// Unknown borrower id — used for 404 assertions.
const BORROWER_UNKNOWN = "b0000000-ffff-4000-8000-000000000000";

// Deterministic amount formula in the seed: amount = 100_000 + (n*17_000 % 500_000).
function plannedAmount(n: number): number {
  return 100_000 + ((n * 17_000) % 500_000);
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

// ─── Auth gate ───────────────────────────────────────────────────────────────

describe("borrowers — auth gate", () => {
  it.each([
    ["detail", () => borrowers.detail(BORROWER_1_BHAW)],
    ["loans", () => borrowers.loans(BORROWER_1_BHAW)],
    ["activity", () => borrowers.activity(BORROWER_1_BHAW)],
  ])("%s: UNAUTHORIZED when no session", async (_name, run) => {
    await expect(run()).rejects.toMatchObject({ code: "UNAUTHORIZED", httpStatus: 401 });
  });

  it("recordPiiReveal: UNAUTHORIZED when no session", async () => {
    await expect(
      borrowers.recordPiiReveal(BORROWER_1_BHAW, { field: "PAN", reason: "kyc" }),
    ).rejects.toMatchObject({ code: "UNAUTHORIZED", httpStatus: 401 });
  });

  it("recordDocumentAccess: UNAUTHORIZED when no session", async () => {
    await expect(
      borrowers.recordDocumentAccess("d0000000-0000-4000-8000-000000000000", { action: "VIEW" }),
    ).rejects.toMatchObject({ code: "UNAUTHORIZED", httpStatus: 401 });
  });
});

// ─── Detail handler ──────────────────────────────────────────────────────────

describe("borrowers.detail", () => {
  it("404s on an unknown borrower id", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    await expect(borrowers.detail(BORROWER_UNKNOWN)).rejects.toMatchObject({
      code: "NOT_FOUND",
      httpStatus: 404,
    });
  });

  it("returns full detail for SYSTEM_ADMIN", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const detail = await borrowers.detail(BORROWER_16_EAST);
    expect(detail.borrower.id).toBe(BORROWER_16_EAST);
    expect(detail.visibleLsps.length).toBe(1);
    expect(detail.visibleLsps[0]?.id).toBe(LSP_EAST);
    expect(detail.visibleLsps[0]?.name).toBeTruthy();
  });

  it("totals derive from the seeded applications (borrower 16 — open+closed)", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const detail = await borrowers.detail(BORROWER_16_EAST);
    // App #16 = APPROVED_PENDING_DISBURSAL (open, no account)
    // App #46 = CLOSED (closed, has account with principal = plannedAmount(46))
    expect(detail.totals.openApplicationsCount).toBe(1);
    expect(detail.totals.closedApplicationsCount).toBe(1);
    expect(detail.totals.lifetimeDisbursedAmount).toBe(plannedAmount(46));
    expect(detail.totals.activeOverdueAmount).toBe(0);
  });

  it("totals.activeOverdueAmount sums OVERDUE installments (borrower 6 — DELINQUENT)", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const detail = await borrowers.detail(BORROWER_6_SOUTH);
    // Borrower 6's app #36 is DELINQUENT → 4 installments at offsets
    // -30, 0, +30, +60. Status=OVERDUE for dueOffsetDays<=0 → 2 OVERDUE rows.
    expect(detail.totals.activeOverdueAmount).toBeGreaterThan(0);
    // App #6 is KYC_PENDING (no account), app #36 is DELINQUENT (open) → 2 open.
    expect(detail.totals.openApplicationsCount).toBe(2);
    expect(detail.totals.closedApplicationsCount).toBe(0);
    // Only app #36 has an account.
    expect(detail.totals.lifetimeDisbursedAmount).toBe(plannedAmount(36));
  });

  it("LSP_UI_READ cannot see a borrower from another LSP (FORBIDDEN)", async () => {
    // lsp.read is on BHAW_DEMO. Borrower 2 is visible only to SOUTH.
    await auth.login({ username: "lsp.read", password: "any" });
    await expect(borrowers.detail(BORROWER_2_SOUTH)).rejects.toMatchObject({
      code: "FORBIDDEN",
      httpStatus: 403,
    });
  });

  it("LSP_UI_READ can see a borrower from its own LSP", async () => {
    // lsp.read is BHAW_DEMO; borrower 1 visibleLspIds=[BHAW_DEMO].
    await auth.login({ username: "lsp.read", password: "any" });
    const detail = await borrowers.detail(BORROWER_1_BHAW);
    expect(detail.borrower.id).toBe(BORROWER_1_BHAW);
    expect(detail.visibleLsps.map((l) => l.id)).toContain(LSP_BHAW_DEMO);
  });
});

// ─── Loans handler ───────────────────────────────────────────────────────────

describe("borrowers.loans", () => {
  it("404s on an unknown borrower id", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    await expect(borrowers.loans(BORROWER_UNKNOWN)).rejects.toMatchObject({
      code: "NOT_FOUND",
      httpStatus: 404,
    });
  });

  it("returns all loans across LSPs for SYSTEM_ADMIN, sorted desc by createdAt", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const res = await borrowers.loans(BORROWER_16_EAST);
    expect(res.loans.length).toBe(2);
    // Sort desc: first loan's createdAt >= second's.
    expect(res.loans[0]!.createdAt >= res.loans[1]!.createdAt).toBe(true);
    const appIds = res.loans.map((l) => l.applicationId);
    expect(appIds).toContain(applicationId(16));
    expect(appIds).toContain(applicationId(46));
  });

  it("LSP_UI_READ only sees loans on its own LSP for a multi-LSP borrower view", async () => {
    // Borrower 1 has app #1 (SOUTH) + app #31 (EAST). Borrower 1 is visible
    // to BHAW_DEMO (because visibleLspIds = [BHAW_DEMO]), so the BHAW_DEMO
    // LSP user sees the borrower but zero loans of its own.
    await auth.login({ username: "lsp.read", password: "any" });
    const res = await borrowers.loans(BORROWER_1_BHAW);
    expect(res.loans.length).toBe(0);
  });

  it("populates lspName and productName for each loan", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const res = await borrowers.loans(BORROWER_16_EAST);
    for (const loan of res.loans) {
      expect(loan.lspName).toBeTruthy();
      expect(loan.lspName).not.toBe("Unknown LSP");
      expect(loan.productName).toBeTruthy();
      expect(loan.productName).not.toBe("Unknown product");
    }
  });
});

// ─── Activity handler ────────────────────────────────────────────────────────

describe("borrowers.activity", () => {
  it("404s on an unknown borrower id", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    await expect(borrowers.activity(BORROWER_UNKNOWN)).rejects.toMatchObject({
      code: "NOT_FOUND",
      httpStatus: 404,
    });
  });

  it("unions three audit streams for a borrower, sorted desc", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const db = getDb();

    // Seed: one PII reveal + one document access for borrower 16's app #46.
    db.auditPiiReveal.push({
      id: "11110000-1111-4111-8111-111111111111",
      subjectType: "BORROWER",
      subjectId: BORROWER_16_EAST,
      fieldName: "PAN",
      actorId: "aaaaaaaa-1111-4aaa-8aaa-aaaaaaaaaaaa",
      actorRole: "SYSTEM_ADMIN",
      reason: "kyc audit",
      correlationId: "11110000-2222-4111-8111-111111111111",
      revealedAt: "2026-05-08T12:00:00.000Z",
    });
    const doc: LoanDocument = {
      id: "d0000000-cccc-4ccc-8ccc-cccccccccccc",
      applicationId: applicationId(46),
      type: "PAN",
      displayName: "PAN card",
      requiredForApproval: true,
      requiredForDisbursement: true,
      status: "VERIFIED",
      notes: null,
      fileMeta: null,
      uploadedAt: null,
      uploadedBy: null,
    };
    db.documents.set(doc.id, doc);
    db.auditDocumentAccess.push({
      id: "22220000-1111-4111-8111-111111111111",
      documentId: doc.id,
      applicationId: applicationId(46),
      action: "VIEWED",
      actorId: "aaaaaaaa-1111-4aaa-8aaa-aaaaaaaaaaaa",
      actorRole: "SYSTEM_ADMIN",
      correlationId: "22220000-2222-4111-8111-111111111111",
      accessedAt: "2026-05-09T08:00:00.000Z",
    });

    const res = await borrowers.activity(BORROWER_16_EAST);
    // 1 PII reveal + 1 document access. Application audit events for this
    // borrower may or may not be seeded; the union must contain at least our
    // two synthetic rows.
    const kinds = res.entries.map((e) => e.kind);
    expect(kinds).toContain("PII_REVEAL");
    expect(kinds).toContain("DOCUMENT_ACCESS");

    // Sort desc by per-stream timestamp.
    const tsOf = (entry: (typeof res.entries)[number]): string => {
      if (entry.kind === "APPLICATION") return entry.event.createdAt;
      if (entry.kind === "PII_REVEAL") return entry.event.revealedAt;
      return entry.event.accessedAt;
    };
    for (let i = 1; i < res.entries.length; i++) {
      expect(tsOf(res.entries[i - 1]!) >= tsOf(res.entries[i]!)).toBe(true);
    }
  });

  it("ignores PII reveal events for other borrowers", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const db = getDb();
    db.auditPiiReveal.push({
      id: "11110000-3333-4111-8111-111111111111",
      subjectType: "BORROWER",
      subjectId: BORROWER_2_SOUTH,
      fieldName: "PAN",
      actorId: "aaaaaaaa-1111-4aaa-8aaa-aaaaaaaaaaaa",
      actorRole: "SYSTEM_ADMIN",
      reason: "different borrower",
      correlationId: "11110000-4444-4111-8111-111111111111",
      revealedAt: "2026-05-08T12:00:00.000Z",
    });
    const res = await borrowers.activity(BORROWER_16_EAST);
    const piiEntries = res.entries.filter((e) => e.kind === "PII_REVEAL");
    expect(piiEntries.length).toBe(0);
  });
});

// ─── PII reveal handler ──────────────────────────────────────────────────────

describe("borrowers.recordPiiReveal", () => {
  it("appends a row to auditPiiReveal and returns the unmasked value", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const db = getDb();
    const before = db.auditPiiReveal.length;
    const res = await borrowers.recordPiiReveal(BORROWER_16_EAST, {
      field: "PAN",
      reason: "kyc audit",
    });
    expect(db.auditPiiReveal.length).toBe(before + 1);
    expect(res.auditId).toBeTruthy();
    // The seed PAN is `ABCDE${num4}F` for borrower N (num4 = N % 10000 padded).
    expect(res.value).toMatch(/^[A-Z]{5}\d{4}[A-Z]$/);
  });

  it("returns the unmasked AADHAAR/MOBILE/ACCOUNT_NUMBER/EMAIL per field", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const aadhaar = await borrowers.recordPiiReveal(BORROWER_16_EAST, {
      field: "AADHAAR",
      reason: "r",
    });
    expect(aadhaar.value.length).toBe(12);

    const mobile = await borrowers.recordPiiReveal(BORROWER_16_EAST, {
      field: "MOBILE",
      reason: "r",
    });
    expect(mobile.value).toMatch(/^\d{10}$/);

    const acct = await borrowers.recordPiiReveal(BORROWER_16_EAST, {
      field: "ACCOUNT_NUMBER",
      reason: "r",
    });
    expect(acct.value.length).toBeGreaterThan(0);

    const email = await borrowers.recordPiiReveal(BORROWER_16_EAST, {
      field: "EMAIL",
      reason: "r",
    });
    expect(email.value).toContain("@");
  });

  it("is idempotent — replay with same key returns cached response, no new audit row", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const db = getDb();
    const key = "pii-replay-key-1234";
    const before = db.auditPiiReveal.length;

    const first = await borrowers.recordPiiReveal(
      BORROWER_16_EAST,
      { field: "PAN", reason: "kyc" },
      { idempotencyKey: key },
    );
    const afterFirst = db.auditPiiReveal.length;
    expect(afterFirst).toBe(before + 1);

    const second = await borrowers.recordPiiReveal(
      BORROWER_16_EAST,
      { field: "PAN", reason: "kyc" },
      { idempotencyKey: key },
    );
    expect(second.auditId).toBe(first.auditId);
    expect(second.value).toBe(first.value);
    expect(db.auditPiiReveal.length).toBe(afterFirst);
  });

  it("404s for unknown borrower", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    await expect(
      borrowers.recordPiiReveal(BORROWER_UNKNOWN, { field: "PAN", reason: "x" }),
    ).rejects.toMatchObject({ code: "NOT_FOUND", httpStatus: 404 });
  });

  it("LSP_UI_READ cannot reveal PII on a borrower from another LSP", async () => {
    await auth.login({ username: "lsp.read", password: "any" });
    await expect(
      borrowers.recordPiiReveal(BORROWER_2_SOUTH, { field: "PAN", reason: "x" }),
    ).rejects.toMatchObject({ code: "FORBIDDEN", httpStatus: 403 });
  });
});

// ─── Document access handler ─────────────────────────────────────────────────

describe("recordDocumentAccess", () => {
  function seedDoc(applicationIdValue: string): LoanDocument {
    const doc: LoanDocument = {
      id: "d0000000-1111-4111-8111-111111111111",
      applicationId: applicationIdValue,
      type: "PAN",
      displayName: "PAN card",
      requiredForApproval: true,
      requiredForDisbursement: true,
      status: "VERIFIED",
      notes: null,
      fileMeta: null,
      uploadedAt: null,
      uploadedBy: null,
    };
    getDb().documents.set(doc.id, doc);
    return doc;
  }

  it("appends a row to auditDocumentAccess and returns auditId", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const doc = seedDoc(applicationId(46));
    const db = getDb();
    const before = db.auditDocumentAccess.length;
    const res = await borrowers.recordDocumentAccess(doc.id, { action: "VIEW" });
    expect(db.auditDocumentAccess.length).toBe(before + 1);
    expect(res.auditId).toBeTruthy();
    const written = db.auditDocumentAccess[db.auditDocumentAccess.length - 1];
    expect(written?.action).toBe("VIEWED");
    expect(written?.documentId).toBe(doc.id);
    expect(written?.applicationId).toBe(applicationId(46));
  });

  it("maps PREVIEW → VIEWED and DOWNLOAD → DOWNLOADED", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const doc = seedDoc(applicationId(46));
    await borrowers.recordDocumentAccess(doc.id, { action: "PREVIEW" });
    const previewRow = getDb().auditDocumentAccess.at(-1);
    expect(previewRow?.action).toBe("VIEWED");

    await borrowers.recordDocumentAccess(doc.id, { action: "DOWNLOAD" });
    const downloadRow = getDb().auditDocumentAccess.at(-1);
    expect(downloadRow?.action).toBe("DOWNLOADED");
  });

  it("is idempotent — replay with same key returns the same auditId", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const doc = seedDoc(applicationId(46));
    const key = "doc-access-replay-1234";
    const db = getDb();
    const before = db.auditDocumentAccess.length;
    const first = await borrowers.recordDocumentAccess(
      doc.id,
      { action: "VIEW" },
      { idempotencyKey: key },
    );
    expect(db.auditDocumentAccess.length).toBe(before + 1);
    const second = await borrowers.recordDocumentAccess(
      doc.id,
      { action: "VIEW" },
      { idempotencyKey: key },
    );
    expect(second.auditId).toBe(first.auditId);
    expect(db.auditDocumentAccess.length).toBe(before + 1);
  });

  it("404s for unknown document id", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    await expect(
      borrowers.recordDocumentAccess("d0000000-ffff-4fff-8fff-ffffffffffff", { action: "VIEW" }),
    ).rejects.toMatchObject({ code: "NOT_FOUND", httpStatus: 404 });
  });

  it("LSP_UI_READ cannot access a document on a cross-LSP application", async () => {
    // App #1 belongs to LSP_SOUTH. lsp.read = BHAW_DEMO.
    await auth.login({ username: "lsp.read", password: "any" });
    const doc = seedDoc(applicationId(1));
    void LSP_SOUTH;
    await expect(
      borrowers.recordDocumentAccess(doc.id, { action: "VIEW" }),
    ).rejects.toMatchObject({ code: "FORBIDDEN", httpStatus: 403 });
  });
});

// ─── Index registration ──────────────────────────────────────────────────────

describe("borrowers — api index registration", () => {
  it("exposes the real namespace (no NotImplementedError stub)", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    // If the stub were still in place this would throw NOT_IMPLEMENTED (501).
    const res = await borrowers.detail(BORROWER_16_EAST);
    expect(res.borrower.id).toBe(BORROWER_16_EAST);
  });
});
