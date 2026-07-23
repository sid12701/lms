/**
 * Reports / portfolio-MIS surface, wired to the live backend.
 *
 * Backend contract: `ReportAdminController` under
 * `/api/v1/internal/reports` (SYSTEM_ADMIN only).
 *
 * The frontend's `MisSummary` field names differ from the backend
 * (`totalDisbursedMtd` vs `totalDisbursed`, `weightedAvgYieldPct` vs
 * `weightedAvgInterestRate`, `portfolioAtRisk30Pct` vs
 * `portfolioAtRiskPct`). We translate inside this module so the page
 * components and tests keep their existing field names.
 */
import { requestJson, requestBlob, buildQueryPath } from "@/lib/api/http-client";
import { parseLoanApplicationStatus } from "@/lib/loan-application-status";
import type { DelinquencyBucket } from "@/schemas/loan-account";
import type { MisPreviewInstallment } from "@/schemas/report";
import type {
  CreateReportRequestInput,
  MisFilters,
  MisPreviewFilters,
  MisPreviewResponseDto,
  MisPreviewRow,
  MisSummary,
  ReportRequest,
  ReportRequestsListResponse,
  ReportStatus,
} from "./types";

const BASE = "/api/v1/internal/reports";

interface MutationOptions {
  idempotencyKey?: string;
}

interface BackendMisSummary {
  totalDisbursed: number;
  activeLoanCount: number;
  weightedAvgInterestRate: number;
  portfolioAtRiskPct: number;
  totalLoanCount: number;
}

export interface BackendPreviewInstallment {
  installmentNumber: number;
  dueDate: string | null;
  installmentAmount: number;
  paidAmount: number;
  received: boolean;
}

export interface BackendPreviewRow {
  lspCode: string;
  lspName: string;
  applicationId: string;
  externalLoanId: string | null;
  borrowerFullName: string;
  productCode: string;
  productName: string;
  accountNumber: string | null;
  principalAmount: number | null;
  accountStatus: string | null;
  disbursalDate: string | null;
  delinquencyBucket: string | null;
  overdueAmount: number | null;
  closureReason: string | null;
  closedDate: string | null;
  applicationCreatedAt: string | null;
  loanYear: number | null;
  processingFeeAmount: number | null;
  disbursalAmount: number | null;
  interestRate: number | null;
  tenureMonths: number;
  borrowerId: string | null;
  perEmiAmount: number | null;
  installments: BackendPreviewInstallment[];
  loanStatusDisplay: string | null;
  foreclosedRepaidAmount: number | null;
  foreclosureDate: string | null;
  normalClosureDate: string | null;
  daysPastDue: number;
  customerName: string | null;
  address: string | null;
  zipCode: string | null;
  borrowerState: string | null;
  ifscCode: string | null;
  bankAccountNumber: string | null;
  gender: string | null;
  aadharNumber: string | null;
  panNumber: string | null;
  profession: string | null;
  income: number | null;
}

interface BackendPreviewPage {
  content: BackendPreviewRow[];
  totalElements: number;
  page: number;
  size: number;
}

interface BackendReportRequest {
  id: string;
  reportType: string;
  status: string;
  requestedByUsername: string;
  lspId: string | null;
  lspCode: string | null;
  lspName: string | null;
  disbursalDateFrom: string | null;
  disbursalDateTo: string | null;
  notificationEmail: string | null;
  notificationSentAt: string | null;
  notificationErrorMessage: string | null;
  fileName: string | null;
  mediaType: string | null;
  errorMessage: string | null;
  completedAt: string | null;
  createdAt: string;
  updatedAt: string;
}

const KNOWN_STATUSES = new Set<ReportStatus>(["QUEUED", "PROCESSING", "COMPLETED", "FAILED"]);

function normaliseStatus(value: string): ReportStatus {
  return KNOWN_STATUSES.has(value as ReportStatus) ? (value as ReportStatus) : "QUEUED";
}

/** Backend `LoanDelinquencyBucket` → frontend chart / table bucket ids. */
const BACKEND_DPD_TO_FE: Record<string, DelinquencyBucket> = {
  CURRENT: "B0",
  DPD_1_30: "B1_30",
  DPD_31_60: "B31_60",
  DPD_61_90: "B61_90",
  DPD_90_PLUS: "B90_PLUS",
};

function mapDelinquencyBucket(value: string | null): DelinquencyBucket | null {
  if (!value) return null;
  return BACKEND_DPD_TO_FE[value] ?? null;
}

function mapInstallments(
  raw: BackendPreviewInstallment[] | null | undefined,
): MisPreviewInstallment[] {
  if (!raw?.length) return [];
  return raw.map((inst) => ({
    installmentNumber: inst.installmentNumber,
    dueDate: inst.dueDate,
    installmentAmount: Number(inst.installmentAmount ?? 0),
    paidAmount: Number(inst.paidAmount ?? 0),
    received: Boolean(inst.received),
  }));
}

function filterToQuery(
  filters: MisFilters | MisPreviewFilters,
): Record<string, string | number | undefined> {
  const out: Record<string, string | number | undefined> = {
    lspId: filters.lspId ?? undefined,
    disbursalDateFrom: filters.dateFrom ?? undefined,
    disbursalDateTo: filters.dateTo ?? undefined,
  };
  if ("page" in filters && typeof filters.page === "number") out.page = filters.page;
  if ("pageSize" in filters && typeof filters.pageSize === "number") out.size = filters.pageSize;
  return out;
}

