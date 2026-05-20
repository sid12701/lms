import { buildQueryPath, requestBlob, requestJson } from './http-client'
import type {
  LoanApplicationAssignmentEventRecord,
  LoanApplicationAuditEventRecord,
  LoanApplicationDetailRecord,
  LoanApplicationDocumentAccessAuditRecord,
  LoanApplicationDocumentPlaceholderRecord,
  LoanApplicationDocumentPlaceholderStatus,
  LoanApplicationIntakeAuditRecord,
  LoanApplicationRecord,
  LoanApplicationStatus,
  LoanApplicationStatusReasonCode,
  LoanApplicationStatusTransitionRecord,
  LoanDisbursementRequestLogRecord,
  LoanForeclosureQuoteRecord,
  LoanInvalidationReason,
  LoanInvalidationReasonOptionRecord,
  LoanPaymentChannel,
  LoanPaymentStatus,
  LoanPaymentTransactionRecord,
  LoanRepaymentScheduleInstallmentRecord,
  MockDisbursementOutcome,
} from './lms-api'

export function listLoanApplications(filters?: {
  lspId?: string
  productId?: string
  status?: string
  sourceChannel?: string
  query?: string
  disbursalDateFrom?: string
  disbursalDateTo?: string
  offset?: number
  limit?: number
  paginationDetails?: 'ON' | 'OFF'
}) {
  return requestJson<LoanApplicationRecord[]>(
    buildQueryPath('/api/v1/internal/ops/loan-applications', {
      lspId: filters?.lspId,
      productId: filters?.productId,
      status: filters?.status,
      sourceChannel: filters?.sourceChannel,
      q: filters?.query,
      disbursalDateFrom: filters?.disbursalDateFrom,
      disbursalDateTo: filters?.disbursalDateTo,
      offset: filters?.offset,
      limit: filters?.limit,
      paginationDetails: filters?.paginationDetails,
    }),
  )
}

