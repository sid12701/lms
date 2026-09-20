/**
 * Shared contract for the Phase 5 loan-applications surface.
 *
 * The list page, detail page, and each per-tab component consume these shapes.
 * Keeping the contract in one file lets parallel work proceed without drift.
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
  PaymentTransaction,
  RepaymentInstallment,
  RepaymentSchedule,
} from "@/types";
// LoanStatus needs to be a value import (Zod schema) — re-import from the
// source rather than from `@/types`, which re-exports it as type only.
import { LoanStatus } from "@/schemas/loan-application";
import type { LoanStatusOrUnknown } from "@/lib/loan-application-status";
import type { LoanAccountStatus } from "@/schemas/loan-account";
import type { EmploymentType, Gender, MaritalStatus } from "@/schemas/borrower";
import type { LspStatus } from "@/schemas/lsp";
import type { ProductStatus } from "@/schemas/product";

// ─── List surface ────────────────────────────────────────────────────────────

/**
 * URL-bound filters for `/loan-applications`. Use with `useUrlFilters` so
 * deep-links round-trip correctly. Every key is optional; the table renders
 * the full set when no filter is selected.
 */
export const LoanApplicationListFilters = z.object({
  /** Free-text borrower/general search. Loan IDs have dedicated filters. */
  q: z.string().trim().min(1).max(120).optional(),
  /** LSP-provided loan identifier, backed by the backend externalLoanId field. */
  lspLoanId: z.string().trim().min(1).max(120).optional(),
  /** Bhawana LMS loan account number, e.g. LMS-LN-... */
  bhawLoanId: z.string().trim().min(1).max(120).optional(),
  /** Inclusive disbursal date lower bound. */
  disbursalDateFrom: z.string().trim().min(1).max(10).optional(),
  /** Inclusive disbursal date upper bound. */
  disbursalDateTo: z.string().trim().min(1).max(10).optional(),
  /**
   * Status filter. Modelled as an array because `useUrlFilters` serializes
   * arrays as repeated params (`?status=X&status=Y`) — note it is *not*
   * comma-separated. H29 — every selected status is sent to the ops list
   * endpoint, which filters with OR semantics and returns the matching total.
   */
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
  sortBy: z.enum(["createdAt", "updatedAt", "requestedAmount", "status"]).optional(),
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
  accountNumber: string | null;
  borrowerId: string;
  /** Masked for the list surface (BR-7) — reveal lives on detail. */
  borrowerNameMasked: string;
  lspId: string;
  lspName: string;
  productId: string;
  productName: string;
  requestedAmount: number;
  tenureMonths: number;
  status: LoanStatusOrUnknown;
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
 * H28 — thin borrower projection embedded in the ops detail payload.
 *
 * The backend detail response carries only identity/contact leftovers (name,
 * PAN, mobile, …); it does NOT supply gender, marital status, KYC state, a
 * full address, banking, references or employment detail. Every field the
 * backend does not supply is nullable — an absent value must render as
 * unknown, never as a plausible-looking default (M / SINGLE / SALARIED /
 * "KYC pending" are all fabricated business facts). Use the borrowers admin
 * surface (`BorrowerDetail`) when the screen genuinely needs master data.
 */
export interface LoanApplicationDetailBorrower {
  id: string;
  fullName: string;
  pan: string | null;
  /** The detail endpoint never sends Aadhaar — always null here. */
  aadhaar: string | null;
  mobile: string | null;
  email: string | null;
  dob: string | null;
  gender: Gender | null;
  maritalStatus: MaritalStatus | null;
  city: string | null;
  state: string | null;
  employmentType: EmploymentType | null;
  /** H28 — null means "not supplied", never ₹0 of income. */
  monthlyIncome: number | null;
  annualIncome: number | null;
  /** The detail endpoint never sends KYC state — always null here. */
  kycComplete: boolean | null;
  visibleLspIds: readonly string[];
}

/**
 * H28 — thin LSP/product references embedded in the ops detail payload. The
 * backend sends id/code/name only; operational status is nullable because the
 * endpoint never supplies it (previously invented as ACTIVE).
 */
export interface LoanApplicationDetailLsp {
  id: string;
  code: string | null;
  name: string;
  status: LspStatus | null;
}

export interface LoanApplicationDetailProduct {
  id: string;
  code: string | null;
  name: string;
  status: ProductStatus | null;
}

/**
 * H28 — the detail's application projection. `status` preserves unknown wire
 * values as `UNKNOWN:<raw>` (never INITIALIZED); `requestedAmount` /
 * `tenureMonths` are null when the backend omits them (never zero).
 */
export interface LoanApplicationDetailApplication extends Omit<
  LoanApplication,
  "status" | "requestedAmount" | "tenureMonths"
> {
  status: LoanStatusOrUnknown;
  requestedAmount: number | null;
  tenureMonths: number | null;
}

/**
 * H28 — the detail's account projection. `accountStatus` preserves unknown
 * wire values as `UNKNOWN:<raw>` (never PENDING_DISBURSEMENT) and stays a
 * separate field from the application lifecycle `status` above; `principal`
 * is null when the backend omits it (never zero).
 */
export interface LoanApplicationDetailAccount extends Omit<
  LoanAccount,
  "accountStatus" | "principal" | "tenureMonths"
> {
  accountStatus: LoanAccountStatus | `UNKNOWN:${string}`;
  principal: number | null;
  tenureMonths: number | null;
}

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
]);
export type LoanApplicationDetailTab = z.infer<typeof LoanApplicationDetailTab>;

