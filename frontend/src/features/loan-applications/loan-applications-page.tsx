import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { ArrowRight, CheckCircle2, FileText } from 'lucide-react'
import { Badge } from '../../components/ui/badge'
import { Button } from '../../components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../../components/ui/card'
import { Input } from '../../components/ui/input'
import { useAuth } from '../auth/auth-context'
import {
  ApiError,
  applyMockLoanDisbursementOutcome,
  assignLoanApplication,
  createLoanApplication,
  getLoanApplication,
  executeLoanApplicationForeclosureQuote,
  initiateLoanApplicationDisbursement,
  listLoanApplicationDocumentAccessAudits,
  listLoanApplicationForeclosureQuotes,
  listLoanApplicationPaymentTransactions,
  listLoanApplicationAuditEvents,
  listLoanApplicationAssignmentEvents,
  listLoanApplicationDisbursementRequests,
  listLoanApplicationIntakeAudits,
  listLoanApplicationDocumentPlaceholders,
  listLoanApplicationRepaymentSchedule,
  listLoanApplicationStatusTransitions,
  listLoanApplications,
  listLoanProducts,
  listLsps,
  loanApplicationStatusOptions,
  loanApplicationStatusReasonCodeOptions,
  loanApplicationDocumentPlaceholderStatusOptions,
  loanPaymentChannelOptions,
  loanPaymentStatusOptions,
  requestLoanApplicationForeclosureQuote,
  recordLoanApplicationPaymentTransaction,
  transitionLoanApplicationStatus,
  manuallyOverrideLoanApplicationStatus,
  updateLoanApplicationDocumentPlaceholder,
  type MockDisbursementOutcome,
  type LoanApplicationAuditAction,
  type LoanApplicationAuditEventRecord,
  type LoanApplicationDocumentAccessAuditRecord,
  type LoanApplicationIntakeAuditRecord,
  type LoanApplicationAssignmentEventRecord,
  type LoanDisbursementRequestLogRecord,
  type LoanApplicationDetailRecord,
  type LoanApplicationLastActivityRecord,
  type LoanApplicationDocumentPlaceholderRecord,
  type LoanApplicationDocumentPlaceholderStatus,
  type LoanApplicationRecord,
  type LoanRepaymentScheduleInstallmentRecord,
  type LoanAccountStatus,
  type LoanPaymentChannel,
  type LoanForeclosureQuoteRecord,
  type LoanPaymentStatus,
  type LoanPaymentTransactionRecord,
  type LoanApplicationStatus,
  type LoanApplicationStatusReasonCode,
  type LoanApplicationStatusTransitionRecord,
  type LoanProductRecord,
  type LspRecord,
} from '../api/lms-api'

type IntakeFormState = {
  lspId: string
  productId: string
  externalLoanId: string
  sourceChannel: string
  borrowerPan: string
  borrowerFullName: string
  borrowerMobile: string
  borrowerEmail: string
  borrowerDateOfBirth: string
  borrowerCity: string
  borrowerState: string
  borrowerEmploymentType: string
  borrowerMonthlyIncome: string
  requestedAmount: string
  tenureMonths: string
}

type ListFilterState = {
  lspId: string
  productId: string
  status: string
  sourceChannel: string
  query: string
  disbursalDateFrom: string
  disbursalDateTo: string
}

type PaymentCaptureState = {
  amount: string
  paymentDate: string
  reference: string
  channel: LoanPaymentChannel
  status: LoanPaymentStatus
  note: string
}

type ForeclosureQuoteExecutionState = {
  settlementDate: string
  reference: string
  note: string
}

type TransitionAction = {
  targetStatus: LoanApplicationStatus
  label: string
  description: string
  variant: 'primary' | 'secondary' | 'outline'
}

const initialFormState: IntakeFormState = {
  lspId: '',
  productId: '',
  externalLoanId: '',
  sourceChannel: 'PARTNER_PORTAL',
  borrowerPan: '',
  borrowerFullName: '',
  borrowerMobile: '',
  borrowerEmail: '',
  borrowerDateOfBirth: '',
  borrowerCity: '',
  borrowerState: '',
  borrowerEmploymentType: '',
  borrowerMonthlyIncome: '',
  requestedAmount: '50000',
  tenureMonths: '12',
}

const borrowerEmploymentTypeOptions = [
  'SALARIED',
  'SELF_EMPLOYED',
  'BUSINESS',
  'STUDENT',
  'RETIRED',
  'HOMEMAKER',
  'OTHER',
] as const

const initialFilterState: ListFilterState = {
  lspId: '',
  productId: '',
  status: '',
  sourceChannel: '',
  query: '',
  disbursalDateFrom: '',
  disbursalDateTo: '',
}

function buildInitialPaymentCaptureState(): PaymentCaptureState {
  return {
    amount: '',
    paymentDate: todayDateValue(),
    reference: '',
    channel: 'UPI',
    status: 'RECEIVED',
    note: '',
  }
}

function buildInitialForeclosureQuoteExecutionState(): ForeclosureQuoteExecutionState {
  return {
    settlementDate: todayDateValue(),
    reference: '',
    note: '',
  }
}

const workflowProgression: Array<{
  status: LoanApplicationStatus | 'DECISION'
  label: string
  description: string
}> = [
  { status: 'RECEIVED', label: 'Received', description: 'Captured from intake and ready for review.' },
  {
    status: 'UNDER_REVIEW',
    label: 'Under review',
    description: 'Ops is validating the case and supporting data.',
  },
  {
    status: 'HOLD',
    label: 'On hold',
    description: 'Review is paused while the case waits for clarification or documents.',
  },
  {
    status: 'DECISION',
    label: 'Decision',
    description: 'Approved or rejected after review.',
  },
]

function currencyLabel(value: number) {
  return new Intl.NumberFormat('en-IN', {
    style: 'currency',
    currency: 'INR',
    maximumFractionDigits: 0,
  }).format(value)
}

function todayDateValue() {
  return new Intl.DateTimeFormat('en-CA').format(new Date())
}

function formatTimestamp(value: string) {
  return new Intl.DateTimeFormat('en-IN', {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date(value))
}

function formatDateLabel(value?: string | null) {
  if (!value) {
    return 'Not provided'
  }

  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) {
    return value
  }

  return new Intl.DateTimeFormat('en-IN', {
    dateStyle: 'medium',
  }).format(parsed)
}

function maskMiddle(value: string, visibleStart: number, visibleEnd: number) {
  if (!value) {
    return value
  }

  if (value.length <= visibleStart + visibleEnd) {
    return '•'.repeat(value.length)
  }

  return (
    value.slice(0, visibleStart) +
    '•'.repeat(value.length - visibleStart - visibleEnd) +
    value.slice(value.length - visibleEnd)
  )
}

function formatBorrowerPan(value?: string | null, reveal = false) {
  if (!value) {
    return 'Not provided'
  }

  return reveal ? value : maskMiddle(value, 3, 2)
}

function formatBorrowerMobile(value?: string | null, reveal = false) {
  if (!value) {
    return 'Not provided'
  }

  return reveal ? value : maskMiddle(value, 0, 4)
}

function formatBorrowerEmail(value?: string | null, reveal = false) {
  if (!value) {
    return 'Not provided'
  }

  if (reveal) {
    return value
  }

  const [localPart, domain] = value.split('@')
  if (!domain) {
    return maskMiddle(value, 1, 0)
  }

  const maskedLocalPart =
    localPart.length <= 1 ? '•' : localPart.charAt(0) + '•'.repeat(Math.max(localPart.length - 1, 2))
  return `${maskedLocalPart}@${domain}`
}

function formatAgeLabel(value?: string | null) {
  if (!value) {
    return 'Age not provided'
  }

  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) {
    return 'Age not available'
  }

  const today = new Date()
  let age = today.getFullYear() - parsed.getFullYear()
  const monthDelta = today.getMonth() - parsed.getMonth()
  if (monthDelta < 0 || (monthDelta === 0 && today.getDate() < parsed.getDate())) {
    age -= 1
  }

  return `${age} years old`
}

function formatIncomeLabel(value?: number | null) {
  if (value == null) {
    return 'Not provided'
  }

  return new Intl.NumberFormat('en-IN', {
    style: 'currency',
    currency: 'INR',
    maximumFractionDigits: 0,
  }).format(value)
}

function formatEmploymentType(value?: string | null) {
  if (!value) {
    return 'Not provided'
  }

  return value
    .toLowerCase()
    .split('_')
    .map((segment) => segment.charAt(0).toUpperCase() + segment.slice(1))
    .join(' ')
}

function countBorrowerProfileSignals(application: LoanApplicationRecord | null) {
  if (!application) {
    return 0
  }

  return [
    application.borrowerDateOfBirth,
    application.borrowerCity,
    application.borrowerState,
    application.borrowerEmploymentType,
    application.borrowerMonthlyIncome,
  ].filter((value) => value != null && value !== '').length
}

function formatIncomeCoverage(application: LoanApplicationRecord | null) {
  if (!application || !application.borrowerMonthlyIncome || application.borrowerMonthlyIncome <= 0) {
    return 'Income context not provided'
  }

  const ratio = application.requestedAmount / application.borrowerMonthlyIncome
  return `${ratio.toFixed(1)}x monthly income`
}

function loanStatusLabel(status: LoanApplicationStatus) {
  switch (status) {
    case 'RECEIVED':
      return 'Received'
    case 'UNDER_REVIEW':
      return 'Under review'
    case 'HOLD':
      return 'On hold'
    case 'APPROVED':
      return 'Approved'
    case 'REJECTED':
      return 'Rejected'
  }
}

function loanStatusVariant(
  status: LoanApplicationStatus,
): 'default' | 'success' | 'warning' | 'destructive' {
  switch (status) {
    case 'RECEIVED':
      return 'warning'
    case 'UNDER_REVIEW':
      return 'default'
    case 'HOLD':
      return 'warning'
    case 'APPROVED':
      return 'success'
    case 'REJECTED':
      return 'destructive'
  }
}

function statusProgressIndex(status: LoanApplicationStatus) {
  switch (status) {
    case 'RECEIVED':
      return 1
    case 'UNDER_REVIEW':
      return 2
    case 'HOLD':
      return 3
    case 'APPROVED':
    case 'REJECTED':
      return 4
  }
}

function getTransitionActions(status: LoanApplicationStatus): TransitionAction[] {
  switch (status) {
    case 'RECEIVED':
      return [
        {
          targetStatus: 'UNDER_REVIEW',
          label: 'Move to review',
          description: 'Promote the case into the review queue.',
          variant: 'secondary',
        },
      ]
    case 'UNDER_REVIEW':
      return [
        {
          targetStatus: 'HOLD',
          label: 'Put on hold',
          description: 'Pause review while waiting for clarification or missing inputs.',
          variant: 'secondary',
        },
        {
          targetStatus: 'APPROVED',
          label: 'Approve',
          description: 'Mark the case as approved and ready for downstream processing.',
          variant: 'primary',
        },
        {
          targetStatus: 'REJECTED',
          label: 'Reject',
          description: 'Close the case and capture the decision note.',
          variant: 'outline',
        },
      ]
    case 'HOLD':
      return [
        {
          targetStatus: 'UNDER_REVIEW',
          label: 'Resume review',
          description: 'Bring the case back into the active review queue.',
          variant: 'secondary',
        },
      ]
    case 'APPROVED':
    case 'REJECTED':
      return []
  }
}

function getVisibleTransitionActions(
  status: LoanApplicationStatus,
  roles: string[] | undefined,
): TransitionAction[] {
  const currentRoles = roles ?? []
  const allActions = getTransitionActions(status)

  if (currentRoles.includes('SYSTEM_ADMIN')) {
    return allActions
  }

  if (currentRoles.includes('OPS_USER')) {
    return allActions.filter((action) => {
      if (status === 'RECEIVED') {
        return action.targetStatus === 'UNDER_REVIEW'
      }

      if (status === 'UNDER_REVIEW') {
        return action.targetStatus === 'HOLD'
      }

      if (status === 'HOLD') {
        return action.targetStatus === 'UNDER_REVIEW'
      }

      return false
    })
  }

  return []
}

function getManualStatusTargets(status: LoanApplicationStatus) {
  return loanApplicationStatusOptions.filter((option) => option !== 'APPROVED' && option !== status)
}

function statusRequiresReasonCode(status: LoanApplicationStatus) {
  return status === 'HOLD' || status === 'REJECTED'
}

function loanStatusReasonCodeLabel(code?: LoanApplicationStatusReasonCode | null) {
  switch (code) {
    case 'MISSING_DOCUMENTS':
      return 'Missing documents'
    case 'BORROWER_CLARIFICATION_REQUIRED':
      return 'Borrower clarification required'
    case 'POLICY_EXCEPTION':
      return 'Policy exception'
    case 'FAILED_VERIFICATION':
      return 'Failed verification'
    case 'DUPLICATE_APPLICATION':
      return 'Duplicate application'
    case 'MANUAL_ADMIN_OVERRIDE':
      return 'Manual admin override'
    default:
      return 'No reason code'
  }
}

function loanAuditActionLabel(action: LoanApplicationAuditAction) {
  switch (action) {
    case 'FORECLOSURE_EXECUTED':
      return 'Foreclosure executed'
    case 'MANUAL_STATUS_OVERRIDE':
      return 'Manual admin override'
    case 'STATUS_TRANSITION':
    default:
      return 'Status transition'
  }
}

function loanAccountStatusLabel(status?: LoanAccountStatus | null) {
  switch (status) {
    case 'DISBURSED':
      return 'Disbursed'
    case 'DISBURSEMENT_FAILED':
      return 'Disbursement failed'
    case 'DISBURSEMENT_PENDING_RECONCILIATION':
      return 'Pending reconciliation'
    case 'DISBURSEMENT_REQUESTED':
      return 'Disbursement requested'
    case 'PENDING_DISBURSEMENT':
      return 'Pending disbursement'
    case 'CLOSED':
      return 'Closed'
    case 'FORECLOSED':
      return 'Foreclosed'
    default:
      return 'Account not created'
  }
}

function loanAccountStatusVariant(
  status?: LoanAccountStatus | null,
): 'default' | 'success' | 'warning' | 'destructive' {
  switch (status) {
    case 'DISBURSED':
      return 'success'
    case 'DISBURSEMENT_PENDING_RECONCILIATION':
      return 'warning'
    case 'DISBURSEMENT_FAILED':
      return 'destructive'
    case 'DISBURSEMENT_REQUESTED':
    case 'PENDING_DISBURSEMENT':
      return 'default'
    case 'CLOSED':
      return 'success'
    case 'FORECLOSED':
      return 'warning'
    default:
      return 'default'
  }
}

function loanClosureReasonLabel(reason?: string | null) {
  switch ((reason ?? '').toUpperCase()) {
    case 'FULLY_REPAID':
      return 'Fully repaid'
    case 'FORECLOSURE':
      return 'Foreclosure'
    default:
      return reason ? reason.replace(/_/g, ' ').toLowerCase() : 'Not closed'
  }
}

function loanPaymentChannelLabel(channel: LoanPaymentChannel) {
  switch (channel) {
    case 'BANK_TRANSFER':
      return 'Bank transfer'
    case 'NACH':
      return 'NACH'
    case 'CASH':
      return 'Cash'
    case 'CHEQUE':
      return 'Cheque'
    case 'FORECLOSURE_SETTLEMENT':
      return 'Foreclosure settlement'
    case 'UPI':
    default:
      return 'UPI'
  }
}

function loanPaymentStatusLabel(status: LoanPaymentStatus) {
  switch (status) {
    case 'PENDING_RECONCILIATION':
      return 'Pending reconciliation'
    case 'FAILED':
      return 'Failed'
    case 'RECEIVED':
    default:
      return 'Received'
  }
}

function loanPaymentStatusVariant(
  status: LoanPaymentStatus,
): 'default' | 'success' | 'warning' | 'destructive' {
  switch (status) {
    case 'FAILED':
      return 'destructive'
    case 'PENDING_RECONCILIATION':
      return 'warning'
    case 'RECEIVED':
    default:
      return 'success'
  }
}

function loanForeclosureQuoteStatusLabel(status: string) {
  switch (status) {
    case 'ACTIVE':
      return 'Active'
    case 'SUPERSEDED':
      return 'Superseded'
    case 'EXECUTED':
      return 'Executed'
    default:
      return status
  }
}

function loanForeclosureQuoteStatusVariant(
  status: string,
): 'default' | 'success' | 'warning' | 'destructive' {
  switch (status) {
    case 'ACTIVE':
      return 'warning'
    case 'EXECUTED':
      return 'success'
    case 'SUPERSEDED':
      return 'default'
    default:
      return 'default'
  }
}

function loanRepaymentInstallmentStatusLabel(status?: string | null) {
  if (!status) {
    return 'Unknown'
  }

  return status
    .toLowerCase()
    .split('_')
    .map((segment) => segment.charAt(0).toUpperCase() + segment.slice(1))
    .join(' ')
}

function loanRepaymentInstallmentStatusVariant(
  status?: string | null,
): 'default' | 'success' | 'warning' | 'destructive' {
  switch ((status ?? '').toUpperCase()) {
    case 'PAID':
    case 'CLOSED':
      return 'success'
    case 'PARTIALLY_PAID':
    case 'DUE':
    case 'PARTIAL':
      return 'warning'
    case 'OVERDUE':
    case 'BILLED':
      return 'destructive'
    default:
      return 'default'
  }
}

