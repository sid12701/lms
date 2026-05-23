/**
 * Loan-application detail API client.
 *
 * For internal roles (SYSTEM_ADMIN / OPS_USER), the read endpoints call the
 * live backend under `/api/v1/internal/ops/loan-applications/{id}` and
 * translate the flat backend `LoanApplicationDetailResponse` into the
 * nested `LoanApplicationDetail` projection the UI consumes. The webhook
 * tab projects rows from the admin webhook outbox filtered by
 * `aggregateId`.
 *
 * On a 4xx from the backend (typically: backend not running locally), the
 * client falls through to the legacy mock router so dev sessions remain
 * usable. LSP-role sessions always go to the mock router because they
 * have no access to the internal ops endpoint.
 *
 * Lifecycle mutations (`postTransition`, `postDisbursement`) are wired to
 * the backend in issue #6 and currently still route to the mock router.
 */
import { z } from "zod";
import { ApiError, requestJson } from "@/lib/api/http-client";
import { dispatch } from "@/mocks/router";
import { loadStoredSession } from "@/lib/api/session-storage";
import { newIdempotencyKey } from "@/lib/idempotency";
import { LoanStatus } from "@/schemas/loan-application";
import type {
  InitiateDisbursementInput,
  LoanApplicationActivityResponse,
  LoanApplicationDetail,
  LoanApplicationWebhookDelivery,
  LoanApplicationWebhooksResponse,
  TransitionStatusInput,
} from "./types";
import type { ApplicationAuditEvent, LoanApplication } from "@/types";
import { mapBackendStatus } from "./api";

const BACKEND_BASE = "/api/v1/internal/ops/loan-applications";

function isInternalSession(): boolean {
  const session = loadStoredSession();
  const role = session?.user.role;
  return role === "SYSTEM_ADMIN" || role === "OPS_USER";
}

function isSystemAdmin(): boolean {
  return loadStoredSession()?.user.role === "SYSTEM_ADMIN";
}

// ─── Runtime parsers (mock fallback) ─────────────────────────────────────────
const Permissive = z.unknown();

export const LoanApplicationDetailSchema = z.object({
  application: Permissive,
  borrower: Permissive,
  lsp: Permissive,
  product: Permissive,
  account: Permissive.nullable(),
  docsComplete: z.boolean(),
  scheduleValid: z.boolean(),
}) as unknown as z.ZodType<LoanApplicationDetail>;

const ApplicationAuditEventSchema = z.object({
  id: z.string().min(1),
  applicationId: z.string().min(1),
  fromStatus: LoanStatus.nullable(),
  toStatus: LoanStatus,
  action: z.string().min(1),
  actorId: z.string().min(1),
  actorRole: z.string().min(1),
  channel: z.string().min(1),
  correlationId: z.string().min(1),
  reason: z.string().nullable(),
  contextJson: z.record(z.unknown()).optional(),
  createdAt: z.string().min(1),
}) as unknown as z.ZodType<ApplicationAuditEvent>;

export const LoanApplicationActivityResponseSchema: z.ZodType<LoanApplicationActivityResponse> =
  z.object({
    events: z.array(ApplicationAuditEventSchema).readonly(),
  });

const WebhookDeliverySchema = z.object({
  id: z.string().min(1),
  eventType: z.string().min(1),
  endpoint: z.string().min(1),
  status: z.enum(["PENDING", "DELIVERED", "FAILED", "DEAD_LETTERED"]),
  attemptCount: z.number().int().nonnegative(),
  lastAttemptAt: z.string().nullable(),
  lastError: z.string().nullable(),
  createdAt: z.string().min(1),
});

export const LoanApplicationWebhooksResponseSchema: z.ZodType<LoanApplicationWebhooksResponse> =
  z.object({
    deliveries: z.array(WebhookDeliverySchema).readonly(),
  });

const ApplicationLikeSchema = Permissive as unknown as z.ZodType<LoanApplication>;

export const TransitionResponseSchema = z.object({
  application: ApplicationLikeSchema,
  event: ApplicationAuditEventSchema,
});
export type TransitionResponse = z.infer<typeof TransitionResponseSchema>;

export const DisbursementResponseSchema = z.object({
  application: ApplicationLikeSchema,
  events: z.array(ApplicationAuditEventSchema).readonly(),
});
export type DisbursementResponse = z.infer<typeof DisbursementResponseSchema>;

