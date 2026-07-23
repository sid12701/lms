/**
 * Loan-application detail API client.
 *
 * Internal roles (SYSTEM_ADMIN / OPS_USER) call the live backend under
 * `/api/v1/internal/ops/loan-applications/{id}` and translate the flat
 * backend `LoanApplicationDetailResponse` into the nested `LoanApplicationDetail`
 * projection the UI consumes. The webhook tab projects rows from the admin
 * webhook outbox filtered by `aggregateId`.
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
  LoanApplicationWebhookDelivery,
  LoanApplicationWebhooksResponse,
  RequestForeclosureQuoteInput,
  TransitionStatusInput,
} from "./types";
import type { ApplicationAuditEvent, LoanApplication } from "@/types";
import { isUploadedBackendChecklistStatus } from "@/schemas/document";
import { parseLoanApplicationStatus } from "@/lib/loan-application-status";

const BACKEND_BASE = "/api/v1/internal/ops/loan-applications";

function isSystemAdmin(): boolean {
  return loadStoredSession()?.user.role === "SYSTEM_ADMIN";
}

export interface TransitionResponse {
  application: LoanApplication;
  event: ApplicationAuditEvent;
}

export interface DisbursementResponse {
  application: LoanApplication;
  events: readonly ApplicationAuditEvent[];
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
    requiredChecklistRows.every((row) => isUploadedBackendChecklistStatus(row.status ?? ""))
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

interface BackendWebhookEventDeliveryRow {
  eventId: string;
  eventType: string;
  targetUrl: string | null;
  status: "PENDING" | "DELIVERED" | "FAILED" | "DEAD_LETTERED";
  attempts: number;
  lastAttemptAt: string | null;
  lastResponseCode: number | null;
  lastError: string | null;
  createdAt: string;
}

function toWebhookDelivery(row: BackendWebhookEventDeliveryRow): LoanApplicationWebhookDelivery {
  return {
    id: row.eventId,
    eventType: row.eventType,
    endpoint: row.targetUrl ?? "—",
    status: row.status,
    attemptCount: row.attempts,
    lastAttemptAt: row.lastAttemptAt,
    lastError: row.lastError,
    createdAt: row.createdAt,
  };
}

/**
 * Fetch the per-application webhook-delivery feed.
 *
 * Both SYSTEM_ADMIN and OPS_USER read from the dedicated per-loan endpoint
 * `/api/v1/internal/ops/loan-applications/{id}/webhook-events` (Gap #5):
 * the backend projects each outbox row + its latest delivery attempt into
 * a single row, capped at 200 events newest-first.
 */
export async function fetchLoanApplicationWebhooks(
  id: string,
): Promise<LoanApplicationWebhooksResponse> {
  const rows = await requestJson<BackendWebhookEventDeliveryRow[]>(
    `${BACKEND_BASE}/${encodeURIComponent(id)}/webhook-events`,
  );
  return { deliveries: rows.map(toWebhookDelivery) };
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

function synthesiseTransitionEvent(
  detail: LoanApplicationDetail,
  fromStatus: LoanApplication["status"] | null,
  reason: string | null,
): ApplicationAuditEvent {
  return {
    id: `transition-${detail.application.id}-${Date.now()}`,
    applicationId: detail.application.id,
    fromStatus,
    toStatus: detail.application.status,
    action: "transition",
    actorId: "current-session",
    actorRole: "OPS_USER" as ApplicationAuditEvent["actorRole"],
    channel: "UI" as ApplicationAuditEvent["channel"],
    correlationId: detail.application.id,
    reason,
    createdAt: detail.application.updatedAt,
  };
}

async function postBackendTransition(
  id: string,
  input: TransitionStatusInput,
  idempotencyKey: string,
): Promise<TransitionResponse> {
  const targetStatus = input.to;
  const body = {
    targetStatus,
    note: input.reason ?? null,
    reasonCode: input.reasonCode ?? null,
  };

  const tryEndpoint = async (endpoint: "status-transitions" | "manual-status") => {
    // manual-status always requires a code; MANUAL_ADMIN_OVERRIDE is the
    // backend enum member for that path ("OTHER" is not a valid code).
    const requestBody =
      endpoint === "manual-status"
        ? {
            ...body,
            note: body.note ?? "Manual override",
            reasonCode: body.reasonCode ?? "MANUAL_ADMIN_OVERRIDE",
          }
        : body;
    return requestJson<OpsLoanApplicationDetailResponse>(
      `${BACKEND_BASE}/${encodeURIComponent(id)}/${endpoint}`,
      { method: "POST", body: JSON.stringify(requestBody) },
      { idempotencyKey },
    );
  };

  let payload: OpsLoanApplicationDetailResponse;
  try {
    payload = await tryEndpoint("status-transitions");
  } catch (error) {
    // Backend rejects out-of-state transitions with 400/403. For
    // SYSTEM_ADMIN, fall through to /manual-status which bypasses the
    // simple state machine. For OPS_USER, surface the error.
    if (
      error instanceof ApiError &&
      (error.status === 400 || error.status === 403) &&
      isSystemAdmin()
    ) {
      payload = await tryEndpoint("manual-status");
    } else {
      throw error;
    }
  }

  const checklist = await fetchChecklist(id);
  const detail = backendToDetail(payload, checklist);
  return {
    application: detail.application,
    event: synthesiseTransitionEvent(detail, null, input.reason),
  };
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

  return postBackendTransition(id, input, idempotencyKey);
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

  const [payload, checklist] = await Promise.all([
    requestJson<OpsLoanApplicationDetailResponse>(
      `${BACKEND_BASE}/${encodeURIComponent(id)}/disbursement-requests`,
      { method: "POST", body: JSON.stringify({}) },
      { idempotencyKey },
    ),
    fetchChecklist(id),
  ]);
  const detail = backendToDetail(payload, checklist);
  return {
    application: detail.application,
    events: [synthesiseTransitionEvent(detail, null, input.note)],
  };
}
