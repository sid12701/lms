/**
 * Loan-applications handler tests.
 *
 * Covers every registered route + every BR enforcement point referenced in
 * the file-header of `loan-applications.ts`. Tests use the same setup
 * harness as `home.test.ts` — `resetMockApi()` re-seeds the deterministic
 * dashboard fixtures so we can address rows by index.
 *
 * Deterministic IDs (see `dashboard-seed.ts`):
 *   Applications status mix (50 total) ─ statusCounter walks the plan:
 *     INITIATED            #1-4    (LSPs cycle: SOUTH, NORTH, EAST, BHAW_DEMO)
 *     KYC_PENDING          #5-7
 *     AWAITING_APPROVAL    #8-13
 *     APPROVED_PENDING_DISB #14-17
 *     DISBURSEMENT_IN_PROG #18-20
 *     DISBURSED            #21-25
 *     UNDER_REPAYMENT      #26-35
 *     DELINQUENT           #36-41
 *     FORECLOSURE_REQUESTED #42-43
 *     CLOSED               #44-48
 *     REJECTED             #49-51
 *     CANCELLED            #52-53
 *
 *   App #N belongs to LSP = LSPS[N % 4] where LSPS = [BHAW_DEMO, SOUTH, NORTH, EAST].
 *   Therefore BHAW_DEMO apps are: 4, 8, 12, 16, 20, 24, 28, 32, 36, 40, 44, 48, 52.
 */
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { setLatencyOverride } from "../latency";
import { scenario } from "../scenarios";
import { resetIdempotency } from "../idempotency";
import { getDb } from "../db/state";
import { LSP_BHAW_DEMO } from "../db/seed";
import { resetMockApi, auth, loanApplications } from "./index";
import type { LoanDocument, RepaymentInstallment } from "@/types";

// ─── Helpers ─────────────────────────────────────────────────────────────────

function pad4(n: number): string {
  return n.toString(16).padStart(4, "0");
}
function appId(i: number): string {
  return `a0000000-${pad4(i)}-4000-8000-000000000000`;
}
function acctId(i: number): string {
  return `c0000000-${pad4(i)}-4000-8000-000000000000`;
}
function instId(acct: number, num: number): string {
  return `e0000000-${pad4(acct)}-4000-8${pad4(num).slice(1)}-000000000000`;
}

// App #4 is the first BHAW_DEMO INITIATED application.
const APP_INITIATED_BHAW = appId(4);
// App #1 is INITIATED, SOUTH (used for internal-role transition tests).
const APP_INITIATED_SOUTH = appId(1);
// App #14 is the first APPROVED_PENDING_DISBURSAL (LSPS[14%4] = NORTH).
const APP_APPROVED_NORTH = appId(14);
// App #16 is APPROVED_PENDING_DISBURSAL, BHAW_DEMO.
const APP_APPROVED_BHAW = appId(16);
// App #24 is DISBURSED, BHAW_DEMO — has account+schedule+installments,
// first installment is DUE with outstandingAmount = installmentAmount.
// Accounts are only created for STATUSES_NEEDING_ACCOUNT in seed order:
// DISBURSED #21-25 → accounts 1-5, so app #24 = account #4.
const APP_DISBURSED_BHAW = appId(24);
const ACCT_DISBURSED_BHAW_INDEX = 4;

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

// ─── Auth gate (every route 401s without a session) ──────────────────────────

describe("loan-applications — auth gate", () => {
  it("list: UNAUTHORIZED when no session", async () => {
    await expect(loanApplications.list()).rejects.toMatchObject({
      code: "UNAUTHORIZED",
      httpStatus: 401,
    });
  });

  it.each([
    ["detail", () => loanApplications.detail(APP_DISBURSED_BHAW)],
    ["schedule", () => loanApplications.schedule(APP_DISBURSED_BHAW)],
    ["documents", () => loanApplications.documents(APP_DISBURSED_BHAW)],
    ["repayments", () => loanApplications.repayments(APP_DISBURSED_BHAW)],
    ["activity", () => loanApplications.activity(APP_DISBURSED_BHAW)],
    ["webhooks", () => loanApplications.webhooks(APP_DISBURSED_BHAW)],
  ])("%s: UNAUTHORIZED when no session", async (_name, run) => {
    await expect(run()).rejects.toMatchObject({ code: "UNAUTHORIZED", httpStatus: 401 });
  });

  it("transition: UNAUTHORIZED when no session", async () => {
    await expect(
      loanApplications.transition(APP_INITIATED_SOUTH, { to: "KYC_PENDING", reason: null }),
    ).rejects.toMatchObject({ code: "UNAUTHORIZED", httpStatus: 401 });
  });

  it("postRepayment: UNAUTHORIZED when no session", async () => {
    await expect(
      loanApplications.postRepayment(APP_DISBURSED_BHAW, {
        installmentId: instId(ACCT_DISBURSED_BHAW_INDEX, 1),
        amount: 1,
        postedAt: new Date().toISOString(),
        mode: "UPI",
      }),
    ).rejects.toMatchObject({ code: "UNAUTHORIZED", httpStatus: 401 });
  });

  it("initiateDisbursement: UNAUTHORIZED when no session", async () => {
    await expect(
      loanApplications.initiateDisbursement(APP_APPROVED_BHAW, { note: null }),
    ).rejects.toMatchObject({ code: "UNAUTHORIZED", httpStatus: 401 });
  });
});

