/**
 * Shared contract for the Phase 5 loan-applications surface.
 *
 * `mocks/api/loan-applications.ts` returns these shapes. The list page,
 * the detail page, and each per-tab component consume them. Keeping the
 * contract in one file lets the four Phase-5 agents ship in parallel
 * without coordination drift.
 *
 * Where a payload is just a re-export of a Zod-inferred domain type
 * (LoanApplication, LoanDocument, etc.), we re-export from here so
 * consumers have a single import surface.
 */
import { z } from "zod";
import type {
  ApplicationAuditEvent,
  LoanAccount,
  LoanApplication,
  LoanDocument,
  LoanProduct,
  Lsp,
  PaymentTransaction,
  RepaymentInstallment,
  RepaymentSchedule,
} from "@/types";
// LoanStatus needs to be a value import (Zod schema) — re-import from the
// source rather than from `@/types`, which re-exports it as type only.
import { LoanStatus } from "@/schemas/loan-application";
import type { Borrower } from "@/schemas/borrower";

// ─── List surface ────────────────────────────────────────────────────────────

/**
 * URL-bound filters for `/loan-applications`. Use with `useUrlFilters` so
 * deep-links round-trip correctly. Every key is optional; the table renders
 * the full set when no filter is selected.
 */
export const LoanApplicationListFilters = z.object({
  /** Free-text search across application id, external loan id, borrower name. */
  q: z.string().trim().min(1).max(120).optional(),
  /** Multi-select of LoanStatus values, encoded as `?status=X,Y,Z`. */
  status: z.array(LoanStatus).optional(),
  /** Filter to one LSP. */
  lspId: z.string().uuid().optional(),
  /** Filter to one product. */
  productId: z.string().uuid().optional(),
  /** Page index, zero-based. Defaults to 0. */
  page: z.coerce.number().int().min(0).optional(),
  /** Page size. Defaults to 25. */
  pageSize: z.coerce.number().int().min(5).max(100).optional(),
  /** Sort key — table column id. */
  sortBy: z
    .enum(["createdAt", "updatedAt", "requestedAmount", "status"])
    .optional(),
  /** Sort direction. */
  sortDir: z.enum(["asc", "desc"]).optional(),
});
export type LoanApplicationListFilters = z.infer<typeof LoanApplicationListFilters>;

/**
 * Triage-queue row. Joins LoanApplication with the labels every list
 * column needs so the table doesn't run N look-ups per row.
 */
export interface LoanApplicationListItem {
  id: string;
  externalLoanId: string | null;
  borrowerId: string;
  /** Masked for the list surface (BR-7) — reveal lives on detail. */
  borrowerNameMasked: string;
  lspId: string;
  lspName: string;
  productId: string;
  productName: string;
  requestedAmount: number;
  tenureMonths: number;
  status: LoanApplication["status"];
  createdAt: string;
  updatedAt: string;
}

export interface LoanApplicationListResponse {
  items: readonly LoanApplicationListItem[];
  total: number;
  page: number;
  pageSize: number;
}

// ─── Detail surface ──────────────────────────────────────────────────────────

/**
 * Tab identifiers for `/loan-applications/:id?tab=...`. The detail page
 * is the single source of truth for which tab is active; per-tab content
 * components are pure renderers.
 */
export const LoanApplicationDetailTab = z.enum([
  "overview",
  "schedule",
  "documents",
  "repayments",
  "activity",
  "webhooks",
]);
export type LoanApplicationDetailTab = z.infer<typeof LoanApplicationDetailTab>;

/**
 * The full detail payload. Returned by `GET /loan-applications/:id`.
 * Per-tab data lives in their own endpoints — keeping the detail call
 * cheap so the page can render the header + active tab in one paint.
 */
export interface LoanApplicationDetail {
  application: LoanApplication;
  borrower: Borrower;
  lsp: Lsp;
  product: LoanProduct;
  /** Present once disbursed; null otherwise. */
  account: LoanAccount | null;
  /** BR-3 gate input — pre-computed for the ActionBar to use without joining. */
  docsComplete: boolean;
  /** BR-10 gate input — true when a valid repayment schedule exists. */
  scheduleValid: boolean;
}

