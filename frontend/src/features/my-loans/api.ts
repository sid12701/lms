/**
 * LSP-scoped `my-loans` API client.
 *
 * The LSP UI loads applications via `GET /api/v1/lsp/loan-applications/{id}`
 * and acts on them through the mark-invalid and audited PII-reveal
 * endpoints. Document upload (single + batch) is also exposed here for
 * issue #19. All mutations forward an `Idempotency-Key` header.
 */
import {
  ApiError,
  buildQueryPath,
  requestJson,
  requestJsonWithHeaders,
} from "@/lib/api/http-client";
import { readPaginationHeaders } from "@/lib/api/pagination-headers";
import { newIdempotencyKey } from "@/lib/idempotency";
import { loadStoredSession } from "@/lib/api/session-storage";
import { parseLoanApplicationStatus } from "@/lib/loan-application-status";
import { finiteNumberOrZero as toNumber } from "@/lib/number";
import type { LoanApplication } from "@/types";

const LSP_BASE = "/api/v1/lsp/loan-applications";

export interface MyLoanListRow {
  id: string;
  externalLoanId: string | null;
  borrowerFullName: string;
  productName: string;
  requestedAmount: number;
  status: string;
  createdAt: string;
}

export interface MyLoanListPage {
  items: MyLoanListRow[];
  totalCount: number;
  offset: number;
  limit: number;
}

interface BackendLspListItem {
  id: string;
  fullName: string;
  loanProductName: string;
  loanAmount: number | null;
  lspLoanId: string | null;
  status: string;
  createdAt: string | null;
}

function toMyLoanListRow(row: BackendLspListItem): MyLoanListRow {
  return {
    id: row.id,
    externalLoanId: row.lspLoanId,
    borrowerFullName: row.fullName,
    productName: row.loanProductName,
    requestedAmount: Number(row.loanAmount ?? 0),
    status: row.status,
    createdAt: row.createdAt ?? "",
  };
}

/** GET `/api/v1/lsp/loan-applications` with header-based pagination metadata. */
export async function fetchMyLoansPage(params: {
  offset: number;
  limit: number;
  /** Free-text borrower / reference search, passed straight to the LSP endpoint. */
  q?: string | undefined;
  /** Single lifecycle status — the endpoint accepts one value. */
  status?: string | undefined;
}): Promise<MyLoanListPage> {
  const path = buildQueryPath(LSP_BASE, {
    offset: params.offset,
    limit: params.limit,
    q: params.q,
    status: params.status,
    paginationDetails: "ON",
  });
  const { data, headers } = await requestJsonWithHeaders<BackendLspListItem[]>(path);
  const pagination = readPaginationHeaders(headers);
  const items = data.map(toMyLoanListRow);
  return {
    items,
    totalCount: pagination.totalCount ?? items.length,
    offset: pagination.offset ?? params.offset,
    limit: pagination.limit ?? params.limit,
  };
}

export interface MyLoanLoanAccountSummary {
  id: string;
  accountNumber: string | null;
  status: string | null;
  principalAmount: number;
  tenureMonths: number | null;
  approvedAt: string | null;
  createdAt: string | null;
  closureReason: string | null;
  closedAt: string | null;
  delinquency: {
    bucket: string | null;
    overdueAmount: number;
    overdueInstallmentCount: number | null;
    maxDaysPastDue: number | null;
  } | null;
  repaymentSchedule: {
    installmentCount: number | null;
    installmentAmount: number;
    firstDueDate: string | null;
    finalDueDate: string | null;
  } | null;
  disbursement: {
    status: string;
    failureReasonCode: string | null;
    failureReason: string | null;
    disbursedAt: string | null;
    grossAmount: number;
    netDisbursedAmount: number;
  } | null;
}

export interface MyLoanDetail {
  id: string;
  borrowerId: string;
  borrowerFullName: string;
  /** PAN as returned by the LSP API — masked by the backend by default. */
  borrowerPanMasked: string | null;
  borrowerAadhaarMasked: string | null;
  borrowerMobile: string | null;
  borrowerEmail: string | null;
  borrowerDob: string | null;
  borrowerCity: string | null;
  borrowerState: string | null;
  productId: string;
  productCode: string;
  productName: string;
  lspId: string;
  lspCode: string;
  lspName: string;
  externalLoanId: string | null;
  requestedAmount: number;
  interestRate: number | null;
  tenureMonths: number;
  status: LoanApplication["status"];
  rawStatus: string;
  invalidReasonCode: string | null;
  invalidReasonText: string | null;
  invalidatedAt: string | null;
  createdAt: string | null;
  updatedAt: string | null;
  loanAccount: MyLoanLoanAccountSummary | null;
  lastActivity: {
    activityType: string;
    actorUsername: string | null;
    summary: string;
    detail: string | null;
    correlationId: string | null;
    occurredAt: string;
  } | null;
}