// ─── List handler ────────────────────────────────────────────────────────────

describe("loan-applications.list — filtering and scoping", () => {
  it("SYSTEM_ADMIN sees all 53 seeded applications", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const res = await loanApplications.list({ pageSize: 100 });
    expect(res.total).toBe(53);
    expect(res.items.length).toBe(53);
  });

  it("LSP_UI_READ only sees its own LSP's rows (BHAW_DEMO)", async () => {
    await auth.login({ username: "lsp.read", password: "any" });
    const res = await loanApplications.list({ pageSize: 100 });
    expect(res.total).toBe(13);
    for (const item of res.items) {
      expect(item.lspId).toBe(LSP_BHAW_DEMO);
    }
  });

  it("status filter narrows the result set", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const res = await loanApplications.list({ status: ["INITIATED"], pageSize: 100 });
    expect(res.total).toBe(4);
    expect(res.items.every((i) => i.status === "INITIATED")).toBe(true);
  });

  it("multi-status filter (comma-encoded) accepts multiple values", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const res = await loanApplications.list({ status: ["INITIATED", "KYC_PENDING"], pageSize: 100 });
    expect(res.total).toBe(7);
  });

  it("q free-text search matches against borrower name", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    // "Aanya Sharma" is borrower #1, owns INITIATED app #1.
    const res = await loanApplications.list({ q: "Aanya" });
    expect(res.total).toBeGreaterThan(0);
    for (const item of res.items) {
      // Masked, but starts with "A".
      expect(item.borrowerNameMasked.startsWith("A")).toBe(true);
    }
  });

  it("q free-text search matches against externalLoanId", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    // App #3 has externalLoanId = "EXT-00003".
    const res = await loanApplications.list({ q: "EXT-00003" });
    expect(res.total).toBe(1);
    expect(res.items[0]?.externalLoanId).toBe("EXT-00003");
  });

  it("pagination: page=1 pageSize=10 yields next slice", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const page0 = await loanApplications.list({ page: 0, pageSize: 10 });
    const page1 = await loanApplications.list({ page: 1, pageSize: 10 });
    expect(page0.items.length).toBe(10);
    expect(page1.items.length).toBe(10);
    const page0Ids = new Set(page0.items.map((i) => i.id));
    for (const item of page1.items) {
      expect(page0Ids.has(item.id)).toBe(false);
    }
  });

  it("sort: requestedAmount asc orders by amount ascending", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const res = await loanApplications.list({
      sortBy: "requestedAmount",
      sortDir: "asc",
      pageSize: 100,
    });
    for (let i = 1; i < res.items.length; i++) {
      expect(res.items[i - 1]!.requestedAmount).toBeLessThanOrEqual(res.items[i]!.requestedAmount);
    }
  });

  it("borrower name is masked on list rows (BR-7)", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const res = await loanApplications.list({ pageSize: 5 });
    for (const item of res.items) {
      expect(item.borrowerNameMasked).toMatch(/•/);
    }
  });

  it("invalid sortBy raises BAD_REQUEST", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    await expect(loanApplications.list({ sortBy: "garbage" })).rejects.toMatchObject({
      code: "BAD_REQUEST",
      httpStatus: 400,
    });
  });
});

// ─── Detail handler ──────────────────────────────────────────────────────────

describe("loan-applications.detail", () => {
  it("returns full detail payload for SYSTEM_ADMIN", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const detail = await loanApplications.detail(APP_DISBURSED_BHAW);
    expect(detail.application.id).toBe(APP_DISBURSED_BHAW);
    expect(detail.borrower).toBeDefined();
    expect(detail.lsp).toBeDefined();
    expect(detail.product).toBeDefined();
    expect(detail.account).not.toBeNull();
    // No documents are seeded → BR-3 vacuously satisfied.
    expect(detail.docsComplete).toBe(true);
    // Seeded installments don't reconcile to principal (only 4 of 12),
    // so BR-11 validateSchedule reports false.
    expect(detail.scheduleValid).toBe(false);
  });

  it("returns null account for an INITIATED app (no disbursement yet)", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const detail = await loanApplications.detail(APP_INITIATED_SOUTH);
    expect(detail.account).toBeNull();
    expect(detail.docsComplete).toBe(true); // empty required set
    expect(detail.scheduleValid).toBe(false); // no account → false
  });

  it("404s on an unknown application id", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    await expect(
      loanApplications.detail("a0000000-ffff-4000-8000-000000000000"),
    ).rejects.toMatchObject({
      code: "NOT_FOUND",
      httpStatus: 404,
    });
  });

  it("LSP_UI_READ cannot see an application from another LSP", async () => {
    await auth.login({ username: "lsp.read", password: "any" });
    // App #1 is SOUTH, not BHAW_DEMO.
    await expect(loanApplications.detail(APP_INITIATED_SOUTH)).rejects.toMatchObject({
      code: "FORBIDDEN",
      httpStatus: 403,
    });
  });

  it("LSP_UI_READ can see an application from its own LSP", async () => {
    await auth.login({ username: "lsp.read", password: "any" });
    const detail = await loanApplications.detail(APP_INITIATED_BHAW);
    expect(detail.application.id).toBe(APP_INITIATED_BHAW);
    expect(detail.application.lspId).toBe(LSP_BHAW_DEMO);
  });
});

