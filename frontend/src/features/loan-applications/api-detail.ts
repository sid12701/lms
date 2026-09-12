/**
 * Loan-application detail API client.
 *
 * Internal roles (SYSTEM_ADMIN / OPS_USER) call the live backend under
 * `/api/v1/internal/ops/loan-applications/{id}` and translate the flat
 * backend `LoanApplicationDetailResponse` into the nested `LoanApplicationDetail`
 * projection the UI consumes.
 *
 * Lifecycle mutations (`postTransition`, `postDisbursement`) call the same
 * internal ops endpoints with BR-5 idempotency keys.
 */
import type {
  OpsLoanApplicationDetailResponse,
  OpsLoanApplicationDocumentChecklistResponse,
} from "@/lib/api/generated/ops-loan-applications";
import { ApiError, requestJson } from "@/lib/api/http-client";
import { loadStoredSession } from "@/lib/api/session-storage";
import { newIdempotencyKey } from "@/lib/idempotency";
import { LoanAccountStatus } from "@/schemas/loan-account";
import type {
  InitiateDisbursementInput,
  ExecuteForeclosureQuoteInput,
  LoanApplicationActivityResponse,
  LoanApplicationDetail,
  LoanForeclosureQuote,
  ManualStatusOverrideInput,
  RequestForeclosureQuoteInput,
  TransitionStatusInput,
} from "./types";
import type { ApplicationAuditEvent, LoanApplication } from "@/types";
import { isSatisfiedBackendChecklistStatus } from "@/schemas/document";
import { parseLoanApplicationStatus } from "@/lib/loan-application-status";

const BACKEND_BASE = "/api/v1/internal/ops/loan-applications";

function isSystemAdmin(): boolean {
  return loadStoredSession()?.user.role === "SYSTEM_ADMIN";
}

/**
 * The authoritative audit timeline lives in the `audit-events` query
 * (invalidated alongside detail on every mutation). Mutation responses carry
 * NO event association — associating a timeline row here would misattribute
 * concurrent history, and a timeline GET must never convert an already
 * committed POST into an apparent failure.
 */
export interface TransitionResponse {
  application: LoanApplication;
  /** Present only when the server explicitly correlates an event. */
  event?: ApplicationAuditEvent | null;
}

export interface DisbursementResponse {
  application: LoanApplication;
  /** Authoritative timeline comes from the invalidated activity query. */
  events?: readonly ApplicationAuditEvent[];
}

interface BackendLoanForeclosureQuoteResponse {
  id?: string;
  loanAccountId?: string;
  version?: number;
  requestedByUsername?: string;
  executedByUsername?: string;
  effectiveDate?: string;
  outstandingPrincipal?: number;
  outstandingInterest?: number;
  settlementAmount?: number;
  status?: string;
  executedAt?: string;
  createdAt?: string;
  updatedAt?: string;
}

function toAmount(value: number | null | undefined): number {
  return value ?? 0;
}

function nowIso(): string {
  return new Date().toISOString();
}

function toForeclosureQuote(row: BackendLoanForeclosureQuoteResponse): LoanForeclosureQuote {
  const createdAt = row.createdAt ?? nowIso();
  return {
    id: row.id ?? "",
    loanAccountId: row.loanAccountId ?? "",
    version: row.version ?? 0,
    requestedByUsername: row.requestedByUsername ?? null,
    executedByUsername: row.executedByUsername ?? null,
    effectiveDate: row.effectiveDate ?? createdAt.slice(0, 10),
    outstandingPrincipal: toAmount(row.outstandingPrincipal),
    outstandingInterest: toAmount(row.outstandingInterest),
    settlementAmount: toAmount(row.settlementAmount),
    status: row.status ?? "UNKNOWN",
    executedAt: row.executedAt ?? null,
    createdAt,
    updatedAt: row.updatedAt ?? createdAt,
  };
}

function safeChannel(value: string | null | undefined): "UI" | "API" | "WEBHOOK" {
  const upper = (value ?? "").toUpperCase();
  // Backend sends ONBOARDING_API for LSP-originated applications; anything
  // API-flavoured must not fall through to "UI" (audit F7 — every application
  // showed the wrong source channel).
  if (upper === "API" || upper.endsWith("_API") || upper.startsWith("API_")) return "API";
  if (upper === "WEBHOOK") return "WEBHOOK";
  return "UI";
}

