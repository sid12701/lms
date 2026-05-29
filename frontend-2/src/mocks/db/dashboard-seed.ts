/**
 * Dashboard fixture seed.
 *
 * Additive on top of {@link seedAuthFixtures}. Adds enough borrowers,
 * applications, accounts, payments, installments, alerts, and application
 * audit events so the Phase 4 home KPIs render with realistic numbers
 * without hand-coded decoration.
 *
 * Idempotent — early-returns when applications are already present.
 *
 * All timestamps are derived from a fixed reference `NOW =
 * 2026-05-09T00:00:00Z` so tests can assert specific values without
 * snapshotting.
 *
 * IDs follow a deterministic shape so individual rows can be addressed by
 * counter in tests:
 *   - Borrowers:   `b0000000-XXXX-4000-8000-000000000000`
 *   - Applications:`a0000000-XXXX-4000-8000-000000000000`
 *   - Accounts:    `c0000000-XXXX-4000-8000-000000000000`
 *   - Schedules:   `d0000000-XXXX-4000-8000-000000000000`
 *   - Installments:`e0000000-XXXX-4000-8000-000000000000`
 *   - Payments:    `f0000000-XXXX-4000-8000-000000000000`
 *   - Alerts:      `01000000-XXXX-4000-8000-000000000000`
 *   - Audit:       `02000000-XXXX-4000-8000-000000000000`
 */
import type {
  AlertSeverity,
  ApplicationAuditEvent,
  Borrower,
  InstallmentStatus,
  LoanAccount,
  LoanApplication,
  LoanStatus,
  OperationalAlert,
  PaymentTransaction,
  RepaymentInstallment,
  RepaymentSchedule,
} from "@/types";
import type { MockDb } from "./state";
import {
  LSP_BHAW_DEMO,
  LSP_EAST,
  LSP_NORTH,
  LSP_SOUTH,
  PROD_BUSINESS,
  PROD_CONSUMER,
  PROD_GOLD,
  PROD_PERSONAL,
  USER_LSP_WRITE,
  USER_OPS_ADMIN,
  USER_OPS_USER,
} from "./seed";

const NOW = new Date("2026-05-09T00:00:00.000Z");
const MS_PER_DAY = 86_400_000;
const MS_PER_HOUR = 3_600_000;

// ─── ID helpers ──────────────────────────────────────────────────────────────

function pad4(n: number): string {
  return n.toString(16).padStart(4, "0");
}
function borrowerId(i: number): string {
  return `b0000000-${pad4(i)}-4000-8000-000000000000`;
}
function applicationId(i: number): string {
  return `a0000000-${pad4(i)}-4000-8000-000000000000`;
}
function accountId(i: number): string {
  return `c0000000-${pad4(i)}-4000-8000-000000000000`;
}
function scheduleId(i: number): string {
  return `d0000000-${pad4(i)}-4000-8000-000000000000`;
}
function installmentId(acct: number, num: number): string {
  // Compose two counters into the same UUID-shaped slot.
  return `e0000000-${pad4(acct)}-4000-8${pad4(num).slice(1)}-000000000000`;
}
function paymentId(i: number): string {
  return `f0000000-${pad4(i)}-4000-8000-000000000000`;
}
function alertId(i: number): string {
  return `01000000-${pad4(i)}-4000-8000-000000000000`;
}
function auditId(i: number): string {
  return `02000000-${pad4(i)}-4000-8000-000000000000`;
}

// ─── Time helpers ────────────────────────────────────────────────────────────

function isoFrom(date: Date): string {
  return date.toISOString();
}
function daysAgo(days: number, baseHourOffset = 0): string {
  return isoFrom(new Date(NOW.getTime() - days * MS_PER_DAY + baseHourOffset * MS_PER_HOUR));
}
function dateStringDaysFromNow(deltaDays: number): string {
  const d = new Date(NOW.getTime() + deltaDays * MS_PER_DAY);
  return d.toISOString().slice(0, 10);
}

// ─── Lookup data ─────────────────────────────────────────────────────────────