export interface InvalidReasonOption {
  code: string;
  label: string;
  requiresText: boolean;
}

interface BackendLspDetail {
  id: string;
  borrowerId: string;
  fullName: string;
  emailAddress: string | null;
  mobileNumber: string | null;
  dob: string | null;
  panNumber: string | null;
  aadharNumber: string | null;
  loanProductId: string;
  loanProductCode: string;
  loanProductName: string;
  loanAmount: number | string | null;
  interestRate: number | string | null;
  loanTenure: number | null;
  lspLoanId: string | null;
  lspId: string;
  lspCode: string;
  lspName: string;
  addressCity: string | null;
  addressState: string | null;
  status: string;
  invalidReasonCode: string | null;
  invalidReasonText: string | null;
  invalidatedAt: string | null;
  createdAt: string | null;
  updatedAt: string | null;
  lastActivity?: {
    activityType: string;
    actorUsername: string | null;
    summary: string;
    detail: string | null;
    correlationId: string | null;
    occurredAt: string;
  } | null;
  loanAccount: {
    id: string | null;
    accountNumber: string | null;
    status: string | null;
    principalAmount: number | string | null;
    tenureMonths: number | null;
    approvedAt: string | null;
    createdAt: string | null;
    closureReason: string | null;
    closedAt: string | null;
    delinquency: {
      bucket: string | null;
      overdueAmount: number | string | null;
      overdueInstallmentCount: number | null;
      maxDaysPastDue: number | null;
    } | null;
    repaymentSchedule: {
      installmentCount: number | null;
      installmentAmount: number | string | null;
      firstDueDate: string | null;
      finalDueDate: string | null;
    } | null;
    disbursement?: {
      status: string;
      failureReasonCode: string | null;
      failureReason: string | null;
      disbursedAt: string | null;
      grossAmount: number | string | null;
      netDisbursedAmount: number | string | null;
    } | null;
  } | null;
}

interface BackendInvalidReasonOption {
  code: string;
  label: string;
  requiresText: boolean;
}

function isLspSession(): boolean {
  const role = loadStoredSession()?.user.role;
  return role === "LSP_UI_READ" || role === "LSP_UI_WRITE" || role === "LSP_API_CLIENT";
}

function ensureLspSession(): void {
  if (!isLspSession()) {
    throw new ApiError(
      "Your session does not have access to LSP loan applications.",
      403,
      "",
      "FORBIDDEN",
    );
  }
}