function toApplication(
  payload: OpsLoanApplicationDetailResponse,
  createdAt: string,
): LoanApplication {
  return {
    id: payload.id ?? "",
    externalLoanId: payload.externalLoanId ?? null,
    borrowerId: payload.borrowerId ?? "",
    lspId: payload.lspId ?? "",
    productId: payload.productId ?? "",
    requestedAmount: toAmount(payload.requestedAmount),
    tenureMonths: payload.tenureMonths ?? 0,
    status: parseLoanApplicationStatus(payload.status ?? "") ?? "INITIALIZED",
    sourceChannel: safeChannel(payload.sourceChannel),
    createdAt,
    updatedAt: payload.updatedAt ?? createdAt,
    invalidatedAt: payload.invalidatedAt ?? null,
    invalidReason: payload.invalidReasonText ?? payload.invalidReasonCode ?? null,
  };
}

function toBorrower(payload: OpsLoanApplicationDetailResponse): LoanApplicationDetail["borrower"] {
  // Borrower: the backend embeds a thin projection inline. Full Borrower
  // master data (Aadhaar, banking, references, address parts) requires
  // a separate `/internal/admin/borrowers/{id}` call (wired in #7) — for
  // the detail surface we project what's in the payload and leave the
  // rest empty so the OverviewTab and DetailHeader can render.
  return {
    id: payload.borrowerId ?? "",
    fullName: payload.borrowerFullName ?? "",
    pan: payload.borrowerPan ?? "",
    aadhaar: "",
    mobile: payload.borrowerMobile ?? "",
    email: payload.borrowerEmail,
    dob: payload.borrowerDateOfBirth ?? "",
    gender: "M" as const,
    maritalStatus: "SINGLE" as const,
    fathersName: "",
    spouseName: null,
    address: {
      residential: "",
      city: payload.borrowerCity ?? "",
      state: payload.borrowerState ?? "",
      zip: "",
    },
    employment: {
      type: "SALARIED" as const,
      organization: null,
      employeeId: null,
      location: null,
      monthlyIncome: toAmount(payload.borrowerMonthlyIncome),
      annualIncome: toAmount(payload.borrowerMonthlyIncome) * 12,
    },
    banking: {
      bank: "",
      accountHolder: "",
      accountNumber: "",
      ifsc: "",
    },
    references: [],
    kycComplete: false,
    visibleLspIds: [payload.lspId ?? ""],
  } as LoanApplicationDetail["borrower"];
}

function toLsp(payload: OpsLoanApplicationDetailResponse): LoanApplicationDetail["lsp"] {
  return {
    id: payload.lspId ?? "",
    code: payload.lspCode ?? "",
    name: payload.lspName ?? "",
    status: "ACTIVE" as const,
  } as LoanApplicationDetail["lsp"];
}

function toProduct(payload: OpsLoanApplicationDetailResponse): LoanApplicationDetail["product"] {
  return {
    id: payload.productId ?? "",
    code: payload.productCode ?? "",
    name: payload.productName ?? "",
    status: "ACTIVE" as const,
  } as LoanApplicationDetail["product"];
}

function toAccount(
  payload: OpsLoanApplicationDetailResponse,
  application: LoanApplication,
): LoanApplicationDetail["account"] {
  const account = payload.loanAccount;
  if (!account) return null;
  return {
    id: account.id ?? "",
    applicationId: application.id,
    accountNumber: account.accountNumber ?? "",
    accountStatus: LoanAccountStatus.parse(account.status ?? "PENDING_DISBURSEMENT"),
    principal: toAmount(account.principalAmount),
    tenureMonths: account.tenureMonths ?? application.tenureMonths,
    approvedAt: account.approvedAt ?? application.createdAt,
    createdAt: account.createdAt ?? application.createdAt,
    closedAt: account.closedAt,
    closureReason: (account.closureReason ?? null) as
      | "FULLY_REPAID"
      | "FORECLOSED"
      | "CANCELLED"
      | null,
  } as LoanApplicationDetail["account"];
}