/**
 * The full detail payload. Returned by `GET /loan-applications/:id`.
 * Per-tab data lives in their own endpoints — keeping the detail call
 * cheap so the page can render the header + active tab in one paint.
 */
export interface LoanApplicationDetail {
  application: LoanApplicationDetailApplication;
  borrower: LoanApplicationDetailBorrower;
  lsp: LoanApplicationDetailLsp;
  product: LoanApplicationDetailProduct;
  /** Present once disbursed; null otherwise. */
  account: LoanApplicationDetailAccount | null;
  /** BR-3 gate input — pre-computed for the ActionBar to use without joining. */
  docsComplete: boolean;
  /** BR-10 gate input — true when a valid repayment schedule exists. */
  scheduleValid: boolean;
  /** Gap #11 — loan-account delinquency aggregates for status badge tone. */
  accountDelinquency: {
    maxDaysPastDue: number | null;
    overdueInstallmentCount: number | null;
  } | null;
  /** Annual interest rate (percent) from the locked product version, when supplied. */
  interestRate: number | null;
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
  /** Server-reported total; `payments.length < totalCount` means rows are still unread. */
  totalCount?: number;
  /** True when the page walk stopped at the safety bound with receipts still unread. */
  truncated?: boolean;
}

export interface LoanApplicationActivityResponse {
  events: readonly ApplicationAuditEvent[];
}

// ─── Mutation contracts ──────────────────────────────────────────────────────

export interface TransitionStatusInput {
  to: LoanApplication["status"];
  reason: string | null;
  /** Structured reason code — required by the backend for REJECTED / DISBURSEMENT_RETRY. */
  reasonCode?: string | null;
  idempotencyKey: string;
}

/**
 * Deliberate admin override. Separate from the ordinary transition:
 * the caller mints a NEW idempotency key (never the failed standard key),
 * and both reason + reasonCode are required explanations recorded verbatim.
 * Targets are limited to the backend `ALLOWED_MANUAL_OVERRIDE_TARGETS`
 * (INITIALIZED, AWAITING_APPROVAL, DISBURSEMENT_RETRY, REJECTED) — the
 * server remains authoritative for source/financial/in-flight guards.
 */
export interface ManualStatusOverrideInput {
  to: LoanApplication["status"];
  reason: string | null;
  reasonCode: string | null;
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

export interface LoanForeclosureQuote {
  id: string;
  loanAccountId: string;
  version: number;
  requestedByUsername: string | null;
  executedByUsername: string | null;
  effectiveDate: string;
  outstandingPrincipal: number;
  outstandingInterest: number;
  settlementAmount: number;
  status: string;
  executedAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface RequestForeclosureQuoteInput {
  effectiveDate: string;
}

export interface ExecuteForeclosureQuoteInput {
  quoteId: string;
  settlementDate: string;
  reference: string;
  note: string | null;
  idempotencyKey: string;
}