const LSPS = [LSP_BHAW_DEMO, LSP_SOUTH, LSP_NORTH, LSP_EAST] as const;
const PRODUCTS = [PROD_PERSONAL, PROD_BUSINESS, PROD_CONSUMER, PROD_GOLD] as const;

const BORROWER_NAMES = [
  "Aanya Sharma",
  "Bharath Iyer",
  "Chitra Devi",
  "Dharmesh Patel",
  "Esha Rao",
  "Farooq Khan",
  "Gita Menon",
  "Harsh Joshi",
  "Indira Banerjee",
  "Jagdish Kumar",
  "Kavya Nair",
  "Lakshmi Pillai",
  "Manish Gupta",
  "Nandita Bose",
  "Omkar Desai",
  "Pooja Reddy",
  "Qadir Ahmed",
  "Rahul Singh",
  "Sneha Kapoor",
  "Tarun Mehta",
  "Uma Krishnan",
  "Vivek Chandra",
  "Wasim Sheikh",
  "Xander Pereira",
  "Yamini Saxena",
  "Zara Mathew",
  "Arjun Verma",
  "Bina Choudhury",
  "Chetan Bhatt",
  "Deepika Shetty",
] as const;

// Status mix per assignment brief (sum ~50).
const STATUS_PLAN: ReadonlyArray<{ status: LoanStatus; count: number }> = [
  { status: "INITIATED", count: 4 },
  { status: "KYC_PENDING", count: 3 },
  { status: "AWAITING_APPROVAL", count: 6 },
  { status: "APPROVED_PENDING_DISBURSAL", count: 4 },
  { status: "DISBURSEMENT_IN_PROGRESS", count: 3 },
  { status: "DISBURSED", count: 5 },
  { status: "UNDER_REPAYMENT", count: 10 },
  { status: "DELINQUENT", count: 6 },
  { status: "FORECLOSURE_REQUESTED", count: 2 },
  { status: "CLOSED", count: 5 },
  { status: "REJECTED", count: 3 },
  { status: "CANCELLED", count: 2 },
];

const STATUSES_NEEDING_ACCOUNT = new Set<LoanStatus>([
  "DISBURSED",
  "UNDER_REPAYMENT",
  "DELINQUENT",
  "FORECLOSURE_REQUESTED",
  "CLOSED",
]);

// Deterministic minimal-valid borrower PII (Pan/Aadhaar/etc. must pass Zod).
function makeBorrower(i: number, name: string, lspId: string): Borrower {
  const idx = pad4(i).toUpperCase().padStart(4, "0");
  const num4 = (i % 10000).toString().padStart(4, "0");
  return {
    id: borrowerId(i),
    fullName: name,
    pan: `ABCDE${num4}F`,
    aadhaar: `1234567890${num4.slice(-2)}`.padStart(12, "0").slice(0, 12),
    mobile: `9${(800000000 + i).toString().padStart(9, "0")}`.slice(0, 10),
    email: `borrower${i}@example.com`,
    dob: "1990-01-01",
    gender: "M",
    maritalStatus: "SINGLE",
    fathersName: `Father of ${name}`,
    spouseName: null,
    address: {
      residential: `${i} Mock Street`,
      city: "Mumbai",
      state: "Maharashtra",
      zip: "400001",
    },
    employment: {
      type: "SALARIED",
      organization: "Acme Corp",
      employeeId: `EMP-${idx}`,
      location: "Mumbai",
      monthlyIncome: 75_000,
      annualIncome: 900_000,
    },
    banking: {
      bank: "HDFC Bank",
      accountHolder: name,
      accountNumber: `1234567890${idx}`,
      ifsc: "HDFC0000001",
    },
    references: [],
    kycComplete: true,
    visibleLspIds: [lspId],
  };
}

interface PlannedApplication {
  index: number;
  status: LoanStatus;
  lspId: string;
  productId: string;
  borrowerIndex: number;
  createdAt: string;
  amount: number;
}

// ─── Main seed ───────────────────────────────────────────────────────────────