function loanDelinquencyBucketLabel(bucket?: string | null) {
  switch ((bucket ?? '').toUpperCase()) {
    case 'DPD_1_30':
      return '1-30 DPD'
    case 'DPD_31_60':
      return '31-60 DPD'
    case 'DPD_61_90':
      return '61-90 DPD'
    case 'DPD_90_PLUS':
      return '90+ DPD'
    case 'CURRENT':
    default:
      return 'Current'
  }
}

function loanDelinquencyBucketVariant(
  bucket?: string | null,
): 'default' | 'success' | 'warning' | 'destructive' {
  switch ((bucket ?? '').toUpperCase()) {
    case 'DPD_61_90':
    case 'DPD_90_PLUS':
      return 'destructive'
    case 'DPD_1_30':
    case 'DPD_31_60':
      return 'warning'
    case 'CURRENT':
    default:
      return 'success'
  }
}

function sortByCreatedAtDesc<T extends { createdAt: string }>(records: T[]) {
  return [...records].sort((left, right) => Date.parse(right.createdAt) - Date.parse(left.createdAt))
}

function sortPaymentTransactions(records: LoanPaymentTransactionRecord[]) {
  return [...records].sort((left, right) => {
    const paymentDateComparison = right.paymentDate.localeCompare(left.paymentDate)
    if (paymentDateComparison !== 0) {
      return paymentDateComparison
    }

    return Date.parse(right.createdAt) - Date.parse(left.createdAt)
  })
}

function maskSensitivePayload(payload: unknown): unknown {
  if (Array.isArray(payload)) {
    return payload.map((item) => maskSensitivePayload(item))
  }

  if (!payload || typeof payload !== 'object') {
    return payload
  }

  return Object.entries(payload).reduce<Record<string, unknown>>((masked, [key, value]) => {
    if (typeof value === 'string') {
      if (key === 'borrowerPan' || key === 'pan') {
        masked[key] = formatBorrowerPan(value, false)
        return masked
      }

      if (key === 'borrowerMobile' || key === 'mobile') {
        masked[key] = formatBorrowerMobile(value, false)
        return masked
      }

      if (key === 'borrowerEmail' || key === 'email') {
        masked[key] = formatBorrowerEmail(value, false)
        return masked
      }
    }

    masked[key] = maskSensitivePayload(value)
    return masked
  }, {})
}

function formatPayloadJson(payloadJson: string, revealSensitiveData = false) {
  try {
    const parsed = JSON.parse(payloadJson)
    const visiblePayload = revealSensitiveData ? parsed : maskSensitivePayload(parsed)
    return JSON.stringify(visiblePayload, null, 2)
  } catch {
    return payloadJson
  }
}

function formatNote(note: string | null) {
  const trimmed = note?.trim()
  return trimmed ? trimmed : 'No note recorded.'
}

function formatMetadataValue(value?: string | null) {
  const trimmed = value?.trim()
  return trimmed ? trimmed : 'Not provided'
}

function formatOptionalTimestamp(value?: string | null) {
  if (!value) {
    return 'Not uploaded'
  }

  return formatTimestamp(value)
}

function loanActivityTypeLabel(activityType?: LoanApplicationLastActivityRecord['activityType'] | null) {
  switch (activityType) {
    case 'INTAKE_CAPTURED':
      return 'Intake captured'
    case 'STATUS_TRANSITION':
      return 'Status transition'
    case 'ASSIGNMENT_UPDATED':
      return 'Assignment updated'
    case 'DOCUMENT_REVIEW_UPDATED':
      return 'Document review updated'
    default:
      return 'Not captured'
  }
}

function loanDocumentPlaceholderReasonLabel(status: LoanApplicationDocumentPlaceholderStatus) {
  return status === 'REJECTED' ? 'Rejection reason' : 'Review reason'
}

function loanDocumentPlaceholderReasonPlaceholder(status: LoanApplicationDocumentPlaceholderStatus) {
  return status === 'REJECTED'
    ? 'Capture why this document was rejected.'
    : 'Capture why this document still needs attention.'
}

function loanDocumentPlaceholderReasonFallback(status: LoanApplicationDocumentPlaceholderStatus) {
  return status === 'REJECTED' ? 'No rejection reason recorded.' : 'No review reason recorded.'
}

type ApprovalBlocker = {
  message: string
  documents: string[]
}

function extractApprovalBlocker(error: unknown): ApprovalBlocker | null {
  if (!(error instanceof ApiError)) {
    return null
  }

  const parsedBody = (() => {
    try {
      return JSON.parse(error.body) as Record<string, unknown>
    } catch {
      return null
    }
  })()

  const code =
    (parsedBody && typeof parsedBody.code === 'string' ? parsedBody.code : null) ?? error.code
  const message =
    (parsedBody && typeof parsedBody.message === 'string' ? parsedBody.message : null) ??
    error.message

  const documentCandidates = [
    parsedBody?.blockingDocuments,
    parsedBody?.blockedDocuments,
    parsedBody?.requiredDocuments,
    parsedBody?.violations,
  ]

  const documents = documentCandidates.flatMap((candidate) => {
    if (!Array.isArray(candidate)) {
      return []
    }

    return candidate.flatMap((entry) => {
      if (typeof entry === 'string') {
        return [entry]
      }

      if (entry && typeof entry === 'object') {
        const record = entry as Record<string, unknown>
        const displayName =
          typeof record.documentDisplayName === 'string'
            ? record.documentDisplayName
            : typeof record.documentType === 'string'
              ? record.documentType
              : typeof record.name === 'string'
                ? record.name
                : null
        const detail =
          typeof record.message === 'string'
            ? record.message
            : typeof record.detail === 'string'
              ? record.detail
              : null

        return displayName ? [detail ? `${displayName}: ${detail}` : displayName] : []
      }

      return []
    })
  })

  const isKycRelated =
    Boolean(code && /KYC|DOCUMENT|CHECKLIST/i.test(code)) ||
    /KYC|document|checklist|required/i.test(message) ||
    documents.length > 0

  if (!isKycRelated) {
    return null
  }

  return {
    message,
    documents: Array.from(new Set(documents)).filter(Boolean),
  }
}

function formatBlockingDocuments(documents: LoanApplicationDocumentPlaceholderRecord[]) {
  return documents.map((document) => document.documentDisplayName)
}

function loanDocumentPlaceholderStatusLabel(status: LoanApplicationDocumentPlaceholderStatus) {
  switch (status) {
    case 'PENDING':
      return 'Pending'
    case 'RECEIVED':
      return 'Received'
    case 'VERIFIED':
      return 'Verified'
    case 'REJECTED':
      return 'Rejected'
    case 'NOT_REQUIRED':
      return 'Not required'
  }
}

function loanDocumentPlaceholderStatusVariant(
  status: LoanApplicationDocumentPlaceholderStatus,
): 'default' | 'success' | 'warning' | 'destructive' {
  switch (status) {
    case 'PENDING':
      return 'warning'
    case 'RECEIVED':
      return 'default'
    case 'VERIFIED':
      return 'success'
    case 'REJECTED':
      return 'destructive'
    case 'NOT_REQUIRED':
      return 'default'
  }
}

function sortLoanDocumentPlaceholders(records: LoanApplicationDocumentPlaceholderRecord[]) {
  return [...records].sort((left, right) => {
    if (left.required !== right.required) {
      return left.required ? -1 : 1
    }

    return left.documentDisplayName.localeCompare(right.documentDisplayName)
  })
}

function seedDocumentDrafts(records: LoanApplicationDocumentPlaceholderRecord[]) {
  return records.reduce<
    Record<
      string,
      {
        status: LoanApplicationDocumentPlaceholderStatus
        note: string
        reviewReason: string
        rejectionReason: string
        fileName: string
        contentType: string
        sourceReference: string
      }
    >
  >((accumulator, record) => {
    accumulator[record.id] = {
      status: record.status,
      note: record.note ?? '',
      reviewReason: record.reviewReason ?? '',
      rejectionReason: record.rejectionReason ?? '',
      fileName: record.fileName ?? '',
      contentType: record.contentType ?? '',
      sourceReference: record.sourceReference ?? '',
    }
    return accumulator
  }, {})
}

function countDocumentMetadataSignals(records: LoanApplicationDocumentPlaceholderRecord[]) {
  return records.filter((item) =>
    Boolean(
      item.fileName?.trim() ||
        item.contentType?.trim() ||
        item.sourceReference?.trim() ||
        item.uploadedAt ||
        item.uploadedByUsername?.trim(),
    ),
  ).length
}