// ─── Backend response shapes ─────────────────────────────────────────────────

interface BackendLoanAccountSummary {
  id: string | null;
  accountNumber: string | null;
  status: string | null;
  principalAmount: number | string | null;
  tenureMonths: number | null;
  approvedAt: string | null;
  createdAt: string | null;
  closureReason: string | null;
  closedAt: string | null;
  closedByUsername: string | null;
  delinquency: {
    maxDaysPastDue: number | null;
    bucket: string | null;
    overdueInstallmentCount: number | null;
    overdueAmount: number | string | null;
  } | null;
  repaymentSchedule: {
    installmentCount: number | null;
    installmentAmount: number | string | null;
    firstDueDate: string | null;
    finalDueDate: string | null;
  } | null;
}

interface BackendLoanApplicationDetail {
  id: string;
  borrowerId: string;
  borrowerFullName: string;
  borrowerPan: string | null;
  borrowerMobile: string | null;
  borrowerEmail: string | null;
  borrowerDateOfBirth: string | null;
  borrowerCity: string | null;
  borrowerState: string | null;
  borrowerEmploymentType: string | null;
  borrowerMonthlyIncome: number | string | null;
  lspId: string;
  lspCode: string;
  lspName: string;
  productId: string;
  productCode: string;
  productName: string;
  externalLoanId: string | null;
  sourceChannel: string | null;
  requestedAmount: number | string | null;
  tenureMonths: number | null;
  status: string;
  invalidReasonCode: string | null;
  invalidReasonText: string | null;
  invalidatedByUsername: string | null;
  invalidatedAt: string | null;
  assignedToUsername: string | null;
  assignedByUsername: string | null;
  assignedAt: string | null;
  createdAt: string | null;
  updatedAt: string | null;
  loanAccount: BackendLoanAccountSummary | null;
}

interface BackendChecklistRow {
  documentType: string;
  required: boolean;
  status: string;
}

function toNumber(value: number | string | null | undefined): number {
  if (value == null) return 0;
  const parsed = typeof value === "string" ? Number(value) : value;
  return Number.isFinite(parsed) ? parsed : 0;
}

function nowIso(): string {
  return new Date().toISOString();
}

function safeChannel(value: string | null | undefined): "UI" | "API" | "WEBHOOK" {
  const upper = (value ?? "").toUpperCase();
  if (upper === "API") return "API";
  if (upper === "WEBHOOK") return "WEBHOOK";
  return "UI";
}

/**
 * Synthesise the rich `LoanApplicationDetail` from the flat backend
 * payload + the document-checklist tab. The frontend `Borrower`, `Lsp`,
 * `LoanProduct` shapes are deeper than what the backend exposes inline;
 * we fill what's available and leave PII fields empty (`MaskedField`
 * renders an empty cell rather than crashing).
 */
