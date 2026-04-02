import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { Badge } from '../../components/ui/badge'
import { Button } from '../../components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../../components/ui/card'
import { Input } from '../../components/ui/input'
import {
  createLsp,
  getAdminMetadata,
  getLspDetail,
  listLsps,
  updateLspWebhookSubscription,
  type AdminMetadata,
  type LspDetailRecord,
  type LspPortfolioSummaryRecord,
  type LspRecord,
  type LspStatus,
  type WebhookEventType,
  webhookEventTypeOptions,
} from '../api/lms-api'

function statusVariant(status: LspStatus): 'success' | 'warning' {
  return status === 'ACTIVE' ? 'success' : 'warning'
}

function currencyLabel(value: number) {
  return new Intl.NumberFormat('en-IN', {
    style: 'currency',
    currency: 'INR',
    maximumFractionDigits: 0,
  }).format(value)
}

function formatDateLabel(value?: string | null) {
  if (!value) {
    return 'No disbursal yet'
  }

  return new Intl.DateTimeFormat('en-IN', { dateStyle: 'medium' }).format(new Date(value))
}

function webhookEventTypeLabel(eventType: WebhookEventType) {
  switch (eventType) {
    case 'LOAN_CREATED':
      return 'Loan created'
    case 'LOAN_STATUS_CHANGED':
      return 'Loan status changed'
    case 'LOAN_DISBURSEMENT_UPDATED':
      return 'Disbursement updated'
    case 'LOAN_REPAYMENT_RECORDED':
      return 'Repayment recorded'
    case 'LOAN_FORECLOSURE_COMPLETED':
      return 'Foreclosure completed'
  }
}

function renderSummaryBadges(summary: LspPortfolioSummaryRecord) {
  return (
    <div className="inline-actions">
      <Badge>{summary.loanApplicationCount} loans</Badge>
      <Badge variant="success">{summary.disbursedLoanCount} disbursed</Badge>
      <Badge variant="warning">{currencyLabel(summary.totalDisbursedAmount)}</Badge>
    </div>
  )
}