function backendToDetail(payload: BackendLspDetail): MyLoanDetail {
  return {
    id: payload.id,
    borrowerId: payload.borrowerId,
    borrowerFullName: payload.fullName,
    borrowerPanMasked: payload.panNumber,
    borrowerAadhaarMasked: payload.aadharNumber,
    borrowerMobile: payload.mobileNumber,
    borrowerEmail: payload.emailAddress,
    borrowerDob: payload.dob,
    borrowerCity: payload.addressCity,
    borrowerState: payload.addressState,
    productId: payload.loanProductId,
    productCode: payload.loanProductCode,
    productName: payload.loanProductName,
    lspId: payload.lspId,
    lspCode: payload.lspCode,
    lspName: payload.lspName,
    externalLoanId: payload.lspLoanId,
    requestedAmount: toNumber(payload.loanAmount),
    interestRate: payload.interestRate == null ? null : toNumber(payload.interestRate),
    tenureMonths: payload.loanTenure ?? 0,
    status: parseLoanApplicationStatus(payload.status) ?? "INITIALIZED",
    rawStatus: payload.status,
    invalidReasonCode: payload.invalidReasonCode,
    invalidReasonText: payload.invalidReasonText,
    invalidatedAt: payload.invalidatedAt,
    createdAt: payload.createdAt,
    updatedAt: payload.updatedAt,
    lastActivity: payload.lastActivity
      ? {
          activityType: payload.lastActivity.activityType,
          actorUsername: payload.lastActivity.actorUsername,
          summary: payload.lastActivity.summary,
          detail: payload.lastActivity.detail,
          correlationId: payload.lastActivity.correlationId,
          occurredAt: payload.lastActivity.occurredAt,
        }
      : null,
    loanAccount: payload.loanAccount
      ? {
          id: payload.loanAccount.id ?? "",
          accountNumber: payload.loanAccount.accountNumber,
          status: payload.loanAccount.status,
          principalAmount: toNumber(payload.loanAccount.principalAmount),
          tenureMonths: payload.loanAccount.tenureMonths,
          approvedAt: payload.loanAccount.approvedAt,
          createdAt: payload.loanAccount.createdAt,
          closureReason: payload.loanAccount.closureReason,
          closedAt: payload.loanAccount.closedAt,
          delinquency: payload.loanAccount.delinquency
            ? {
                bucket: payload.loanAccount.delinquency.bucket,
                overdueAmount: toNumber(payload.loanAccount.delinquency.overdueAmount),
                overdueInstallmentCount: payload.loanAccount.delinquency.overdueInstallmentCount,
                maxDaysPastDue: payload.loanAccount.delinquency.maxDaysPastDue,
              }
            : null,
          repaymentSchedule: payload.loanAccount.repaymentSchedule
            ? {
                installmentCount: payload.loanAccount.repaymentSchedule.installmentCount,
                installmentAmount: toNumber(
                  payload.loanAccount.repaymentSchedule.installmentAmount,
                ),
                firstDueDate: payload.loanAccount.repaymentSchedule.firstDueDate,
                finalDueDate: payload.loanAccount.repaymentSchedule.finalDueDate,
              }
            : null,
          disbursement: payload.loanAccount.disbursement
            ? {
                status: payload.loanAccount.disbursement.status,
                failureReasonCode: payload.loanAccount.disbursement.failureReasonCode,
                failureReason: payload.loanAccount.disbursement.failureReason,
                disbursedAt: payload.loanAccount.disbursement.disbursedAt,
                grossAmount: toNumber(payload.loanAccount.disbursement.grossAmount),
                netDisbursedAmount: toNumber(payload.loanAccount.disbursement.netDisbursedAmount),
              }
            : null,
        }
      : null,
  };
}

/** GET `/api/v1/lsp/loan-applications/{id}`. */
export async function fetchMyLoanDetail(id: string): Promise<MyLoanDetail> {
  ensureLspSession();
  const payload = await requestJson<BackendLspDetail>(`${LSP_BASE}/${encodeURIComponent(id)}`);
  return backendToDetail(payload);
}

/** GET `/api/v1/lsp/loan-applications/invalid-reasons`. */
export async function fetchInvalidReasons(): Promise<InvalidReasonOption[]> {
  ensureLspSession();
  const rows = await requestJson<BackendInvalidReasonOption[]>(`${LSP_BASE}/invalid-reasons`);
  return rows.map((row) => ({
    code: row.code,
    label: row.label,
    requiresText: row.requiresText,
  }));
}

export interface MarkInvalidInput {
  applicationId: string;
  reasonCode: string;
  /** Only sent when reasonCode === "OTHERS". */
  reasonText?: string | null;
  /** Optional — minted by the API when not supplied. */
  idempotencyKey?: string;
}

/** POST `/api/v1/lsp/loan-applications/{id}/invalid`. */
export async function markLoanInvalid(input: MarkInvalidInput): Promise<MyLoanDetail> {
  ensureLspSession();
  const idempotencyKey =
    typeof input.idempotencyKey === "string" && input.idempotencyKey.length > 0
      ? input.idempotencyKey
      : newIdempotencyKey();
  // reasonText is only valid for OTHERS — strip it otherwise so the
  // backend doesn't reject the request.
  const body: { reasonCode: string; reasonText?: string } = {
    reasonCode: input.reasonCode,
  };
  if (input.reasonCode === "OTHERS" && input.reasonText) {
    body.reasonText = input.reasonText;
  }
  const payload = await requestJson<BackendLspDetail>(
    `${LSP_BASE}/${encodeURIComponent(input.applicationId)}/invalid`,
    { method: "POST", body: JSON.stringify(body) },
    { idempotencyKey },
  );
  return backendToDetail(payload);
}

export type LspDocumentType =
  | "PAN"
  | "AADHAAR"
  | "ADDRESS_PROOF"
  | "INCOME_PROOF"
  | "BANK_STATEMENT"
  | "PHOTOGRAPH"
  | "KFS"
  | "LOAN_AGREEMENT"
  | "OTHER";