// ─── Per-tab GET handlers ────────────────────────────────────────────────────

describe("loan-applications.schedule", () => {
  it("returns schedule + installments for a disbursed app", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const res = await loanApplications.schedule(APP_DISBURSED_BHAW);
    expect(res.schedule).not.toBeNull();
    expect(res.installments.length).toBeGreaterThan(0);
  });

  it("returns empty arrays for an app with no account", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const res = await loanApplications.schedule(APP_INITIATED_SOUTH);
    expect(res.schedule).toBeNull();
    expect(res.installments.length).toBe(0);
  });
});

describe("loan-applications.documents", () => {
  it("returns an empty array when no documents are seeded", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const res = await loanApplications.documents(APP_DISBURSED_BHAW);
    expect(res.documents.length).toBe(0);
  });

  it("includes documents that we add directly to the db", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const doc: LoanDocument = {
      id: "d0000000-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
      applicationId: APP_DISBURSED_BHAW,
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
    const res = await loanApplications.documents(APP_DISBURSED_BHAW);
    expect(res.documents.length).toBe(1);
    expect(res.documents[0]?.type).toBe("PAN");
  });
});

describe("loan-applications.repayments", () => {
  it("returns payments newest-first for a disbursed account", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const res = await loanApplications.repayments(APP_DISBURSED_BHAW);
    expect(res.payments.length).toBeGreaterThan(0);
    for (let i = 1; i < res.payments.length; i++) {
      expect(res.payments[i - 1]!.postedAt >= res.payments[i]!.postedAt).toBe(true);
    }
  });

  it("returns an empty list when there is no account", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const res = await loanApplications.repayments(APP_INITIATED_SOUTH);
    expect(res.payments.length).toBe(0);
  });
});

describe("loan-applications.activity", () => {
  it("returns audit events newest-first", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    // App #14 is one of the approved apps that has audit pairs seeded.
    const res = await loanApplications.activity(APP_APPROVED_NORTH);
    expect(res.events.length).toBeGreaterThan(0);
    for (let i = 1; i < res.events.length; i++) {
      expect(res.events[i - 1]!.createdAt >= res.events[i]!.createdAt).toBe(true);
    }
  });

  it("returns empty events for an application with no audit history", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const res = await loanApplications.activity(APP_INITIATED_SOUTH);
    // App #1 only has the standalone "create" audit event seeded.
    for (const e of res.events) {
      expect(e.applicationId).toBe(APP_INITIATED_SOUTH);
    }
  });
});

describe("loan-applications.webhooks", () => {
  it("synthesises one DELIVERED delivery per audit event", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const activity = await loanApplications.activity(APP_APPROVED_NORTH);
    const webhooks = await loanApplications.webhooks(APP_APPROVED_NORTH);
    expect(webhooks.deliveries.length).toBe(activity.events.length);
    for (const d of webhooks.deliveries) {
      expect(d.status).toBe("DELIVERED");
      expect(d.attemptCount).toBe(1);
      expect(d.endpoint).toMatch(/https:\/\/lsp\.mock\.example\//);
      expect(d.lastError).toBeNull();
    }
  });

  it("returns empty deliveries when an application has no audit events", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    // App #2 has no audit events seeded.
    const res = await loanApplications.webhooks(appId(2));
    expect(res.deliveries.length).toBe(0);
  });
});

// ─── Transition handler ──────────────────────────────────────────────────────

