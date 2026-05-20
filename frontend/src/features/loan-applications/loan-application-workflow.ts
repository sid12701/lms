import {
  loanApplicationStatusOptions,
  type LoanAccountStatus,
  type LoanApplicationAuditAction,
  type LoanApplicationLastActivityRecord,
  type LoanApplicationStatus,
  type LoanApplicationStatusReasonCode,
  type LoanPaymentChannel,
  type LoanPaymentStatus,
} from '../api/lms-api'

export type TransitionAction = {
  targetStatus: LoanApplicationStatus
  label: string
  description: string
  variant: 'primary' | 'secondary' | 'outline'
}

const loanApplicationStatusMeta: Record<
  LoanApplicationStatus,
  {
    label: string
    variant: 'default' | 'success' | 'warning' | 'destructive'
    progressIndex: number
  }
> = {
  INITIALIZED: {
    label: 'Initialized',
    variant: 'warning',
    progressIndex: 1,
  },
  AWAITING_APPROVAL: {
    label: 'Awaiting approval',
    variant: 'default',
    progressIndex: 2,
  },
  APPROVED_PENDING_DISBURSAL: {
    label: 'Application approved - pending for disbursal',
    variant: 'warning',
    progressIndex: 3,
  },
  REJECTED: {
    label: 'Rejected',
    variant: 'destructive',
    progressIndex: 2,
  },
  PAYMENT_REINITIATION: {
    label: 'Payment re-initiation',
    variant: 'warning',
    progressIndex: 3,
  },
  INVALID: {
    label: 'Invalid',
    variant: 'destructive',
    progressIndex: 2,
  },
  DISBURSED: {
    label: 'Disbursed',
    variant: 'success',
    progressIndex: 4,
  },
  UNDER_REPAYMENT: {
    label: 'Under repayment',
    variant: 'success',
    progressIndex: 5,
  },
  CLOSED: {
    label: 'Closed',
    variant: 'default',
    progressIndex: 6,
  },
}

export const workflowProgression: Array<{
  status: LoanApplicationStatus
  label: string
  description: string
}> = [
  { status: 'INITIALIZED', label: 'Initialized', description: 'Intake is captured and ready for queueing.' },
  {
    status: 'AWAITING_APPROVAL',
    label: 'Awaiting approval',
    description: 'Ops and underwriting are validating the application.',
  },
  {
    status: 'APPROVED_PENDING_DISBURSAL',
    label: 'Pending disbursal',
    description: 'The application is approved and waiting for disbursal processing.',
  },
  {
    status: 'DISBURSED',
    label: 'Disbursed',
    description: 'Funds have been released to the borrower.',
  },
  {
    status: 'UNDER_REPAYMENT',
    label: 'Under repayment',
    description: 'The loan is active and repayments are being tracked.',
  },
  {
    status: 'CLOSED',
    label: 'Closed',
    description: 'The loan is settled and the account is closed.',
  },
]

export function loanStatusLabel(status: LoanApplicationStatus) {
  return loanApplicationStatusMeta[status].label
}

export function loanStatusVariant(
  status: LoanApplicationStatus,
): 'default' | 'success' | 'warning' | 'destructive' {
  return loanApplicationStatusMeta[status].variant
}

export function statusProgressIndex(status: LoanApplicationStatus) {
  return loanApplicationStatusMeta[status].progressIndex
}

export function getTransitionActions(status: LoanApplicationStatus): TransitionAction[] {
  switch (status) {
    case 'INITIALIZED':
      return [
        {
          targetStatus: 'AWAITING_APPROVAL',
          label: 'Send for approval',
          description: 'Move the case into the approval queue.',
          variant: 'secondary',
        },
      ]
    case 'AWAITING_APPROVAL':
      return [
        {
          targetStatus: 'APPROVED_PENDING_DISBURSAL',
          label: 'Approve',
          description: 'Approve the application and queue it for disbursal.',
          variant: 'primary',
        },
        {
          targetStatus: 'REJECTED',
          label: 'Reject',
          description: 'Reject the application and capture the decision note.',
          variant: 'outline',
        },
      ]
    case 'APPROVED_PENDING_DISBURSAL':
    case 'PAYMENT_REINITIATION':
    case 'INVALID':
    case 'DISBURSED':
    case 'UNDER_REPAYMENT':
    case 'CLOSED':
    case 'REJECTED':
      return []
  }
}

export function getVisibleTransitionActions(
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
      if (status === 'INITIALIZED') {
        return action.targetStatus === 'AWAITING_APPROVAL'
      }

      return false
    })
  }

  return []
}

export function getManualStatusTargets(status: LoanApplicationStatus) {
  return loanApplicationStatusOptions.filter(
    (option) =>
      option !== status &&
      option !== 'APPROVED_PENDING_DISBURSAL' &&
      option !== 'DISBURSED' &&
      option !== 'UNDER_REPAYMENT' &&
      option !== 'INVALID' &&
      option !== 'CLOSED',
  )
}

export function statusRequiresReasonCode(status: LoanApplicationStatus) {
  return status === 'PAYMENT_REINITIATION' || status === 'REJECTED'
}

export function loanStatusReasonCodeLabel(code?: LoanApplicationStatusReasonCode | null) {
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

export function loanAuditActionLabel(action: LoanApplicationAuditAction) {
  switch (action) {
    case 'FORECLOSURE_EXECUTED':
      return 'Foreclosure executed'
    case 'INVALIDATED':
      return 'Invalidated'
    case 'MANUAL_STATUS_OVERRIDE':
      return 'Manual admin override'
    case 'STATUS_TRANSITION':
    default:
      return 'Status transition'
  }
}

export function loanAccountStatusLabel(status?: LoanAccountStatus | null) {
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
    case 'INVALID':
      return 'Invalid'
    case 'CLOSED':
      return 'Closed'
    case 'FORECLOSED':
      return 'Foreclosed'
    default:
      return 'Account not created'
  }
}

export function loanAccountStatusVariant(
  status?: LoanAccountStatus | null,
): 'default' | 'success' | 'warning' | 'destructive' {
  switch (status) {
    case 'DISBURSED':
      return 'success'
    case 'DISBURSEMENT_PENDING_RECONCILIATION':
      return 'warning'
    case 'DISBURSEMENT_FAILED':
    case 'INVALID':
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

export function loanClosureReasonLabel(reason?: string | null) {
  switch ((reason ?? '').toUpperCase()) {
    case 'FULLY_REPAID':
      return 'Fully repaid'
    case 'FORECLOSURE':
      return 'Foreclosure'
    default:
      return reason ? reason.replace(/_/g, ' ').toLowerCase() : 'Not closed'
  }
}

export function loanPaymentChannelLabel(channel: LoanPaymentChannel) {
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

export function loanPaymentStatusLabel(status: LoanPaymentStatus) {
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

export function loanPaymentStatusVariant(
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

export function loanForeclosureQuoteStatusLabel(status: string) {
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

export function loanForeclosureQuoteStatusVariant(
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

export function loanRepaymentInstallmentStatusLabel(status?: string | null) {
  if (!status) {
    return 'Unknown'
  }

  return status
    .toLowerCase()
    .split('_')
    .map((segment) => segment.charAt(0).toUpperCase() + segment.slice(1))
    .join(' ')
}

export function loanRepaymentInstallmentStatusVariant(
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

export function loanDelinquencyBucketLabel(bucket?: string | null) {
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

export function loanDelinquencyBucketVariant(
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

export function loanActivityTypeLabel(
  activityType?: LoanApplicationLastActivityRecord['activityType'] | null,
) {
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
