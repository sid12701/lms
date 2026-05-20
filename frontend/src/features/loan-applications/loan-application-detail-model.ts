import type {
  LoanAccountStatus,
  LoanApplicationDetailRecord,
  LoanApplicationDocumentPlaceholderRecord,
  LoanApplicationStatus,
} from '../api/lms-api'
import {
  currencyLabel,
  formatBorrowerMobile,
  formatBorrowerPan,
  formatEmploymentType,
  formatIncomeLabel,
  formatTimestamp,
} from './loan-application-formatters'
import {
  loanAccountStatusLabel,
  loanAccountStatusVariant,
  loanDelinquencyBucketLabel,
  loanRepaymentInstallmentStatusLabel,
  loanRepaymentInstallmentStatusVariant,
  loanStatusLabel,
  loanStatusVariant,
} from './loan-application-workflow'

export type LoanDetailAction = {
  id: string
  label: string
  pendingLabel: string
  variant: 'default' | 'destructive'
  kind: 'transition' | 'disburse'
  targetStatus?: LoanApplicationStatus
}

export function formatRelativeTime(value: string, now = Date.now()) {
  const diff = now - new Date(value).getTime()
  const minutes = Math.floor(diff / 60_000)

  if (minutes < 1) {
    return 'just now'
  }

  if (minutes < 60) {
    return `${minutes}m ago`
  }

  const hours = Math.floor(minutes / 60)
  if (hours < 24) {
    return `${hours}h ago`
  }

  const days = Math.floor(hours / 24)
  return `${days}d ago`
}

export function getBorrowerInitials(name?: string | null) {
  const normalizedName = name?.trim()

  if (!normalizedName) {
    return '?'
  }

  return normalizedName
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((segment) => segment[0].toUpperCase())
    .join('')
}

export function formatMonthYearLabel(value?: string | null, fallback = 'Not scheduled') {
  if (!value) {
    return fallback
  }

  return new Date(value).toLocaleDateString('en-IN', {
    month: 'short',
    year: 'numeric',
  })
}

export function formatBorrowerLocation(city?: string | null, state?: string | null) {
  const location = [city, state].filter(Boolean).join(', ')
  return location || 'Not provided'
}

export function loanDocumentStatusVariant(
  status?: LoanApplicationDocumentPlaceholderRecord['status'] | null,
): 'default' | 'success' | 'warning' | 'destructive' {
  switch (status) {
    case 'VERIFIED':
      return 'success'
    case 'PENDING':
      return 'warning'
    case 'REJECTED':
      return 'destructive'
    default:
      return 'default'
  }
}

export function loanDocumentStatusLabel(status?: string | null) {
  return status ? status.replace(/_/g, ' ') : 'Unknown'
}

export function getLoanDetailActions(
  loan: LoanApplicationDetailRecord,
  roles: string[] | undefined,
): LoanDetailAction[] {
  const currentRoles = roles ?? []
  const isAdmin = currentRoles.includes('SYSTEM_ADMIN')
  const isOps = currentRoles.includes('OPS_USER')
  const actions: LoanDetailAction[] = []

  if (loan.status === 'INITIALIZED' && (isAdmin || isOps)) {
    actions.push({
      id: 'submit-for-review',
      label: 'Submit for Review',
      pendingLabel: 'Submitting...',
      variant: 'default',
      kind: 'transition',
      targetStatus: 'AWAITING_APPROVAL',
    })
  }

  if (loan.status === 'AWAITING_APPROVAL' && isAdmin) {
    actions.push(
      {
        id: 'reject-application',
        label: 'Reject',
        pendingLabel: 'Rejecting...',
        variant: 'destructive',
        kind: 'transition',
        targetStatus: 'REJECTED',
      },
      {
        id: 'approve-application',
        label: 'Approve',
        pendingLabel: 'Approving...',
        variant: 'default',
        kind: 'transition',
        targetStatus: 'APPROVED_PENDING_DISBURSAL',
      },
    )
  }

  if (
    loan.status === 'APPROVED_PENDING_DISBURSAL' &&
    loan.loanAccount?.status === 'PENDING_DISBURSEMENT' &&
    isAdmin
  ) {
    actions.push({
      id: 'initiate-disbursal',
      label: 'Initiate Disbursal',
      pendingLabel: 'Initiating...',
      variant: 'default',
      kind: 'disburse',
    })
  }

  return actions
}