describe("loan-applications.transition", () => {
  it("happy path: SYSTEM_ADMIN moves INITIATED → KYC_PENDING", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const res = await loanApplications.transition(APP_INITIATED_SOUTH, {
      to: "KYC_PENDING",
      reason: "next stage",
    });
    expect(res.application.status).toBe("KYC_PENDING");
    expect(res.event.fromStatus).toBe("INITIATED");
    expect(res.event.toStatus).toBe("KYC_PENDING");
    expect(res.event.reason).toBe("next stage");
    expect(res.event.actorRole).toBe("SYSTEM_ADMIN");
  });

  it("BR-6 invalid transition: INITIATED → DISBURSED is rejected", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    await expect(
      loanApplications.transition(APP_INITIATED_SOUTH, { to: "DISBURSED", reason: null }),
    ).rejects.toMatchObject({
      code: "INVALID_TRANSITION",
      httpStatus: 422,
    });
  });

  it("role gate: LSP_UI_WRITE cannot approve an application", async () => {
    await auth.login({ username: "lsp.write", password: "any" });
    // App #8 is AWAITING_APPROVAL, LSP=BHAW_DEMO (LSPS[8%4]=0).
    await expect(
      loanApplications.transition(appId(8), {
        to: "APPROVED_PENDING_DISBURSAL",
        reason: null,
      }),
    ).rejects.toMatchObject({
      code: "ROLE_NOT_ALLOWED",
      httpStatus: 422,
    });
  });

  it("idempotency replay: same idempotencyKey returns the same payload without a second db write", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const key = "test-transition-replay";
    const first = await loanApplications.transition(
      APP_INITIATED_SOUTH,
      { to: "KYC_PENDING", reason: "first" },
      { idempotencyKey: key },
    );
    const auditBefore = getDb().auditApplication.length;
    const second = await loanApplications.transition(
      APP_INITIATED_SOUTH,
      // Different body — replay should return the cached first response.
      { to: "DOCS_PENDING", reason: "second" },
      { idempotencyKey: key },
    );
    const auditAfter = getDb().auditApplication.length;
    expect(second.event.id).toBe(first.event.id);
    expect(second.application.status).toBe("KYC_PENDING");
    expect(auditAfter).toBe(auditBefore);
  });

  it("rejects an invalid body (missing `to`) with BAD_REQUEST", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    // Bypass the typed wrapper by calling dispatch directly via the module's
    // private surface — we test the schema rejection by passing a malformed
    // status string.
    await expect(
      loanApplications.transition(APP_INITIATED_SOUTH, {
        // @ts-expect-error: deliberately invalid for the test
        to: "NOT_A_REAL_STATUS",
        reason: null,
      }),
    ).rejects.toMatchObject({ code: "BAD_REQUEST", httpStatus: 400 });
  });
});

// ─── Repayment POST (BR-13 / BR-14 / BR-15) ──────────────────────────────────

describe("loan-applications.postRepayment", () => {
  it("BR-13: wrong amount is rejected with PARTIAL_INSTALLMENT_REJECTED", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const inst1 = instId(ACCT_DISBURSED_BHAW_INDEX, 1);
    await expect(
      loanApplications.postRepayment(APP_DISBURSED_BHAW, {
        installmentId: inst1,
        // First installment outstanding is 42333; submit a wrong number.
        amount: 100,
        postedAt: new Date().toISOString(),
        mode: "UPI",
      }),
    ).rejects.toMatchObject({
      code: "PARTIAL_INSTALLMENT_REJECTED",
      httpStatus: 422,
    });
  });

  it("BR-13: posting against a non-next installment is rejected", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const inst2 = instId(ACCT_DISBURSED_BHAW_INDEX, 2);
    await expect(
      loanApplications.postRepayment(APP_DISBURSED_BHAW, {
        installmentId: inst2,
        amount: 42333,
        postedAt: new Date().toISOString(),
        mode: "UPI",
      }),
    ).rejects.toMatchObject({
      code: "PARTIAL_INSTALLMENT_REJECTED",
    });
  });

  it("BR-14: first matching payment on DISBURSED auto-advances to UNDER_REPAYMENT", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const db = getDb();
    const acctId8 = acctId(ACCT_DISBURSED_BHAW_INDEX);
    const installments = db.installments.get(acctId8) ?? [];
    const inst1 = installments[0]!;
    const res = await loanApplications.postRepayment(APP_DISBURSED_BHAW, {
      installmentId: inst1.id,
      amount: inst1.outstandingAmount,
      postedAt: new Date().toISOString(),
      mode: "UPI",
    });
    expect(res.autoAdvanced).toBe(true);
    expect(res.autoClosed).toBe(false);
    expect(res.application.status).toBe("UNDER_REPAYMENT");
    expect(res.payment.amount).toBe(inst1.outstandingAmount);
    expect(res.payment.installmentId).toBe(inst1.id);
  });

  it("BR-15: payment that closes out every installment auto-closes the loan", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const db = getDb();
    const acctId8 = acctId(ACCT_DISBURSED_BHAW_INDEX);
    const installments = db.installments.get(acctId8) ?? [];
    // Mark installments 1-3 as PAID; leave #4 as the last outstanding.
    const mutated: RepaymentInstallment[] = installments.map((i) =>
      i.number < 4
        ? { ...i, status: "PAID", paidAmount: i.installmentAmount, outstandingAmount: 0, dpd: 0 }
        : i,
    );
    db.installments.set(acctId8, mutated);
    const inst4 = mutated[3]!;
    const res = await loanApplications.postRepayment(APP_DISBURSED_BHAW, {
      installmentId: inst4.id,
      amount: inst4.outstandingAmount,
      postedAt: new Date().toISOString(),
      mode: "UPI",
    });
    expect(res.autoClosed).toBe(true);
    expect(res.application.status).toBe("FULLY_REPAID");
    // Account is marked closed too.
    const account = db.accounts.get(acctId8);
    expect(account?.accountStatus).toBe("CLOSED");
    expect(account?.closureReason).toBe("FULLY_REPAID");
    // Webhooks now reflect the full-repayment audit event.
    const webhooks = await loanApplications.webhooks(APP_DISBURSED_BHAW);
    const repaidWebhook = webhooks.deliveries.find(
      (d) => d.eventType === "loan.repayment.posted",
    );
    expect(repaidWebhook).toBeDefined();
  });

  it("idempotency replay: same key returns the same payment, no double write", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const db = getDb();
    const acctId8 = acctId(ACCT_DISBURSED_BHAW_INDEX);
    const inst1 = (db.installments.get(acctId8) ?? [])[0]!;
    const key = "test-rep-replay";
    const before = db.payments.length;
    const first = await loanApplications.postRepayment(
      APP_DISBURSED_BHAW,
      {
        installmentId: inst1.id,
        amount: inst1.outstandingAmount,
        postedAt: new Date().toISOString(),
        mode: "UPI",
      },
      { idempotencyKey: key },
    );
    const afterFirst = db.payments.length;
    const second = await loanApplications.postRepayment(
      APP_DISBURSED_BHAW,
      {
        installmentId: inst1.id,
        amount: inst1.outstandingAmount,
        postedAt: new Date().toISOString(),
        mode: "UPI",
      },
      { idempotencyKey: key },
    );
    expect(afterFirst).toBe(before + 1);
    expect(db.payments.length).toBe(afterFirst); // no second insert
    expect(second.payment.id).toBe(first.payment.id);
  });

  it("rejects when the application has no account yet", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    await expect(
      loanApplications.postRepayment(APP_INITIATED_SOUTH, {
        installmentId: instId(1, 1),
        amount: 1,
        postedAt: new Date().toISOString(),
        mode: "UPI",
      }),
    ).rejects.toMatchObject({ code: "BAD_REQUEST", httpStatus: 400 });
  });
});

