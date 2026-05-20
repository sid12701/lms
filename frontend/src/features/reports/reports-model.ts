import type {
  MisPortfolioSummary,
  ReportRequestRecord,
  ReportRequestStatus,
} from '@/features/api/lms-api'

export const REPORT_PAGE_SIZE = 50

export type ReportFilters = {
  lspId?: string
  disbursalDateFrom?: string
  disbursalDateTo?: string
}

export type ReportDraftFilters = {
  selectedLspId: string
  disbursalDateFrom: string
  disbursalDateTo: string
}

export type ReportBadgeTone = 'default' | 'success' | 'warning' | 'danger'

export function buildReportFilters({
  selectedLspId,
  disbursalDateFrom,
  disbursalDateTo,
}: ReportDraftFilters): ReportFilters {
  return {
    lspId: selectedLspId || undefined,
    disbursalDateFrom: disbursalDateFrom || undefined,
    disbursalDateTo: disbursalDateTo || undefined,
  }
}

export function hasInvalidReportDateRange(disbursalDateFrom: string, disbursalDateTo: string) {
  return Boolean(disbursalDateFrom && disbursalDateTo && disbursalDateFrom > disbursalDateTo)
}

export function formatReportCurrency(value: number | null | undefined) {
  if (value == null) {
    return '--'
  }

  return value.toLocaleString('en-IN', { maximumFractionDigits: 2 })
}

export function formatCompactPortfolioAmount(value: number | null | undefined) {
  const amount = value ?? 0

  if (amount >= 1_00_00_000) {
    return `Rs ${(amount / 1_00_00_000).toFixed(1)} Cr`
  }

  if (amount >= 1_00_000) {
    return `Rs ${(amount / 1_00_000).toFixed(1)} L`
  }

  return `Rs ${formatReportCurrency(amount)}`
}

export function generateReportPageNumbers(
  current: number,
  total: number,
): Array<number | '...'> {
  if (total <= 7) {
    return Array.from({ length: total }, (_, index) => index + 1)
  }

  const pages: Array<number | '...'> = [1]

  if (current > 3) {
    pages.push('...')
  }

  for (
    let page = Math.max(2, current - 1);
    page <= Math.min(total - 1, current + 1);
    page += 1
  ) {
    pages.push(page)
  }

  if (current < total - 2) {
    pages.push('...')
  }

  pages.push(total)

  return pages
}

export function resolvePreviewStatusTone(status: string): ReportBadgeTone {
  switch (status.toUpperCase()) {
    case 'ACTIVE':
    case 'CLOSED':
      return 'success'
    case 'DELINQUENT':
      return 'danger'
    case 'FORECLOSED':
      return 'warning'
    default:
      return 'default'
  }
}

export function resolveReportRequestTone(status: ReportRequestStatus): ReportBadgeTone {
  switch (status) {
    case 'COMPLETED':
      return 'success'
    case 'FAILED':
      return 'danger'
    case 'PENDING':
    case 'PROCESSING':
      return 'warning'
    default:
      return 'default'
  }
}

export function formatReportDateTime(value: string | null | undefined, fallback = '--') {
  if (!value) {
    return fallback
  }

  return new Date(value).toLocaleString('en-IN')
}

export function formatReportDateRange(
  from: string | null | undefined,
  to: string | null | undefined,
) {
  return `${from || 'Start'} to ${to || 'End'}`
}

export function getSummaryMetrics(summary: MisPortfolioSummary | null) {
  return [
    {
      label: 'Total Disbursed (MTD)',
      value: formatCompactPortfolioAmount(summary?.totalDisbursed),
    },
    {
      label: 'Active Loan Count',
      value: (summary?.activeLoanCount ?? 0).toLocaleString('en-IN'),
    },
    {
      label: 'Weighted Avg. Yield',
      value: `${(summary?.weightedAvgInterestRate ?? 0).toFixed(1)}%`,
    },
    {
      label: 'Portfolio at Risk (PAR 30)',
      value: `${(summary?.portfolioAtRiskPct ?? 0).toFixed(2)}%`,
    },
  ]
}

export function formatRequestMeta(
  request: Pick<ReportRequestRecord, 'requestedByUsername' | 'createdAt'>,
) {
  return `${request.requestedByUsername} - ${formatReportDateTime(request.createdAt)}`
}
