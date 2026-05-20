import type { LoanApplicationRecord, LoanApplicationStatus } from '../api/lms-api'

export type LoanLedgerFilterState = {
  lspId: string
  productId: string
  status: string
  query: string
}

export const initialLoanLedgerFilters: LoanLedgerFilterState = {
  lspId: '',
  productId: '',
  status: '',
  query: '',
}

export type LoanLedgerMetrics = {
  activePortfolioValue: number
  pendingApprovalCount: number
  disbursedCount: number
}

export function buildLoanLedgerQuery(filters: LoanLedgerFilterState) {
  return {
    lspId: filters.lspId || undefined,
    productId: filters.productId || undefined,
    status: filters.status || undefined,
    query: filters.query.trim() || undefined,
  }
}

export function formatCompactLoanAmount(value: number) {
  if (!value) {
    return 'Rs 0'
  }

  const absoluteValue = Math.abs(value)

  if (absoluteValue >= 1e7) {
    return `Rs ${(value / 1e7).toFixed(2)} Cr`
  }

  if (absoluteValue >= 1e5) {
    return `Rs ${(value / 1e5).toFixed(2)} L`
  }

  if (absoluteValue >= 1e3) {
    return `Rs ${(value / 1e3).toFixed(1)} K`
  }

  return `Rs ${value}`
}

export function calculateLoanLedgerMetrics(
  applications: LoanApplicationRecord[],
): LoanLedgerMetrics {
  return applications.reduce<LoanLedgerMetrics>(
    (metrics, application) => {
      if (application.status === 'DISBURSED' || application.status === 'UNDER_REPAYMENT') {
        metrics.activePortfolioValue += application.requestedAmount
      }

      if (application.status === 'AWAITING_APPROVAL') {
        metrics.pendingApprovalCount += 1
      }

      if (application.status === 'DISBURSED') {
        metrics.disbursedCount += 1
      }

      return metrics
    },
    {
      activePortfolioValue: 0,
      pendingApprovalCount: 0,
      disbursedCount: 0,
    },
  )
}

export function getLoanLedgerStatusVariant(
  status: LoanApplicationStatus,
): 'default' | 'success' | 'warning' | 'destructive' {
  switch (status) {
    case 'DISBURSED':
    case 'UNDER_REPAYMENT':
      return 'success'
    case 'APPROVED_PENDING_DISBURSAL':
    case 'PAYMENT_REINITIATION':
    case 'INITIALIZED':
      return 'warning'
    case 'REJECTED':
    case 'INVALID':
      return 'destructive'
    case 'AWAITING_APPROVAL':
    case 'CLOSED':
    default:
      return 'default'
  }
}

export function getLoanLedgerBorrowerInitials(name: string) {
  const parts = name.trim().split(/\s+/).filter(Boolean)

  if (!parts.length) {
    return '--'
  }

  const first = parts[0]?.[0] ?? ''
  const last = parts.length > 1 ? parts[parts.length - 1]?.[0] ?? '' : ''

  return (first + last).toUpperCase()
}

export function getLoanLedgerAvatarClassName(index: number) {
  const tones = [
    'bg-primary/10 text-primary',
    'bg-sky-100 text-sky-800',
    'bg-violet-100 text-violet-800',
    'bg-slate-200 text-slate-800',
  ] as const

  return tones[index % tones.length]
}

export function getShortLoanId(id: string) {
  const normalized = (id || '').replace(/-/g, '').toUpperCase()
  return `BHAW${normalized.slice(0, 8)}`
}