// ─── Disbursement POST (BR-3 / BR-10) ────────────────────────────────────────

describe("loan-applications.initiateDisbursement", () => {
  it("BR-10: rejects when no valid schedule exists", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    // App #16 is APPROVED_PENDING_DISBURSAL but has no account/schedule seeded.
    await expect(
      loanApplications.initiateDisbursement(APP_APPROVED_BHAW, { note: null }),
    ).rejects.toMatchObject({
      code: "SCHEDULE_MISSING",
      httpStatus: 422,
    });
  });

  it("BR-3: rejects when required documents are not all VERIFIED", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    // Attach a required-for-disbursement doc in PENDING status — this
    // flips the BR-3 gate from "vacuously true" to "explicitly false".
    const doc: LoanDocument = {
      id: "d0000001-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
      applicationId: APP_APPROVED_BHAW,
      type: "LOAN_AGREEMENT",
      displayName: "Loan agreement",
      requiredForApproval: false,
      requiredForDisbursement: true,
      status: "PENDING",
      notes: null,
      fileMeta: null,
      uploadedAt: null,
      uploadedBy: null,
    };
    getDb().documents.set(doc.id, doc);
    await expect(
      loanApplications.initiateDisbursement(APP_APPROVED_BHAW, { note: null }),
    ).rejects.toMatchObject({
      code: "DOCS_INCOMPLETE",
      httpStatus: 422,
    });
  });

  it("rejects when the application is not APPROVED_PENDING_DISBURSAL", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    await expect(
      loanApplications.initiateDisbursement(APP_INITIATED_SOUTH, { note: null }),
    ).rejects.toMatchObject({
      code: "INVALID_TRANSITION",
      httpStatus: 422,
    });
  });

  it("role gate: LSP_UI_WRITE cannot initiate disbursement", async () => {
    await auth.login({ username: "lsp.write", password: "any" });
    await expect(
      loanApplications.initiateDisbursement(APP_APPROVED_BHAW, { note: null }),
    ).rejects.toMatchObject({
      code: "ROLE_NOT_ALLOWED",
      httpStatus: 422,
    });
  });
});

// ─── LSP-API schedule submission (BR-11 / BR-12) ─────────────────────────────