function areRequiredDocumentsComplete(
  checklist: readonly OpsLoanApplicationDocumentChecklistResponse[],
): boolean {
  const requiredChecklistRows = checklist.filter((row) => row.required);
  return (
    requiredChecklistRows.length === 0 ||
    requiredChecklistRows.every((row) => isSatisfiedBackendChecklistStatus(row.status ?? ""))
  );
}

function hasValidRepaymentSchedule(payload: OpsLoanApplicationDetailResponse): boolean {
  const schedule = payload.loanAccount?.repaymentSchedule;
  return schedule != null && (schedule.installmentCount ?? 0) > 0;
}

function toAccountDelinquency(
  payload: OpsLoanApplicationDetailResponse,
): LoanApplicationDetail["accountDelinquency"] {
  const delinquency = payload.loanAccount?.delinquency;
  if (!delinquency) return null;
  return {
    maxDaysPastDue: delinquency.maxDaysPastDue ?? null,
    overdueInstallmentCount: delinquency.overdueInstallmentCount ?? null,
  };
}

/**
 * Synthesise the rich `LoanApplicationDetail` from the flat backend
 * payload + document checklist while keeping each projection independently
 * testable and aligned with one domain object.
 */
function backendToDetail(
  payload: OpsLoanApplicationDetailResponse,
  checklist: readonly OpsLoanApplicationDocumentChecklistResponse[],
): LoanApplicationDetail {
  const application = toApplication(payload, payload.createdAt ?? nowIso());

  return {
    application,
    borrower: toBorrower(payload),
    lsp: toLsp(payload),
    product: toProduct(payload),
    account: toAccount(payload, application),
    docsComplete: areRequiredDocumentsComplete(checklist),
    scheduleValid: hasValidRepaymentSchedule(payload),
    accountDelinquency: toAccountDelinquency(payload),
    interestRate: payload.interestRate == null ? null : toAmount(payload.interestRate),
  };
}

async function fetchChecklist(
  id: string,
): Promise<readonly OpsLoanApplicationDocumentChecklistResponse[]> {
  return requestJson<readonly OpsLoanApplicationDocumentChecklistResponse[]>(
    `${BACKEND_BASE}/${encodeURIComponent(id)}/kyc-documents`,
  );
}

// ─── Public surface ──────────────────────────────────────────────────────────

/** Fetch the full detail payload for one loan application. */
export async function fetchLoanApplicationDetail(id: string): Promise<LoanApplicationDetail> {
  const [payload, checklist] = await Promise.all([
    requestJson<OpsLoanApplicationDetailResponse>(`${BACKEND_BASE}/${encodeURIComponent(id)}`),
    fetchChecklist(id),
  ]);
  return backendToDetail(payload, checklist);
}

interface BackendAuditEvent {
  id: string;
  loanApplicationId: string;
  action: string;
  actorUsername: string | null;
  fromStatus: string | null;
  toStatus: string | null;
  note: string | null;
  reasonCode: string | null;
  correlationId: string | null;
  createdAt: string;
}

const APPLICATION_ROLE_FALLBACK = "OPS_USER";
const APPLICATION_CHANNEL_FALLBACK = "UI";

function toAuditEvent(row: BackendAuditEvent): ApplicationAuditEvent {
  return {
    id: row.id,
    applicationId: row.loanApplicationId,
    fromStatus: row.fromStatus
      ? (parseLoanApplicationStatus(row.fromStatus) as ApplicationAuditEvent["fromStatus"])
      : null,
    toStatus:
      parseLoanApplicationStatus(row.toStatus) ??
      ("INITIALIZED" as ApplicationAuditEvent["toStatus"]),
    action: row.action || "transition",
    actorId: row.actorUsername ?? "system",
    actorRole: APPLICATION_ROLE_FALLBACK as ApplicationAuditEvent["actorRole"],
    channel: APPLICATION_CHANNEL_FALLBACK as ApplicationAuditEvent["channel"],
    correlationId: row.correlationId ?? row.id,
    reason: row.note ?? row.reasonCode,
    contextJson: row.reasonCode ? { reasonCode: row.reasonCode } : undefined,
    createdAt: row.createdAt,
  };
}

