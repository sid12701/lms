/**
 * Per-tab API client for the loan-application detail surface.
 *
 * Internal roles (SYSTEM_ADMIN / OPS_USER) call the live backend under
 * `/api/v1/internal/ops/loan-applications/{id}/<tab>` and translate the flat
 * backend rows into the rich `LoanDocument` / `RepaymentInstallment` /
 * `PaymentTransaction` projections the tabs consume.
 *
 * `postRepayment` posts strict per-installment payments to the live backend
 * (Gap #17).
 */
import { requestJson } from "@/lib/api/http-client";
import type {
  LoanApplicationDocumentsResponse,
  LoanApplicationRepaymentsResponse,
  LoanApplicationScheduleResponse,
  PostRepaymentInput,
} from "./types";
import type {
  LoanDocument,
  PaymentTransaction,
  RepaymentInstallment,
  RepaymentSchedule,
} from "@/types";

const BACKEND_BASE = "/api/v1/internal/ops/loan-applications";

function toNumber(value: number | string | null | undefined): number {
  if (value == null) return 0;
  const parsed = typeof value === "string" ? Number(value) : value;
  return Number.isFinite(parsed) ? parsed : 0;
}

interface BackendScheduleRow {
  id: string;
  loanAccountId: string;
  installmentNumber: number;
  dueDate: string;
  openingPrincipal: number | string | null;
  principalDue: number | string | null;
  interestDue: number | string | null;
  installmentAmount: number | string | null;
  closingPrincipal: number | string | null;
  status: string;
  paidPrincipal: number | string | null;
  paidInterest: number | string | null;
  paidAmount: number | string | null;
  outstandingAmount: number | string | null;
  daysPastDue: number | null;
  delinquencyBucket: string | null;
  createdAt: string;
}

const INSTALLMENT_STATUSES = new Set(["DUE", "PARTIALLY_PAID", "PAID", "OVERDUE"]);
const DELINQUENCY_BUCKETS = new Set(["B0", "B1_30", "B31_60", "B61_90", "B90_PLUS"]);

function safeInstallmentStatus(value: string): RepaymentInstallment["status"] {
  if (INSTALLMENT_STATUSES.has(value)) return value as RepaymentInstallment["status"];
  if (value === "SCHEDULED" || value === "PENDING") return "DUE";
  if (value === "SETTLED") return "PAID";
  return "DUE";
}

function safeDelinquencyBucket(value: string | null): RepaymentInstallment["delinquencyBucket"] {
  if (value && DELINQUENCY_BUCKETS.has(value)) {
    return value as RepaymentInstallment["delinquencyBucket"];
  }
  return "B0";
}

function toInstallment(row: BackendScheduleRow): RepaymentInstallment {
  return {
    id: row.id,
    accountId: row.loanAccountId,
    scheduleId: row.loanAccountId,
    number: row.installmentNumber,
    dueDate: row.dueDate,
    principalDue: toNumber(row.principalDue),
    interestDue: toNumber(row.interestDue),
    installmentAmount: toNumber(row.installmentAmount),
    paidAmount: toNumber(row.paidAmount),
    outstandingAmount: toNumber(row.outstandingAmount),
    dpd: row.daysPastDue ?? 0,
    delinquencyBucket: safeDelinquencyBucket(row.delinquencyBucket),
    status: safeInstallmentStatus(row.status),
  };
}

function synthesiseSchedule(installments: RepaymentInstallment[]): RepaymentSchedule | null {
  if (installments.length === 0) return null;
  const accountId = installments[0]!.accountId;
  return {
    id: accountId,
    accountId,
    generatedBy: "PLATFORM",
    frozen: true,
    createdAt: installments[0]!.dueDate,
  };
}

/** GET `/api/v1/loan-applications/:id/schedule` — Schedule tab data. */
export async function fetchLoanApplicationSchedule(
  id: string,
): Promise<LoanApplicationScheduleResponse> {
  const rows = await requestJson<BackendScheduleRow[]>(
    `${BACKEND_BASE}/${encodeURIComponent(id)}/repayment-schedule`,
  );
  const installments = rows.map(toInstallment);
  return {
    schedule: synthesiseSchedule(installments),
    installments,
  };
}

interface BackendChecklistRow {
  id: string;
  loanApplicationId: string;
  documentType: string;
  documentDisplayName: string;
  required: boolean;
  status: string;
  note: string | null;
  fileName: string | null;
  fileReference: string | null;
  contentType: string | null;
  sourceReference: string | null;
  lmsManagedContent: boolean;
  storageKey: string | null;
  fileChecksum: string | null;
  fileSizeBytes: number | null;
  uploadedAt: string | null;
  uploadedByUsername: string | null;
  updatedByUsername: string | null;
  createdAt: string;
  updatedAt: string;
}

const DOCUMENT_TYPES = new Set([
  "PAN",
  "AADHAAR",
  "ADDRESS_PROOF",
  "INCOME_PROOF",
  "BANK_STATEMENT",
  "PHOTOGRAPH",
  "KFS",
  "LOAN_AGREEMENT",
  "OTHER",
]);

function safeDocumentType(value: string): LoanDocument["type"] {
  if (DOCUMENT_TYPES.has(value)) return value as LoanDocument["type"];
  if (value === "ADDRESS") return "ADDRESS_PROOF";
  if (value === "INCOME") return "INCOME_PROOF";
  // Backend `LoanApplicationDocumentType` enum names that don't match the FE vocab.
  if (value === "PAN_CARD") return "PAN";
  if (value === "AADHAAR_FILE" || value === "AADHAAR_CARD") return "AADHAAR";
  if (value === "SELFIE_PHOTOGRAPH") return "PHOTOGRAPH";
  return "OTHER";
}