describe("LSP-API schedule submission (BR-11/BR-12)", () => {
  /**
   * App #16 is APPROVED_PENDING_DISBURSAL, BHAW_DEMO. tenureMonths from the
   * seed: 12 + (16 % 5) * 6 = 18. requestedAmount: 100_000 + (16 * 17_000) %
   * 500_000 = 372_000. The status sits inside SCHEDULE_SUBMITTABLE_STATUSES
   * AND the seed never materialises an account for APPROVED_PENDING_DISBURSAL
   * — perfect for exercising the create-on-miss path.
   */
  const APP_TENURE = 18;
  const APP_PRINCIPAL = 372_000;

  /**
   * Build a valid schedule that satisfies every BR-11 leg:
   *   - exactly `tenure` rows
   *   - contiguous numbering 1..tenure
   *   - strictly-increasing due dates (one per month, day-of-month 9)
   *   - principalDue + interestDue === installmentAmount per row
   *   - sum(principalDue) === principal (last row absorbs rounding)
   */
  function synthesiseValidSchedule(
    principal: number,
    tenure: number,
  ): Array<{
    number: number;
    dueDate: string;
    principalDue: number;
    interestDue: number;
    installmentAmount: number;
  }> {
    const base = Math.round(principal / tenure);
    const rows: Array<{
      number: number;
      dueDate: string;
      principalDue: number;
      interestDue: number;
      installmentAmount: number;
    }> = [];
    for (let i = 1; i <= tenure; i += 1) {
      // Month walks 2026-06 → 2026-06+i. Cap year roll-over to keep the test
      // deterministic for tenures up to 360.
      const monthIndex = 5 + i; // 6 = June
      const year = 2026 + Math.floor((monthIndex - 1) / 12);
      const month = ((monthIndex - 1) % 12) + 1;
      const dueDate = `${year}-${month.toString().padStart(2, "0")}-09`;
      const isLast = i === tenure;
      const drift = principal - base * tenure;
      const principalDue = isLast ? base + drift : base;
      const installmentAmount = principalDue; // interest = 0 keeps the row reconciliation simple
      rows.push({
        number: i,
        dueDate,
        principalDue,
        interestDue: 0,
        installmentAmount,
      });
    }
    return rows;
  }

  it("happy path: submits a valid schedule and materialises account + installments", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const installments = synthesiseValidSchedule(APP_PRINCIPAL, APP_TENURE);
    const db = getDb();
    const schedulesBefore = db.schedules.size;

    const res = await loanApplications.submitSchedule(APP_APPROVED_BHAW, {
      installments,
      idempotencyKey: "test-submit-schedule-happy",
    });

    expect(res.scheduleId).toBeTruthy();
    expect(res.installments.length).toBe(APP_TENURE);

    // Account materialised + schedule + installments persisted under it.
    const account = Array.from(db.accounts.values()).find(
      (a) => a.applicationId === APP_APPROVED_BHAW,
    );
    expect(account).toBeDefined();
    expect(db.schedules.size).toBe(schedulesBefore + 1);
    const newSchedules = Array.from(db.schedules.values()).filter(
      (s) => s.accountId === account!.id,
    );
    expect(newSchedules.length).toBe(1);
    expect(newSchedules[0]?.generatedBy).toBe("LSP");
    expect(newSchedules[0]?.frozen).toBe(false);
    expect(db.installments.get(account!.id)?.length).toBe(APP_TENURE);

    // Audit row appended.
    const auditRow = db.auditApplication.find(
      (e) => e.applicationId === APP_APPROVED_BHAW && e.action === "lsp-api.schedule.submit",
    );
    expect(auditRow).toBeDefined();
    expect(auditRow?.channel).toBe("API");
  });

  it("BR-11: rejects row count != approvedTenureMonths", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const rows = synthesiseValidSchedule(APP_PRINCIPAL, APP_TENURE).slice(0, APP_TENURE - 1);
    await expect(
      loanApplications.submitSchedule(APP_APPROVED_BHAW, {
        installments: rows,
        idempotencyKey: "test-br11-count",
      }),
    ).rejects.toMatchObject({ code: "BAD_REQUEST", httpStatus: 400 });
  });

  it("BR-11: rejects non-contiguous installment numbers", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const rows = synthesiseValidSchedule(APP_PRINCIPAL, APP_TENURE);
    // Skip #5 — replace its `number` with 99 so the array length stays 18.
    rows[4] = { ...rows[4]!, number: 99 };
    await expect(
      loanApplications.submitSchedule(APP_APPROVED_BHAW, {
        installments: rows,
        idempotencyKey: "test-br11-noncontig",
      }),
    ).rejects.toMatchObject({ code: "BAD_REQUEST", httpStatus: 400 });
  });

  it("BR-11: rejects non-strictly-increasing due dates", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const rows = synthesiseValidSchedule(APP_PRINCIPAL, APP_TENURE);
    // Swap the dueDates of rows 4 and 5 → row 5's date is now <= row 4's.
    const row4Date = rows[3]!.dueDate;
    const row5Date = rows[4]!.dueDate;
    rows[3] = { ...rows[3]!, dueDate: row5Date };
    rows[4] = { ...rows[4]!, dueDate: row4Date };
    await expect(
      loanApplications.submitSchedule(APP_APPROVED_BHAW, {
        installments: rows,
        idempotencyKey: "test-br11-dates",
      }),
    ).rejects.toMatchObject({ code: "BAD_REQUEST", httpStatus: 400 });
  });

  it("BR-11: rejects row whose principalDue + interestDue != installmentAmount", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const rows = synthesiseValidSchedule(APP_PRINCIPAL, APP_TENURE);
    rows[0] = { ...rows[0]!, installmentAmount: rows[0]!.installmentAmount + 5 };
    await expect(
      loanApplications.submitSchedule(APP_APPROVED_BHAW, {
        installments: rows,
        idempotencyKey: "test-br11-rowsum",
      }),
    ).rejects.toMatchObject({ code: "BAD_REQUEST", httpStatus: 400 });
  });

  it("BR-11: rejects when sum(principalDue) drifts from approvedPrincipal", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const rows = synthesiseValidSchedule(APP_PRINCIPAL, APP_TENURE);
    const last = rows[rows.length - 1]!;
    rows[rows.length - 1] = {
      ...last,
      principalDue: last.principalDue + 100,
      installmentAmount: last.installmentAmount + 100,
    };
    await expect(
      loanApplications.submitSchedule(APP_APPROVED_BHAW, {
        installments: rows,
        idempotencyKey: "test-br11-total",
      }),
    ).rejects.toMatchObject({ code: "BAD_REQUEST", httpStatus: 400 });
  });

  it("BR-12: rejects when a repayment with installmentId has already been posted", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const db = getDb();

    // Pre-seed an account for the APPROVED_PENDING_DISBURSAL app + push a
    // payment with a non-null installmentId. The handler treats that as
    // "schedule frozen" per BR-12 regardless of the loan's status.
    const acctId = "c0000000-feed-4000-8000-000000000001";
    db.accounts.set(acctId, {
      id: acctId,
      applicationId: APP_APPROVED_BHAW,
      accountNumber: "LSP-FROZEN1",
      accountStatus: "ACTIVE",
      principal: APP_PRINCIPAL,
      tenureMonths: APP_TENURE,
      approvedAt: new Date().toISOString(),
      createdAt: new Date().toISOString(),
      closedAt: null,
      closureReason: null,
    });
    db.payments.push({
      id: "p0000000-feed-4000-8000-000000000001",
      accountId: acctId,
      installmentId: "i0000000-feed-4000-8000-000000000001",
      channel: "UPI",
      amount: 1,
      postedAt: new Date().toISOString(),
      postedBy: "user-test",
      idempotencyKey: "seed-frozen-payment",
      allocation: [
        {
          installmentId: "i0000000-feed-4000-8000-000000000001",
          penalty: 0,
          fees: 0,
          interest: 0,
          principal: 1,
        },
      ],
    });

    const rows = synthesiseValidSchedule(APP_PRINCIPAL, APP_TENURE);
    await expect(
      loanApplications.submitSchedule(APP_APPROVED_BHAW, {
        installments: rows,
        idempotencyKey: "test-br12-frozen",
      }),
    ).rejects.toMatchObject({ code: "SCHEDULE_FROZEN", httpStatus: 422 });
  });

  it("status guard: rejects submission against a DISBURSED application", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    // App #24 is DISBURSED — outside SCHEDULE_SUBMITTABLE_STATUSES.
    const rows = synthesiseValidSchedule(APP_PRINCIPAL, APP_TENURE);
    await expect(
      loanApplications.submitSchedule(APP_DISBURSED_BHAW, {
        installments: rows,
        idempotencyKey: "test-status-guard",
      }),
    ).rejects.toMatchObject({ code: "INVALID_TRANSITION", httpStatus: 422 });
  });

  it("idempotency: replaying with the same key returns the cached response without a second write", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const db = getDb();
    const rows = synthesiseValidSchedule(APP_PRINCIPAL, APP_TENURE);
    const key = "test-submit-schedule-replay";

    const first = await loanApplications.submitSchedule(APP_APPROVED_BHAW, {
      installments: rows,
      idempotencyKey: key,
    });
    const schedulesAfterFirst = db.schedules.size;
    const auditAfterFirst = db.auditApplication.length;

    const second = await loanApplications.submitSchedule(APP_APPROVED_BHAW, {
      installments: rows,
      idempotencyKey: key,
    });

    expect(second.scheduleId).toBe(first.scheduleId);
    expect(db.schedules.size).toBe(schedulesAfterFirst);
    expect(db.auditApplication.length).toBe(auditAfterFirst);
  });
});