export async function misSummary(filters: MisFilters = {}): Promise<MisSummary> {
  const path = buildQueryPath(`${BASE}/portfolio-mis/summary`, filterToQuery(filters));
  const payload = await requestJson<BackendMisSummary>(path);
  return {
    totalDisbursedMtd: Number(payload.totalDisbursed),
    activeLoanCount: Number(payload.activeLoanCount),
    weightedAvgYieldPct: Number(payload.weightedAvgInterestRate),
    portfolioAtRisk30Pct: Number(payload.portfolioAtRiskPct),
    totalLoanCount: Number(payload.totalLoanCount),
  };
}

function loanStatusFromBackend(value: string | null): MisPreviewRow["status"] {
  const parsed = parseLoanApplicationStatus(value);
  if (parsed === "UNDER_REPAYMENT") return "DISBURSED";
  if (parsed) return parsed;
  return "INITIALIZED";
}

/** Maps a backend portfolio-MIS preview row to the frontend table shape (Gap #10). */
export function mapBackendPreviewRowToMisPreviewRow(payload: BackendPreviewRow): MisPreviewRow {
  const installments = mapInstallments(payload.installments);
  return {
    loanId: payload.applicationId,
    externalLoanId: payload.externalLoanId,
    borrowerName: payload.borrowerFullName || payload.customerName || "—",
    borrowerId: payload.borrowerId,
    lspCode: payload.lspCode,
    lspName: payload.lspName,
    productCode: payload.productCode,
    productName: payload.productName,
    accountNumber: payload.accountNumber,
    amount: Number(payload.principalAmount ?? 0),
    status: loanStatusFromBackend(payload.accountStatus ?? payload.loanStatusDisplay),
    loanStatusDisplay: payload.loanStatusDisplay ?? payload.accountStatus,
    disbursalDate: payload.disbursalDate,
    applicationCreatedAt: payload.applicationCreatedAt,
    dpd: payload.daysPastDue,
    delinquencyBucket: mapDelinquencyBucket(payload.delinquencyBucket),
    year: payload.loanYear,
    processingFee: Number(payload.processingFeeAmount ?? 0),
    disbursalAmount: Number(payload.disbursalAmount ?? 0),
    interestPct: Number(payload.interestRate ?? 0),
    tenureMonths: payload.tenureMonths,
    emiAmount: Number(payload.perEmiAmount ?? 0),
    overdueAmount: Number(payload.overdueAmount ?? 0),
    closureDate: payload.closedDate ?? payload.normalClosureDate,
    closureReason: payload.closureReason,
    foreclosureDate: payload.foreclosureDate,
    foreclosedAmount: payload.foreclosedRepaidAmount,
    address: payload.address,
    pan: payload.panNumber,
    aadhaar: payload.aadharNumber,
    gender: payload.gender,
    state: payload.borrowerState,
    zip: payload.zipCode,
    ifsc: payload.ifscCode,
    bankAccount: payload.bankAccountNumber,
    profession: payload.profession,
    income: payload.income,
    installments: installments.length > 0 ? installments : undefined,
  };
}

export async function misPreview(filters: MisPreviewFilters = {}): Promise<MisPreviewResponseDto> {
  const path = buildQueryPath(`${BASE}/portfolio-mis/preview`, filterToQuery(filters));
  const payload = await requestJson<BackendPreviewPage>(path);
  return {
    items: payload.content.map(mapBackendPreviewRowToMisPreviewRow),
    total: payload.totalElements,
    page: payload.page,
    pageSize: payload.size,
  };
}

function toReportRequest(payload: BackendReportRequest): ReportRequest {
  return {
    id: payload.id,
    type: "PORTFOLIO_MIS",
    status: normaliseStatus(payload.status),
    requestedBy: payload.requestedByUsername,
    lspId: payload.lspId,
    dateFrom: payload.disbursalDateFrom,
    dateTo: payload.disbursalDateTo,
    notificationEmail: payload.notificationEmail,
    fileMeta:
      payload.status === "COMPLETED" && payload.fileName
        ? {
            storageKey: payload.fileName,
            size: 0,
            rowCount: 0,
            generatedAt: payload.completedAt ?? payload.updatedAt,
          }
        : null,
    errorMessage: payload.errorMessage,
    queuedAt: payload.createdAt,
    completedAt: payload.completedAt,
  };
}

export async function listRequests(): Promise<ReportRequestsListResponse> {
  const payload = await requestJson<BackendReportRequest[]>(`${BASE}/requests`);
  return { items: payload.map(toReportRequest) };
}

export async function createRequest(
  input: CreateReportRequestInput,
  opts: MutationOptions = {},
): Promise<ReportRequest> {
  const body = {
    lspId: input.lspId,
    disbursalDateFrom: input.dateFrom,
    disbursalDateTo: input.dateTo,
    recipientEmail: input.notificationEmail,
  };
  const payload = await requestJson<BackendReportRequest>(
    `${BASE}/portfolio-mis/requests`,
    { method: "POST", body: JSON.stringify(body) },
    { idempotencyKey: opts.idempotencyKey },
  );
  return toReportRequest(payload);
}

export async function downloadRequest(id: string): Promise<{ url: string }> {
  const { blob } = await requestBlob(`${BASE}/requests/${id}/download`);
  const url = URL.createObjectURL(blob);
  return { url };
}
