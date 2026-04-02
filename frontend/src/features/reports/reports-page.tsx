import { useEffect, useState } from 'react'
import { Badge } from '../../components/ui/badge'
import { Button } from '../../components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../../components/ui/card'
import {
  downloadPortfolioMisReport,
  listLsps,
  type LspRecord,
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
  const [selectedLspId, setSelectedLspId] = useState('')
  const [disbursalDateFrom, setDisbursalDateFrom] = useState('')
  const [disbursalDateTo, setDisbursalDateTo] = useState('')
  const [loading, setLoading] = useState(true)
  const [downloading, setDownloading] = useState(false)
  const [error, setError] = useState('')

  useEffect(() => {
    let cancelled = false

    async function loadLsps() {
      setLoading(true)
      setError('')

      try {
        const response = await listLsps()
        if (!cancelled) {
          setLsps(response)
        }
      } catch (loadError) {
        if (!cancelled) {
          setError(loadError instanceof Error ? loadError.message : 'Unable to load LSP filters.')
        }
      } finally {
        if (!cancelled) {
          setLoading(false)
        }
      }
    }

    void loadLsps()

    return () => {
      cancelled = true
    }
  }, [])

  async function handleDownload() {
    if (disbursalDateFrom && disbursalDateTo && disbursalDateFrom > disbursalDateTo) {
      setError('Disbursal date from cannot be after disbursal date to.')
      return
    }

    setDownloading(true)
    setError('')

    try {
      const response = await downloadPortfolioMisReport({
        lspId: selectedLspId || undefined,
        disbursalDateFrom: disbursalDateFrom || undefined,
        disbursalDateTo: disbursalDateTo || undefined,
      })
      triggerDownload(response.blob, response.filename ?? 'portfolio-mis.csv')
    } catch (downloadError) {
      setError(downloadError instanceof Error ? downloadError.message : 'Unable to download the MIS report.')
    } finally {
      setDownloading(false)
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
            <Badge variant="warning">CSV export</Badge>
          </div>
          {loading ? <div className="empty-state">Loading report filters...</div> : null}
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
                  CSV columns include LSP, application id, external loan id, borrower, product, account status,
                  disbursal date, delinquency bucket, overdue amount, and closure state.
                </div>
              </div>
              {error ? <div className="empty-state">{error}</div> : null}
              <Button type="button" onClick={() => void handleDownload()} disabled={loading || downloading}>
                {downloading ? 'Downloading...' : 'Download portfolio MIS'}
              </Button>
            </div>
          ) : null}
        </CardContent>
      </Card>
    </div>
  )
}