export function LspAdminPage() {
  const [lsps, setLsps] = useState<LspRecord[]>([])
  const [metadata, setMetadata] = useState<AdminMetadata | null>(null)
  const [selectedLspId, setSelectedLspId] = useState('')
  const [selectedLsp, setSelectedLsp] = useState<LspDetailRecord | null>(null)
  const [code, setCode] = useState('')
  const [name, setName] = useState('')
  const [status, setStatus] = useState<LspStatus | ''>('')
  const [webhookEnabled, setWebhookEnabled] = useState(false)
  const [webhookEndpointUrl, setWebhookEndpointUrl] = useState('')
  const [webhookSigningSecret, setWebhookSigningSecret] = useState('')
  const [webhookEventTypes, setWebhookEventTypes] = useState<WebhookEventType[]>([])
  const [loading, setLoading] = useState(true)
  const [detailLoading, setDetailLoading] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [webhookSaving, setWebhookSaving] = useState(false)
  const [error, setError] = useState('')
  const [detailError, setDetailError] = useState('')
  const [webhookError, setWebhookError] = useState('')
  const [webhookSuccess, setWebhookSuccess] = useState('')

  const selectedListRecord = useMemo(
    () => lsps.find((item) => item.id === selectedLspId) ?? null,
    [lsps, selectedLspId],
  )

  useEffect(() => {
    let cancelled = false

    async function loadLsps() {
      setLoading(true)
      setError('')

      try {
        const [metadataResponse, response] = await Promise.all([getAdminMetadata(), listLsps()])
        if (!cancelled) {
          setMetadata(metadataResponse)
          setLsps(response)
          setStatus((current) => current || (metadataResponse.lspStatuses[0] as LspStatus | ''))
          setSelectedLspId((current) => current || response[0]?.id || '')
        }
      } catch (loadError) {
        if (!cancelled) {
          setError(loadError instanceof Error ? loadError.message : 'Unable to load LSPs.')
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

  useEffect(() => {
    if (!selectedLspId) {
      setSelectedLsp(null)
      return
    }

    let cancelled = false

    async function loadDetail() {
      setDetailLoading(true)
      setDetailError('')

      try {
        const response = await getLspDetail(selectedLspId)
        if (!cancelled) {
          setSelectedLsp(response)
        }
      } catch (loadError) {
        if (!cancelled) {
          setDetailError(loadError instanceof Error ? loadError.message : 'Unable to load LSP detail.')
        }
      } finally {
        if (!cancelled) {
          setDetailLoading(false)
        }
      }
    }

    void loadDetail()

    return () => {
      cancelled = true
    }
  }, [selectedLspId])

  useEffect(() => {
    const subscription = selectedLsp?.webhookSubscription ?? selectedListRecord?.webhookSubscription ?? null
    setWebhookEnabled(subscription?.enabled ?? false)
    setWebhookEndpointUrl(subscription?.endpointUrl ?? '')
    setWebhookSigningSecret(subscription?.signingSecret ?? '')
    setWebhookEventTypes(subscription?.eventTypes ?? [])
    setWebhookError('')
    setWebhookSuccess('')
  }, [selectedLsp, selectedListRecord])

  async function handleCreate(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!code.trim() || !name.trim()) {
      return
    }

    const nextStatus = status || (metadata?.lspStatuses[0] as LspStatus | undefined) || 'ACTIVE'

    setSubmitting(true)
    setError('')

    try {
      const created = await createLsp({
        code,
        name,
        status: nextStatus,
      })

      setLsps((current) => [created, ...current.filter((item) => item.id !== created.id)])
      setCode('')
      setName('')
      setStatus(nextStatus)
      setSelectedLspId(created.id)
    } catch (createError) {
      setError(createError instanceof Error ? createError.message : 'Unable to create LSP.')
    } finally {
      setSubmitting(false)
    }
  }

  function toggleWebhookEventType(eventType: WebhookEventType) {
    setWebhookEventTypes((current) =>
      current.includes(eventType) ? current.filter((item) => item !== eventType) : [...current, eventType],
    )
  }

  async function handleWebhookSave(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!selectedLspId) {
      return
    }

    const trimmedEndpoint = webhookEndpointUrl.trim()
    const trimmedSecret = webhookSigningSecret.trim()

    if (webhookEnabled && !trimmedEndpoint) {
      setWebhookError('Endpoint URL is required when the webhook subscription is enabled.')
      return
    }

    if (webhookEnabled) {
      try {
        new URL(trimmedEndpoint)
      } catch {
        setWebhookError('Endpoint URL must be a valid absolute URL.')
        return
      }
    }

    if (webhookEnabled && !trimmedSecret) {
      setWebhookError('Signing secret is required when the webhook subscription is enabled.')
      return
    }

    if (webhookEnabled && !webhookEventTypes.length) {
      setWebhookError('Select at least one webhook event type.')
      return
    }

    setWebhookSaving(true)
    setWebhookError('')
    setWebhookSuccess('')

    try {
      await updateLspWebhookSubscription(selectedLspId, {
        enabled: webhookEnabled,
        endpointUrl: webhookEnabled ? trimmedEndpoint : undefined,
        signingSecret: webhookEnabled ? trimmedSecret : undefined,
        eventTypes: webhookEventTypes,
      })

      const [lspList, detail] = await Promise.all([listLsps(), getLspDetail(selectedLspId)])
      setLsps(lspList)
      setSelectedLsp(detail)
      setWebhookSuccess('Webhook subscription saved.')
    } catch (saveError) {
      setWebhookError(saveError instanceof Error ? saveError.message : 'Unable to save webhook settings.')
    } finally {
      setWebhookSaving(false)
    }
  }

  return (
    <div className="users-layout">
      <Card className="list-card">
        <CardHeader>
          <div className="section-eyebrow">LSPs</div>
          <CardTitle>Tenant registry</CardTitle>
          <CardDescription>
            Review every LSP in collapsed form, then open one tenant to inspect users, loan count, and disbursal
            summary.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <div className="inline-actions" style={{ marginBottom: '1rem' }}>
            <Badge>{lsps.length} tenants</Badge>
            <Badge variant="warning">{metadata?.lspStatuses.length ?? 0} statuses</Badge>
          </div>
          {loading ? <div className="empty-state">Loading tenant registry...</div> : null}
          {error ? <div className="empty-state">{error}</div> : null}
          {!loading && !error ? (
            <div className="table-grid">
              {lsps.map((lsp) => (
                <button
                  key={lsp.id}
                  type="button"
                  className="table-row"
                  onClick={() => setSelectedLspId(lsp.id)}
                  style={{
                    cursor: 'pointer',
                    textAlign: 'left',
                    border:
                      selectedLspId === lsp.id ? '1px solid rgba(180, 142, 75, 0.5)' : '1px solid transparent',
                  }}
                >
                  <div>
                    <strong>{lsp.name}</strong>
                    <p className="helper-copy">{lsp.code}</p>
                    <p className="helper-copy">{formatDateLabel(lsp.portfolioSummary.latestDisbursalDate)}</p>
                  </div>
                  <div style={{ display: 'grid', gap: '0.5rem', justifyItems: 'end' }}>
                    <Badge variant={statusVariant(lsp.status)}>{lsp.status}</Badge>
                    {renderSummaryBadges(lsp.portfolioSummary)}
                  </div>
                </button>
              ))}
              {!lsps.length ? <div className="empty-state">No LSPs found.</div> : null}
            </div>
          ) : null}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <div className="section-eyebrow">Selected LSP</div>
          <CardTitle>{selectedLsp?.name ?? selectedListRecord?.name ?? 'Choose a tenant'}</CardTitle>
          <CardDescription>
            View the sanctioned users, loan portfolio summary, and webhook configuration for the selected LSP.
          </CardDescription>
        </CardHeader>
        <CardContent>
          {!selectedLspId ? <div className="empty-state">Select an LSP to inspect its details.</div> : null}
          {detailLoading ? <div className="empty-state">Loading tenant details...</div> : null}
          {detailError ? <div className="empty-state">{detailError}</div> : null}
          {selectedLsp && !detailLoading ? (
            <div className="form-grid">
              <div className="table-row">
                <div>
                  <strong>{selectedLsp.code}</strong>
                  <p className="helper-copy">{selectedLsp.status}</p>
                </div>
                <div className="inline-actions">
                  <Badge>{selectedLsp.userCount} users</Badge>
                  <Badge variant={selectedLsp.webhookSubscription?.enabled ? 'success' : 'warning'}>
                    {selectedLsp.webhookSubscription?.enabled ? 'Webhook on' : 'Webhook off'}
                  </Badge>
                </div>
              </div>

              <div className="table-grid" style={{ gridColumn: '1 / -1' }}>
                <div className="table-row">
                  <div>
                    <strong>{selectedLsp.portfolioSummary.loanApplicationCount}</strong>
                    <p className="helper-copy">Loans captured</p>
                  </div>
                  <div>
                    <strong>{selectedLsp.portfolioSummary.approvedLoanCount}</strong>
                    <p className="helper-copy">Approved</p>
                  </div>
                  <div>
                    <strong>{selectedLsp.portfolioSummary.disbursedLoanCount}</strong>
                    <p className="helper-copy">Disbursed</p>
                  </div>
                  <div>
                    <strong>{currencyLabel(selectedLsp.portfolioSummary.totalDisbursedAmount)}</strong>
                    <p className="helper-copy">Amount disbursed</p>
                  </div>
                </div>
              </div>

              <div className="field-stack" style={{ gridColumn: '1 / -1' }}>
                <label>Users sanctioned in the system</label>
                {!selectedLsp.users.length ? (
                  <div className="empty-state">No users are assigned to this LSP yet.</div>
                ) : (
                  <div className="table-grid">
                    {selectedLsp.users.map((user) => (
                      <div className="table-row" key={user.id}>
                        <div>
                          <strong>{user.username}</strong>
                          <p className="helper-copy">{user.email ?? 'No email'}</p>
                        </div>
                        <Badge>{user.roles.join(', ')}</Badge>
                        <span className="helper-copy">{user.status}</span>
                      </div>
                    ))}
                  </div>
                )}
              </div>

              <form className="form-grid" style={{ gridColumn: '1 / -1' }} onSubmit={handleWebhookSave}>
                <div className="field-stack">
                  <label htmlFor="webhook-enabled">Webhook delivery</label>
                  <select
                    id="webhook-enabled"
                    className="ui-input"
                    value={webhookEnabled ? 'enabled' : 'disabled'}
                    onChange={(event) => setWebhookEnabled(event.target.value === 'enabled')}
                  >
                    <option value="disabled">Disabled</option>
                    <option value="enabled">Enabled</option>
                  </select>
                </div>
                <div className="field-stack">
                  <label htmlFor="webhook-endpoint">Endpoint URL</label>
                  <Input
                    id="webhook-endpoint"
                    value={webhookEndpointUrl}
                    onChange={(event) => setWebhookEndpointUrl(event.target.value)}
                    placeholder="https://hooks.example.com/lms"
                  />
                </div>
                <div className="field-stack">
                  <label htmlFor="webhook-secret">Signing secret</label>
                  <Input
                    id="webhook-secret"
                    value={webhookSigningSecret}
                    onChange={(event) => setWebhookSigningSecret(event.target.value)}
                    placeholder="Shared signing secret"
                  />
                </div>
                <div className="field-stack" style={{ gridColumn: '1 / -1' }}>
                  <label>Event types</label>
                  <div className="table-grid" style={{ gap: '0.75rem', marginTop: '0.5rem' }}>
                    {webhookEventTypeOptions.map((eventType) => (
                      <label
                        key={eventType}
                        className="table-row"
                        style={{ justifyContent: 'flex-start', gap: '0.75rem' }}
                      >
                        <input
                          type="checkbox"
                          checked={webhookEventTypes.includes(eventType)}
                          onChange={() => toggleWebhookEventType(eventType)}
                        />
                        <div>
                          <strong>{webhookEventTypeLabel(eventType)}</strong>
                          <p className="helper-copy">{eventType}</p>
                        </div>
                      </label>
                    ))}
                  </div>
                </div>
                {webhookError ? <div className="empty-state">{webhookError}</div> : null}
                {webhookSuccess ? <div className="empty-state">{webhookSuccess}</div> : null}
                <Button disabled={webhookSaving} type="submit">
                  {webhookSaving ? 'Saving...' : 'Save webhook subscription'}
                </Button>
              </form>
            </div>
          ) : null}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <div className="section-eyebrow">Create LSP</div>
          <CardTitle>Add tenant</CardTitle>
          <CardDescription>Admins retain full write access to add and configure new tenants.</CardDescription>
        </CardHeader>
        <CardContent>
          <form className="form-grid" onSubmit={handleCreate}>
            <div className="field-stack">
              <label htmlFor="code">Tenant code</label>
              <Input id="code" value={code} onChange={(event) => setCode(event.target.value.toUpperCase())} />
            </div>
            <div className="field-stack">
              <label htmlFor="name">Tenant name</label>
              <Input id="name" value={name} onChange={(event) => setName(event.target.value)} />
            </div>
            <div className="field-stack">
              <label htmlFor="status">Status</label>
              <select
                id="status"
                className="ui-input"
                value={status}
                onChange={(event) => setStatus(event.target.value as LspStatus)}
                disabled={!metadata?.lspStatuses.length}
              >
                <option value="">Select a status</option>
                {(metadata?.lspStatuses ?? []).map((option) => (
                  <option key={option} value={option}>
                    {option}
                  </option>
                ))}
              </select>
            </div>
            <Button disabled={submitting} type="submit">
              {submitting ? 'Creating...' : 'Create tenant'}
            </Button>
          </form>
        </CardContent>
      </Card>
    </div>
  )
}
