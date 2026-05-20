import { useCallback, useEffect, useMemo, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { downloadBlob } from '@/lib/download-blob'
import { queryKeys } from '@/features/api/query-keys'
import {
  type ReportRequestRecord,
  type LspOptionRecord,
} from '@/features/api/lms-api'
import { listLspOptions } from '@/features/api/admin-api'
import {
  downloadPortfolioMisReport,
  downloadReportRequest,
  getPortfolioMisSummary,
  listReportRequests,
  previewPortfolioMisReport,
  requestPortfolioMisReport,
} from '@/features/api/reports-api'
import {
  REPORT_PAGE_SIZE,
  buildReportFilters,
  hasInvalidReportDateRange,
  type ReportFilters,
} from './reports-model'

export function useReportsPage() {
  const queryClient = useQueryClient()
  const [selectedLspId, setSelectedLspId] = useState('')
  const [disbursalDateFrom, setDisbursalDateFrom] = useState('')
  const [disbursalDateTo, setDisbursalDateTo] = useState('')
  const [recipientEmail, setRecipientEmail] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [refreshingRequests, setRefreshingRequests] = useState(false)
  const [localError, setLocalError] = useState('')
  const [success, setSuccess] = useState('')
  const [currentPage, setCurrentPage] = useState(1)
  const [appliedFilters, setAppliedFilters] = useState<ReportFilters>({})

  const lspQuery = useQuery({
    queryKey: queryKeys.lspOptions,
    queryFn: listLspOptions,
  })
  const requestsQuery = useQuery({
    queryKey: queryKeys.reportRequests,
    queryFn: listReportRequests,
  })
  const previewQuery = useQuery({
    queryKey: queryKeys.portfolioMisPreview(appliedFilters, currentPage - 1, REPORT_PAGE_SIZE),
    queryFn: () => previewPortfolioMisReport({ ...appliedFilters, page: currentPage - 1, size: REPORT_PAGE_SIZE }),
  })
  const summaryQuery = useQuery({
    queryKey: queryKeys.portfolioMisSummary(appliedFilters),
    queryFn: () => getPortfolioMisSummary(appliedFilters),
  })

  const lsps: LspOptionRecord[] = lspQuery.data ?? []
  const requests = requestsQuery.data ?? []
  const previewRows = previewQuery.data?.content ?? []
  const totalElements = previewQuery.data?.totalElements ?? 0
  const summary = summaryQuery.data ?? null
  const loading = lspQuery.isLoading || requestsQuery.isLoading || previewQuery.isLoading || summaryQuery.isLoading
  const previewing = !loading && (previewQuery.isFetching || summaryQuery.isFetching)
  const queryError = lspQuery.error ?? requestsQuery.error ?? previewQuery.error ?? summaryQuery.error
  const error = localError || (queryError instanceof Error ? queryError.message : '')
  const initialLoadDone = !loading

  const draftFilters = useMemo(
    () =>
      buildReportFilters({
        selectedLspId,
        disbursalDateFrom,
        disbursalDateTo,
      }),
    [selectedLspId, disbursalDateFrom, disbursalDateTo],
  )

  const hasPendingRequests = useMemo(
    () => requests.some((request) => request.status === 'PENDING' || request.status === 'PROCESSING'),
    [requests],
  )

  const maxInstallments = useMemo(
    () => previewRows.reduce((max, row) => Math.max(max, row.installments.length), 0),
    [previewRows],
  )

  const totalPages = Math.max(1, Math.ceil(totalElements / REPORT_PAGE_SIZE))
  const showingFrom = totalElements === 0 ? 0 : (currentPage - 1) * REPORT_PAGE_SIZE + 1
  const showingTo = Math.min(currentPage * REPORT_PAGE_SIZE, totalElements)

  const refreshRequests = useCallback(async (showSpinner = true) => {
    if (showSpinner) {
      setRefreshingRequests(true)
    }

    try {
      const response = await listReportRequests()
      queryClient.setQueryData<ReportRequestRecord[]>(queryKeys.reportRequests, response)
    } catch (loadError) {
      setLocalError(loadError instanceof Error ? loadError.message : 'Unable to refresh report history.')
    } finally {
      if (showSpinner) {
        setRefreshingRequests(false)
      }
    }
  }, [queryClient])

  useEffect(() => {
    if (!hasPendingRequests) {
      return
    }

    const intervalId = window.setInterval(() => {
      void refreshRequests(false)
    }, 5000)

    return () => {
      window.clearInterval(intervalId)
    }
  }, [hasPendingRequests, refreshRequests])

  const handlePreview = useCallback(async () => {
    if (hasInvalidReportDateRange(disbursalDateFrom, disbursalDateTo)) {
      setLocalError('Disbursal date from cannot be after disbursal date to.')
      return
    }

    setLocalError('')
    setAppliedFilters(draftFilters)
    setCurrentPage(1)
  }, [disbursalDateFrom, disbursalDateTo, draftFilters])

  const handlePageChange = useCallback(
    async (page: number) => {
      if (page === currentPage || page < 1 || page > totalPages) {
        return
      }

      setLocalError('')
      setCurrentPage(page)
    },
    [currentPage, totalPages],
  )

  const handleDownloadCsv = useCallback(async () => {
    setLocalError('')

    try {
      const response = await downloadPortfolioMisReport(draftFilters)
      downloadBlob(response.blob, response.filename ?? 'portfolio-mis.csv')
    } catch (downloadError) {
      setLocalError(downloadError instanceof Error ? downloadError.message : 'Unable to download the MIS report.')
    }
  }, [draftFilters])

  const handleGenerate = useCallback(async () => {
    if (hasInvalidReportDateRange(disbursalDateFrom, disbursalDateTo)) {
      setLocalError('Disbursal date from cannot be after disbursal date to.')
      return
    }

    setSubmitting(true)
    setLocalError('')
    setSuccess('')

    try {
      const reportRequest = await requestPortfolioMisReport({
        ...draftFilters,
        recipientEmail: recipientEmail.trim() || undefined,
      })

      queryClient.setQueryData<ReportRequestRecord[]>(queryKeys.reportRequests, (current = []) => [
        reportRequest,
        ...current.filter((item) => item.id !== reportRequest.id),
      ])
      setSuccess(
        reportRequest.notificationEmail
          ? 'Report request queued. A completion email will be sent once processing finishes.'
          : 'Report request queued. The file will appear in history once processing completes.',
      )
    } catch (requestError) {
      setLocalError(requestError instanceof Error ? requestError.message : 'Unable to queue the MIS report.')
    } finally {
      setSubmitting(false)
    }
  }, [disbursalDateFrom, disbursalDateTo, draftFilters, recipientEmail, queryClient])

  const handleDownloadRequest = useCallback(async (requestId: string) => {
    setLocalError('')

    try {
      const response = await downloadReportRequest(requestId)
      downloadBlob(response.blob, response.filename ?? 'portfolio-mis.csv')
    } catch (downloadError) {
      setLocalError(
        downloadError instanceof Error ? downloadError.message : 'Unable to download the generated report.',
      )
    }
  }, [])

  return {
    lsps,
    requests,
    selectedLspId,
    setSelectedLspId,
    disbursalDateFrom,
    setDisbursalDateFrom,
    disbursalDateTo,
    setDisbursalDateTo,
    recipientEmail,
    setRecipientEmail,
    loading,
    submitting,
    refreshingRequests,
    error,
    success,
    previewRows,
    summary,
    previewing,
    initialLoadDone,
    currentPage,
    totalElements,
    totalPages,
    maxInstallments,
    showingFrom,
    showingTo,
    handlePreview,
    handlePageChange,
    handleDownloadCsv,
    handleGenerate,
    handleDownloadRequest,
    refreshRequests,
  }
}