/** Fetch the per-application audit timeline. */
export async function fetchLoanApplicationActivity(
  id: string,
): Promise<LoanApplicationActivityResponse> {
  const rows = await requestJson<BackendAuditEvent[]>(
    `${BACKEND_BASE}/${encodeURIComponent(id)}/audit-events`,
  );
  return { events: rows.map(toAuditEvent) };
}

export async function fetchForeclosureQuotes(id: string): Promise<readonly LoanForeclosureQuote[]> {
  const rows = await requestJson<BackendLoanForeclosureQuoteResponse[]>(
    `${BACKEND_BASE}/${encodeURIComponent(id)}/foreclosure-quotes`,
    {},
    { dedupe: false },
  );
  return rows.map(toForeclosureQuote);
}

export async function requestForeclosureQuote(
  id: string,
  input: RequestForeclosureQuoteInput,
): Promise<LoanForeclosureQuote> {
  const row = await requestJson<BackendLoanForeclosureQuoteResponse>(
    `${BACKEND_BASE}/${encodeURIComponent(id)}/foreclosure-quotes`,
    { method: "POST", body: JSON.stringify({ effectiveDate: input.effectiveDate }) },
  );
  return toForeclosureQuote(row);
}

export async function executeForeclosureQuote(
  id: string,
  input: ExecuteForeclosureQuoteInput,
): Promise<LoanForeclosureQuote> {
  const row = await requestJson<BackendLoanForeclosureQuoteResponse>(
    `${BACKEND_BASE}/${encodeURIComponent(id)}/foreclosure-quotes/${encodeURIComponent(input.quoteId)}/execute`,
    {
      method: "POST",
      body: JSON.stringify({
        settlementDate: input.settlementDate,
        reference: input.reference,
        note: input.note ?? undefined,
      }),
    },
    { idempotencyKey: input.idempotencyKey },
  );
  return toForeclosureQuote(row);
}

/**
 * Backend `ALLOWED_MANUAL_OVERRIDE_TARGETS`. Mirrors
 * `LoanApplicationStatus.ALLOWED_MANUAL_OVERRIDE_TARGETS`; the server
 * remains authoritative (source/financial/in-flight guards), this is only
 * the client-side guard so the UI never submits a blocked target.
 */
export const ALLOWED_MANUAL_OVERRIDE_TARGETS: ReadonlySet<LoanApplication["status"]> = new Set([
  "INITIALIZED",
  "AWAITING_APPROVAL",
  "DISBURSEMENT_RETRY",
  "REJECTED",
]);

/**
 * Sources the backend refuses to override from. Mirrors
 * `MANUAL_OVERRIDE_SOURCE_BLOCKED` ∪ servicing
 * (`APPROVED_PENDING_DISBURSAL`, `INVALID`, `DISBURSED`, `UNDER_REPAYMENT`,
 * `CLOSED`, `FORECLOSED`). Client-side hide/disable only; the server stays
 * authoritative for races.
 */
const MANUAL_OVERRIDE_SOURCE_BLOCKED: ReadonlySet<LoanApplication["status"]> = new Set([
  "APPROVED_PENDING_DISBURSAL",
  "INVALID",
  "DISBURSED",
  "UNDER_REPAYMENT",
  "CLOSED",
  "FORECLOSED",
]);

/**
 * Account states matching the backend in-flight guard
 * (`DISBURSEMENT_REQUESTED` / `DISBURSEMENT_PENDING_RECONCILIATION`): a
 * submitted disbursement or parked reconciliation means the only forward
 * path is reconciling the original reference, never a manual override.
 */
const ACCOUNT_IN_FLIGHT_STATUSES: ReadonlySet<string> = new Set([
  "DISBURSEMENT_REQUESTED",
  "DISBURSEMENT_PENDING_RECONCILIATION",
]);