// Backend `LoanApplicationDocumentType` enum names. FE uses shorter names for
// historical reasons; translate on the wire so uploads land on the right slot.
const FE_TO_BE_DOCUMENT_TYPE: Record<LspDocumentType, string> = {
  PAN: "PAN_CARD",
  AADHAAR: "AADHAAR_FILE",
  ADDRESS_PROOF: "ADDRESS_PROOF",
  INCOME_PROOF: "INCOME_PROOF",
  BANK_STATEMENT: "BANK_STATEMENT",
  PHOTOGRAPH: "SELFIE_PHOTOGRAPH",
  KFS: "KFS",
  LOAN_AGREEMENT: "LOAN_AGREEMENT",
  OTHER: "OTHER",
};

// Inverse map: backend responses must land back on the FE slot names or the
// checklist can't match rows to slots (PAN_CARD uploads showed as a missing
// "PAN" — audit LSP pass).
const BE_TO_FE_DOCUMENT_TYPE: Record<string, LspDocumentType> = Object.fromEntries(
  (Object.entries(FE_TO_BE_DOCUMENT_TYPE) as [LspDocumentType, string][]).map(([fe, be]) => [
    be,
    fe,
  ]),
) as Record<string, LspDocumentType>;

function normaliseDocumentType(backendType: string): string {
  return BE_TO_FE_DOCUMENT_TYPE[backendType] ?? backendType;
}

export interface UploadLspDocumentInput {
  applicationId: string;
  documentType: LspDocumentType;
  file: File;
  note?: string | null;
  sourceReference?: string | null;
}

interface BackendLspChecklistResponse {
  id: string;
  loanApplicationId: string;
  documentType: string;
  documentDisplayName: string;
  status: string;
  fileName: string | null;
  contentType: string | null;
  fileSizeBytes: number | null;
  uploadedAt: string | null;
  uploadedByUsername: string | null;
}

export interface UploadedLspDocument {
  id: string;
  documentType: string;
  documentDisplayName: string;
  status: string;
  fileName: string | null;
  contentType: string | null;
  fileSizeBytes: number | null;
  uploadedAt: string | null;
  uploadedByUsername: string | null;
}

function toUploadedDocument(payload: BackendLspChecklistResponse): UploadedLspDocument {
  return {
    id: payload.id,
    documentType: normaliseDocumentType(payload.documentType),
    documentDisplayName: payload.documentDisplayName,
    status: payload.status,
    fileName: payload.fileName,
    contentType: payload.contentType,
    fileSizeBytes: payload.fileSizeBytes,
    uploadedAt: payload.uploadedAt,
    uploadedByUsername: payload.uploadedByUsername,
  };
}

/**
 * Gap #4: server-shape of the new LSP-scoped GET. The BE folds status
 * down to PENDING | SUBMITTED (Gap #18 contract); we surface it as-is.
 */
interface BackendLspSubmittedDocument {
  documentType: string;
  status: string;
  fileName: string | null;
  contentType: string | null;
  note: string | null;
  uploadedAt: string | null;
  uploadedByUsername: string | null;
}

export interface SubmittedLspDocument {
  documentType: string;
  status: "PENDING" | "SUBMITTED";
  fileName: string | null;
  contentType: string | null;
  note: string | null;
  uploadedAt: string | null;
  uploadedByUsername: string | null;
}

function toSubmittedLspDocument(payload: BackendLspSubmittedDocument): SubmittedLspDocument {
  return {
    documentType: normaliseDocumentType(payload.documentType),
    status: payload.status === "SUBMITTED" ? "SUBMITTED" : "PENDING",
    fileName: payload.fileName,
    contentType: payload.contentType,
    note: payload.note,
    uploadedAt: payload.uploadedAt,
    uploadedByUsername: payload.uploadedByUsername,
  };
}

/**
 * GET `/api/v1/lsp/loan-applications/{id}/documents` — Gap #4 read.
 * Body is "uploads only" — un-submitted placeholders are filtered server-side
 * so the FE only sees what was actually uploaded. Used to seed the checklist
 * UI on page load (fixes the "checklist resets on reload" bug).
 */
export async function listLspSubmittedDocuments(
  applicationId: string,
): Promise<SubmittedLspDocument[]> {
  ensureLspSession();
  const payload = await requestJson<BackendLspSubmittedDocument[]>(
    `${LSP_BASE}/${encodeURIComponent(applicationId)}/documents`,
  );
  return payload.map(toSubmittedLspDocument);
}