function safeDocumentStatus(value: string): LoanDocument["status"] {
  // BE statuses are {PENDING, SUBMITTED, NOT_REQUIRED}. SUBMITTED/UPLOADED/
  // PENDING_REVIEW and any legacy VERIFIED/REJECTED rows fold onto UPLOADED so
  // existing data stays visible. NOT_REQUIRED is carried through rather than
  // folded onto PENDING: it records a deliberate exemption, and reporting it as
  // outstanding made the platform overstate compliance gaps on real loans.
  if (
    value === "SUBMITTED" ||
    value === "UPLOADED" ||
    value === "PENDING_REVIEW" ||
    value === "VERIFIED" ||
    value === "REJECTED"
  ) {
    return "UPLOADED";
  }
  if (value === "NOT_REQUIRED") {
    return "NOT_REQUIRED";
  }
  return "PENDING";
}

function toDocument(row: BackendChecklistRow): LoanDocument {
  return {
    id: row.id,
    applicationId: row.loanApplicationId,
    type: safeDocumentType(row.documentType),
    displayName: row.documentDisplayName || row.documentType,
    requiredForApproval: row.required,
    requiredForDisbursement: row.required,
    status: safeDocumentStatus(row.status),
    notes: row.note,
    fileMeta:
      row.fileReference || row.storageKey
        ? {
            storageKey: row.storageKey ?? row.fileReference ?? "",
            fileName: row.fileName ?? undefined,
            mime: row.contentType ?? "application/octet-stream",
            size: row.fileSizeBytes ?? 0,
            checksum: row.fileChecksum ?? "",
          }
        : null,
    uploadedAt: row.uploadedAt,
    uploadedBy: row.uploadedByUsername,
  };
}

/** GET `/api/v1/loan-applications/:id/documents` — Documents tab data. */
export async function fetchLoanApplicationDocuments(
  id: string,
): Promise<LoanApplicationDocumentsResponse> {
  const rows = await requestJson<BackendChecklistRow[]>(
    `${BACKEND_BASE}/${encodeURIComponent(id)}/kyc-documents`,
  );
  return { documents: rows.map(toDocument) };
}

interface BackendPaymentRow {
  id: string;
  loanAccountId: string;
  targetInstallmentId?: string | null;
  actorUsername: string | null;
  amount: number | string | null;
  paymentDate: string | null;
  reference: string | null;
  channel: string | null;
  status: string | null;
  allocatedAmount: number | string | null;
  unallocatedAmount: number | string | null;
  note: string | null;
  correlationId: string | null;
  createdAt: string;
  updatedAt: string;
}

const PAYMENT_CHANNELS = new Set([
  "NEFT",
  "RTGS",
  "IMPS",
  "UPI",
  "BANK_TRANSFER",
  "CASH",
  "ADJUSTMENT",
  "CHEQUE",
]);

function safePaymentChannel(value: string | null): PaymentTransaction["channel"] {
  if (value && PAYMENT_CHANNELS.has(value)) return value as PaymentTransaction["channel"];
  if (value === "BANK") return "BANK_TRANSFER";
  return "BANK_TRANSFER";
}

function toPaymentTransaction(row: BackendPaymentRow): PaymentTransaction {
  return {
    id: row.id,
    accountId: row.loanAccountId,
    installmentId: row.targetInstallmentId ?? null,
    channel: safePaymentChannel(row.channel),
    amount: Math.max(0.01, toNumber(row.amount)),
    postedAt: row.paymentDate ? new Date(row.paymentDate).toISOString() : row.createdAt,
    postedBy: row.actorUsername ?? "system",
    reference: row.reference,
    idempotencyKey: row.correlationId ?? row.id,
    allocation: [],
  };
}

/** GET `/api/v1/loan-applications/:id/repayments` — Repayments tab data. */
export async function fetchLoanApplicationRepayments(
  id: string,
): Promise<LoanApplicationRepaymentsResponse> {
  const rows = await requestJson<BackendPaymentRow[]>(
    `${BACKEND_BASE}/${encodeURIComponent(id)}/payments`,
  );
  return { payments: rows.map(toPaymentTransaction) };
}

function toBackendPaymentChannel(mode: string): string {
  const upper = (mode ?? "").toUpperCase();
  if (PAYMENT_CHANNELS.has(upper)) return upper;
  if (upper === "BANK" || upper === "CHEQUE") return "BANK_TRANSFER";
  return "BANK_TRANSFER";
}

function toIsoDate(value: string): string {
  // Backend wants LocalDate (YYYY-MM-DD). Tolerate ISO-8601 datetimes.
  const idx = value.indexOf("T");
  return idx >= 0 ? value.slice(0, idx) : value;
}

/**
 * POST `/api/v1/loan-applications/:id/repayments` — post a repayment.
 *
 * For SYSTEM_ADMIN / OPS_USER sessions, posts against the live backend
 * `/payments` endpoint with the idempotency key forwarded as the request header.
 */
export async function postRepayment(id: string, input: PostRepaymentInput): Promise<void> {
  const body: Record<string, unknown> = {
    targetInstallmentId: input.installmentId,
    amount: input.amount,
    postedAt: toIsoDate(input.postedAt),
    channel: toBackendPaymentChannel(input.mode),
  };
  if (input.reference && input.reference.trim().length > 0) {
    body.reference = input.reference.trim();
  }
  await requestJson<unknown>(
    `${BACKEND_BASE}/${encodeURIComponent(id)}/payments`,
    { method: "POST", body: JSON.stringify(body) },
    { idempotencyKey: input.idempotencyKey },
  );
}