/** Client-side hide/disable check for the Override action. */
export function isManualOverrideSourceBlocked(
  applicationStatus: LoanApplication["status"],
  accountStatus: string | null | undefined,
): boolean {
  if (MANUAL_OVERRIDE_SOURCE_BLOCKED.has(applicationStatus)) return true;
  if (accountStatus != null && ACCOUNT_IN_FLIGHT_STATUSES.has(accountStatus)) return true;
  return false;
}

/** Override targets offered for `currentStatus` (never the status it is in). */
export function manualOverrideTargetsFor(
  currentStatus: LoanApplication["status"],
): readonly LoanApplication["status"][] {
  return [...ALLOWED_MANUAL_OVERRIDE_TARGETS].filter((target) => target !== currentStatus);
}

async function postStandardTransition(
  id: string,
  input: TransitionStatusInput,
  idempotencyKey: string,
): Promise<TransitionResponse> {
  // Exactly ONE command. A 400/403 from the standard state machine is
  // surfaced verbatim — even when local session metadata claims
  // SYSTEM_ADMIN (that metadata is forgeable; the server is authoritative).
  // Admin recovery is the separate `postManualStatusOverride` action below.
  const payload = await requestJson<OpsLoanApplicationDetailResponse>(
    `${BACKEND_BASE}/${encodeURIComponent(id)}/status-transitions`,
    {
      method: "POST",
      body: JSON.stringify({
        targetStatus: input.to,
        note: input.reason ?? null,
        reasonCode: input.reasonCode ?? null,
      }),
    },
    { idempotencyKey },
  );

  // Project the POST response directly — no follow-up GET. A KYC
  // checklist (or audit) 500 after a committed POST must never convert the
  // success into an apparent failure. Authoritative detail/checklist refresh
  // via the invalidated queries; no audit row is attributed to this command.
  return { application: toApplication(payload, payload.createdAt ?? nowIso()) };
}

/**
 * Deliberate admin override — a SEPARATE visible action, never an
 * automatic fallback. Posts exactly one `manual-status` command with the
 * operator-entered reason code + explanation under the caller-supplied key.
 * Cancel/dismiss sends nothing (the caller only invokes this on confirm).
 */
export async function postManualStatusOverride(
  id: string,
  input: ManualStatusOverrideInput,
): Promise<TransitionResponse> {
  if (!ALLOWED_MANUAL_OVERRIDE_TARGETS.has(input.to)) {
    throw new ApiError(
      `Manual override is not supported for ${input.to}.`,
      400,
      "",
      "MANUAL_OVERRIDE_NOT_ALLOWED",
    );
  }
  const reason = (input.reason ?? "").trim();
  const reasonCode = (input.reasonCode ?? "").trim();
  if (!reason) {
    throw new ApiError("Manual override requires an explanation.", 400, "", "NOTE_REQUIRED");
  }
  if (!reasonCode) {
    throw new ApiError("Manual override requires a reason code.", 400, "", "REASON_CODE_REQUIRED");
  }
  if (!input.idempotencyKey) {
    throw new ApiError(
      "Manual override requires an idempotency key.",
      400,
      "",
      "IDEMPOTENCY_KEY_REQUIRED",
    );
  }

  const payload = await requestJson<OpsLoanApplicationDetailResponse>(
    `${BACKEND_BASE}/${encodeURIComponent(id)}/manual-status`,
    {
      method: "POST",
      body: JSON.stringify({ targetStatus: input.to, note: reason, reasonCode }),
    },
    { idempotencyKey: input.idempotencyKey },
  );

  // Same post-commit rule as the standard path — project the POST
  // response directly, no follow-up GET (see `postStandardTransition`).
  return { application: toApplication(payload, payload.createdAt ?? nowIso()) };
}

/**
 * Post a lifecycle transition. The `idempotencyKey` on the input must be a
 * non-empty string (minted by `ActionBar`/`TransitionConfirmDialog`); we
 * forward it as the `Idempotency-Key` header so retries from the same
 * confirm dialog don't double-submit.
 */
export async function postTransition(
  id: string,
  input: TransitionStatusInput,
): Promise<TransitionResponse> {
  const idempotencyKey =
    typeof input.idempotencyKey === "string" && input.idempotencyKey.length > 0
      ? input.idempotencyKey
      : newIdempotencyKey();

  return postStandardTransition(id, input, idempotencyKey);
}

