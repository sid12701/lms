import { useEffect, useMemo, useState } from 'react'
import { Badge } from '../../components/ui/badge'
import { Button } from '../../components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../../components/ui/card'
import {
  downloadReportRequest,
  listLsps,
  listReportRequests,
  requestPortfolioMisReport,
  type LspRecord,
  type ReportRequestRecord,
  type ReportRequestStatus,
} from '../api/lms-api'

function triggerDownload(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  URL.revokeObjectURL(url)
}

export function ReportsPage() {
  const [lsps, setLsps] = useState<LspRecord[]>([])
  const [requests, setRequests] = useState<ReportRequestRecord[]>([])
  const [selectedLspId, setSelectedLspId] = useState('')
  const [disbursalDateFrom, setDisbursalDateFrom] = useState('')
  const [disbursalDateTo, setDisbursalDateTo] = useState('')
  const [loading, setLoading] = useState(true)
  const [submitting, setSubmitting] = useState(false)
  const [refreshingRequests, setRefreshingRequests] = useState(false)
  const [error, setError] = useState('')
  const [success, setSuccess] = useState('')

  const hasPendingRequests = useMemo(
    () => requests.some((request) => request.status === 'PENDING' || request.status === 'PROCESSING'),
    [requests],
  )

  useEffect(() => {
    let cancelled = false

    async function loadPage() {
      setLoading(true)
      setError('')

      try {
        const [lspResponse, requestResponse] = await Promise.all([listLsps(), listReportRequests()])
        if (!cancelled) {
          setLsps(lspResponse)
          setRequests(requestResponse)
        }
      } catch (loadError) {
        if (!cancelled) {
          setError(loadError instanceof Error ? loadError.message : 'Unable to load report configuration.')
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
    if (!hasPendingRequests) {
      return
    }

    const intervalId = window.setInterval(() => {
      void refreshRequests(false)
    }, 5000)

    return () => {
      window.clearInterval(intervalId)
    }
  }, [hasPendingRequests])

  async function refreshRequests(showSpinner = true) {
    if (showSpinner) {
      setRefreshingRequests(true)
    }

    try {
      const response = await listReportRequests()
      setRequests(response)
    } catch (loadError) {
      setError(loadError instanceof Error ? loadError.message : 'Unable to refresh report history.')
    } finally {
      if (showSpinner) {
        setRefreshingRequests(false)
      }
    }
  }

  async function handleGenerate() {
    if (disbursalDateFrom && disbursalDateTo && disbursalDateFrom > disbursalDateTo) {
      setError('Disbursal date from cannot be after disbursal date to.')
      return
    }

    setSubmitting(true)
    setError('')
    setSuccess('')

    try {
      const reportRequest = await requestPortfolioMisReport({
        lspId: selectedLspId || undefined,
        disbursalDateFrom: disbursalDateFrom || undefined,
        disbursalDateTo: disbursalDateTo || undefined,
      })
      setRequests((current) => [reportRequest, ...current.filter((item) => item.id !== reportRequest.id)])
      setSuccess('Report request queued. The file will appear in history once processing completes.')
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : 'Unable to queue the MIS report.')
    } finally {
      setSubmitting(false)
    }
  }

  async function handleDownload(requestId: string) {
    setError('')

    try {
      const response = await downloadReportRequest(requestId)
      triggerDownload(response.blob, response.filename ?? 'portfolio-mis.csv')
    } catch (downloadError) {
      setError(downloadError instanceof Error ? downloadError.message : 'Unable to download the generated report.')
    }
  }

  function statusVariant(status: ReportRequestStatus): 'default' | 'warning' | 'success' {
    switch (status) {
      case 'COMPLETED':
        return 'success'
      case 'FAILED':
        return 'warning'
      default:
        return 'default'
    }
  }

  return (
    <div className="users-layout">
      <Card className="content-card">
        <CardHeader>
          <div className="section-eyebrow">Internal reporting</div>
          <CardTitle>Portfolio MIS</CardTitle>
          <CardDescription>
            Generate the day-one admin CSV for all LSPs or a single tenant, filtered by disbursal date.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <div className="inline-actions" style={{ marginBottom: '1rem' }}>
            <Badge>{lsps.length} tenants</Badge>
            <Badge variant="warning">Async generation</Badge>
          </div>
          {loading ? <div className="empty-state">Loading report filters and history...</div> : null}
          {!loading ? (
            <div className="form-grid">
              <div className="field-stack">
                <label htmlFor="report-lsp">LSP</label>
                <select
                  id="report-lsp"
                  className="ui-input"
                  value={selectedLspId}
                  onChange={(event) => setSelectedLspId(event.target.value)}
                >
                  <option value="">All LSPs</option>
                  {lsps.map((lsp) => (
                    <option key={lsp.id} value={lsp.id}>
                      {lsp.code} - {lsp.name}
                    </option>
                  ))}
                </select>
              </div>
              <div className="field-stack">
                <label htmlFor="report-disbursal-from">Disbursal date from</label>
                <input
                  id="report-disbursal-from"
                  className="ui-input"
                  type="date"
                  value={disbursalDateFrom}
                  onChange={(event) => setDisbursalDateFrom(event.target.value)}
                />
              </div>
              <div className="field-stack">
                <label htmlFor="report-disbursal-to">Disbursal date to</label>
                <input
                  id="report-disbursal-to"
                  className="ui-input"
                  type="date"
                  value={disbursalDateTo}
                  onChange={(event) => setDisbursalDateTo(event.target.value)}
                />
              </div>
              <div className="field-stack" style={{ gridColumn: '1 / -1' }}>
                <div className="helper-copy">
                  Queue the day-one MIS export for background processing. CSV columns include LSP, application id,
                  external loan id, borrower, product, account status, disbursal date, delinquency bucket, overdue
                  amount, and closure state.
                </div>
              </div>
              {error ? <div className="empty-state">{error}</div> : null}
              {success ? <div className="empty-state">{success}</div> : null}
              <Button type="button" onClick={() => void handleGenerate()} disabled={loading || submitting}>
                {submitting ? 'Queueing...' : 'Generate portfolio MIS'}
              </Button>
            </div>
          ) : null}
        </CardContent>
      </Card>

      <Card className="list-card">
        <CardHeader>
          <div className="section-eyebrow">Report history</div>
          <CardTitle>Generated requests</CardTitle>
          <CardDescription>
            Review recent admin report requests and download completed files once the worker finishes processing them.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <div className="inline-actions" style={{ marginBottom: '1rem' }}>
            <Badge>{requests.length} requests</Badge>
            <Button type="button" variant="outline" onClick={() => void refreshRequests()} disabled={refreshingRequests}>
              {refreshingRequests ? 'Refreshing...' : 'Refresh history'}
            </Button>
          </div>
          {!requests.length ? <div className="empty-state">No report requests have been queued yet.</div> : null}
          {!!requests.length ? (
            <div className="table-grid">
              {requests.map((request) => (
                <div className="table-row" key={request.id}>
                  <div>
                    <strong>{request.reportType}</strong>
                    <p className="helper-copy">
                      Requested by {request.requestedByUsername} · {new Date(request.createdAt).toLocaleString()}
                    </p>
                    <p className="helper-copy">
                      {request.lspName ?? 'All LSPs'} ·{' '}
                      {request.disbursalDateFrom || 'Start'} to {request.disbursalDateTo || 'End'}
                    </p>
                    {request.errorMessage ? <p className="helper-copy">{request.errorMessage}</p> : null}
                  </div>
                  <Badge variant={statusVariant(request.status)}>{request.status}</Badge>
                  <span className="helper-copy">
                    {request.completedAt ? new Date(request.completedAt).toLocaleString() : 'Awaiting worker'}
                  </span>
                  <Button
                    type="button"
                    variant="ghost"
                    onClick={() => void handleDownload(request.id)}
                    disabled={request.status !== 'COMPLETED'}
                  >
                    Download
                  </Button>
                </div>
              ))}
            </div>
          ) : null}
        </CardContent>
      </Card>
    </div>
  )
}
