import { buildQueryPath, requestBlob, requestJson } from './http-client'
import type {
  MisPortfolioSummary,
  MisPreviewPage,
  ReportRequestRecord,
} from './lms-api'

export function downloadPortfolioMisReport(filters?: {
  lspId?: string
  disbursalDateFrom?: string
  disbursalDateTo?: string
}) {
  return requestBlob(
    buildQueryPath('/api/v1/internal/reports/portfolio-mis', {
      lspId: filters?.lspId,
      disbursalDateFrom: filters?.disbursalDateFrom,
      disbursalDateTo: filters?.disbursalDateTo,
    }),
  )
}

export function requestPortfolioMisReport(payload: {
  lspId?: string
  disbursalDateFrom?: string
  disbursalDateTo?: string
  recipientEmail?: string
}) {
  return requestJson<ReportRequestRecord>('/api/v1/internal/reports/portfolio-mis/requests', {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

export function listReportRequests() {
  return requestJson<ReportRequestRecord[]>('/api/v1/internal/reports/requests')
}

export function downloadReportRequest(requestId: string) {
  return requestBlob(`/api/v1/internal/reports/requests/${requestId}/download`)
}

export function getPortfolioMisSummary(filters?: {
  lspId?: string
  disbursalDateFrom?: string
  disbursalDateTo?: string
}) {
  return requestJson<MisPortfolioSummary>(
    buildQueryPath('/api/v1/internal/reports/portfolio-mis/summary', {
      lspId: filters?.lspId,
      disbursalDateFrom: filters?.disbursalDateFrom,
      disbursalDateTo: filters?.disbursalDateTo,
    }),
  )
}

export function previewPortfolioMisReport(filters?: {
  lspId?: string
  disbursalDateFrom?: string
  disbursalDateTo?: string
  page?: number
  size?: number
}) {
  return requestJson<MisPreviewPage>(
    buildQueryPath('/api/v1/internal/reports/portfolio-mis/preview', {
      lspId: filters?.lspId,
      disbursalDateFrom: filters?.disbursalDateFrom,
      disbursalDateTo: filters?.disbursalDateTo,
      page: filters?.page,
      size: filters?.size,
    }),
  )
}