export function describeLastUpdated(loan: LoanApplicationDetailRecord) {
  if (loan.lastActivity) {
    return `Last modified ${formatRelativeTime(loan.lastActivity.occurredAt)}${
      loan.lastActivity.actorUsername ? ` by ${loan.lastActivity.actorUsername}` : ''
    }`
  }

  return `Created ${formatRelativeTime(loan.createdAt)}`
}

export function getBorrowerProfileEntries(loan: LoanApplicationDetailRecord) {
  return [
    { label: 'PAN', value: formatBorrowerPan(loan.borrowerPan) },
    { label: 'Mobile', value: formatBorrowerMobile(loan.borrowerMobile) },
    { label: 'Email', value: loan.borrowerEmail || 'Not provided' },
    {
      label: 'Location',
      value: formatBorrowerLocation(loan.borrowerCity, loan.borrowerState),
    },
    {
      label: 'Employment',
      value: formatEmploymentType(loan.borrowerEmploymentType),
    },
    {
      label: 'Monthly Income',
      value: formatIncomeLabel(loan.borrowerMonthlyIncome),
    },
    {
      label: 'Assigned To',
      value: loan.assignedToUsername || 'Unassigned',
    },
  ]
}

export function getApplicationInfoEntries(loan: LoanApplicationDetailRecord) {
  const entries = [
    { label: 'LSP', value: loan.lspName },
    { label: 'LSP Code', value: loan.lspCode },
    { label: 'Source Channel', value: loan.sourceChannel },
    { label: 'External Loan ID', value: loan.externalLoanId || 'Not provided' },
    { label: 'Created', value: formatTimestamp(loan.createdAt) },
  ]

  if (loan.invalidReasonCode) {
    entries.push({
      label: 'Invalid Reason',
      value: loan.invalidReasonText
        ? `${loan.invalidReasonCode} - ${loan.invalidReasonText}`
        : loan.invalidReasonCode,
    })
  }

  if (loan.invalidatedAt) {
    entries.push({
      label: 'Invalidated',
      value: `${formatTimestamp(loan.invalidatedAt)}${
        loan.invalidatedByUsername ? ` by ${loan.invalidatedByUsername}` : ''
      }`,
    })
  }

  if (loan.loanAccount?.delinquency) {
    entries.push({
      label: 'Delinquency',
      value: `${loanDelinquencyBucketLabel(loan.loanAccount.delinquency.bucket)} (${loan.loanAccount.delinquency.maxDaysPastDue}d DPD)`,
    })
  }

  return entries
}

export function getOverviewMetrics(loan: LoanApplicationDetailRecord) {
  const metrics = [
    { label: 'Principal', value: currencyLabel(loan.requestedAmount) },
    { label: 'Tenure', value: `${loan.tenureMonths} months` },
    { label: 'Product', value: loan.productName },
  ]

  if (loan.loanAccount?.repaymentSchedule) {
    metrics.push(
      {
        label: 'EMI Amount',
        value: currencyLabel(loan.loanAccount.repaymentSchedule.installmentAmount),
      },
      {
        label: 'First Due',
        value: formatMonthYearLabel(loan.loanAccount.repaymentSchedule.firstDueDate),
      },
      {
        label: 'Final Due',
        value: formatMonthYearLabel(loan.loanAccount.repaymentSchedule.finalDueDate),
      },
    )
  }

  if (loan.loanAccount?.delinquency) {
    metrics.push(
      { label: 'Max DPD', value: `${loan.loanAccount.delinquency.maxDaysPastDue}d` },
      {
        label: 'DPD Bucket',
        value: loanDelinquencyBucketLabel(loan.loanAccount.delinquency.bucket),
      },
      {
        label: 'Overdue Amount',
        value: currencyLabel(loan.loanAccount.delinquency.overdueAmount),
      },
    )
  }

  return metrics
}

export function getLoanStatusBadge(status: LoanApplicationStatus) {
  return {
    label: loanStatusLabel(status),
    variant: loanStatusVariant(status),
  }
}

export function getLoanAccountBadge(status?: LoanAccountStatus | null) {
  return {
    label: loanAccountStatusLabel(status),
    variant: loanAccountStatusVariant(status),
  }
}

export function getInstallmentBadge(status?: string | null) {
  return {
    label: loanRepaymentInstallmentStatusLabel(status),
    variant: loanRepaymentInstallmentStatusVariant(status),
  }
}