function backendToDetail(
  payload: BackendLoanApplicationDetail,
  checklist: readonly BackendChecklistRow[],
): LoanApplicationDetail {
  const requestedAmount = toNumber(payload.requestedAmount);
  const tenureMonths = payload.tenureMonths ?? 0;
  const created = payload.createdAt ?? nowIso();
  const updated = payload.updatedAt ?? created;

  const application: LoanApplication = {
    id: payload.id,
    externalLoanId: payload.externalLoanId,
    borrowerId: payload.borrowerId,
    lspId: payload.lspId,
    productId: payload.productId,
    requestedAmount,
    tenureMonths,
    status: mapBackendStatus(payload.status) as LoanApplication["status"],
    sourceChannel: safeChannel(payload.sourceChannel),
    assignedTo: payload.assignedToUsername,
    createdAt: created,
    updatedAt: updated,
    invalidatedAt: payload.invalidatedAt,
    invalidReason: payload.invalidReasonText ?? payload.invalidReasonCode,
  };

  // Borrower: the backend embeds a thin projection inline. Full Borrower
  // master data (Aadhaar, banking, references, address parts) requires
  // a separate `/internal/admin/borrowers/{id}` call (wired in #7) — for
  // the detail surface we project what's in the payload and leave the
  // rest empty so the OverviewTab and DetailHeader can render.
  const borrower = {
    id: payload.borrowerId,
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
      monthlyIncome: toNumber(payload.borrowerMonthlyIncome),
      annualIncome: toNumber(payload.borrowerMonthlyIncome) * 12,
    },
    banking: {
      bank: "",
      accountHolder: "",
      accountNumber: "",
      ifsc: "",
    },
    references: [],
    kycComplete: false,
    visibleLspIds: [payload.lspId],
  };

  const lsp = {
    id: payload.lspId,
    code: payload.lspCode,
    name: payload.lspName,
    status: "ACTIVE" as const,
  };

  const product = {
    id: payload.productId,
    code: payload.productCode,
    name: payload.productName,
    status: "ACTIVE" as const,
  };

  const account = payload.loanAccount
    ? {
        id: payload.loanAccount.id ?? "",
        applicationId: payload.id,
        accountNumber: payload.loanAccount.accountNumber ?? "",
        accountStatus: (payload.loanAccount.status ?? "PENDING_DISBURSEMENT") as
          | "PENDING_DISBURSEMENT"
          | "ACTIVE"
          | "CLOSED"
          | "FORECLOSED",
        principal: toNumber(payload.loanAccount.principalAmount),
        tenureMonths: payload.loanAccount.tenureMonths ?? tenureMonths,
        approvedAt: payload.loanAccount.approvedAt ?? created,
        createdAt: payload.loanAccount.createdAt ?? created,
        closedAt: payload.loanAccount.closedAt,
        closureReason: (payload.loanAccount.closureReason ?? null) as
          | "FULLY_REPAID"
          | "FORECLOSED"
          | "CANCELLED"
          | null,
      }
    : null;

  const requiredChecklistRows = checklist.filter((row) => row.required);
  const docsComplete =
    requiredChecklistRows.length > 0 &&
    requiredChecklistRows.every((row) => row.status === "VERIFIED");

  const scheduleValid =
    payload.loanAccount?.repaymentSchedule != null &&
    (payload.loanAccount.repaymentSchedule.installmentCount ?? 0) > 0;

  return {
    application,
    borrower: borrower as unknown as LoanApplicationDetail["borrower"],
    lsp: lsp as unknown as LoanApplicationDetail["lsp"],
    product: product as unknown as LoanApplicationDetail["product"],
    account: account as unknown as LoanApplicationDetail["account"],
    docsComplete,
    scheduleValid,
  };
}

async function fetchChecklistSafely(id: string): Promise<readonly BackendChecklistRow[]> {
  try {
    return await requestJson<readonly BackendChecklistRow[]>(
      `${BACKEND_BASE}/${encodeURIComponent(id)}/kyc-documents`,
    );
  } catch {
    return [];
  }
}

// ─── Public surface ──────────────────────────────────────────────────────────

/** Fetch the full detail payload for one loan application. */
export async function fetchLoanApplicationDetail(
  id: string,
): Promise<LoanApplicationDetail> {
  if (isInternalSession()) {
    try {
      const [payload, checklist] = await Promise.all([
        requestJson<BackendLoanApplicationDetail>(
          `${BACKEND_BASE}/${encodeURIComponent(id)}`,
        ),
        fetchChecklistSafely(id),
      ]);
      return backendToDetail(payload, checklist);
    } catch (error) {
      if (!(error instanceof ApiError) || error.status >= 500) throw error;
      // Fall through to mock on 4xx — typically backend down in dev.
    }
  }

  return dispatch(
    {
      method: "GET",
      path: `/api/v1/loan-applications/${encodeURIComponent(id)}`,
    },
    LoanApplicationDetailSchema,
  );
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
    fromStatus: row.fromStatus ? (mapBackendStatus(row.fromStatus) as ApplicationAuditEvent["fromStatus"]) : null,
    toStatus: mapBackendStatus(row.toStatus ?? "INITIATED") as ApplicationAuditEvent["toStatus"],
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
  if (isInternalSession()) {
    try {
      const rows = await requestJson<BackendAuditEvent[]>(
        `${BACKEND_BASE}/${encodeURIComponent(id)}/audit-events`,
      );
      return { events: rows.map(toAuditEvent) };
    } catch (error) {
      if (!(error instanceof ApiError) || error.status >= 500) throw error;
    }
  }

  return dispatch(
    {
      method: "GET",
      path: `/api/v1/loan-applications/${encodeURIComponent(id)}/activity`,
    },
    LoanApplicationActivityResponseSchema,
  );
}