export function seedDashboardFixtures(db: MockDb): MockDb {
  // Idempotency: applications are the marker — auth seed never touches them.
  if (db.applications.size > 0) return db;

  // ── Borrowers ─────────────────────────────────────────────────────────────
  for (let i = 0; i < BORROWER_NAMES.length; i++) {
    const lspId = LSPS[i % LSPS.length] ?? LSP_BHAW_DEMO;
    const name = BORROWER_NAMES[i] ?? `Borrower ${i}`;
    const borrower = makeBorrower(i + 1, name, lspId);
    db.borrowers.set(borrower.id, borrower);
  }

  // ── Plan applications across statuses ─────────────────────────────────────
  const planned: PlannedApplication[] = [];
  let appCounter = 1;
  let statusCounter = 0;
  for (const { status, count } of STATUS_PLAN) {
    for (let k = 0; k < count; k++) {
      const lspId = LSPS[appCounter % LSPS.length] ?? LSP_BHAW_DEMO;
      const productId = PRODUCTS[appCounter % PRODUCTS.length] ?? PROD_PERSONAL;
      const borrowerIndex = ((appCounter - 1) % BORROWER_NAMES.length) + 1;
      // Spread createdAt: older apps for terminal statuses, newer for early.
      const ageDays = statusCounter * 2 + k;
      const createdAt = daysAgo(ageDays, k);
      const amount = 100_000 + ((appCounter * 17_000) % 500_000);
      planned.push({
        index: appCounter,
        status,
        lspId,
        productId,
        borrowerIndex,
        createdAt,
        amount,
      });
      appCounter++;
    }
    statusCounter++;
  }

  // ── Write applications ────────────────────────────────────────────────────
  for (const p of planned) {
    const borrower = db.borrowers.get(borrowerId(p.borrowerIndex));
    if (!borrower) continue;
    const app: LoanApplication = {
      id: applicationId(p.index),
      externalLoanId: p.index % 3 === 0 ? `EXT-${p.index.toString().padStart(5, "0")}` : null,
      borrowerId: borrower.id,
      lspId: p.lspId,
      productId: p.productId,
      requestedAmount: p.amount,
      tenureMonths: 12 + (p.index % 5) * 6,
      status: p.status,
      sourceChannel: p.index % 2 === 0 ? "UI" : "API",
      createdAt: p.createdAt,
      updatedAt: p.createdAt,
      invalidatedAt: null,
      invalidReason: null,
    };
    db.applications.set(app.id, app);
  }

  // ── Accounts + Schedules + Installments + Payments ────────────────────────
  let acctCounter = 0;
  let installmentEntries = 0;
  let paymentCounter = 0;
  let mtdPaymentsAdded = 0;

  for (const p of planned) {
    if (!STATUSES_NEEDING_ACCOUNT.has(p.status)) continue;
    acctCounter++;

    const principal = p.amount;
    const tenure = 12;
    const installmentAmount = Math.round(principal / tenure);
    const approvedAt = p.createdAt;

    const isClosed = p.status === "CLOSED";
    const isDelinquent = p.status === "DELINQUENT";
    const isForeclosed = p.status === "FORECLOSURE_REQUESTED";

    const account: LoanAccount = {
      id: accountId(acctCounter),
      applicationId: applicationId(p.index),
      accountNumber: `BHAW${acctCounter.toString().padStart(8, "0")}`,
      accountStatus: isClosed ? "CLOSED" : isForeclosed ? "ACTIVE" : "ACTIVE",
      principal,
      tenureMonths: tenure,
      approvedAt,
      createdAt: approvedAt,
      closedAt: isClosed ? daysAgo(2, 0) : null,
      closureReason: isClosed ? "FULLY_REPAID" : null,
    };
    db.accounts.set(account.id, account);

    const schedule: RepaymentSchedule = {
      id: scheduleId(acctCounter),
      accountId: account.id,
      generatedBy: "PLATFORM",
      frozen: true,
      createdAt: approvedAt,
    };
    db.schedules.set(schedule.id, schedule);

    // ── Installments — 4 per account on average (cap at ~40 total)
    const installments: RepaymentInstallment[] = [];
    const installmentsPerAcct = 4;
    for (let n = 1; n <= installmentsPerAcct; n++) {
      installmentEntries++;
      // Past-due cohort for DELINQUENT + a sprinkle for UNDER_REPAYMENT.
      const dueOffsetDays = (n - 2) * 30; // -30, 0, +30, +60
      const dueDate = dateStringDaysFromNow(dueOffsetDays);
      let status: InstallmentStatus;
      let paidAmount = 0;
      let outstandingAmount = installmentAmount;
      let dpd = 0;
      if (isClosed) {
        status = "PAID";
        paidAmount = installmentAmount;
        outstandingAmount = 0;
      } else if (isDelinquent && dueOffsetDays <= 0) {
        status = "OVERDUE";
        dpd = Math.max(1, -dueOffsetDays);
      } else if (p.status === "UNDER_REPAYMENT" && dueOffsetDays < 0) {
        status = "PAID";
        paidAmount = installmentAmount;
        outstandingAmount = 0;
      } else if (dueOffsetDays < -3 && p.status !== "DISBURSED") {
        status = "OVERDUE";
        dpd = Math.max(1, -dueOffsetDays);
      } else {
        status = "DUE";
      }

      installments.push({
        id: installmentId(acctCounter, n),
        accountId: account.id,
        scheduleId: schedule.id,
        number: n,
        dueDate,
        principalDue: Math.round(installmentAmount * 0.7),
        interestDue: Math.round(installmentAmount * 0.3),
        installmentAmount,
        paidAmount,
        outstandingAmount,
        dpd,
        delinquencyBucket:
          dpd === 0
            ? "B0"
            : dpd <= 30
              ? "B1_30"
              : dpd <= 60
                ? "B31_60"
                : dpd <= 90
                  ? "B61_90"
                  : "B90_PLUS",
        status,
      });
    }
    db.installments.set(account.id, installments);

    // ── Disbursement payment (counts toward MTD when in current month) ─────
    paymentCounter++;
    // Disbursed within this month (May 2026) for ~half of accounts so MTD > 0.
    const disbursedDaysAgo = acctCounter % 2 === 0 ? 4 : 35;
    if (disbursedDaysAgo < 9) mtdPaymentsAdded++;
    const disbursementPayment: PaymentTransaction = {
      id: paymentId(paymentCounter),
      accountId: account.id,
      installmentId: null,
      channel: "BANK_TRANSFER",
      amount: principal,
      postedAt: daysAgo(disbursedDaysAgo),
      postedBy: USER_OPS_ADMIN,
      idempotencyKey: `disb-${acctCounter}`,
      allocation: [],
    };
    db.payments.push(disbursementPayment);

    // ── Repayment payments (1-3 per account for non-INITIATED accounts) ───
    const paymentsToPost =
      p.status === "UNDER_REPAYMENT" ? 2 : p.status === "DELINQUENT" ? 1 : isClosed ? 3 : 1;
    for (let r = 0; r < paymentsToPost; r++) {
      paymentCounter++;
      const ageDays = 60 - r * 25;
      db.payments.push({
        id: paymentId(paymentCounter),
        accountId: account.id,
        installmentId: installments[r]?.id ?? null,
        channel: "UPI",
        amount: installmentAmount,
        postedAt: daysAgo(ageDays),
        postedBy: USER_LSP_WRITE,
        idempotencyKey: `pay-${acctCounter}-${r}`,
        allocation: [],
      });
    }
  }

  // Sanity: enforce >= 1 MTD payment even if math drifted.
  void mtdPaymentsAdded; // counted only for documentation
  void installmentEntries;

  // ── Alerts ────────────────────────────────────────────────────────────────
  const severities: AlertSeverity[] = ["CRITICAL", "HIGH", "MEDIUM", "LOW"];
  for (let i = 1; i <= 12; i++) {
    const isOpen = i <= 8;
    const severity = severities[(i - 1) % severities.length] ?? "MEDIUM";
    const alert: OperationalAlert = {
      id: alertId(i),
      type: "DISBURSEMENT_BACKLOG",
      severity,
      status: isOpen ? "OPEN" : "ACKNOWLEDGED",
      title: `Alert ${i}: ${severity.toLowerCase()} severity event`,
      message: `Mock alert ${i} message for dashboard fixture.`,
      subjectType: i % 2 === 0 ? "LOAN_APPLICATION" : "LOAN_ACCOUNT",
      subjectId: applicationId(i),
      correlationId: `c0000000-${pad4(i)}-4ccc-8ccc-cccccccccccc`,
      contextJson: {},
      createdAt: daysAgo(i, 0),
      acknowledgedAt: isOpen ? null : daysAgo(i - 1, 1),
      acknowledgedBy: isOpen ? null : USER_OPS_ADMIN,
      acknowledgmentNote: isOpen ? null : "ack",
    };
    db.alerts.set(alert.id, alert);
  }

  // ── Application audit events ──────────────────────────────────────────────
  // Pair AWAITING_APPROVAL -> APPROVED_PENDING_DISBURSAL transitions for the
  // last 30 days so avgApprovalTatHours is computable.
  let auditCounter = 0;
  const approvedApps = planned.filter(
    (p) =>
      p.status === "APPROVED_PENDING_DISBURSAL" ||
      p.status === "DISBURSEMENT_IN_PROGRESS" ||
      p.status === "DISBURSED" ||
      p.status === "UNDER_REPAYMENT",
  );
  // Cap to ~7 pairs (14 events) + 1 standalone = ~15 events.
  const pairCount = Math.min(7, approvedApps.length);
  for (let i = 0; i < pairCount; i++) {
    const app = approvedApps[i];
    if (!app) continue;
    const submittedDaysAgo = 5 + i * 2; // 5,7,9,...
    const tatHours = 4 + i * 2; // 4,6,8,...
    auditCounter++;
    const awaiting: ApplicationAuditEvent = {
      id: auditId(auditCounter),
      applicationId: applicationId(app.index),
      fromStatus: "UNDER_REVIEW",
      toStatus: "AWAITING_APPROVAL",
      action: "submit-for-approval",
      actorId: USER_OPS_USER,
      actorRole: "OPS_USER",
      channel: "UI",
      correlationId: `c0000000-${pad4(auditCounter)}-4ddd-8ddd-dddddddddddd`,
      reason: null,
      createdAt: isoFrom(new Date(NOW.getTime() - submittedDaysAgo * MS_PER_DAY)),
    };
    db.auditApplication.push(awaiting);

    auditCounter++;
    const approved: ApplicationAuditEvent = {
      id: auditId(auditCounter),
      applicationId: applicationId(app.index),
      fromStatus: "AWAITING_APPROVAL",
      toStatus: "APPROVED_PENDING_DISBURSAL",
      action: "approve",
      actorId: USER_OPS_ADMIN,
      actorRole: "SYSTEM_ADMIN",
      channel: "UI",
      correlationId: `c0000000-${pad4(auditCounter)}-4ddd-8ddd-dddddddddddd`,
      reason: null,
      createdAt: isoFrom(
        new Date(NOW.getTime() - submittedDaysAgo * MS_PER_DAY + tatHours * MS_PER_HOUR),
      ),
    };
    db.auditApplication.push(approved);
  }
  // One standalone event to reach ~15.
  auditCounter++;
  db.auditApplication.push({
    id: auditId(auditCounter),
    applicationId: applicationId(1),
    fromStatus: null,
    toStatus: "INITIATED",
    action: "create",
    actorId: USER_OPS_USER,
    actorRole: "OPS_USER",
    channel: "UI",
    correlationId: `c0000000-${pad4(auditCounter)}-4ddd-8ddd-dddddddddddd`,
    reason: null,
    createdAt: daysAgo(2),
  });

  return db;
}