/** Initiate disbursement (SYSTEM_ADMIN only on the live backend). */
export interface DisbursementPreviewResponse {
  applicationId: string;
  loanAccountId: string;
  loanAccountNumber: string;
  externalLoanId: string;
  principal: number;
  processingFee: number;
  netDisbursalAmount: number;
  paymentMode: string;
  beneficiaryAccountHolderName: string;
  beneficiaryBankName?: string | null;
  beneficiaryIfsc: string;
  maskedBeneficiaryAccountNumber: string;
  beneficiarySource?: string | null;
  pendingIntentId?: string | null;
  pendingIntentTranRefNo?: string | null;
  pendingIntentState?: string | null;
}

interface BackendDisbursementRequestRow {
  providerRequestId?: string;
  requestPayloadJson?: string;
}

interface DisbursementReferenceResponse {
  tranRefNo: string;
  source: string;
  intentId?: string | null;
  intentState?: string | null;
}

function toPreviewAmount(value: number | null | undefined): number {
  return value ?? 0;
}

/** Read-only disbursement figures for the confirmation dialog (SYSTEM_ADMIN). */
export async function fetchDisbursementPreview(id: string): Promise<DisbursementPreviewResponse> {
  if (!isSystemAdmin()) {
    throw new ApiError(
      "Disbursement preview requires a system administrator session.",
      403,
      "",
      "FORBIDDEN",
    );
  }

  const payload = await requestJson<DisbursementPreviewResponse>(
    `${BACKEND_BASE}/${encodeURIComponent(id)}/disbursement-preview`,
    { method: "GET" },
  );
  return {
    ...payload,
    principal: toPreviewAmount(payload.principal),
    processingFee: toPreviewAmount(payload.processingFee),
    netDisbursalAmount: toPreviewAmount(payload.netDisbursalAmount),
  };
}

/**
 * Durable provider/intent reference after initiate — works when intent workflow
 * has committed Tx-A but the request log is not yet written.
 */
export async function fetchLatestDisbursementReference(id: string): Promise<string | null> {
  try {
    const reference = await requestJson<DisbursementReferenceResponse | undefined>(
      `${BACKEND_BASE}/${encodeURIComponent(id)}/disbursement-reference`,
      { method: "GET" },
    );
    if (reference?.tranRefNo) return reference.tranRefNo;
  } catch {
    // Fall through to request-log scan for older backends / empty 204.
  }

  const rows = await requestJson<BackendDisbursementRequestRow[]>(
    `${BACKEND_BASE}/${encodeURIComponent(id)}/disbursement-requests`,
    { method: "GET" },
  );
  const latest = rows[0];
  if (!latest) return null;
  if (latest.providerRequestId) return latest.providerRequestId;
  if (latest.requestPayloadJson) {
    try {
      const parsed = JSON.parse(latest.requestPayloadJson) as { tranRefNo?: string };
      if (parsed.tranRefNo) return parsed.tranRefNo;
    } catch {
      return null;
    }
  }
  return null;
}

export async function postDisbursement(
  id: string,
  input: InitiateDisbursementInput,
): Promise<DisbursementResponse> {
  const idempotencyKey =
    typeof input.idempotencyKey === "string" && input.idempotencyKey.length > 0
      ? input.idempotencyKey
      : newIdempotencyKey();

  if (!isSystemAdmin()) {
    throw new ApiError(
      "Disbursement requires a system administrator session.",
      403,
      "",
      "FORBIDDEN",
    );
  }

  const payload = await requestJson<OpsLoanApplicationDetailResponse>(
    `${BACKEND_BASE}/${encodeURIComponent(id)}/disbursement-requests`,
    { method: "POST", body: JSON.stringify({}) },
    { idempotencyKey },
  );
  // Same post-commit rule — project the POST response directly, no
  // follow-up GET and no synthesised event. The audit timeline refreshes via
  // the invalidated activity query (see `TransitionResponse`).
  return { application: toApplication(payload, payload.createdAt ?? nowIso()) };
}