/**
 * POST `/api/v1/lsp/loan-applications/{id}/documents` (multipart) —
 * single-document upload. The backend assigns the storage key and
 * returns the checklist row.
 */
export async function uploadLspDocument(
  input: UploadLspDocumentInput,
): Promise<UploadedLspDocument> {
  ensureLspSession();
  const form = new FormData();
  form.set("documentType", FE_TO_BE_DOCUMENT_TYPE[input.documentType]);
  if (input.note) form.set("note", input.note);
  if (input.sourceReference) form.set("sourceReference", input.sourceReference);
  form.set("file", input.file);
  const payload = await requestJson<BackendLspChecklistResponse>(
    `${LSP_BASE}/${encodeURIComponent(input.applicationId)}/documents`,
    { method: "POST", body: form },
  );
  return toUploadedDocument(payload);
}

export interface LspDocumentRequirement {
  code: string;
  displayName: string;
  requiredForApproval: boolean;
  requiredForDisbursement: boolean;
}

interface BackendLspDocumentRequirement {
  code: string;
  displayName: string;
  requiredForApproval: boolean;
  requiredForDisbursement: boolean;
}

/** GET `/api/v1/lsp/loan-applications/document-requirements`. */
export async function fetchLspDocumentRequirements(): Promise<LspDocumentRequirement[]> {
  ensureLspSession();
  const rows = await requestJson<BackendLspDocumentRequirement[]>(
    `${LSP_BASE}/document-requirements`,
  );
  return rows.map((row) => ({
    code: row.code,
    displayName: row.displayName,
    requiredForApproval: row.requiredForApproval,
    requiredForDisbursement: row.requiredForDisbursement,
  }));
}

export interface MyLoanScheduleInstallment {
  id: string;
  installmentNumber: number;
  dueDate: string;
  principalDue: number;
  interestDue: number;
  installmentAmount: number;
  paidAmount: number;
  outstandingAmount: number;
  status: string;
  daysPastDue: number | null;
  delinquencyBucket: string | null;
}

interface BackendLspScheduleInstallment {
  id: string;
  loanAccountId?: string;
  installmentNumber: number;
  dueDate: string;
  principalDue?: number | string | null;
  interestDue?: number | string | null;
  installmentAmount: number | string;
  paidAmount: number | string | null;
  outstandingAmount: number | string | null;
  status: string;
  daysPastDue?: number | null;
  delinquencyBucket?: string | null;
}

/** GET `/api/v1/lsp/loans/{loanId}/repayment-schedule`. */
export async function fetchMyLoanRepaymentSchedule(
  loanId: string,
): Promise<MyLoanScheduleInstallment[]> {
  ensureLspSession();
  const rows = await requestJson<BackendLspScheduleInstallment[]>(
    `/api/v1/lsp/loans/${encodeURIComponent(loanId)}/repayment-schedule`,
  );
  return rows.map((row) => ({
    id: row.id,
    installmentNumber: row.installmentNumber,
    dueDate: row.dueDate,
    principalDue: toNumber(row.principalDue),
    interestDue: toNumber(row.interestDue),
    installmentAmount: toNumber(row.installmentAmount),
    paidAmount: toNumber(row.paidAmount),
    outstandingAmount: toNumber(row.outstandingAmount),
    status: row.status,
    daysPastDue: row.daysPastDue ?? null,
    delinquencyBucket: row.delinquencyBucket ?? null,
  }));
}

export interface MyLoanPayment {
  id: string;
  amount: number;
  paymentDate: string;
  channel: string;
  reference: string | null;
  status: string;
  createdAt: string | null;
}

interface BackendLspPayment {
  id: string;
  amount: number | string;
  paymentDate: string;
  channel: string;
  reference: string | null;
  status: string;
  createdAt: string | null;
}

/** GET `/api/v1/lsp/loans/{loanId}/payments`. */
export async function fetchMyLoanPayments(loanId: string): Promise<MyLoanPayment[]> {
  ensureLspSession();
  const rows = await requestJson<BackendLspPayment[]>(
    `/api/v1/lsp/loans/${encodeURIComponent(loanId)}/payments`,
  );
  return rows.map((row) => ({
    id: row.id,
    amount: toNumber(row.amount),
    paymentDate: row.paymentDate,
    channel: row.channel,
    reference: row.reference,
    status: row.status,
    createdAt: row.createdAt,
  }));
}