export function LoanApplicationsPage() {
  const { user } = useAuth()
  const [applications, setApplications] = useState<LoanApplicationRecord[]>([])
  const [selectedLoan, setSelectedLoan] = useState<LoanApplicationDetailRecord | null>(null)
  const [assignmentHistory, setAssignmentHistory] = useState<LoanApplicationAssignmentEventRecord[]>([])
  const [statusHistory, setStatusHistory] = useState<LoanApplicationStatusTransitionRecord[]>([])
  const [auditTrail, setAuditTrail] = useState<LoanApplicationAuditEventRecord[]>([])
  const [documentAccessAudits, setDocumentAccessAudits] = useState<LoanApplicationDocumentAccessAuditRecord[]>([])
  const [disbursementRequests, setDisbursementRequests] = useState<LoanDisbursementRequestLogRecord[]>([])
  const [repaymentSchedule, setRepaymentSchedule] = useState<LoanRepaymentScheduleInstallmentRecord[]>([])
  const [paymentTransactions, setPaymentTransactions] = useState<LoanPaymentTransactionRecord[]>([])
  const [foreclosureQuotes, setForeclosureQuotes] = useState<LoanForeclosureQuoteRecord[]>([])
  const [intakeAudits, setIntakeAudits] = useState<LoanApplicationIntakeAuditRecord[]>([])
  const [lsps, setLsps] = useState<LspRecord[]>([])
  const [products, setProducts] = useState<LoanProductRecord[]>([])
  const [form, setForm] = useState<IntakeFormState>(initialFormState)
  const [filters, setFilters] = useState<ListFilterState>(initialFilterState)
  const [selectedApplicationId, setSelectedApplicationId] = useState('')
  const [loading, setLoading] = useState(true)
  const [detailLoading, setDetailLoading] = useState(false)
  const [auditLoading, setAuditLoading] = useState(false)
  const [auditTrailLoading, setAuditTrailLoading] = useState(false)
  const [documentAccessAuditsLoading, setDocumentAccessAuditsLoading] = useState(false)
  const [disbursementRequestsLoading, setDisbursementRequestsLoading] = useState(false)
  const [repaymentScheduleLoading, setRepaymentScheduleLoading] = useState(false)
  const [paymentTransactionsLoading, setPaymentTransactionsLoading] = useState(false)
  const [foreclosureQuotesLoading, setForeclosureQuotesLoading] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [transitioning, setTransitioning] = useState(false)
  const [assigning, setAssigning] = useState(false)
  const [requestingDisbursement, setRequestingDisbursement] = useState(false)
  const [resolvingDisbursementOutcome, setResolvingDisbursementOutcome] = useState(false)
  const [recordingPayment, setRecordingPayment] = useState(false)
  const [pageError, setPageError] = useState('')
  const [workflowError, setWorkflowError] = useState('')
  const [auditError, setAuditError] = useState('')
  const [auditTrailError, setAuditTrailError] = useState('')
  const [documentAccessAuditsError, setDocumentAccessAuditsError] = useState('')
  const [disbursementRequestsError, setDisbursementRequestsError] = useState('')
  const [repaymentScheduleError, setRepaymentScheduleError] = useState('')
  const [paymentTransactionsError, setPaymentTransactionsError] = useState('')
  const [foreclosureQuotesError, setForeclosureQuotesError] = useState('')
  const [assignmentError, setAssignmentError] = useState('')
  const [documentPlaceholders, setDocumentPlaceholders] = useState<
    LoanApplicationDocumentPlaceholderRecord[]
  >([])
  const [documentPlaceholderDrafts, setDocumentPlaceholderDrafts] = useState<
    Record<
      string,
      {
        status: LoanApplicationDocumentPlaceholderStatus
        note: string
        reviewReason: string
        rejectionReason: string
        fileName: string
        contentType: string
        sourceReference: string
      }
    >
  >({})
  const [documentPlaceholdersLoading, setDocumentPlaceholdersLoading] = useState(false)
  const [documentPlaceholdersError, setDocumentPlaceholdersError] = useState('')
  const [documentPlaceholderSavingId, setDocumentPlaceholderSavingId] = useState('')
  const [approvalBlocker, setApprovalBlocker] = useState<ApprovalBlocker | null>(null)
  const [transitionNote, setTransitionNote] = useState('')
  const [transitionReasonCode, setTransitionReasonCode] = useState<LoanApplicationStatusReasonCode | ''>('')
  const [manualStatusTarget, setManualStatusTarget] = useState<LoanApplicationStatus | ''>('')
  const [manualStatusNote, setManualStatusNote] = useState('')
  const [manualStatusReasonCode, setManualStatusReasonCode] =
    useState<LoanApplicationStatusReasonCode>('MANUAL_ADMIN_OVERRIDE')
  const [statusHistoryReasonCodeFilter, setStatusHistoryReasonCodeFilter] =
    useState<LoanApplicationStatusReasonCode | ''>('')
  const [manualStatusSubmitting, setManualStatusSubmitting] = useState(false)
  const [manualStatusError, setManualStatusError] = useState('')
  const [assignmentNote, setAssignmentNote] = useState('')
  const [paymentCapture, setPaymentCapture] = useState<PaymentCaptureState>(buildInitialPaymentCaptureState)
  const [foreclosureExecution, setForeclosureExecution] = useState<ForeclosureQuoteExecutionState>(
    buildInitialForeclosureQuoteExecutionState,
  )
  const [requestingForeclosureQuote, setRequestingForeclosureQuote] = useState(false)
  const [executingForeclosureQuoteId, setExecutingForeclosureQuoteId] = useState('')
  const [foreclosureQuoteActionError, setForeclosureQuoteActionError] = useState('')
  const [showSensitiveData, setShowSensitiveData] = useState(false)

  const activeLsps = useMemo(() => lsps.filter((item) => item.status === 'ACTIVE'), [lsps])
  const activeProducts = useMemo(
    () => products.filter((item) => item.status === 'ACTIVE'),
    [products],
  )
  const sourceChannelOptions = useMemo(
    () =>
      Array.from(new Set(applications.map((item) => item.sourceChannel)))
        .filter(Boolean)
        .sort((left, right) => left.localeCompare(right)),
    [applications],
  )
  const selectedApplicationFromList =
    applications.find((application) => application.id === selectedApplicationId) ?? null
  const visibleSelectedApplication =
    selectedLoan && selectedApplicationFromList
      ? { ...selectedLoan, ...selectedApplicationFromList }
      : selectedLoan ?? selectedApplicationFromList
  const latestAudit = intakeAudits[0] ?? null
  const selectedStatusHistory = useMemo(() => sortByCreatedAtDesc(statusHistory), [statusHistory])
  const selectedDisbursementRequests = useMemo(
    () => sortByCreatedAtDesc(disbursementRequests),
    [disbursementRequests],
  )
  const selectedRepaymentSchedule = useMemo(
    () => [...repaymentSchedule].sort((left, right) => left.installmentNumber - right.installmentNumber),
    [repaymentSchedule],
  )
  const selectedPaymentTransactions = useMemo(
    () => sortPaymentTransactions(paymentTransactions),
    [paymentTransactions],
  )
  const selectedForeclosureQuotes = useMemo(
    () => [...foreclosureQuotes].sort((left, right) => {
      const versionComparison = right.version - left.version
      if (versionComparison !== 0) {
        return versionComparison
      }
      return Date.parse(right.createdAt) - Date.parse(left.createdAt)
    }),
    [foreclosureQuotes],
  )
  const selectedAuditTrail = useMemo(() => sortByCreatedAtDesc(auditTrail), [auditTrail])
  const selectedDocumentAccessAudits = useMemo(
    () => sortByCreatedAtDesc(documentAccessAudits),
    [documentAccessAudits],
  )
  const availableStatusHistoryReasonCodes = useMemo(
    () =>
      Array.from(
        new Set(
          selectedStatusHistory.flatMap((transition) =>
            transition.reasonCode ? [transition.reasonCode] : [],
          ),
        ),
      ).sort((left, right) =>
        loanStatusReasonCodeLabel(left).localeCompare(loanStatusReasonCodeLabel(right)),
      ),
    [selectedStatusHistory],
  )
  const filteredStatusHistory = useMemo(
    () =>
      statusHistoryReasonCodeFilter
        ? selectedStatusHistory.filter(
            (transition) => transition.reasonCode === statusHistoryReasonCodeFilter,
          )
        : selectedStatusHistory,
    [selectedStatusHistory, statusHistoryReasonCodeFilter],
  )
  const selectedAssignmentHistory = useMemo(
    () => sortByCreatedAtDesc(assignmentHistory),
    [assignmentHistory],
  )
  const statusDrivenTransitionActions = useMemo(
    () =>
      visibleSelectedApplication
        ? getTransitionActions(visibleSelectedApplication.status as LoanApplicationStatus)
        : [],
    [visibleSelectedApplication],
  )
  const transitionActions = useMemo(
    () =>
      visibleSelectedApplication
        ? getVisibleTransitionActions(
            visibleSelectedApplication.status as LoanApplicationStatus,
            user?.roles,
          )
        : [],
    [visibleSelectedApplication, user?.roles],
  )
  const currentProgressIndex = visibleSelectedApplication
    ? statusProgressIndex(visibleSelectedApplication.status as LoanApplicationStatus)
    : 0
  const manualStatusTargets = useMemo(
    () =>
      visibleSelectedApplication
        ? getManualStatusTargets(visibleSelectedApplication.status as LoanApplicationStatus)
        : [],
    [visibleSelectedApplication],
  )
  const canManualOverrideStatus = Boolean(user?.roles?.includes('SYSTEM_ADMIN'))
  const canInitiateDisbursement = Boolean(
    user?.roles?.includes('SYSTEM_ADMIN') &&
      selectedLoan?.loanAccount?.status === 'PENDING_DISBURSEMENT',
  )
  const canResolveDisbursementOutcome = Boolean(
    user?.roles?.includes('SYSTEM_ADMIN') &&
      selectedLoan?.loanAccount?.status === 'DISBURSEMENT_REQUESTED',
  )
  const canRecordPayments = Boolean(
    user?.roles?.includes('SYSTEM_ADMIN') && selectedLoan?.loanAccount?.status === 'DISBURSED',
  )
  const canRequestForeclosureQuote = Boolean(
    user?.roles?.includes('SYSTEM_ADMIN') && selectedLoan?.loanAccount?.status === 'DISBURSED',
  )
  const canExecuteForeclosureQuote = Boolean(
    user?.roles?.includes('SYSTEM_ADMIN') && selectedLoan?.loanAccount?.status === 'DISBURSED',
  )
  const canRevealSensitiveData = Boolean(user?.roles?.includes('SYSTEM_ADMIN'))
  const revealSensitiveData = canRevealSensitiveData && showSensitiveData
  const borrowerProfileCompleteness = countBorrowerProfileSignals(visibleSelectedApplication)
  const borrowerLocation = visibleSelectedApplication
    ? [visibleSelectedApplication.borrowerCity, visibleSelectedApplication.borrowerState]
        .filter(Boolean)
        .join(', ') || 'Location not provided'
    : 'Location not provided'
  const borrowerEmployment = formatEmploymentType(
    visibleSelectedApplication?.borrowerEmploymentType,
  )
  const borrowerIncomeCoverage = formatIncomeCoverage(visibleSelectedApplication)
  const lastActivity =
    selectedLoan && selectedLoan.id === selectedApplicationId ? selectedLoan.lastActivity ?? null : null
  const sortedDocumentPlaceholders = useMemo(
    () => sortLoanDocumentPlaceholders(documentPlaceholders),
    [documentPlaceholders],
  )
  const requiredDocumentCount = useMemo(
    () => sortedDocumentPlaceholders.filter((item) => item.required).length,
    [sortedDocumentPlaceholders],
  )
  const verifiedDocumentCount = useMemo(
    () =>
      sortedDocumentPlaceholders.filter(
        (item) => item.status === 'VERIFIED' || item.status === 'NOT_REQUIRED',
      ).length,
    [sortedDocumentPlaceholders],
  )
  const receivedDocumentCount = useMemo(
    () => sortedDocumentPlaceholders.filter((item) => item.status === 'RECEIVED').length,
    [sortedDocumentPlaceholders],
  )
  const pendingDocumentCount = useMemo(
    () => sortedDocumentPlaceholders.filter((item) => item.status === 'PENDING').length,
    [sortedDocumentPlaceholders],
  )
  const metadataDocumentCount = useMemo(
    () => countDocumentMetadataSignals(sortedDocumentPlaceholders),
    [sortedDocumentPlaceholders],
  )
  const incompleteRequiredDocuments = useMemo(
    () =>
      sortedDocumentPlaceholders.filter(
        (item) => item.required && item.status !== 'VERIFIED' && item.status !== 'NOT_REQUIRED',
      ),
    [sortedDocumentPlaceholders],
  )
  const incompleteRequiredDocumentLabels = useMemo(
    () => formatBlockingDocuments(incompleteRequiredDocuments),
    [incompleteRequiredDocuments],
  )
  const kycApprovalReady = incompleteRequiredDocuments.length === 0

  useEffect(() => {
    if (kycApprovalReady) {
      setApprovalBlocker(null)
    }
  }, [kycApprovalReady])

  useEffect(() => {
    if (!manualStatusTargets.length) {
      setManualStatusTarget('')
      return
    }

    setManualStatusTarget((current) =>
      current && manualStatusTargets.includes(current) ? current : manualStatusTargets[0],
    )
  }, [manualStatusTargets])

  useEffect(() => {
    setStatusHistoryReasonCodeFilter((current) =>
      current && !availableStatusHistoryReasonCodes.includes(current) ? '' : current,
    )
  }, [availableStatusHistoryReasonCodes])

  useEffect(() => {
    setPaymentCapture(buildInitialPaymentCaptureState())
    setPaymentTransactionsError('')
  }, [selectedApplicationId])

  useEffect(() => {
    setForeclosureExecution(buildInitialForeclosureQuoteExecutionState())
    setForeclosureQuoteActionError('')
  }, [selectedApplicationId])

  async function loadApplications(nextFilters: ListFilterState) {
    const response = await listLoanApplications({
      lspId: nextFilters.lspId || undefined,
      productId: nextFilters.productId || undefined,
      status: nextFilters.status || undefined,
      sourceChannel: nextFilters.sourceChannel || undefined,
      query: nextFilters.query.trim() || undefined,
      disbursalDateFrom: nextFilters.disbursalDateFrom || undefined,
      disbursalDateTo: nextFilters.disbursalDateTo || undefined,
    })

    setApplications(response)
    setSelectedApplicationId((current) =>
      response.some((application) => application.id === current) ? current : response[0]?.id ?? '',
    )

    return response
  }

  async function refreshSelectedLoan(applicationId: string) {
    const [detailResponse, historyResponse, assignmentResponse] = await Promise.all([
      getLoanApplication(applicationId),
      listLoanApplicationStatusTransitions(applicationId),
      listLoanApplicationAssignmentEvents(applicationId),
    ])

    setSelectedLoan(detailResponse)
    setStatusHistory(sortByCreatedAtDesc(historyResponse))
    setAssignmentHistory(sortByCreatedAtDesc(assignmentResponse))
    await refreshAuditTrail(applicationId)
    await refreshDocumentAccessAudits(applicationId)
    await refreshDisbursementRequests(applicationId)
    await refreshRepaymentSchedule(applicationId)
    await refreshPaymentTransactions(applicationId)
    await refreshForeclosureQuotes(applicationId)
  }

  async function refreshAuditTrail(applicationId: string, showLoading = false) {
    if (showLoading) {
      setAuditTrailLoading(true)
    }

    try {
      const response = await listLoanApplicationAuditEvents(applicationId)
      setAuditTrail(sortByCreatedAtDesc(response))
      setAuditTrailError('')
    } catch (loadError) {
      const message = loadError instanceof Error ? loadError.message : 'Unable to load loan audit trail.'
      setAuditTrail([])
      setAuditTrailError(message)
    } finally {
      if (showLoading) {
        setAuditTrailLoading(false)
      }
    }
  }

  async function refreshDocumentAccessAudits(applicationId: string, showLoading = false) {
    if (showLoading) {
      setDocumentAccessAuditsLoading(true)
    }

    try {
      const response = await listLoanApplicationDocumentAccessAudits(applicationId)
      setDocumentAccessAudits(sortByCreatedAtDesc(response))
      setDocumentAccessAuditsError('')
    } catch (loadError) {
      const message =
        loadError instanceof Error ? loadError.message : 'Unable to load document access audit.'
      setDocumentAccessAudits([])
      setDocumentAccessAuditsError(message)
    } finally {
      if (showLoading) {
        setDocumentAccessAuditsLoading(false)
      }
    }
  }

  async function refreshDisbursementRequests(applicationId: string, showLoading = false) {
    if (showLoading) {
      setDisbursementRequestsLoading(true)
    }

    try {
      const response = await listLoanApplicationDisbursementRequests(applicationId)
      setDisbursementRequests(sortByCreatedAtDesc(response))
      setDisbursementRequestsError('')
    } catch (loadError) {
      const message =
        loadError instanceof Error ? loadError.message : 'Unable to load disbursement requests.'
      setDisbursementRequests([])
      setDisbursementRequestsError(message)
    } finally {
      if (showLoading) {
        setDisbursementRequestsLoading(false)
      }
    }
  }

  async function refreshRepaymentSchedule(applicationId: string, showLoading = false) {
    if (showLoading) {
      setRepaymentScheduleLoading(true)
    }

    try {
      const response = await listLoanApplicationRepaymentSchedule(applicationId)
      setRepaymentSchedule(response)
      setRepaymentScheduleError('')
    } catch (loadError) {
      const message =
        loadError instanceof Error ? loadError.message : 'Unable to load repayment schedule.'
      setRepaymentSchedule([])
      setRepaymentScheduleError(message)
    } finally {
      if (showLoading) {
        setRepaymentScheduleLoading(false)
      }
    }
  }

  async function refreshPaymentTransactions(applicationId: string, showLoading = false) {
    if (showLoading) {
      setPaymentTransactionsLoading(true)
    }

    try {
      const response = await listLoanApplicationPaymentTransactions(applicationId)
      setPaymentTransactions(sortPaymentTransactions(response))
      setPaymentTransactionsError('')
    } catch (loadError) {
      const message =
        loadError instanceof Error ? loadError.message : 'Unable to load payment transactions.'
      setPaymentTransactions([])
      setPaymentTransactionsError(message)
    } finally {
      if (showLoading) {
        setPaymentTransactionsLoading(false)
      }
    }
  }

  async function refreshForeclosureQuotes(applicationId: string, showLoading = false) {
    if (showLoading) {
      setForeclosureQuotesLoading(true)
    }

    try {
      const response = await listLoanApplicationForeclosureQuotes(applicationId)
      setForeclosureQuotes(response)
      setForeclosureQuotesError('')
    } catch (loadError) {
      const message =
        loadError instanceof Error ? loadError.message : 'Unable to load foreclosure quotes.'
      setForeclosureQuotes([])
      setForeclosureQuotesError(message)
    } finally {
      if (showLoading) {
        setForeclosureQuotesLoading(false)
      }
    }
  }

  async function refreshDocumentPlaceholders(applicationId: string) {
    try {
      const response = await listLoanApplicationDocumentPlaceholders(applicationId)
      setDocumentPlaceholders(sortLoanDocumentPlaceholders(response))
      setDocumentPlaceholderDrafts(seedDocumentDrafts(response))
      setDocumentPlaceholdersError('')
    } catch (loadError) {
      if (loadError instanceof ApiError && loadError.status === 404) {
        setDocumentPlaceholders([])
        setDocumentPlaceholderDrafts({})
        setDocumentPlaceholdersError('')
        return
      }

      const message =
        loadError instanceof Error ? loadError.message : 'Unable to load document placeholders.'
      setDocumentPlaceholders([])
      setDocumentPlaceholderDrafts({})
      setDocumentPlaceholdersError(message)
    } finally {
      setDocumentPlaceholdersLoading(false)
    }
  }

  useEffect(() => {
    let cancelled = false

    async function loadPage() {
      setLoading(true)
      setPageError('')

      try {
        const [applicationResponse, lspResponse, productResponse] = await Promise.all([
          listLoanApplications(),
          listLsps(),
          listLoanProducts(),
        ])

        if (!cancelled) {
          setApplications(applicationResponse)
          setSelectedApplicationId(applicationResponse[0]?.id ?? '')
          setLsps(lspResponse)
          setProducts(productResponse)
          setForm((current) => ({
            ...current,
            lspId: current.lspId || lspResponse.find((item) => item.status === 'ACTIVE')?.id || '',
            productId:
              current.productId || productResponse.find((item) => item.status === 'ACTIVE')?.id || '',
          }))
        }
      } catch (loadError) {
        const message = loadError instanceof Error ? loadError.message : 'Unable to load intake data.'
        if (!cancelled) {
          setPageError(message)
        }
      } finally {
        if (!cancelled) {
          setLoading(false)
        }
      }
    }

    void loadPage()

    return () => {
      cancelled = true
    }
  }, [])

  useEffect(() => {
    let cancelled = false

    async function loadSelectedLoan() {
        if (!selectedApplicationId) {
        setSelectedLoan(null)
        setAssignmentHistory([])
        setStatusHistory([])
        setAuditTrail([])
        setDisbursementRequests([])
        setRepaymentSchedule([])
        setPaymentTransactions([])
        setForeclosureQuotes([])
        setWorkflowError('')
        setApprovalBlocker(null)
        setAssignmentError('')
        setPaymentTransactionsError('')
        setForeclosureQuotesError('')
        return
      }

        setSelectedLoan(null)
        setAssignmentHistory([])
        setStatusHistory([])
        setAuditTrail([])
        setDisbursementRequests([])
        setRepaymentSchedule([])
        setPaymentTransactions([])
        setForeclosureQuotes([])
        setDetailLoading(true)
        setWorkflowError('')
        setApprovalBlocker(null)
        setAssignmentError('')
        setPaymentTransactionsError('')
        setForeclosureQuotesError('')

      try {
        const [detailResponse, historyResponse, assignmentResponse] = await Promise.all([
          getLoanApplication(selectedApplicationId),
          listLoanApplicationStatusTransitions(selectedApplicationId),
          listLoanApplicationAssignmentEvents(selectedApplicationId),
        ])

        if (!cancelled) {
          setSelectedLoan(detailResponse)
          setStatusHistory(sortByCreatedAtDesc(historyResponse))
          setAssignmentHistory(sortByCreatedAtDesc(assignmentResponse))
        }
      } catch (loadError) {
        const message =
          loadError instanceof Error ? loadError.message : 'Unable to load loan detail.'
        if (!cancelled) {
          setSelectedLoan(null)
          setAssignmentHistory([])
          setStatusHistory([])
          setWorkflowError(message)
          setPaymentTransactions([])
        }
      } finally {
        if (!cancelled) {
          setDetailLoading(false)
        }
      }
    }

    void loadSelectedLoan()

    return () => {
      cancelled = true
    }
  }, [selectedApplicationId])

  useEffect(() => {
    let cancelled = false

    async function loadPaymentTransactions() {
      if (!selectedApplicationId) {
        setPaymentTransactions([])
        setPaymentTransactionsError('')
        setPaymentTransactionsLoading(false)
        return
      }

      setPaymentTransactionsLoading(true)
      setPaymentTransactionsError('')

      try {
        const response = await listLoanApplicationPaymentTransactions(selectedApplicationId)
        if (!cancelled) {
          setPaymentTransactions(sortPaymentTransactions(response))
        }
      } catch (loadError) {
        const message =
          loadError instanceof Error ? loadError.message : 'Unable to load payment transactions.'
        if (!cancelled) {
          setPaymentTransactions([])
          setPaymentTransactionsError(message)
        }
      } finally {
        if (!cancelled) {
          setPaymentTransactionsLoading(false)
        }
      }
    }

    void loadPaymentTransactions()

    return () => {
      cancelled = true
    }
  }, [selectedApplicationId])

  useEffect(() => {
    let cancelled = false

    async function loadForeclosureQuotes() {
      if (!selectedApplicationId) {
        setForeclosureQuotes([])
        setForeclosureQuotesError('')
        setForeclosureQuotesLoading(false)
        return
      }

      setForeclosureQuotesLoading(true)
      setForeclosureQuotesError('')

      try {
        const response = await listLoanApplicationForeclosureQuotes(selectedApplicationId)
        if (!cancelled) {
          setForeclosureQuotes(response)
        }
      } catch (loadError) {
        const message =
          loadError instanceof Error ? loadError.message : 'Unable to load foreclosure quotes.'
        if (!cancelled) {
          setForeclosureQuotes([])
          setForeclosureQuotesError(message)
        }
      } finally {
        if (!cancelled) {
          setForeclosureQuotesLoading(false)
        }
      }
    }

    void loadForeclosureQuotes()

    return () => {
      cancelled = true
    }
  }, [selectedApplicationId])

  useEffect(() => {
    let cancelled = false

    async function loadRepaymentSchedule() {
      if (!selectedApplicationId) {
        setRepaymentSchedule([])
        setRepaymentScheduleError('')
        setRepaymentScheduleLoading(false)
        return
      }

      setRepaymentScheduleLoading(true)
      setRepaymentScheduleError('')

      try {
        const response = await listLoanApplicationRepaymentSchedule(selectedApplicationId)
        if (!cancelled) {
          setRepaymentSchedule(response)
        }
      } catch (loadError) {
        const message =
          loadError instanceof Error ? loadError.message : 'Unable to load repayment schedule.'
        if (!cancelled) {
          setRepaymentSchedule([])
          setRepaymentScheduleError(message)
        }
      } finally {
        if (!cancelled) {
          setRepaymentScheduleLoading(false)
        }
      }
    }

    void loadRepaymentSchedule()

    return () => {
      cancelled = true
    }
  }, [selectedApplicationId])

  useEffect(() => {
    let cancelled = false

    async function loadDisbursementRequests() {
      if (!selectedApplicationId) {
        setDisbursementRequests([])
        setDisbursementRequestsError('')
        setDisbursementRequestsLoading(false)
        return
      }

      setDisbursementRequestsLoading(true)
      setDisbursementRequestsError('')

      try {
        const response = await listLoanApplicationDisbursementRequests(selectedApplicationId)
        if (!cancelled) {
          setDisbursementRequests(sortByCreatedAtDesc(response))
        }
      } catch (loadError) {
        const message =
          loadError instanceof Error ? loadError.message : 'Unable to load disbursement requests.'
        if (!cancelled) {
          setDisbursementRequests([])
          setDisbursementRequestsError(message)
        }
      } finally {
        if (!cancelled) {
          setDisbursementRequestsLoading(false)
        }
      }
    }

    void loadDisbursementRequests()

    return () => {
      cancelled = true
    }
  }, [selectedApplicationId])

  useEffect(() => {
    let cancelled = false

    async function loadAuditTrail() {
      if (!selectedApplicationId) {
        setAuditTrail([])
        setAuditTrailError('')
        setAuditTrailLoading(false)
        return
      }

      setAuditTrailLoading(true)
      setAuditTrailError('')

      try {
        const response = await listLoanApplicationAuditEvents(selectedApplicationId)
        if (!cancelled) {
          setAuditTrail(sortByCreatedAtDesc(response))
        }
      } catch (loadError) {
        const message =
          loadError instanceof Error ? loadError.message : 'Unable to load loan audit trail.'
        if (!cancelled) {
          setAuditTrail([])
          setAuditTrailError(message)
        }
      } finally {
        if (!cancelled) {
          setAuditTrailLoading(false)
        }
      }
    }

    void loadAuditTrail()

    return () => {
      cancelled = true
    }
  }, [selectedApplicationId])

  useEffect(() => {
    let cancelled = false

    async function loadDocumentAccessAudits() {
      if (!selectedApplicationId) {
        setDocumentAccessAudits([])
        setDocumentAccessAuditsError('')
        setDocumentAccessAuditsLoading(false)
        return
      }

      setDocumentAccessAuditsLoading(true)
      setDocumentAccessAuditsError('')

      try {
        const response = await listLoanApplicationDocumentAccessAudits(selectedApplicationId)
        if (!cancelled) {
          setDocumentAccessAudits(sortByCreatedAtDesc(response))
        }
      } catch (loadError) {
        const message =
          loadError instanceof Error ? loadError.message : 'Unable to load document access audit.'
        if (!cancelled) {
          setDocumentAccessAudits([])
          setDocumentAccessAuditsError(message)
        }
      } finally {
        if (!cancelled) {
          setDocumentAccessAuditsLoading(false)
        }
      }
    }

    void loadDocumentAccessAudits()

    return () => {
      cancelled = true
    }
  }, [selectedApplicationId])

  useEffect(() => {
    let cancelled = false

    async function loadDocumentPlaceholders() {
      if (!selectedApplicationId) {
        setDocumentPlaceholders([])
        setDocumentPlaceholderDrafts({})
        setDocumentPlaceholdersError('')
        setDocumentPlaceholdersLoading(false)
        return
      }

      setDocumentPlaceholdersLoading(true)
      setDocumentPlaceholdersError('')

      try {
        const response = await listLoanApplicationDocumentPlaceholders(selectedApplicationId)
        if (!cancelled) {
          setDocumentPlaceholders(sortLoanDocumentPlaceholders(response))
          setDocumentPlaceholderDrafts(seedDocumentDrafts(response))
        }
      } catch (loadError) {
        if (!cancelled) {
          if (loadError instanceof ApiError && loadError.status === 404) {
            setDocumentPlaceholders([])
            setDocumentPlaceholderDrafts({})
            setDocumentPlaceholdersError('')
          } else {
            const message =
              loadError instanceof Error
                ? loadError.message
                : 'Unable to load document placeholders.'
            setDocumentPlaceholders([])
            setDocumentPlaceholderDrafts({})
            setDocumentPlaceholdersError(message)
          }
        }
      } finally {
        if (!cancelled) {
          setDocumentPlaceholdersLoading(false)
        }
      }
    }

    void loadDocumentPlaceholders()

    return () => {
      cancelled = true
    }
  }, [selectedApplicationId])

  useEffect(() => {
    let cancelled = false

    async function refreshFilteredList() {
      if (loading) {
        return
      }

      try {
        const response = await listLoanApplications({
          lspId: filters.lspId || undefined,
          productId: filters.productId || undefined,
          status: filters.status || undefined,
          sourceChannel: filters.sourceChannel || undefined,
          query: filters.query.trim() || undefined,
          disbursalDateFrom: filters.disbursalDateFrom || undefined,
          disbursalDateTo: filters.disbursalDateTo || undefined,
        })
        if (!cancelled) {
          setApplications(response)
          setPageError('')
          setSelectedApplicationId((current) =>
            response.some((application) => application.id === current) ? current : response[0]?.id ?? '',
          )
        }
      } catch (loadError) {
        const message =
          loadError instanceof Error ? loadError.message : 'Unable to filter loan applications.'
        if (!cancelled) {
          setPageError(message)
        }
      }
    }

    void refreshFilteredList()

    return () => {
      cancelled = true
    }
  }, [filters, loading])

  useEffect(() => {
    let cancelled = false

    async function loadIntakeAudit() {
      if (!selectedApplicationId) {
        setIntakeAudits([])
        setAuditError('')
        return
      }

      setAuditLoading(true)
      setAuditError('')

      try {
        const response = await listLoanApplicationIntakeAudits(selectedApplicationId)
        if (!cancelled) {
          setIntakeAudits(sortByCreatedAtDesc(response))
        }
      } catch (loadError) {
        const message =
          loadError instanceof Error ? loadError.message : 'Unable to load intake audit.'
        if (!cancelled) {
          setIntakeAudits([])
          setAuditError(message)
        }
      } finally {
        if (!cancelled) {
          setAuditLoading(false)
        }
      }
    }

    void loadIntakeAudit()

    return () => {
      cancelled = true
    }
  }, [selectedApplicationId])

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setSubmitting(true)
    setPageError('')

    try {
      const created = await createLoanApplication({
        lspId: form.lspId,
        productId: form.productId,
        externalLoanId: form.externalLoanId,
        sourceChannel: form.sourceChannel,
        borrowerPan: form.borrowerPan.toUpperCase(),
        borrowerFullName: form.borrowerFullName,
        borrowerMobile: form.borrowerMobile,
        borrowerEmail: form.borrowerEmail || undefined,
        borrowerDateOfBirth: form.borrowerDateOfBirth || undefined,
        borrowerCity: form.borrowerCity || undefined,
        borrowerState: form.borrowerState || undefined,
        borrowerEmploymentType: form.borrowerEmploymentType || undefined,
        borrowerMonthlyIncome: form.borrowerMonthlyIncome ? Number(form.borrowerMonthlyIncome) : undefined,
        requestedAmount: Number(form.requestedAmount),
        tenureMonths: Number(form.tenureMonths),
      })

      await loadApplications(filters)
      setSelectedApplicationId(created.id)
      setForm((current) => ({
        ...initialFormState,
        lspId: current.lspId,
        productId: current.productId,
        sourceChannel: current.sourceChannel,
      }))
    } catch (submitError) {
      const message =
        submitError instanceof Error ? submitError.message : 'Unable to create loan application.'
      setPageError(message)
    } finally {
      setSubmitting(false)
    }
  }

  async function handleStatusTransition(targetStatus: LoanApplicationStatus) {
    if (!selectedApplicationId) {
      return
    }

    setTransitioning(true)
    setWorkflowError('')
    setApprovalBlocker(null)

    try {
      await transitionLoanApplicationStatus(selectedApplicationId, {
        targetStatus,
        note: transitionNote.trim() || undefined,
        reasonCode: transitionReasonCode || undefined,
      })

      setTransitionNote('')
      setTransitionReasonCode('')
      await refreshSelectedLoan(selectedApplicationId)
      await loadApplications(filters)
    } catch (submitError) {
      const message =
        submitError instanceof Error ? submitError.message : 'Unable to update loan status.'
      setWorkflowError(message)
      if (targetStatus === 'APPROVED') {
        setApprovalBlocker(extractApprovalBlocker(submitError))
      }
    } finally {
      setTransitioning(false)
    }
  }

  async function handleManualStatusOverride() {
    if (!selectedApplicationId || !manualStatusTarget) {
      return
    }

    setManualStatusSubmitting(true)
    setManualStatusError('')

    try {
      await manuallyOverrideLoanApplicationStatus(selectedApplicationId, {
        targetStatus: manualStatusTarget,
        note: manualStatusNote,
        reasonCode: manualStatusReasonCode,
      })

      setManualStatusNote('')
      setManualStatusReasonCode('MANUAL_ADMIN_OVERRIDE')
      await refreshSelectedLoan(selectedApplicationId)
      await loadApplications(filters)
    } catch (submitError) {
      const message =
        submitError instanceof Error ? submitError.message : 'Unable to manually update loan status.'
      setManualStatusError(message)
    } finally {
      setManualStatusSubmitting(false)
    }
  }

  async function handleAssignment(assigneeUsername?: string | null) {
    if (!selectedApplicationId) {
      return
    }

    setAssigning(true)
    setAssignmentError('')

    try {
      await assignLoanApplication(selectedApplicationId, {
        assigneeUsername,
        note: assignmentNote.trim() || undefined,
      })
      setAssignmentNote('')
      await refreshSelectedLoan(selectedApplicationId)
      await loadApplications(filters)
    } catch (submitError) {
      const message =
        submitError instanceof Error ? submitError.message : 'Unable to update assignment.'
      setAssignmentError(message)
    } finally {
      setAssigning(false)
    }
  }

  async function handleDisbursementRequest() {
    if (!selectedApplicationId) {
      return
    }

    setRequestingDisbursement(true)
    setDisbursementRequestsError('')

    try {
      await initiateLoanApplicationDisbursement(selectedApplicationId)
      await refreshSelectedLoan(selectedApplicationId)
      await loadApplications(filters)
    } catch (submitError) {
      const message =
        submitError instanceof Error ? submitError.message : 'Unable to request disbursement.'
      setDisbursementRequestsError(message)
    } finally {
      setRequestingDisbursement(false)
    }
  }

  async function handleDisbursementOutcome(outcome: MockDisbursementOutcome) {
    if (!selectedApplicationId) {
      return
    }

    setResolvingDisbursementOutcome(true)
    setDisbursementRequestsError('')

    try {
      await applyMockLoanDisbursementOutcome(selectedApplicationId, { outcome })
      await refreshSelectedLoan(selectedApplicationId)
      await loadApplications(filters)
    } catch (submitError) {
      const message =
        submitError instanceof Error ? submitError.message : 'Unable to apply disbursement outcome.'
      setDisbursementRequestsError(message)
    } finally {
      setResolvingDisbursementOutcome(false)
    }
  }

  async function handleRecordPaymentTransaction() {
    if (!selectedApplicationId) {
      return
    }

    setRecordingPayment(true)
    setPaymentTransactionsError('')

    try {
      await recordLoanApplicationPaymentTransaction(selectedApplicationId, {
        amount: Number(paymentCapture.amount),
        paymentDate: paymentCapture.paymentDate,
        reference: paymentCapture.reference.trim(),
        channel: paymentCapture.channel,
        status: paymentCapture.status,
        note: paymentCapture.note.trim() || undefined,
      })
      setPaymentCapture(buildInitialPaymentCaptureState())
      await refreshSelectedLoan(selectedApplicationId)
      await loadApplications(filters)
    } catch (submitError) {
      const message =
        submitError instanceof Error ? submitError.message : 'Unable to record payment transaction.'
      setPaymentTransactionsError(message)
    } finally {
      setRecordingPayment(false)
    }
  }

  async function handleRequestForeclosureQuote() {
    if (!selectedApplicationId) {
      return
    }

    setRequestingForeclosureQuote(true)
    setForeclosureQuoteActionError('')

    try {
      await requestLoanApplicationForeclosureQuote(selectedApplicationId, {
        effectiveDate: foreclosureExecution.settlementDate,
      })
      await refreshSelectedLoan(selectedApplicationId)
      await loadApplications(filters)
    } catch (submitError) {
      const message =
        submitError instanceof Error ? submitError.message : 'Unable to request foreclosure quote.'
      setForeclosureQuoteActionError(message)
    } finally {
      setRequestingForeclosureQuote(false)
    }
  }

  async function handleExecuteForeclosureQuote(quoteId: string) {
    if (!selectedApplicationId) {
      return
    }

    setExecutingForeclosureQuoteId(quoteId)
    setForeclosureQuoteActionError('')

    try {
      await executeLoanApplicationForeclosureQuote(selectedApplicationId, quoteId, {
        settlementDate: foreclosureExecution.settlementDate,
        reference: foreclosureExecution.reference.trim(),
        note: foreclosureExecution.note.trim() || undefined,
      })
      setForeclosureExecution(buildInitialForeclosureQuoteExecutionState())
      await refreshSelectedLoan(selectedApplicationId)
      await loadApplications(filters)
    } catch (submitError) {
      const message =
        submitError instanceof Error ? submitError.message : 'Unable to execute foreclosure quote.'
      setForeclosureQuoteActionError(message)
    } finally {
      setExecutingForeclosureQuoteId('')
    }
  }

  async function handleDocumentPlaceholderSave(placeholderId: string) {
    if (!selectedApplicationId) {
      return
    }

    const placeholder = documentPlaceholders.find((item) => item.id === placeholderId)
    const draft = documentPlaceholderDrafts[placeholderId]
    if (!placeholder || !draft) {
      return
    }

    setDocumentPlaceholderSavingId(placeholderId)
    setDocumentPlaceholdersError('')

    try {
      await updateLoanApplicationDocumentPlaceholder(selectedApplicationId, placeholder.documentType, {
        status: draft.status,
        note: draft.note.trim() || undefined,
        reviewReason: draft.reviewReason.trim() || undefined,
        rejectionReason: draft.rejectionReason.trim() || undefined,
        fileName: draft.fileName.trim() || undefined,
        contentType: draft.contentType.trim() || undefined,
        sourceReference: draft.sourceReference.trim() || undefined,
      })
      setDocumentPlaceholdersLoading(true)
      await refreshDocumentPlaceholders(selectedApplicationId)
    } catch (submitError) {
      const message =
        submitError instanceof Error ? submitError.message : 'Unable to update document placeholder.'
      setDocumentPlaceholdersError(message)
    } finally {
      setDocumentPlaceholderSavingId('')
    }
  }

  return (
    <div className="users-layout">
      <Card className="list-card">
        <CardHeader>
          <div className="section-eyebrow">Loan intake</div>
          <CardTitle>Received applications</CardTitle>
          <CardDescription>
            Phase 4 foundation: capture borrower-linked intake records and review newly received
            applications.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <div className="inline-actions" style={{ marginBottom: '1rem' }}>
            <Badge>{applications.length} applications</Badge>
            <Badge variant="warning">{activeLsps.length} active LSPs</Badge>
            <Badge variant="success">{activeProducts.length} active products</Badge>
            <Badge variant={revealSensitiveData ? 'destructive' : 'warning'}>
              {revealSensitiveData ? 'Sensitive data visible' : 'Sensitive data masked'}
            </Badge>
            {canRevealSensitiveData ? (
              <Button
                onClick={() => setShowSensitiveData((current) => !current)}
                size="sm"
                type="button"
                variant="outline"
              >
                {revealSensitiveData ? 'Hide sensitive data' : 'Show sensitive data'}
              </Button>
            ) : null}
          </div>
          <div className="form-grid" style={{ marginBottom: '1rem' }}>
            <div className="field-stack">
              <label htmlFor="filter-lsp">Filter by LSP</label>
              <select
                id="filter-lsp"
                className="ui-input"
                value={filters.lspId}
                onChange={(event) =>
                  setFilters((current) => ({ ...current, lspId: event.target.value }))
                }
              >
                <option value="">All LSPs</option>
                {activeLsps.map((lsp) => (
                  <option key={lsp.id} value={lsp.id}>
                    {lsp.code} - {lsp.name}
                  </option>
                ))}
              </select>
            </div>
            <div className="field-stack">
              <label htmlFor="filter-product">Filter by product</label>
              <select
                id="filter-product"
                className="ui-input"
                value={filters.productId}
                onChange={(event) =>
                  setFilters((current) => ({ ...current, productId: event.target.value }))
                }
              >
                <option value="">All products</option>
                {activeProducts.map((product) => (
                  <option key={product.id} value={product.id}>
                    {product.code} - {product.name}
                  </option>
                ))}
              </select>
            </div>
            <div className="field-stack">
              <label htmlFor="filter-query">Search</label>
              <Input
                id="filter-query"
                value={filters.query}
                onChange={(event) =>
                  setFilters((current) => ({ ...current, query: event.target.value }))
                }
                placeholder="Application id, borrower, PAN, mobile, external loan id"
              />
            </div>
            <div className="field-stack">
              <label htmlFor="filter-status">Filter by status</label>
              <select
                id="filter-status"
                className="ui-input"
                value={filters.status}
                onChange={(event) =>
                  setFilters((current) => ({ ...current, status: event.target.value }))
                }
              >
                <option value="">All statuses</option>
                {loanApplicationStatusOptions.map((status) => (
                  <option key={status} value={status}>
                    {loanStatusLabel(status)}
                  </option>
                ))}
              </select>
            </div>
            <div className="field-stack">
              <label htmlFor="filter-source-channel">Filter by source</label>
              <select
                id="filter-source-channel"
                className="ui-input"
                value={filters.sourceChannel}
                onChange={(event) =>
                  setFilters((current) => ({ ...current, sourceChannel: event.target.value }))
                }
              >
                <option value="">All sources</option>
                {sourceChannelOptions.map((sourceChannel) => (
                  <option key={sourceChannel} value={sourceChannel}>
                    {sourceChannel}
                  </option>
                ))}
              </select>
            </div>
            <div className="field-stack">
              <label htmlFor="filter-disbursal-date-from">Disbursal date from</label>
              <input
                id="filter-disbursal-date-from"
                className="ui-input"
                type="date"
                value={filters.disbursalDateFrom}
                onChange={(event) =>
                  setFilters((current) => ({ ...current, disbursalDateFrom: event.target.value }))
                }
              />
            </div>
            <div className="field-stack">
              <label htmlFor="filter-disbursal-date-to">Disbursal date to</label>
              <input
                id="filter-disbursal-date-to"
                className="ui-input"
                type="date"
                value={filters.disbursalDateTo}
                onChange={(event) =>
                  setFilters((current) => ({ ...current, disbursalDateTo: event.target.value }))
                }
              />
            </div>
          </div>
          {loading ? <div className="empty-state">Loading loan applications...</div> : null}
          {pageError ? <div className="empty-state">{pageError}</div> : null}
          {!loading && !pageError ? (
            <div className="table-grid">
              {applications.map((application) => (
                <button
                  className="table-row"
                  key={application.id}
                  onClick={() => setSelectedApplicationId(application.id)}
                  style={{
                    cursor: 'pointer',
                    border:
                      application.id === selectedApplicationId
                        ? '1px solid var(--color-accent)'
                        : undefined,
                    background:
                      application.id === selectedApplicationId
                        ? 'color-mix(in srgb, var(--color-accent) 10%, transparent)'
                        : undefined,
                    textAlign: 'left',
                    width: '100%',
                  }}
                  type="button"
                >
                  <div>
                    <strong>{application.borrowerFullName}</strong>
                    <p className="helper-copy">
                      {formatBorrowerPan(application.borrowerPan, revealSensitiveData)} -{' '}
                      {formatBorrowerMobile(application.borrowerMobile, revealSensitiveData)}
                    </p>
                  </div>
                  <Badge variant={loanStatusVariant(application.status as LoanApplicationStatus)}>
                    {loanStatusLabel(application.status as LoanApplicationStatus)}
                  </Badge>
                  <span>{currencyLabel(application.requestedAmount)}</span>
                  <span>{application.tenureMonths} months</span>
                  <span className="helper-copy">
                    {application.lspCode} - {application.productCode}
                  </span>
                  <span className="helper-copy">Application {application.id}</span>
                  <span className="helper-copy">{application.externalLoanId}</span>
                  <span className="helper-copy">
                    {application.assignedToUsername ? `Owner ${application.assignedToUsername}` : 'Unassigned'}
                  </span>
                  <span className="helper-copy">{formatTimestamp(application.createdAt)}</span>
                </button>
              ))}
              {!applications.length ? (
                <div className="empty-state">No loan applications matched the current filters.</div>
              ) : null}
            </div>
          ) : null}
        </CardContent>
      </Card>

      <div className="form-stack">
        <Card>
          <CardHeader>
            <div className="section-eyebrow">Create intake</div>
            <CardTitle>Register a loan application</CardTitle>
            <CardDescription>
              Use this internal intake form to create the borrower and application foundation for
              Phase 4.
            </CardDescription>
          </CardHeader>
          <CardContent>
            <form className="form-grid" onSubmit={handleSubmit}>
              <div className="field-stack">
                <label htmlFor="intake-lsp">LSP</label>
                <select
                  id="intake-lsp"
                  className="ui-input"
                  value={form.lspId}
                  onChange={(event) =>
                    setForm((current) => ({ ...current, lspId: event.target.value }))
                  }
                >
                  <option value="">Select an LSP</option>
                  {activeLsps.map((lsp) => (
                    <option key={lsp.id} value={lsp.id}>
                      {lsp.code} - {lsp.name}
                    </option>
                  ))}
                </select>
              </div>
              <div className="field-stack">
                <label htmlFor="intake-product">Product</label>
                <select
                  id="intake-product"
                  className="ui-input"
                  value={form.productId}
                  onChange={(event) =>
                    setForm((current) => ({ ...current, productId: event.target.value }))
                  }
                >
                  <option value="">Select a product</option>
                  {activeProducts.map((product) => (
                    <option key={product.id} value={product.id}>
                      {product.code} - {product.name}
                    </option>
                  ))}
                </select>
              </div>
              <div className="field-stack">
                <label htmlFor="external-loan-id">External loan id</label>
                <Input
                  id="external-loan-id"
                  value={form.externalLoanId}
                  onChange={(event) =>
                    setForm((current) => ({ ...current, externalLoanId: event.target.value }))
                  }
                  placeholder="EXT-1001"
                />
              </div>
              <div className="field-stack">
                <label htmlFor="source-channel">Source channel</label>
                <Input
                  id="source-channel"
                  value={form.sourceChannel}
                  onChange={(event) =>
                    setForm((current) => ({
                      ...current,
                      sourceChannel: event.target.value.toUpperCase(),
                    }))
                  }
                  placeholder="PARTNER_PORTAL"
                />
              </div>
              <div className="field-stack">
                <label htmlFor="borrower-pan">Borrower PAN</label>
                <Input
                  id="borrower-pan"
                  value={form.borrowerPan}
                  onChange={(event) =>
                    setForm((current) => ({
                      ...current,
                      borrowerPan: event.target.value.toUpperCase(),
                    }))
                  }
                  placeholder="ABCDE1234F"
                />
              </div>
              <div className="field-stack">
                <label htmlFor="borrower-name">Borrower name</label>
                <Input
                  id="borrower-name"
                  value={form.borrowerFullName}
                  onChange={(event) =>
                    setForm((current) => ({ ...current, borrowerFullName: event.target.value }))
                  }
                  placeholder="Anika Sharma"
                />
              </div>
              <div className="field-stack">
                <label htmlFor="borrower-mobile">Borrower mobile</label>
                <Input
                  id="borrower-mobile"
                  value={form.borrowerMobile}
                  onChange={(event) =>
                    setForm((current) => ({ ...current, borrowerMobile: event.target.value }))
                  }
                  placeholder="9999999999"
                />
              </div>
              <div className="field-stack">
                <label htmlFor="borrower-email">Borrower email</label>
                <Input
                  id="borrower-email"
                  value={form.borrowerEmail}
                  onChange={(event) =>
                    setForm((current) => ({ ...current, borrowerEmail: event.target.value }))
                  }
                  placeholder="anika@example.com"
                />
              </div>
              <div className="section-divider">
                <div className="section-eyebrow">Borrower profile</div>
                <p className="helper-copy">
                  Optional enrichment that makes the selected-loan view more borrower-centric.
                </p>
              </div>
              <div className="borrower-enrichment-grid">
                <div className="field-stack">
                  <label htmlFor="borrower-dob">Date of birth</label>
                  <Input
                    id="borrower-dob"
                    type="date"
                    value={form.borrowerDateOfBirth}
                    onChange={(event) =>
                      setForm((current) => ({ ...current, borrowerDateOfBirth: event.target.value }))
                    }
                  />
                </div>
                <div className="field-stack">
                  <label htmlFor="borrower-employment">Employment type</label>
                  <select
                    id="borrower-employment"
                    className="ui-input"
                    value={form.borrowerEmploymentType}
                    onChange={(event) =>
                      setForm((current) => ({
                        ...current,
                        borrowerEmploymentType: event.target.value,
                      }))
                    }
                  >
                    <option value="">Select employment type</option>
                    {borrowerEmploymentTypeOptions.map((employmentType) => (
                      <option key={employmentType} value={employmentType}>
                        {formatEmploymentType(employmentType)}
                      </option>
                    ))}
                  </select>
                </div>
                <div className="field-stack">
                  <label htmlFor="borrower-city">City</label>
                  <Input
                    id="borrower-city"
                    value={form.borrowerCity}
                    onChange={(event) =>
                      setForm((current) => ({ ...current, borrowerCity: event.target.value }))
                    }
                    placeholder="Pune"
                  />
                </div>
                <div className="field-stack">
                  <label htmlFor="borrower-state">State</label>
                  <Input
                    id="borrower-state"
                    value={form.borrowerState}
                    onChange={(event) =>
                      setForm((current) => ({ ...current, borrowerState: event.target.value }))
                    }
                    placeholder="Maharashtra"
                  />
                </div>
                <div className="field-stack">
                  <label htmlFor="borrower-income">Monthly income</label>
                  <Input
                    id="borrower-income"
                    type="number"
                    min="0"
                    step="1"
                    value={form.borrowerMonthlyIncome}
                    onChange={(event) =>
                      setForm((current) => ({
                        ...current,
                        borrowerMonthlyIncome: event.target.value,
                      }))
                    }
                    placeholder="75000"
                  />
                </div>
              </div>
              <div className="field-stack">
                <label htmlFor="requested-amount">Requested amount</label>
                <Input
                  id="requested-amount"
                  type="number"
                  min="0"
                  step="0.01"
                  value={form.requestedAmount}
                  onChange={(event) =>
                    setForm((current) => ({ ...current, requestedAmount: event.target.value }))
                  }
                />
              </div>
              <div className="field-stack">
                <label htmlFor="tenure-months">Tenure months</label>
                <Input
                  id="tenure-months"
                  type="number"
                  min="1"
                  step="1"
                  value={form.tenureMonths}
                  onChange={(event) =>
                    setForm((current) => ({ ...current, tenureMonths: event.target.value }))
                  }
                />
              </div>
              {pageError ? <div className="empty-state">{pageError}</div> : null}
              <div className="inline-actions">
                <Button disabled={submitting} type="submit">
                  {submitting ? 'Creating...' : 'Create application'}
                </Button>
              </div>
            </form>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <div className="section-eyebrow">Loan workflow</div>
            <CardTitle>Selected loan detail and status</CardTitle>
            <CardDescription>
              Review the selected application, move it through the first review lifecycle, and
              inspect the status history.
            </CardDescription>
          </CardHeader>
          <CardContent>
            {visibleSelectedApplication ? (
              <div className="loan-workflow">
                <div className="loan-workflow__summary borrower-summary">
                  <div className="borrower-summary__identity">
                    <div className="inline-actions">
                      <Badge variant={loanStatusVariant(visibleSelectedApplication.status as LoanApplicationStatus)}>
                        {loanStatusLabel(visibleSelectedApplication.status as LoanApplicationStatus)}
                      </Badge>
                      <Badge>{visibleSelectedApplication.externalLoanId}</Badge>
                      <Badge variant={borrowerProfileCompleteness >= 5 ? 'success' : 'warning'}>
                        {borrowerProfileCompleteness}/5 profile fields
                      </Badge>
                    </div>
                    <h3>{visibleSelectedApplication.borrowerFullName}</h3>
                    <p className="helper-copy">
                      {formatBorrowerPan(visibleSelectedApplication.borrowerPan, revealSensitiveData)} -{' '}
                      {formatBorrowerMobile(
                        visibleSelectedApplication.borrowerMobile,
                        revealSensitiveData,
                      )}
                      {' '}| {formatBorrowerEmail(
                        visibleSelectedApplication.borrowerEmail,
                        revealSensitiveData,
                      )}
                    </p>
                    <div className="borrower-summary__meta">
                      <span>{formatDateLabel(visibleSelectedApplication.borrowerDateOfBirth)} / {formatAgeLabel(visibleSelectedApplication.borrowerDateOfBirth)}</span>
                      <span>{borrowerLocation}</span>
                      <span>{borrowerEmployment}</span>
                    </div>
                  </div>
                  <div className="loan-workflow__headline-metrics">
                    <div className="loan-workflow__metric">
                      <span>Monthly income</span>
                      <strong>{formatIncomeLabel(visibleSelectedApplication.borrowerMonthlyIncome)}</strong>
                    </div>
                    <div className="loan-workflow__metric">
                      <span>Requested amount</span>
                      <strong>{currencyLabel(visibleSelectedApplication.requestedAmount)}</strong>
                    </div>
                    <div className="loan-workflow__metric">
                      <span>Income coverage</span>
                      <strong>{borrowerIncomeCoverage}</strong>
                    </div>
                  </div>
                </div>

                <div className="detail-section">
                  <div className="section-eyebrow">Latest activity</div>
                  <div className="loan-detail-grid">
                    <div className="loan-detail-field">
                      <span>Activity type</span>
                      <strong>{loanActivityTypeLabel(lastActivity?.activityType)}</strong>
                    </div>
                    <div className="loan-detail-field">
                      <span>Last modified by</span>
                      <strong>{lastActivity?.actorUsername || 'Not captured'}</strong>
                    </div>
                    <div className="loan-detail-field">
                      <span>Last modified at</span>
                      <strong>
                        {lastActivity?.occurredAt ? formatTimestamp(lastActivity.occurredAt) : 'Not captured'}
                      </strong>
                    </div>
                    <div className="loan-detail-field">
                      <span>Correlation ID</span>
                      <strong>{lastActivity?.correlationId || 'Not captured'}</strong>
                    </div>
                  </div>
                  <div className="loan-transition-panel" style={{ marginTop: '1rem' }}>
                    <div className="loan-transition-panel__header">
                      <div>
                        <h4>{lastActivity?.summary || 'No recent workflow activity yet'}</h4>
                        <p className="helper-copy">
                          {lastActivity?.detail || 'The latest workflow mutation will appear here once review activity starts.'}
                        </p>
                      </div>
                      <Badge>{visibleSelectedApplication.sourceChannel}</Badge>
                    </div>
                  </div>
                </div>

                <div className="detail-section">
                  <div className="section-eyebrow">Borrower profile</div>
                  <div className="loan-detail-grid">
                    <div className="loan-detail-field">
                      <span>Date of birth</span>
                      <strong>{formatDateLabel(visibleSelectedApplication.borrowerDateOfBirth)}</strong>
                    </div>
                    <div className="loan-detail-field">
                      <span>Borrower PAN</span>
                      <strong>
                        {formatBorrowerPan(visibleSelectedApplication.borrowerPan, revealSensitiveData)}
                      </strong>
                    </div>
                    <div className="loan-detail-field">
                      <span>Borrower mobile</span>
                      <strong>
                        {formatBorrowerMobile(
                          visibleSelectedApplication.borrowerMobile,
                          revealSensitiveData,
                        )}
                      </strong>
                    </div>
                    <div className="loan-detail-field">
                      <span>Employment type</span>
                      <strong>{borrowerEmployment}</strong>
                    </div>
                    <div className="loan-detail-field">
                      <span>City</span>
                      <strong>{visibleSelectedApplication.borrowerCity || 'Not provided'}</strong>
                    </div>
                    <div className="loan-detail-field">
                      <span>State</span>
                      <strong>{visibleSelectedApplication.borrowerState || 'Not provided'}</strong>
                    </div>
                    <div className="loan-detail-field">
                      <span>Monthly income</span>
                      <strong>{formatIncomeLabel(visibleSelectedApplication.borrowerMonthlyIncome)}</strong>
                    </div>
                    <div className="loan-detail-field">
                      <span>Borrower email</span>
                      <strong>
                        {formatBorrowerEmail(visibleSelectedApplication.borrowerEmail, revealSensitiveData)}
                      </strong>
                    </div>
                  </div>
                </div>

                <div className="detail-section">
                  <div className="section-eyebrow">KYC checklist</div>
                  <div className="loan-transition-panel">
                    <div className="loan-transition-panel__header">
                      <div>
                        <h4>Document placeholders</h4>
                        <p className="helper-copy">
                          Track the per-loan KYC checklist and document metadata until live upload handling is added.
                        </p>
                      </div>
                      <Badge
                        variant={
                          sortedDocumentPlaceholders.length > 0 &&
                          verifiedDocumentCount === sortedDocumentPlaceholders.length
                            ? 'success'
                            : 'warning'
                        }
                      >
                        {verifiedDocumentCount}/{sortedDocumentPlaceholders.length || 0} ready
                      </Badge>
                      </div>
                    <div className="loan-workflow__headline-metrics">
                      <div className="loan-workflow__metric">
                        <span>Required</span>
                        <strong>{requiredDocumentCount}</strong>
                        </div>
                        <div className="loan-workflow__metric">
                          <span>Metadata tagged</span>
                          <strong>{metadataDocumentCount}</strong>
                        </div>
                      <div className="loan-workflow__metric">
                        <span>Awaiting action</span>
                        <strong>{receivedDocumentCount + pendingDocumentCount}</strong>
                      </div>
                    </div>
                    {!kycApprovalReady ? (
                      <div className="loan-alert loan-alert--warning">
                        <div className="loan-alert__title">Approval blocked by KYC</div>
                        <p className="helper-copy">
                          {incompleteRequiredDocumentLabels.length
                            ? `Required documents still need review: ${incompleteRequiredDocumentLabels.join(', ')}.`
                            : 'Required KYC documents are still incomplete.'}
                        </p>
                        <p className="helper-copy">
                          Approval will remain blocked until each required item is marked Verified or Not required.
                        </p>
                      </div>
                    ) : null}
                    {documentPlaceholdersLoading ? <div className="empty-state">Loading document placeholders...</div> : null}
                    {documentPlaceholdersError ? <div className="empty-state">{documentPlaceholdersError}</div> : null}
                    {!documentPlaceholdersLoading && !documentPlaceholdersError && !sortedDocumentPlaceholders.length ? (
                      <div className="empty-state">No KYC document placeholders are configured for this loan yet.</div>
                    ) : null}
                      {!documentPlaceholdersLoading && !documentPlaceholdersError && sortedDocumentPlaceholders.length ? (
                        <div className="loan-checklist">
                          {sortedDocumentPlaceholders.map((placeholder) => {
                              const draft = documentPlaceholderDrafts[placeholder.id] ?? {
                                status: placeholder.status,
                                note: placeholder.note ?? '',
                                reviewReason: placeholder.reviewReason ?? '',
                                rejectionReason: placeholder.rejectionReason ?? '',
                                fileName: placeholder.fileName ?? '',
                                contentType: placeholder.contentType ?? '',
                                sourceReference: placeholder.sourceReference ?? '',
                            }
                            const isSaving = documentPlaceholderSavingId === placeholder.id
                            const metadataIsPresent = Boolean(
                              placeholder.fileName?.trim() ||
                                placeholder.contentType?.trim() ||
                                placeholder.sourceReference?.trim(),
                            )

                            return (
                              <div className="loan-checklist__item" key={placeholder.id}>
                                <div className="loan-checklist__body">
                                  <div className="inline-actions">
                                    <strong>{placeholder.documentDisplayName}</strong>
                                    <Badge variant={placeholder.required ? 'destructive' : 'default'}>
                                      {placeholder.required ? 'Required' : 'Optional'}
                                    </Badge>
                                    <Badge variant={loanDocumentPlaceholderStatusVariant(draft.status)}>
                                      {loanDocumentPlaceholderStatusLabel(draft.status)}
                                    </Badge>
                                  </div>
                                  <div className="loan-checklist__meta-grid">
                                    <div className="loan-detail-field">
                                      <span>File name</span>
                                      <strong>{formatMetadataValue(placeholder.fileName)}</strong>
                                    </div>
                                    <div className="loan-detail-field">
                                      <span>Content type</span>
                                      <strong>{formatMetadataValue(placeholder.contentType)}</strong>
                                    </div>
                                    <div className="loan-detail-field">
                                      <span>Source / reference</span>
                                      <strong>{formatMetadataValue(placeholder.sourceReference)}</strong>
                                    </div>
                                    <div className="loan-detail-field">
                                      <span>Uploaded at</span>
                                      <strong>{formatOptionalTimestamp(placeholder.uploadedAt)}</strong>
                                    </div>
                                    <div className="loan-detail-field">
                                      <span>Uploaded by</span>
                                      <strong>{formatMetadataValue(placeholder.uploadedByUsername)}</strong>
                                    </div>
                                    <div className="loan-detail-field">
                                      <span>Metadata state</span>
                                      <strong>{metadataIsPresent ? 'Captured' : 'Awaiting metadata'}</strong>
                                    </div>
                                  </div>
                                  <p className="helper-copy">
                                    {placeholder.updatedAt
                                      ? `Updated ${formatTimestamp(placeholder.updatedAt)} by ${placeholder.updatedByUsername ?? 'system'}`
                                      : 'Awaiting first review.'}
                                  </p>
                                  <p className="helper-copy">
                                    <strong>{loanDocumentPlaceholderReasonLabel(draft.status)}:</strong>{' '}
                                    {(draft.status === 'REJECTED'
                                      ? placeholder.rejectionReason
                                      : placeholder.reviewReason)?.trim()
                                      ? draft.status === 'REJECTED'
                                        ? placeholder.rejectionReason
                                        : placeholder.reviewReason
                                      : loanDocumentPlaceholderReasonFallback(draft.status)}
                                  </p>
                                  <p className="helper-copy">
                                    <strong>Ops note:</strong>{' '}
                                    {placeholder.note?.trim()
                                      ? placeholder.note
                                      : 'No general note recorded.'}
                                  </p>
                                </div>
                                <div className="loan-checklist__controls">
                                  <div className="field-stack">
                                    <label htmlFor={`placeholder-status-${placeholder.id}`}>Status</label>
                                  <select
                                    id={`placeholder-status-${placeholder.id}`}
                                    className="ui-input ui-select"
                                    value={draft.status}
                                    onChange={(event) =>
                                      setDocumentPlaceholderDrafts((current) => ({
                                        ...current,
                                        [placeholder.id]: {
                                          ...(current[placeholder.id] ?? draft),
                                          status: event.target.value as LoanApplicationDocumentPlaceholderStatus,
                                        },
                                      }))
                                    }
                                  >
                                      {loanApplicationDocumentPlaceholderStatusOptions.map((option) => (
                                        <option key={option} value={option}>
                                          {loanDocumentPlaceholderStatusLabel(option)}
                                        </option>
                                      ))}
                                    </select>
                                  </div>
                                  <div className="field-stack">
                                    <label htmlFor={`placeholder-file-${placeholder.id}`}>File name</label>
                                    <Input
                                      id={`placeholder-file-${placeholder.id}`}
                                      value={draft.fileName}
                                      onChange={(event) =>
                                        setDocumentPlaceholderDrafts((current) => ({
                                          ...current,
                                          [placeholder.id]: {
                                            ...(current[placeholder.id] ?? draft),
                                            fileName: event.target.value,
                                          },
                                        }))
                                      }
                                      placeholder="e.g. pan-card.pdf"
                                    />
                                  </div>
                                  <div className="field-stack">
                                    <label htmlFor={`placeholder-content-${placeholder.id}`}>Content type</label>
                                    <Input
                                      id={`placeholder-content-${placeholder.id}`}
                                      value={draft.contentType}
                                      onChange={(event) =>
                                        setDocumentPlaceholderDrafts((current) => ({
                                          ...current,
                                          [placeholder.id]: {
                                            ...(current[placeholder.id] ?? draft),
                                            contentType: event.target.value,
                                          },
                                        }))
                                      }
                                      placeholder="e.g. application/pdf"
                                    />
                                  </div>
                                  <div className="field-stack">
                                    <label htmlFor={`placeholder-reference-${placeholder.id}`}>Source / reference</label>
                                    <Input
                                      id={`placeholder-reference-${placeholder.id}`}
                                      value={draft.sourceReference}
                                      onChange={(event) =>
                                        setDocumentPlaceholderDrafts((current) => ({
                                          ...current,
                                          [placeholder.id]: {
                                            ...(current[placeholder.id] ?? draft),
                                            sourceReference: event.target.value,
                                          },
                                        }))
                                      }
                                      placeholder="e.g. DigiLocker ref or storage key"
                                    />
                                  </div>
                                  <div className="field-stack">
                                    <label htmlFor={`placeholder-reason-${placeholder.id}`}>
                                      {loanDocumentPlaceholderReasonLabel(draft.status)}
                                    </label>
                                    <textarea
                                      id={`placeholder-reason-${placeholder.id}`}
                                      className="ui-textarea"
                                      value={
                                        draft.status === 'REJECTED'
                                          ? draft.rejectionReason
                                          : draft.reviewReason
                                      }
                                      onChange={(event) =>
                                        setDocumentPlaceholderDrafts((current) => ({
                                          ...current,
                                          [placeholder.id]: {
                                            ...(current[placeholder.id] ?? draft),
                                            reviewReason:
                                              draft.status === 'REJECTED'
                                                ? (current[placeholder.id] ?? draft).reviewReason
                                                : event.target.value,
                                            rejectionReason:
                                              draft.status === 'REJECTED'
                                                ? event.target.value
                                                : (current[placeholder.id] ?? draft).rejectionReason,
                                          },
                                        }))
                                      }
                                      placeholder={loanDocumentPlaceholderReasonPlaceholder(draft.status)}
                                      rows={3}
                                    />
                                  </div>
                                  <div className="field-stack">
                                    <label htmlFor={`placeholder-note-${placeholder.id}`}>
                                      Ops note
                                    </label>
                                    <textarea
                                      id={`placeholder-note-${placeholder.id}`}
                                      className="ui-textarea"
                                      value={draft.note}
                                      onChange={(event) =>
                                        setDocumentPlaceholderDrafts((current) => ({
                                          ...current,
                                          [placeholder.id]: {
                                            ...(current[placeholder.id] ?? draft),
                                            note: event.target.value,
                                          },
                                        }))
                                      }
                                      placeholder="Optional general note for handoff or review context."
                                      rows={3}
                                    />
                                  </div>
                                  <div className="loan-checklist__actions">
                                    <Button
                                    disabled={isSaving}
                                    onClick={() => void handleDocumentPlaceholderSave(placeholder.id)}
                                    type="button"
                                    variant="secondary"
                                    size="sm"
                                  >
                                      {isSaving ? 'Saving...' : 'Save'}
                                    </Button>
                                  </div>
                                </div>
                              </div>
                          )
                        })}
                      </div>
                    ) : null}
                  </div>
                </div>

                <div className="detail-section">
                  <div className="section-eyebrow">Queue ownership</div>
                  <div className="loan-transition-panel">
                    <div className="loan-transition-panel__header">
                      <div>
                        <h4>Assignment</h4>
                        <p className="helper-copy">
                          Claim the case or release it back to the shared review queue.
                        </p>
                      </div>
                      <Badge variant={visibleSelectedApplication.assignedToUsername ? 'default' : 'warning'}>
                        {visibleSelectedApplication.assignedToUsername || 'Unassigned'}
                      </Badge>
                    </div>
                    <div className="loan-assignment-summary">
                      <div className="loan-detail-field">
                        <span>Assigned to</span>
                        <strong>{visibleSelectedApplication.assignedToUsername || 'Shared queue'}</strong>
                      </div>
                      <div className="loan-detail-field">
                        <span>Assigned by</span>
                        <strong>{visibleSelectedApplication.assignedByUsername || 'Not captured'}</strong>
                      </div>
                      <div className="loan-detail-field">
                        <span>Assigned at</span>
                        <strong>
                          {visibleSelectedApplication.assignedAt
                            ? formatTimestamp(visibleSelectedApplication.assignedAt)
                            : 'Not assigned'}
                        </strong>
                      </div>
                    </div>
                    <div className="field-stack">
                      <label htmlFor="assignment-note">Assignment note</label>
                      <textarea
                        id="assignment-note"
                        className="ui-textarea"
                        value={assignmentNote}
                        onChange={(event) => setAssignmentNote(event.target.value)}
                        placeholder="Add context for the handoff. This is optional."
                        rows={3}
                      />
                    </div>
                    {assignmentError ? <div className="empty-state">{assignmentError}</div> : null}
                    <div className="loan-transition-actions">
                      <Button
                        disabled={assigning || !user?.username}
                        onClick={() => void handleAssignment(user?.username)}
                        type="button"
                        variant="secondary"
                      >
                        {assigning ? 'Updating...' : 'Assign to me'}
                      </Button>
                      <Button
                        disabled={assigning || !visibleSelectedApplication.assignedToUsername}
                        onClick={() => void handleAssignment(null)}
                        type="button"
                        variant="outline"
                      >
                        {assigning ? 'Updating...' : 'Release'}
                      </Button>
                    </div>
                    <div className="loan-history">
                      <div className="loan-history__header">
                        <div className="section-eyebrow">Assignment history</div>
                        <Badge>{selectedAssignmentHistory.length} events</Badge>
                      </div>
                      {!selectedAssignmentHistory.length ? (
                        <div className="empty-state">No assignment events recorded yet.</div>
                      ) : (
                        <div className="loan-history__list">
                          {selectedAssignmentHistory.map((event) => (
                            <div className="loan-history__item" key={event.id}>
                              <div>
                                <strong>
                                  {event.fromAssigneeUsername || 'Shared queue'} - {event.toAssigneeUsername || 'Shared queue'}
                                </strong>
                                <p className="helper-copy">{formatTimestamp(event.createdAt)}</p>
                                <p className="helper-copy">{formatNote(event.note)}</p>
                                <p className="helper-copy">
                                  Correlation ID: {event.correlationId ?? 'Not captured'}
                                </p>
                              </div>
                              <div className="inline-actions">
                                <Badge>{event.actorUsername}</Badge>
                              </div>
                            </div>
                          ))}
                        </div>
                      )}
                    </div>
                  </div>
                </div>

                <div className="detail-section">
                  <div className="section-eyebrow">Application context</div>
                  <div className="loan-detail-grid">
                    <div className="loan-detail-field">
                      <span>Application id</span>
                      <strong>{visibleSelectedApplication.id}</strong>
                    </div>
                    <div className="loan-detail-field">
                      <span>LSP</span>
                      <strong>
                        {visibleSelectedApplication.lspCode} - {visibleSelectedApplication.lspName}
                      </strong>
                    </div>
                    <div className="loan-detail-field">
                      <span>Product</span>
                      <strong>
                        {visibleSelectedApplication.productCode} - {visibleSelectedApplication.productName}
                      </strong>
                    </div>
                    <div className="loan-detail-field">
                      <span>Source channel</span>
                      <strong>{visibleSelectedApplication.sourceChannel}</strong>
                    </div>
                    <div className="loan-detail-field">
                      <span>Requested amount</span>
                      <strong>{currencyLabel(visibleSelectedApplication.requestedAmount)}</strong>
                    </div>
                    <div className="loan-detail-field">
                      <span>Tenure</span>
                      <strong>{visibleSelectedApplication.tenureMonths} months</strong>
                    </div>
                  </div>
                  {selectedLoan?.loanAccount ? (
                    <div className="loan-history" style={{ marginTop: '1.5rem' }}>
                      <div className="loan-history__header">
                        <div>
                          <div className="section-eyebrow">Servicing account</div>
                          <p className="helper-copy">
                            Created automatically when the application is approved.
                          </p>
                        </div>
                        <div className="inline-actions">
                          <Badge variant={loanAccountStatusVariant(selectedLoan.loanAccount.status)}>
                            {loanAccountStatusLabel(selectedLoan.loanAccount.status)}
                          </Badge>
                          {user?.roles?.includes('SYSTEM_ADMIN') ? (
                            <Button
                              disabled={!canInitiateDisbursement || requestingDisbursement}
                              onClick={() => void handleDisbursementRequest()}
                              type="button"
                              variant="secondary"
                            >
                              {requestingDisbursement ? 'Requesting...' : 'Request disbursement'}
                            </Button>
                          ) : null}
                        </div>
                      </div>
                      {canResolveDisbursementOutcome ? (
                        <div className="loan-transition-actions" style={{ marginBottom: '1rem' }}>
                          <Button
                            disabled={resolvingDisbursementOutcome}
                            onClick={() => void handleDisbursementOutcome('DISBURSED')}
                            type="button"
                            variant="secondary"
                          >
                            {resolvingDisbursementOutcome ? 'Updating...' : 'Mark disbursed'}
                          </Button>
                          <Button
                            disabled={resolvingDisbursementOutcome}
                            onClick={() => void handleDisbursementOutcome('FAILED')}
                            type="button"
                            variant="outline"
                          >
                            {resolvingDisbursementOutcome ? 'Updating...' : 'Mark failed'}
                          </Button>
                          <Button
                            disabled={resolvingDisbursementOutcome}
                            onClick={() => void handleDisbursementOutcome('PENDING_RECONCILIATION')}
                            type="button"
                            variant="outline"
                          >
                            {resolvingDisbursementOutcome ? 'Updating...' : 'Mark pending reconciliation'}
                          </Button>
                        </div>
                      ) : null}
                      {(selectedLoan.loanAccount.closureReason ||
                        selectedLoan.loanAccount.closedAt ||
                        selectedLoan.loanAccount.closedByUsername) ? (
                        <div className="loan-alert loan-alert--warning">
                          <div className="loan-alert__title">Closure metadata</div>
                          <p className="helper-copy">
                            Reason: {loanClosureReasonLabel(selectedLoan.loanAccount.closureReason)}
                          </p>
                          <p className="helper-copy">
                            Closed at: {selectedLoan.loanAccount.closedAt ? formatTimestamp(selectedLoan.loanAccount.closedAt) : 'Not closed'}
                          </p>
                          <p className="helper-copy">
                            Closed by: {selectedLoan.loanAccount.closedByUsername ?? 'Not captured'}
                          </p>
                        </div>
                      ) : null}
                      <div className="loan-detail-grid">
                        <div className="loan-detail-field">
                          <span>Account number</span>
                          <strong>{selectedLoan.loanAccount.accountNumber}</strong>
                        </div>
                        <div className="loan-detail-field">
                          <span>Principal amount</span>
                          <strong>{currencyLabel(selectedLoan.loanAccount.principalAmount)}</strong>
                        </div>
                        <div className="loan-detail-field">
                          <span>Tenure</span>
                          <strong>{selectedLoan.loanAccount.tenureMonths} months</strong>
                        </div>
                        <div className="loan-detail-field">
                          <span>Approved at</span>
                          <strong>{formatTimestamp(selectedLoan.loanAccount.approvedAt)}</strong>
                        </div>
                        <div className="loan-detail-field">
                          <span>Closure reason</span>
                          <strong>{loanClosureReasonLabel(selectedLoan.loanAccount.closureReason)}</strong>
                        </div>
                        <div className="loan-detail-field">
                          <span>Closed at</span>
                          <strong>
                            {selectedLoan.loanAccount.closedAt
                              ? formatTimestamp(selectedLoan.loanAccount.closedAt)
                              : 'Not closed'}
                          </strong>
                        </div>
                        <div className="loan-detail-field">
                          <span>Closed by</span>
                          <strong>{selectedLoan.loanAccount.closedByUsername ?? 'Not captured'}</strong>
                        </div>
                        <div className="loan-detail-field">
                          <span>Scheduled installments</span>
                          <strong>
                            {selectedLoan.loanAccount.repaymentSchedule?.installmentCount ?? 0}
                          </strong>
                        </div>
                        <div className="loan-detail-field">
                          <span>Monthly EMI</span>
                          <strong>
                            {selectedLoan.loanAccount.repaymentSchedule
                              ? currencyLabel(selectedLoan.loanAccount.repaymentSchedule.installmentAmount)
                              : 'Not generated'}
                          </strong>
                        </div>
                        <div className="loan-detail-field">
                          <span>First due date</span>
                          <strong>
                            {selectedLoan.loanAccount.repaymentSchedule?.firstDueDate
                              ? formatDateLabel(selectedLoan.loanAccount.repaymentSchedule.firstDueDate)
                              : 'Not generated'}
                          </strong>
                        </div>
                        <div className="loan-detail-field">
                          <span>Final due date</span>
                          <strong>
                            {selectedLoan.loanAccount.repaymentSchedule?.finalDueDate
                              ? formatDateLabel(selectedLoan.loanAccount.repaymentSchedule.finalDueDate)
                              : 'Not generated'}
                          </strong>
                        </div>
                        <div className="loan-detail-field">
                          <span>Delinquency bucket</span>
                          <strong>
                            {loanDelinquencyBucketLabel(selectedLoan.loanAccount.delinquency?.bucket)}
                          </strong>
                        </div>
                        <div className="loan-detail-field">
                          <span>Max DPD</span>
                          <strong>{selectedLoan.loanAccount.delinquency?.maxDaysPastDue ?? 0} days</strong>
                        </div>
                        <div className="loan-detail-field">
                          <span>Overdue installments</span>
                          <strong>{selectedLoan.loanAccount.delinquency?.overdueInstallmentCount ?? 0}</strong>
                        </div>
                        <div className="loan-detail-field">
                          <span>Overdue amount</span>
                          <strong>
                            {currencyLabel(selectedLoan.loanAccount.delinquency?.overdueAmount ?? 0)}
                          </strong>
                        </div>
                      </div>
                      {canRequestForeclosureQuote ? (
                        <div className="loan-transition-panel" style={{ marginTop: '1.5rem' }}>
                          <div className="loan-transition-panel__header">
                            <div>
                              <h4>Foreclosure quotes</h4>
                              <p className="helper-copy">
                                Request payoff quotes tied to a specific settlement date, then execute the matching settlement.
                              </p>
                            </div>
                            <Badge variant="warning">Admin only</Badge>
                          </div>
                          <div className="field-stack">
                            <label htmlFor="foreclosure-settlement-date">Settlement date</label>
                            <Input
                              id="foreclosure-settlement-date"
                              type="date"
                              value={foreclosureExecution.settlementDate}
                              onChange={(event) =>
                                setForeclosureExecution((current) => ({
                                  ...current,
                                  settlementDate: event.target.value,
                                }))
                              }
                            />
                            <p className="helper-copy">
                              The quote is locked to this effective settlement date for execution.
                            </p>
                          </div>
                          <div className="field-stack">
                            <label htmlFor="foreclosure-reference">Settlement reference</label>
                            <Input
                              id="foreclosure-reference"
                              value={foreclosureExecution.reference}
                              onChange={(event) =>
                                setForeclosureExecution((current) => ({
                                  ...current,
                                  reference: event.target.value,
                                }))
                              }
                              placeholder="e.g. FC-SETTLE-001"
                            />
                          </div>
                          <div className="field-stack">
                            <label htmlFor="foreclosure-note">Settlement note</label>
                            <textarea
                              id="foreclosure-note"
                              className="ui-textarea"
                              value={foreclosureExecution.note}
                              onChange={(event) =>
                                setForeclosureExecution((current) => ({
                                  ...current,
                                  note: event.target.value,
                                }))
                              }
                              placeholder="Optional context for the foreclosure settlement."
                              rows={3}
                            />
                          </div>
                          {foreclosureQuoteActionError ? (
                            <div className="empty-state">{foreclosureQuoteActionError}</div>
                          ) : null}
                          <div className="loan-transition-actions">
                            <Button
                              disabled={requestingForeclosureQuote || !canRequestForeclosureQuote}
                              onClick={() => void handleRequestForeclosureQuote()}
                              type="button"
                              variant="secondary"
                            >
                              {requestingForeclosureQuote ? 'Requesting...' : 'Request foreclosure quote'}
                            </Button>
                          </div>
                          {foreclosureQuotesLoading ? (
                            <div className="empty-state">Loading foreclosure quotes...</div>
                          ) : null}
                          {!foreclosureQuotesLoading && foreclosureQuotesError ? (
                            <div className="empty-state">{foreclosureQuotesError}</div>
                          ) : null}
                          {!foreclosureQuotesLoading && !foreclosureQuotesError && !selectedForeclosureQuotes.length ? (
                            <div className="empty-state">
                              No foreclosure quotes have been requested for this loan account yet.
                            </div>
                          ) : null}
                          {!foreclosureQuotesLoading && !foreclosureQuotesError && selectedForeclosureQuotes.length ? (
                            <div className="loan-history" style={{ marginTop: '1rem' }}>
                              <div className="loan-history__header">
                                <div className="section-eyebrow">Quote history</div>
                                <Badge>{selectedForeclosureQuotes.length} quotes</Badge>
                              </div>
                              <div className="loan-history__list">
                                {selectedForeclosureQuotes.map((quote) => (
                                  <div className="loan-history__item" key={quote.id}>
                                    <div>
                                      <strong>
                                        Quote v{quote.version} - {currencyLabel(quote.settlementAmount)}
                                      </strong>
                                      <p className="helper-copy">
                                        Requested by {quote.requestedByUsername} on {formatTimestamp(quote.createdAt)}
                                      </p>
                                      <p className="helper-copy">
                                        Effective date: {formatDateLabel(quote.effectiveDate)}
                                      </p>
                                      <p className="helper-copy">
                                        Principal: {currencyLabel(quote.outstandingPrincipal)} / Interest:{' '}
                                        {currencyLabel(quote.outstandingInterest)}
                                      </p>
                                      <p className="helper-copy">
                                        Executed: {quote.executedAt ? formatTimestamp(quote.executedAt) : 'Not executed'}
                                      </p>
                                    </div>
                                    <div className="inline-actions">
                                      <Badge variant={loanForeclosureQuoteStatusVariant(quote.status)}>
                                        {loanForeclosureQuoteStatusLabel(quote.status)}
                                      </Badge>
                                      <Badge>{quote.executedByUsername || 'Pending execution'}</Badge>
                                      {quote.status === 'ACTIVE' ? (
                                        <Button
                                          disabled={
                                            executingForeclosureQuoteId === quote.id ||
                                            !canExecuteForeclosureQuote ||
                                            !foreclosureExecution.settlementDate ||
                                            !foreclosureExecution.reference.trim() ||
                                            foreclosureExecution.settlementDate !== quote.effectiveDate
                                          }
                                          onClick={() => void handleExecuteForeclosureQuote(quote.id)}
                                          type="button"
                                          variant="secondary"
                                        >
                                          {executingForeclosureQuoteId === quote.id
                                            ? 'Executing...'
                                            : 'Execute quote'}
                                        </Button>
                                      ) : null}
                                    </div>
                                  </div>
                                ))}
                              </div>
                              <p className="helper-copy" style={{ marginTop: '0.75rem' }}>
                                Active quotes can only be executed using their quoted effective date.
                              </p>
                            </div>
                          ) : null}
                        </div>
                      ) : null}
                      {repaymentScheduleError ? (
                        <div className="empty-state">{repaymentScheduleError}</div>
                      ) : null}
                      <div className="loan-history" style={{ marginTop: '1.5rem' }}>
                        <div className="loan-history__header">
                          <div>
                            <div className="section-eyebrow">Repayment schedule</div>
                            <p className="helper-copy">
                              Read-only installment plan generated when the loan account was created.
                            </p>
                          </div>
                          <Badge>{selectedRepaymentSchedule.length} installments</Badge>
                        </div>
                        {repaymentScheduleLoading ? (
                          <div className="empty-state">Loading repayment schedule...</div>
                        ) : null}
                        {!repaymentScheduleLoading &&
                        !repaymentScheduleError &&
                        !selectedRepaymentSchedule.length ? (
                          <div className="empty-state">
                            No repayment schedule has been generated for this loan account yet.
                          </div>
                        ) : null}
                        {!repaymentScheduleLoading &&
                        !repaymentScheduleError &&
                        selectedRepaymentSchedule.length ? (
                          <div className="loan-history__list">
                            {selectedRepaymentSchedule.map((installment) => (
                              <div className="loan-history__item" key={installment.id}>
                                <div>
                                  <strong>
                                    Installment {installment.installmentNumber} -{' '}
                                    {formatDateLabel(installment.dueDate)}
                                  </strong>
                                  <p className="helper-copy">
                                    Status: {loanRepaymentInstallmentStatusLabel(installment.status)}
                                  </p>
                                  <p className="helper-copy">
                                    Opening principal: {currencyLabel(installment.openingPrincipal)}
                                  </p>
                                  <p className="helper-copy">
                                    Principal due: {currencyLabel(installment.principalDue)} / Interest due:{' '}
                                    {currencyLabel(installment.interestDue)}
                                  </p>
                                  <p className="helper-copy">
                                    Paid: {currencyLabel(installment.paidAmount)} / Outstanding:{' '}
                                    {currencyLabel(installment.outstandingAmount)}
                                  </p>
                                  <p className="helper-copy">
                                    DPD: {installment.daysPastDue} days / Bucket:{' '}
                                    {loanDelinquencyBucketLabel(installment.delinquencyBucket)}
                                  </p>
                                  <p className="helper-copy">
                                    Paid principal: {currencyLabel(installment.paidPrincipal)} / Paid interest:{' '}
                                    {currencyLabel(installment.paidInterest)}
                                  </p>
                                  <p className="helper-copy">
                                    Closing principal: {currencyLabel(installment.closingPrincipal)}
                                  </p>
                                </div>
                                <div className="inline-actions">
                                  <Badge>{currencyLabel(installment.installmentAmount)}</Badge>
                                  <Badge variant={loanRepaymentInstallmentStatusVariant(installment.status)}>
                                    {loanRepaymentInstallmentStatusLabel(installment.status)}
                                  </Badge>
                                  <Badge variant={loanDelinquencyBucketVariant(installment.delinquencyBucket)}>
                                    {loanDelinquencyBucketLabel(installment.delinquencyBucket)}
                                  </Badge>
                                </div>
                              </div>
                            ))}
                          </div>
                        ) : null}
                      </div>
                      {disbursementRequestsError ? (
                        <div className="empty-state">{disbursementRequestsError}</div>
                      ) : null}
                      <div className="loan-history" style={{ marginTop: '1.5rem' }}>
                        <div className="loan-history__header">
                          <div>
                            <div className="section-eyebrow">Disbursement requests</div>
                            <p className="helper-copy">
                              Logged mock provider requests raised for this loan account.
                            </p>
                          </div>
                          <Badge>{selectedDisbursementRequests.length} events</Badge>
                        </div>
                        {disbursementRequestsLoading ? (
                          <div className="empty-state">Loading disbursement requests...</div>
                        ) : null}
                        {!disbursementRequestsLoading &&
                        !disbursementRequestsError &&
                        !selectedDisbursementRequests.length ? (
                          <div className="empty-state">
                            No disbursement requests have been logged for this loan account yet.
                          </div>
                        ) : null}
                        {!disbursementRequestsLoading &&
                        !disbursementRequestsError &&
                        selectedDisbursementRequests.length ? (
                          <div className="loan-history__list">
                            {selectedDisbursementRequests.map((request) => (
                              <div className="loan-history__item" key={request.id}>
                                <div>
                                  <strong>
                                    {request.providerName} - {request.providerRequestId}
                                  </strong>
                                  <p className="helper-copy">{formatTimestamp(request.createdAt)}</p>
                                  <p className="helper-copy">
                                    Amount: {currencyLabel(request.amount)}
                                  </p>
                                  <p className="helper-copy">
                                    Correlation ID: {request.correlationId ?? 'Not captured'}
                                  </p>
                                  <p className="helper-copy">
                                    Updated: {formatTimestamp(request.updatedAt)}
                                  </p>
                                </div>
                                <div className="inline-actions">
                                  <Badge>{request.providerStatus}</Badge>
                                  <Badge>{request.actorUsername}</Badge>
                                </div>
                              </div>
                            ))}
                          </div>
                        ) : null}
                      </div>
                      {paymentTransactionsError ? (
                        <div className="empty-state">{paymentTransactionsError}</div>
                      ) : null}
                      <div className="loan-history" style={{ marginTop: '1.5rem' }}>
                        <div className="loan-history__header">
                          <div>
                            <div className="section-eyebrow">Payment transactions</div>
                            <p className="helper-copy">
                              Raw repayment receipts are captured here before installment allocation is added.
                            </p>
                          </div>
                          <div className="inline-actions">
                            <Badge>{selectedPaymentTransactions.length} events</Badge>
                            {user?.roles?.includes('SYSTEM_ADMIN') ? <Badge variant="warning">Admin write</Badge> : null}
                          </div>
                        </div>
                        {user?.roles?.includes('SYSTEM_ADMIN') ? (
                          <div className="form-grid" style={{ marginBottom: '1rem' }}>
                            <div className="field-stack">
                              <label htmlFor="payment-amount">Amount</label>
                              <Input
                                id="payment-amount"
                                type="number"
                                min="0"
                                step="0.01"
                                value={paymentCapture.amount}
                                onChange={(event) =>
                                  setPaymentCapture((current) => ({
                                    ...current,
                                    amount: event.target.value,
                                  }))
                                }
                                placeholder="e.g. 4136.32"
                              />
                            </div>
                            <div className="field-stack">
                              <label htmlFor="payment-date">Payment date</label>
                              <Input
                                id="payment-date"
                                type="date"
                                value={paymentCapture.paymentDate}
                                onChange={(event) =>
                                  setPaymentCapture((current) => ({
                                    ...current,
                                    paymentDate: event.target.value,
                                  }))
                                }
                              />
                            </div>
                            <div className="field-stack">
                              <label htmlFor="payment-reference">Reference</label>
                              <Input
                                id="payment-reference"
                                value={paymentCapture.reference}
                                onChange={(event) =>
                                  setPaymentCapture((current) => ({
                                    ...current,
                                    reference: event.target.value,
                                  }))
                                }
                                placeholder="e.g. UPI-APR-001"
                              />
                            </div>
                            <div className="field-stack">
                              <label htmlFor="payment-channel">Channel</label>
                              <select
                                id="payment-channel"
                                className="ui-input ui-select"
                                value={paymentCapture.channel}
                                onChange={(event) =>
                                  setPaymentCapture((current) => ({
                                    ...current,
                                    channel: event.target.value as LoanPaymentChannel,
                                  }))
                                }
                              >
                                {loanPaymentChannelOptions.map((channel) => (
                                  <option key={channel} value={channel}>
                                    {loanPaymentChannelLabel(channel)}
                                  </option>
                                ))}
                              </select>
                            </div>
                            <div className="field-stack">
                              <label htmlFor="payment-status">Status</label>
                              <select
                                id="payment-status"
                                className="ui-input ui-select"
                                value={paymentCapture.status}
                                onChange={(event) =>
                                  setPaymentCapture((current) => ({
                                    ...current,
                                    status: event.target.value as LoanPaymentStatus,
                                  }))
                                }
                              >
                                {loanPaymentStatusOptions.map((status) => (
                                  <option key={status} value={status}>
                                    {loanPaymentStatusLabel(status)}
                                  </option>
                                ))}
                              </select>
                            </div>
                            <div className="field-stack" style={{ gridColumn: '1 / -1' }}>
                              <label htmlFor="payment-note">Note</label>
                              <textarea
                                id="payment-note"
                                className="ui-textarea"
                                value={paymentCapture.note}
                                onChange={(event) =>
                                  setPaymentCapture((current) => ({
                                    ...current,
                                    note: event.target.value,
                                  }))
                                }
                                placeholder="Optional context for this payment capture."
                                rows={3}
                              />
                            </div>
                            <div className="loan-transition-actions" style={{ gridColumn: '1 / -1' }}>
                              <Button
                                disabled={
                                  recordingPayment ||
                                  !canRecordPayments ||
                                  !paymentCapture.amount ||
                                  Number(paymentCapture.amount) <= 0 ||
                                  !paymentCapture.paymentDate ||
                                  !paymentCapture.reference.trim()
                                }
                                onClick={() => void handleRecordPaymentTransaction()}
                                type="button"
                                variant="secondary"
                              >
                                {recordingPayment ? 'Recording...' : 'Record payment'}
                              </Button>
                              {!canRecordPayments ? (
                                <p className="helper-copy">
                                  Payments can only be recorded while the loan account is actively disbursed.
                                </p>
                              ) : null}
                            </div>
                          </div>
                        ) : null}
                        {paymentTransactionsLoading ? (
                          <div className="empty-state">Loading payment transactions...</div>
                        ) : null}
                        {!paymentTransactionsLoading &&
                        !paymentTransactionsError &&
                        !selectedPaymentTransactions.length ? (
                          <div className="empty-state">
                            No payment transactions have been captured for this loan account yet.
                          </div>
                        ) : null}
                        {!paymentTransactionsLoading &&
                        !paymentTransactionsError &&
                        selectedPaymentTransactions.length ? (
                          <div className="loan-history__list">
                            {selectedPaymentTransactions.map((payment) => (
                              <div className="loan-history__item" key={payment.id}>
                                <div>
                                  <strong>
                                    {currencyLabel(payment.amount)} - {payment.reference}
                                  </strong>
                                  <p className="helper-copy">
                                    Payment date: {formatDateLabel(payment.paymentDate)}
                                  </p>
                                  <p className="helper-copy">
                                    Channel: {loanPaymentChannelLabel(payment.channel)}
                                  </p>
                                  <p className="helper-copy">
                                    Allocated: {currencyLabel(payment.allocatedAmount)} / Unallocated:{' '}
                                    {currencyLabel(payment.unallocatedAmount)}
                                  </p>
                                  <p className="helper-copy">{formatNote(payment.note)}</p>
                                  <p className="helper-copy">
                                    Correlation ID: {payment.correlationId ?? 'Not captured'}
                                  </p>
                                </div>
                                <div className="inline-actions">
                                  <Badge variant={loanPaymentStatusVariant(payment.status)}>
                                    {loanPaymentStatusLabel(payment.status)}
                                  </Badge>
                                  <Badge>{payment.actorUsername}</Badge>
                                  <Badge variant="warning">{formatTimestamp(payment.createdAt)}</Badge>
                                </div>
                              </div>
                            ))}
                          </div>
                        ) : null}
                      </div>
                    </div>
                  ) : null}
                </div>

                <div className="loan-status-lane" aria-label="Loan status progression">
                  {workflowProgression.map((step) => {
                    const stepIndex =
                      step.status === 'RECEIVED'
                        ? 1
                        : step.status === 'UNDER_REVIEW'
                          ? 2
                          : step.status === 'HOLD'
                            ? 3
                            : 4
                    const isCurrent = currentProgressIndex === stepIndex
                    const isComplete = currentProgressIndex > stepIndex

                    return (
                      <div
                        key={step.status}
                        className={[
                          'loan-status-lane__step',
                          isCurrent ? 'loan-status-lane__step--current' : '',
                          isComplete ? 'loan-status-lane__step--complete' : '',
                        ]
                          .filter(Boolean)
                          .join(' ')}
                      >
                        <div className="loan-status-lane__step-number">
                          {step.status === 'DECISION' ? '4' : statusProgressIndex(step.status)}
                        </div>
                        <div>
                          <strong>{step.label}</strong>
                          <p className="helper-copy">{step.description}</p>
                        </div>
                        {step.status === 'DECISION' ? (
                          <div className="inline-actions">
                            <Badge variant="success">Approved</Badge>
                            <Badge variant="destructive">Rejected</Badge>
                          </div>
                        ) : null}
                      </div>
                    )
                  })}
                </div>

                <div className="loan-transition-panel">
                  <div className="loan-transition-panel__header">
                    <div>
                      <h4>Transition action</h4>
                      <p className="helper-copy">
                        Add an optional note before updating the status.
                      </p>
                    </div>
                    <Badge variant={loanStatusVariant(visibleSelectedApplication.status as LoanApplicationStatus)}>
                      {loanStatusLabel(visibleSelectedApplication.status as LoanApplicationStatus)}
                    </Badge>
                  </div>
                  <div className="field-stack">
                    <label htmlFor="status-note">Decision note</label>
                    <textarea
                      id="status-note"
                      className="ui-textarea"
                      value={transitionNote}
                      onChange={(event) => setTransitionNote(event.target.value)}
                      placeholder="Add context for the review decision. This is optional."
                      rows={4}
                    />
                  </div>
                  <div className="field-stack">
                    <label htmlFor="status-reason-code">Reason code</label>
                    <select
                      id="status-reason-code"
                      className="ui-input ui-select"
                      value={transitionReasonCode}
                      onChange={(event) =>
                        setTransitionReasonCode(
                          (event.target.value as LoanApplicationStatusReasonCode | '') || '',
                        )
                      }
                    >
                      <option value="">No reason code</option>
                      {loanApplicationStatusReasonCodeOptions.map((code) => (
                        <option key={code} value={code}>
                          {loanStatusReasonCodeLabel(code)}
                        </option>
                      ))}
                    </select>
                    <p className="helper-copy">
                      Required when moving a case to hold or rejection.
                    </p>
                  </div>
                  {!kycApprovalReady ? (
                    <div className="loan-alert loan-alert--warning">
                      <div className="loan-alert__title">Approval currently blocked</div>
                      <p className="helper-copy">
                        {incompleteRequiredDocumentLabels.length
                          ? `Complete these required KYC items before approving: ${incompleteRequiredDocumentLabels.join(', ')}.`
                          : 'Complete the required KYC checklist before approving this loan.'}
                      </p>
                    </div>
                  ) : null}
                  {approvalBlocker ? (
                    <div className="loan-alert loan-alert--warning">
                      <div className="loan-alert__title">Approval blocked by backend validation</div>
                      <p className="helper-copy">{approvalBlocker.message}</p>
                      {approvalBlocker.documents.length ? (
                        <ul className="loan-alert__list">
                          {approvalBlocker.documents.map((document) => (
                            <li key={document}>{document}</li>
                          ))}
                        </ul>
                      ) : null}
                    </div>
                  ) : null}
                  {workflowError ? <div className="empty-state">{workflowError}</div> : null}
                  <div className="loan-transition-actions">
                    {transitionActions.map((action) => (
                      <Button
                        key={action.targetStatus}
                        disabled={
                          transitioning ||
                          (statusRequiresReasonCode(action.targetStatus) && !transitionReasonCode)
                        }
                        onClick={() => void handleStatusTransition(action.targetStatus)}
                        type="button"
                        variant={action.variant}
                        className={
                          action.targetStatus === 'REJECTED'
                            ? 'loan-transition-button--reject'
                            : undefined
                        }
                      >
                        {action.targetStatus === 'APPROVED' ? (
                          <CheckCircle2 size={16} />
                        ) : action.targetStatus === 'REJECTED' ? (
                          <FileText size={16} />
                        ) : (
                          <ArrowRight size={16} />
                        )}
                        {transitioning ? 'Updating...' : action.label}
                      </Button>
                    ))}
                    {!transitionActions.length ? (
                      <div className="empty-state">
                        {statusDrivenTransitionActions.length
                          ? 'No transition actions are available for your current role.'
                          : 'This loan is in a terminal state.'}
                      </div>
                    ) : null}
                  </div>
                </div>

                {canManualOverrideStatus ? (
                  <div className="loan-transition-panel">
                    <div className="loan-transition-panel__header">
                      <div>
                        <h4>Manual admin override</h4>
                        <p className="helper-copy">
                          System admins can move the case between review states or reopen a rejected case.
                          Approval is intentionally excluded from this override path.
                        </p>
                      </div>
                      <Badge variant="warning">Admin only</Badge>
                    </div>
                    {!manualStatusTargets.length ? (
                      <div className="empty-state">
                        Manual status override is not available for the current status.
                      </div>
                    ) : (
                      <>
                        <div className="field-stack">
                          <label htmlFor="manual-status-target">Override target</label>
                          <select
                            id="manual-status-target"
                            className="ui-input ui-select"
                            value={manualStatusTarget}
                            onChange={(event) =>
                              setManualStatusTarget(event.target.value as LoanApplicationStatus)
                            }
                          >
                            {manualStatusTargets.map((status) => (
                              <option key={status} value={status}>
                                {loanStatusLabel(status)}
                              </option>
                            ))}
                          </select>
                        </div>
                        <div className="field-stack">
                          <label htmlFor="manual-status-note">Override note</label>
                          <textarea
                            id="manual-status-note"
                            className="ui-textarea"
                            value={manualStatusNote}
                            onChange={(event) => setManualStatusNote(event.target.value)}
                            placeholder="Required. Explain why this manual override is needed."
                            rows={4}
                          />
                        </div>
                        <div className="field-stack">
                          <label htmlFor="manual-status-reason-code">Reason code</label>
                          <select
                            id="manual-status-reason-code"
                            className="ui-input ui-select"
                            value={manualStatusReasonCode}
                            onChange={(event) =>
                              setManualStatusReasonCode(
                                event.target.value as LoanApplicationStatusReasonCode,
                              )
                            }
                          >
                            {loanApplicationStatusReasonCodeOptions.map((code) => (
                              <option key={code} value={code}>
                                {loanStatusReasonCodeLabel(code)}
                              </option>
                            ))}
                          </select>
                        </div>
                        {manualStatusError ? <div className="empty-state">{manualStatusError}</div> : null}
                        <div className="loan-transition-actions">
                          <Button
                            disabled={
                              manualStatusSubmitting ||
                              !manualStatusTarget ||
                              !manualStatusNote.trim() ||
                              !manualStatusReasonCode
                            }
                            onClick={() => void handleManualStatusOverride()}
                            type="button"
                            variant="outline"
                          >
                            {manualStatusSubmitting ? 'Updating...' : 'Apply manual status'}
                          </Button>
                        </div>
                      </>
                    )}
                  </div>
                ) : null}

                <div className="loan-history">
                  <div className="loan-history__header">
                    <div>
                      <div className="section-eyebrow">Status history</div>
                      <p className="helper-copy">
                        Filter by structured reason code to isolate hold and rejection patterns.
                      </p>
                    </div>
                    <div className="inline-actions">
                      {availableStatusHistoryReasonCodes.length ? (
                        <select
                          aria-label="Filter status history by reason code"
                          className="ui-input ui-select"
                          value={statusHistoryReasonCodeFilter}
                          onChange={(event) =>
                            setStatusHistoryReasonCodeFilter(
                              (event.target.value as LoanApplicationStatusReasonCode | '') || '',
                            )
                          }
                        >
                          <option value="">All reason codes</option>
                          {availableStatusHistoryReasonCodes.map((code) => (
                            <option key={code} value={code}>
                              {loanStatusReasonCodeLabel(code)}
                            </option>
                          ))}
                        </select>
                      ) : null}
                      <Badge>
                        {filteredStatusHistory.length}
                        {statusHistoryReasonCodeFilter ? ` of ${selectedStatusHistory.length}` : ''} events
                      </Badge>
                    </div>
                  </div>
                  {detailLoading ? <div className="empty-state">Loading loan detail...</div> : null}
                  {!detailLoading && !selectedStatusHistory.length ? (
                    <div className="empty-state">No status transitions recorded yet.</div>
                  ) : null}
                  {!detailLoading && selectedStatusHistory.length && !filteredStatusHistory.length ? (
                    <div className="empty-state">
                      No status transitions match the selected reason code.
                    </div>
                  ) : null}
                  {!detailLoading && filteredStatusHistory.length ? (
                    <div className="loan-history__list">
                      {filteredStatusHistory.map((transition) => (
                        <div className="loan-history__item" key={transition.id}>
                          <div>
                            <strong>
                              {loanStatusLabel(transition.fromStatus)} - {loanStatusLabel(transition.toStatus)}
                            </strong>
                            <p className="helper-copy">{formatTimestamp(transition.createdAt)}</p>
                            <p className="helper-copy">{formatNote(transition.note)}</p>
                            <p className="helper-copy">
                              Reason code: {loanStatusReasonCodeLabel(transition.reasonCode)}
                            </p>
                            <p className="helper-copy">
                              Correlation ID: {transition.correlationId ?? 'Not captured'}
                            </p>
                          </div>
                          <div className="inline-actions">
                            <Badge variant={loanStatusVariant(transition.toStatus)}>
                              {loanStatusLabel(transition.toStatus)}
                            </Badge>
                            <Badge variant="warning">{transition.actorUsername}</Badge>
                          </div>
                        </div>
                      ))}
                    </div>
                  ) : null}
                </div>
              </div>
            ) : (
              <div className="empty-state">Select a loan application to inspect its detail view.</div>
            )}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <div className="section-eyebrow">Troubleshooting</div>
            <CardTitle>Selected intake audit</CardTitle>
            <CardDescription>
              Inspect the persisted raw intake payload captured when the selected application was
              created.
            </CardDescription>
          </CardHeader>
          <CardContent>
            {visibleSelectedApplication ? (
              <div className="form-grid">
                <div className="inline-actions">
                  <Badge>{visibleSelectedApplication.externalLoanId}</Badge>
                  <Badge variant={loanStatusVariant(visibleSelectedApplication.status as LoanApplicationStatus)}>
                    {loanStatusLabel(visibleSelectedApplication.status as LoanApplicationStatus)}
                  </Badge>
                </div>
                <div className="helper-copy">
                  Borrower: {visibleSelectedApplication.borrowerFullName} -{' '}
                  {formatBorrowerPan(visibleSelectedApplication.borrowerPan, revealSensitiveData)}
                </div>
                <div className="helper-copy">
                  Product: {visibleSelectedApplication.productCode} - Source:{' '}
                  {visibleSelectedApplication.sourceChannel}
                </div>
                <div className="loan-history">
                  <div className="loan-history__header">
                    <div>
                      <div className="section-eyebrow">Document access audit</div>
                      <p className="helper-copy">
                        Recorded whenever intake payloads or KYC document metadata are opened for this application.
                      </p>
                    </div>
                    <Badge>{selectedDocumentAccessAudits.length} events</Badge>
                  </div>
                  {documentAccessAuditsLoading ? (
                    <div className="empty-state">Loading document access audit...</div>
                  ) : null}
                  {!documentAccessAuditsLoading && documentAccessAuditsError ? (
                    <div className="empty-state">{documentAccessAuditsError}</div>
                  ) : null}
                  {!documentAccessAuditsLoading &&
                  !documentAccessAuditsError &&
                  !selectedDocumentAccessAudits.length ? (
                    <div className="empty-state">
                      No document access audit events are available for the selected application.
                    </div>
                  ) : null}
                  {!documentAccessAuditsLoading &&
                  !documentAccessAuditsError &&
                  selectedDocumentAccessAudits.length ? (
                    <div className="loan-history__list">
                      {selectedDocumentAccessAudits.map((event) => (
                        <div className="loan-history__item" key={event.id}>
                          <div>
                            <strong>{event.summary}</strong>
                            <p className="helper-copy">{formatTimestamp(event.createdAt)}</p>
                            <p className="helper-copy">
                              Documents: {event.documentTypes.join(', ') || 'None captured'}
                            </p>
                            <p className="helper-copy">
                              Correlation ID: {event.correlationId ?? 'Not captured'}
                            </p>
                          </div>
                          <div className="inline-actions">
                            <Badge>{event.action.replace(/_/g, ' ')}</Badge>
                            <Badge variant="warning">{event.actorUsername}</Badge>
                          </div>
                        </div>
                      ))}
                    </div>
                  ) : null}
                </div>
                <div className="loan-history">
                  <div className="loan-history__header">
                    <div>
                      <div className="section-eyebrow">Status audit trail</div>
                      <p className="helper-copy">
                        Immutable events emitted whenever loan status is changed.
                      </p>
                    </div>
                    <Badge>{selectedAuditTrail.length} events</Badge>
                  </div>
                  {auditTrailLoading ? <div className="empty-state">Loading status audit trail...</div> : null}
                  {!auditTrailLoading && auditTrailError ? (
                    <div className="empty-state">{auditTrailError}</div>
                  ) : null}
                  {!auditTrailLoading && !auditTrailError && !selectedAuditTrail.length ? (
                    <div className="empty-state">
                      No status audit events are available for the selected application.
                    </div>
                  ) : null}
                  {!auditTrailLoading && !auditTrailError && selectedAuditTrail.length ? (
                    <div className="loan-history__list">
                      {selectedAuditTrail.map((event) => (
                        <div className="loan-history__item" key={event.id}>
                          <div>
                            <strong>
                              {loanStatusLabel(event.fromStatus)} - {loanStatusLabel(event.toStatus)}
                            </strong>
                            <p className="helper-copy">{formatTimestamp(event.createdAt)}</p>
                            <p className="helper-copy">{formatNote(event.note)}</p>
                            <p className="helper-copy">
                              Reason code: {loanStatusReasonCodeLabel(event.reasonCode)}
                            </p>
                            <p className="helper-copy">
                              Correlation ID: {event.correlationId ?? 'Not captured'}
                            </p>
                          </div>
                          <div className="inline-actions">
                            <Badge variant={loanStatusVariant(event.toStatus)}>
                              {loanStatusLabel(event.toStatus)}
                            </Badge>
                            <Badge>{loanAuditActionLabel(event.action)}</Badge>
                            <Badge variant="warning">{event.actorUsername}</Badge>
                          </div>
                        </div>
                      ))}
                    </div>
                  ) : null}
                </div>
                {auditLoading ? <div className="empty-state">Loading intake audit...</div> : null}
                {auditError ? <div className="empty-state">{auditError}</div> : null}
                {!auditLoading && !auditError && latestAudit ? (
                  <>
                    <div className="helper-copy">
                      Captured {formatTimestamp(latestAudit.createdAt)} by {latestAudit.actorUsername}
                    </div>
                    <div className="helper-copy">
                      Correlation ID: {latestAudit.correlationId ?? 'Not captured'}
                    </div>
                    <pre
                      className="helper-copy"
                      style={{
                        margin: 0,
                        maxHeight: '22rem',
                        overflow: 'auto',
                        padding: '1rem',
                        borderRadius: '1rem',
                        background: 'var(--color-panel-muted)',
                        whiteSpace: 'pre-wrap',
                        wordBreak: 'break-word',
                      }}
                    >
                      {formatPayloadJson(latestAudit.payloadJson, revealSensitiveData)}
                    </pre>
                  </>
                ) : null}
                {!auditLoading && !auditError && !latestAudit ? (
                  <div className="empty-state">
                    No intake audit is available for the selected application.
                  </div>
                ) : null}
              </div>
            ) : (
              <div className="empty-state">Select a loan application to inspect its intake audit.</div>
            )}
          </CardContent>
        </Card>
      </div>
    </div>
  )
}