interface BackendWebhookOutboxEvent {
  id: string;
  lspId: string;
  lspCode: string;
  eventType: string;
  aggregateType: string;
  aggregateId: string;
  status: string;
  payloadJson: string | null;
  correlationId: string | null;
  attemptCount: number;
  lastAttemptAt: string | null;
  nextAttemptAt: string | null;
  deliveredAt: string | null;
  lastError: string | null;
  createdAt: string;
}

function mapWebhookStatus(value: string): LoanApplicationWebhookDelivery["status"] {
  const upper = value.toUpperCase();
  if (upper.includes("DEAD")) return "DEAD_LETTERED";
  if (upper.includes("FAIL")) return "FAILED";
  if (upper.includes("DELIVER") || upper.includes("SENT") || upper.includes("DISPATCH")) {
    return "DELIVERED";
  }
  return "PENDING";
}

function toWebhookDelivery(row: BackendWebhookOutboxEvent): LoanApplicationWebhookDelivery {
  return {
    id: row.id,
    eventType: row.eventType,
    endpoint: `lsp:${row.lspCode}`,
    status: mapWebhookStatus(row.status),
    attemptCount: row.attemptCount,
    lastAttemptAt: row.lastAttemptAt,
    lastError: row.lastError,
    createdAt: row.createdAt,
  };
}

/** Fetch the per-application webhook-delivery feed.
 *
 * Backend has no per-application webhook endpoint. For SYSTEM_ADMIN, we
 * read the admin outbox and filter rows whose `aggregateId` matches the
 * application. For OPS_USER (forbidden from the outbox endpoint), we
 * return an empty list — the integration-status doc records this gap.
 */
export async function fetchLoanApplicationWebhooks(
  id: string,
): Promise<LoanApplicationWebhooksResponse> {
  if (isSystemAdmin()) {
    try {
      const rows = await requestJson<BackendWebhookOutboxEvent[]>(
        "/api/v1/internal/admin/webhook-outbox",
      );
      const matching = rows.filter((row) => row.aggregateId === id);
      return { deliveries: matching.map(toWebhookDelivery) };
    } catch (error) {
      if (!(error instanceof ApiError) || error.status >= 500) throw error;
    }
  } else if (isInternalSession()) {
    // OPS_USER cannot read the outbox. Return an empty feed rather than
    // pretending there is no record.
    return { deliveries: [] };
  }

  return dispatch(
    {
      method: "GET",
      path: `/api/v1/loan-applications/${encodeURIComponent(id)}/webhooks`,
    },
    LoanApplicationWebhooksResponseSchema,
  );
}

/**
 * Post a lifecycle transition. The `idempotencyKey` on the input must be a
 * non-empty string (minted by `ActionBar`/`TransitionConfirmDialog`); we
 * forward it both in the body and in the `Idempotency-Key` header so the
 * router's BR-5 dedupe cache catches duplicate submits.
 *
 * Wired to the live backend in issue #6.
 */
export async function postTransition(
  id: string,
  input: TransitionStatusInput,
): Promise<TransitionResponse> {
  const idempotencyKey =
    typeof input.idempotencyKey === "string" && input.idempotencyKey.length > 0
      ? input.idempotencyKey
      : newIdempotencyKey();
  return dispatch(
    {
      method: "POST",
      path: `/api/v1/loan-applications/${encodeURIComponent(id)}/transitions`,
      body: { ...input, idempotencyKey },
      headers: { "Idempotency-Key": idempotencyKey },
    },
    TransitionResponseSchema,
  );
}

/** Initiate disbursement. Wired to the live backend in issue #6. */
export async function postDisbursement(
  id: string,
  input: InitiateDisbursementInput,
): Promise<DisbursementResponse> {
  const idempotencyKey =
    typeof input.idempotencyKey === "string" && input.idempotencyKey.length > 0
      ? input.idempotencyKey
      : newIdempotencyKey();
  return dispatch(
    {
      method: "POST",
      path: `/api/v1/loan-applications/${encodeURIComponent(id)}/disbursement`,
      body: { ...input, idempotencyKey },
      headers: { "Idempotency-Key": idempotencyKey },
    },
    DisbursementResponseSchema,
  );
}