export function createLoanApplication(payload: {
  lspId: string
  productId: string
  externalLoanId: string
  sourceChannel: string
  borrowerPan: string
  borrowerFullName: string
  borrowerMobile: string
  borrowerEmail?: string
  borrowerDateOfBirth?: string
  borrowerCity?: string
  borrowerState?: string
  borrowerEmploymentType?: string
  borrowerMonthlyIncome?: number
  requestedAmount: number
  tenureMonths: number
}) {
  return requestJson<LoanApplicationRecord>('/api/v1/internal/ops/loan-applications', {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

export function listExternalLspLoanApplications(filters?: {
  productId?: string
  status?: string
  sourceChannel?: string
  query?: string
  offset?: number
  limit?: number
  paginationDetails?: 'ON' | 'OFF'
}) {
  return requestJson<LoanApplicationRecord[]>(
    buildQueryPath('/api/v1/lsp/loan-applications', {
      productId: filters?.productId,
      status: filters?.status,
      sourceChannel: filters?.sourceChannel,
      q: filters?.query,
      offset: filters?.offset,
      limit: filters?.limit,
      paginationDetails: filters?.paginationDetails,
    }),
  )
}

export function createExternalLspLoanApplication(payload: {
  productId: string
  externalLoanId: string
  sourceChannel: string
  borrowerPan: string
  borrowerFullName: string
  borrowerMobile: string
  borrowerEmail?: string
  borrowerDateOfBirth?: string
  borrowerCity?: string
  borrowerState?: string
  borrowerEmploymentType?: string
  borrowerMonthlyIncome?: number
  requestedAmount: number
  tenureMonths: number
}) {
  return requestJson<LoanApplicationRecord>('/api/v1/lsp/loan-applications', {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

export function getExternalLspLoanApplication(applicationId: string) {
  return requestJson<LoanApplicationDetailRecord>(`/api/v1/lsp/loan-applications/${applicationId}`)
}

export function getExternalLspLoanApplicationByExternalLoanId(externalLoanId: string) {
  return requestJson<LoanApplicationDetailRecord>(
    `/api/v1/lsp/loan-applications/external/${encodeURIComponent(externalLoanId)}`,
  )
}

export function listExternalLspLoanInvalidationReasons() {
  return requestJson<LoanInvalidationReasonOptionRecord[]>('/api/v1/lsp/loan-applications/invalid-reasons')
}

export function invalidateExternalLspLoanApplication(
  applicationId: string,
  payload: {
    reasonCode: LoanInvalidationReason
    reasonText?: string
  },
  idempotencyKey: string,
) {
  return requestJson<LoanApplicationDetailRecord>(
    `/api/v1/lsp/loan-applications/${applicationId}/invalid`,
    {
      method: 'POST',
      headers: {
        'Idempotency-Key': idempotencyKey,
      },
      body: JSON.stringify(payload),
    },
  )
}

export function listLoanApplicationIntakeAudits(applicationId: string) {
  return requestJson<LoanApplicationIntakeAuditRecord[]>(
    `/api/v1/internal/ops/loan-applications/${applicationId}/intake-audits`,
  )
}

export function getLoanApplication(applicationId: string) {
  return requestJson<LoanApplicationDetailRecord>(
    `/api/v1/internal/ops/loan-applications/${applicationId}`,
  )
}

export function listLoanApplicationStatusTransitions(applicationId: string) {
  return requestJson<LoanApplicationStatusTransitionRecord[]>(
    `/api/v1/internal/ops/loan-applications/${applicationId}/status-transitions`,
  )
}

export function listLoanApplicationAssignmentEvents(applicationId: string) {
  return requestJson<LoanApplicationAssignmentEventRecord[]>(
    `/api/v1/internal/ops/loan-applications/${applicationId}/assignment-events`,
  )
}

export function listLoanApplicationDisbursementRequests(applicationId: string) {
  return requestJson<LoanDisbursementRequestLogRecord[]>(
    `/api/v1/internal/ops/loan-applications/${applicationId}/disbursement-requests`,
  )
}

export function listLoanApplicationRepaymentSchedule(applicationId: string) {
  return requestJson<LoanRepaymentScheduleInstallmentRecord[]>(
    `/api/v1/internal/ops/loan-applications/${applicationId}/repayment-schedule`,
  )
}

export function listLoanApplicationPaymentTransactions(applicationId: string) {
  return requestJson<LoanPaymentTransactionRecord[]>(
    `/api/v1/internal/ops/loan-applications/${applicationId}/payments`,
  )
}

export function listLoanApplicationForeclosureQuotes(applicationId: string) {
  return requestJson<LoanForeclosureQuoteRecord[]>(
    `/api/v1/internal/ops/loan-applications/${applicationId}/foreclosure-quotes`,
  )
}

export function listLoanApplicationAuditEvents(applicationId: string) {
  return requestJson<LoanApplicationAuditEventRecord[]>(
    `/api/v1/internal/ops/loan-applications/${applicationId}/audit-events`,
  )
}

export function listLoanApplicationDocumentAccessAudits(applicationId: string) {
  return requestJson<LoanApplicationDocumentAccessAuditRecord[]>(
    `/api/v1/internal/ops/loan-applications/${applicationId}/document-access-audits`,
  )
}

export function listLoanApplicationDocumentPlaceholders(applicationId: string) {
  return requestJson<LoanApplicationDocumentPlaceholderRecord[]>(
    `/api/v1/internal/ops/loan-applications/${applicationId}/kyc-documents`,
  )
}

export function downloadDocumentContent(applicationId: string, documentType: string) {
  return requestBlob(
    `/api/v1/internal/ops/loan-applications/${applicationId}/kyc-documents/${documentType}/content`,
  )
}

export function downloadAllDocuments(applicationId: string) {
  return requestBlob(
    `/api/v1/internal/ops/loan-applications/${applicationId}/kyc-documents/download-all`,
  )
}

export function updateLoanApplicationDocumentPlaceholder(
  applicationId: string,
  documentType: string,
  payload: {
    status: LoanApplicationDocumentPlaceholderStatus
    note?: string
    reviewReason?: string
    rejectionReason?: string
    fileName?: string
    contentType?: string
    sourceReference?: string
  },
) {
  return requestJson<LoanApplicationDocumentPlaceholderRecord>(
    `/api/v1/internal/ops/loan-applications/${applicationId}/kyc-documents/${documentType}`,
    {
      method: 'PUT',
      body: JSON.stringify(payload),
    },
  )
}

export function transitionLoanApplicationStatus(
  applicationId: string,
  payload: {
    targetStatus: LoanApplicationStatus
    note?: string
    reasonCode?: LoanApplicationStatusReasonCode
  },
) {
  return requestJson<LoanApplicationDetailRecord>(
    `/api/v1/internal/ops/loan-applications/${applicationId}/status-transitions`,
    {
      method: 'POST',
      body: JSON.stringify(payload),
    },
  )
}

export function manuallyOverrideLoanApplicationStatus(
  applicationId: string,
  payload: {
    targetStatus: LoanApplicationStatus
    note: string
    reasonCode: LoanApplicationStatusReasonCode
  },
) {
  return requestJson<LoanApplicationDetailRecord>(
    `/api/v1/internal/ops/loan-applications/${applicationId}/manual-status`,
    {
      method: 'POST',
      body: JSON.stringify(payload),
    },
  )
}

export function assignLoanApplication(
  applicationId: string,
  payload: {
    assigneeUsername?: string | null
    note?: string
  },
) {
  return requestJson<LoanApplicationDetailRecord>(
    `/api/v1/internal/ops/loan-applications/${applicationId}/assignment`,
    {
      method: 'POST',
      body: JSON.stringify(payload),
    },
  )
}

export function initiateLoanApplicationDisbursement(applicationId: string) {
  return requestJson<LoanApplicationDetailRecord>(
    `/api/v1/internal/ops/loan-applications/${applicationId}/disbursement-requests`,
    {
      method: 'POST',
    },
  )
}

export function applyMockLoanDisbursementOutcome(
  applicationId: string,
  payload: {
    outcome: MockDisbursementOutcome
  },
) {
  return requestJson<LoanApplicationDetailRecord>(
    `/api/v1/internal/ops/loan-applications/${applicationId}/disbursement-requests/mock-outcome`,
    {
      method: 'POST',
      body: JSON.stringify(payload),
    },
  )
}

export function recordLoanApplicationPaymentTransaction(
  applicationId: string,
  payload: {
    amount: number
    paymentDate: string
    reference: string
    channel: LoanPaymentChannel
    status: LoanPaymentStatus
    note?: string
  },
) {
  return requestJson<LoanPaymentTransactionRecord>(
    `/api/v1/internal/ops/loan-applications/${applicationId}/payments`,
    {
      method: 'POST',
      body: JSON.stringify(payload),
    },
  )
}

export function requestLoanApplicationForeclosureQuote(
  applicationId: string,
  payload: {
    effectiveDate: string
  },
) {
  return requestJson<LoanForeclosureQuoteRecord>(
    `/api/v1/internal/ops/loan-applications/${applicationId}/foreclosure-quotes`,
    {
      method: 'POST',
      body: JSON.stringify(payload),
    },
  )
}

export function executeLoanApplicationForeclosureQuote(
  applicationId: string,
  quoteId: string,
  payload: {
    settlementDate: string
    reference: string
    note?: string
  },
) {
  return requestJson<LoanForeclosureQuoteRecord>(
    `/api/v1/internal/ops/loan-applications/${applicationId}/foreclosure-quotes/${quoteId}/execute`,
    {
      method: 'POST',
      body: JSON.stringify(payload),
    },
  )
}