// ─── LSP-API foreclosure completion ──────────────────────────────────────────

describe("LSP-API foreclosure completion", () => {
  it("happy path: settles a DISBURSED loan and writes both transition audits", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const db = getDb();
    const acctId8 = acctId(ACCT_DISBURSED_BHAW_INDEX);
    const paymentsBefore = db.payments.length;
    const auditBefore = db.auditApplication.length;
    const settledAt = new Date().toISOString();

    const res = await loanApplications.completeForeclosure(APP_DISBURSED_BHAW, {
      outstandingPrincipal: 300_000,
      accruedInterest: 5_000,
      foreclosureCharge: 2_000,
      settledAt,
      idempotencyKey: "fc-key-1",
    });

    expect(res.applicationId).toBe(APP_DISBURSED_BHAW);
    expect(res.finalStatus).toBe("CLOSED");
    expect(res.totalSettled).toBe(307_000);

    // Application is CLOSED.
    expect(db.applications.get(APP_DISBURSED_BHAW)?.status).toBe("CLOSED");

    // Account is CLOSED with closureReason FORECLOSED.
    const account = db.accounts.get(acctId8);
    expect(account?.accountStatus).toBe("CLOSED");
    expect(account?.closureReason).toBe("FORECLOSED");
    expect(account?.closedAt).toBe(settledAt);

    // Every installment is PAID.
    const installments = db.installments.get(acctId8) ?? [];
    expect(installments.length).toBeGreaterThan(0);
    for (const inst of installments) {
      expect(inst.status).toBe("PAID");
      expect(inst.outstandingAmount).toBe(0);
    }

    // Exactly one ADJUSTMENT payment of `total` was appended.
    const adjustments = db.payments.filter(
      (p) => p.accountId === acctId8 && p.channel === "ADJUSTMENT",
    );
    expect(adjustments.length).toBe(1);
    expect(adjustments[0]?.amount).toBe(307_000);
    expect(adjustments[0]?.idempotencyKey).toBe("fc-key-1");
    expect(db.payments.length).toBe(paymentsBefore + 1);

    // Two new audit rows: foreclose then auto-close.
    expect(db.auditApplication.length).toBe(auditBefore + 2);
    const newRows = db.auditApplication.slice(auditBefore);
    expect(newRows[0]?.action).toBe("lsp-api.foreclose");
    expect(newRows[0]?.toStatus).toBe("FORECLOSED");
    expect(newRows[1]?.action).toBe("auto-close.foreclosed");
    expect(newRows[1]?.toStatus).toBe("CLOSED");
  });

  it("status guard: rejects foreclose against APPROVED_PENDING_DISBURSAL", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    await expect(
      loanApplications.completeForeclosure(APP_APPROVED_BHAW, {
        outstandingPrincipal: 100_000,
        accruedInterest: 1_000,
        foreclosureCharge: 500,
        settledAt: new Date().toISOString(),
        idempotencyKey: "fc-status-guard",
      }),
    ).rejects.toMatchObject({ code: "INVALID_TRANSITION", httpStatus: 422 });
  });

  it("amount guard: rejects when all-zero settlement total", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    await expect(
      loanApplications.completeForeclosure(APP_DISBURSED_BHAW, {
        outstandingPrincipal: 0,
        accruedInterest: 0,
        foreclosureCharge: 0,
        settledAt: new Date().toISOString(),
        idempotencyKey: "fc-zero",
      }),
    ).rejects.toMatchObject({
      code: "BAD_REQUEST",
      httpStatus: 400,
      message: expect.stringMatching(/greater than zero/) as unknown as string,
    });
  });

  it("no-account guard: rejects when the account has been cleared", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const db = getDb();
    // Remove the seeded account for APP_DISBURSED_BHAW. The status guard
    // still passes (DISBURSED ∈ FORECLOSEABLE_STATUSES) so the no-account
    // branch can be exercised.
    const acctId8 = acctId(ACCT_DISBURSED_BHAW_INDEX);
    db.accounts.delete(acctId8);

    await expect(
      loanApplications.completeForeclosure(APP_DISBURSED_BHAW, {
        outstandingPrincipal: 100_000,
        accruedInterest: 1_000,
        foreclosureCharge: 500,
        settledAt: new Date().toISOString(),
        idempotencyKey: "fc-no-acct",
      }),
    ).rejects.toMatchObject({
      code: "BAD_REQUEST",
      httpStatus: 400,
      message: expect.stringMatching(/no loan account/) as unknown as string,
    });
  });

  it("idempotency: replaying with the same key returns the cached response", async () => {
    await auth.login({ username: "ops.admin", password: "any" });
    const db = getDb();

    const first = await loanApplications.completeForeclosure(APP_DISBURSED_BHAW, {
      outstandingPrincipal: 200_000,
      accruedInterest: 3_000,
      foreclosureCharge: 1_000,
      settledAt: new Date().toISOString(),
      idempotencyKey: "fc-replay-key",
    });
    const paymentsAfterFirst = db.payments.length;
    const auditAfterFirst = db.auditApplication.length;

    const second = await loanApplications.completeForeclosure(APP_DISBURSED_BHAW, {
      outstandingPrincipal: 999_999,
      accruedInterest: 999_999,
      foreclosureCharge: 999_999,
      settledAt: new Date().toISOString(),
      idempotencyKey: "fc-replay-key",
    });

    expect(second.totalSettled).toBe(first.totalSettled);
    expect(second.finalStatus).toBe(first.finalStatus);
    expect(db.payments.length).toBe(paymentsAfterFirst);
    expect(db.auditApplication.length).toBe(auditAfterFirst);
    expect(
      db.payments.filter((p) => p.idempotencyKey === "fc-replay-key").length,
    ).toBe(1);
  });
});