export interface LoanApplicationScheduleResponse {
  schedule: RepaymentSchedule | null;
  installments: readonly RepaymentInstallment[];
}

export interface LoanApplicationDocumentsResponse {
  documents: readonly LoanDocument[];
}

export interface LoanApplicationRepaymentsResponse {
  payments: readonly PaymentTransaction[];
}

export interface LoanApplicationActivityResponse {
  events: readonly ApplicationAuditEvent[];
}

/**
 * Webhook delivery row surfaced on the Webhooks tab.
 * No Zod schema exists for `WebhookDelivery` yet (BR-15 outbox), so this
 * is a minimal projection. If the production system lands a richer
 * schema, swap this for the inferred type and adjust the handler.
 */
export interface LoanApplicationWebhookDelivery {
  id: string;
  eventType: string;
  /** Target endpoint URL. */
  endpoint: string;
  status: "PENDING" | "DELIVERED" | "FAILED" | "DEAD_LETTERED";
  attemptCount: number;
  lastAttemptAt: string | null;
  lastError: string | null;
  createdAt: string;
}

export interface LoanApplicationWebhooksResponse {
  deliveries: readonly LoanApplicationWebhookDelivery[];
}

// ─── Mutation contracts ──────────────────────────────────────────────────────

export interface TransitionStatusInput {
  to: LoanApplication["status"];
  reason: string | null;
  idempotencyKey: string;
}

export interface PostRepaymentInput {
  installmentId: string;
  amount: number;
  postedAt: string;
  mode: string;
  /** Bank UTR / cheque / UPI reference for reconciliation (optional). */
  reference?: string | null;
  idempotencyKey: string;
}

export interface InitiateDisbursementInput {
  note: string | null;
  idempotencyKey: string;
}

// ─── Phase 7: LSP-API contracts ──────────────────────────────────────────────
//
// The LSP integrates with the LMS server-to-server: there is no manual UI
// for schedule submission or foreclosure settlement. These contracts model
// what an external LSP API client would POST. The mock router exposes the
// same handlers so DevTopBarTools (and `mocks/lifecycle-automation.ts`) can
// drive them in-app for demos.

/**
 * BR-11 LSP-provided schedule submission. Shape mirrors `LspProvidedSchedule`
 * from `src/schemas/lsp-provided-schedule.ts`, plus an idempotency key.
 *
 * The server-side handler must:
 *   - run BR-11 validation (count, contiguous numbering, increasing due
 *     dates, per-row reconciliation, total-principal reconciliation)
 *   - enforce BR-12: reject when any repayment payment is already posted
 *     against this loan account
 *   - on success: replace the persisted schedule + installments
 */
export interface SubmitScheduleInstallmentInput {
  number: number;
  dueDate: string;
  principalDue: number;
  interestDue: number;
  installmentAmount: number;
}

export interface SubmitScheduleInput {
  installments: readonly SubmitScheduleInstallmentInput[];
  idempotencyKey: string;
}

export interface SubmitScheduleResponse {
  scheduleId: string;
  installments: readonly SubmitScheduleInstallmentInput[];
}

/**
 * LSP-API foreclosure completion. The LSP supplies the settlement amounts
 * directly — no quote review on the LMS side. The handler tags the loan
 * as FORECLOSED → CLOSED, writes the audit row, and stores the amounts
 * on the loan account for reporting.
 */
export interface CompleteForeclosureInput {
  outstandingPrincipal: number;
  accruedInterest: number;
  foreclosureCharge: number;
  settledAt: string;
  idempotencyKey: string;
}

export interface CompleteForeclosureResponse {
  applicationId: string;
  /** "FORECLOSED" or "CLOSED" depending on whether downstream auto-close ran. */
  finalStatus: string;
  totalSettled: number;
}
