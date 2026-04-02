import { useEffect, useState, type FormEvent } from 'react'
import { Badge } from '../../components/ui/badge'
import { Button } from '../../components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../../components/ui/card'
import { Input } from '../../components/ui/input'
import {
  createLsp,
  getAdminMetadata,
  listLsps,
  updateLspWebhookSubscription,
  type AdminMetadata,
  type LspRecord,
  type LspStatus,
  type WebhookEventType,
  webhookEventTypeOptions,
} from '../api/lms-api'

function statusVariant(status: LspStatus): 'success' | 'warning' {
  if (status === 'ACTIVE') {
    return 'success'
  }

  return 'warning'
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

export function LspAdminPage() {
  const [lsps, setLsps] = useState<LspRecord[]>([])
  const [metadata, setMetadata] = useState<AdminMetadata | null>(null)
  const [selectedLspId, setSelectedLspId] = useState('')
  const [code, setCode] = useState('')
  const [name, setName] = useState('')
  const [status, setStatus] = useState<LspStatus | ''>('')
  const [webhookEnabled, setWebhookEnabled] = useState(false)
  const [webhookEndpointUrl, setWebhookEndpointUrl] = useState('')
  const [webhookSigningSecret, setWebhookSigningSecret] = useState('')
  const [webhookEventTypes, setWebhookEventTypes] = useState<WebhookEventType[]>([])
  const [loading, setLoading] = useState(true)
  const [submitting, setSubmitting] = useState(false)
  const [webhookSaving, setWebhookSaving] = useState(false)
  const [error, setError] = useState('')
  const [webhookError, setWebhookError] = useState('')
  const [webhookSuccess, setWebhookSuccess] = useState('')

  const selectedLsp = lsps.find((item) => item.id === selectedLspId) ?? null
  const selectedWebhookSubscription = selectedLsp?.webhookSubscription ?? null

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
        const message = loadError instanceof Error ? loadError.message : 'Unable to load LSPs.'
        if (!cancelled) {
          setError(message)
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
    if (!lsps.length) {
      setSelectedLspId('')
      return
    }

    setSelectedLspId((current) => (lsps.some((item) => item.id === current) ? current : lsps[0].id))
  }, [lsps])

  useEffect(() => {
    const currentSelectedLsp = lsps.find((item) => item.id === selectedLspId) ?? null

    if (!currentSelectedLsp) {
      setWebhookEnabled(false)
      setWebhookEndpointUrl('')
      setWebhookSigningSecret('')
      setWebhookEventTypes([])
      return
    }

    const subscription = currentSelectedLsp.webhookSubscription
    setWebhookEnabled(subscription?.enabled ?? false)
    setWebhookEndpointUrl(subscription?.endpointUrl ?? '')
    setWebhookSigningSecret(subscription?.signingSecret ?? '')
    setWebhookEventTypes(subscription?.eventTypes ?? [])
    setWebhookError('')
    setWebhookSuccess('')
  }, [selectedLspId])

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
      const message = createError instanceof Error ? createError.message : 'Unable to create LSP.'
      setError(message)
    } finally {
      setSubmitting(false)
    }
  }

  function toggleWebhookEventType(eventType: WebhookEventType) {
    setWebhookEventTypes((current) =>
      current.includes(eventType)
        ? current.filter((item) => item !== eventType)
        : [...current, eventType],
    )
  }

  function selectAllWebhookEventTypes() {
    setWebhookEventTypes([...webhookEventTypeOptions])
  }

  function clearWebhookEventTypes() {
    setWebhookEventTypes([])
  }

  async function handleWebhookSave(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!selectedLsp) {
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
        // Validate URL format before sending it to the server.
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
      const updated = await updateLspWebhookSubscription(selectedLsp.id, {
        enabled: webhookEnabled,
        endpointUrl: webhookEnabled ? trimmedEndpoint : undefined,
        signingSecret: webhookEnabled ? trimmedSecret : undefined,
        eventTypes: webhookEventTypes,
      })

      setLsps((current) => current.map((item) => (item.id === updated.id ? updated : item)))
      setWebhookSuccess('Webhook subscription saved.')
    } catch (saveError) {
      const message = saveError instanceof Error ? saveError.message : 'Unable to save webhook settings.'
      setWebhookError(message)
    } finally {
      setWebhookSaving(false)
    }
  }

  return (
    <div className="users-layout">
      <Card className="list-card">
        <CardHeader>
          <div className="section-eyebrow">LSP administration</div>
          <CardTitle>Tenant registry</CardTitle>
          <CardDescription>
            Phase 2 target: manage onboarding state and the tenant list from the same console.
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
                <div className="table-row" key={lsp.id}>
                  <div>
                    <strong>{lsp.name}</strong>
                    <p className="helper-copy">{lsp.code}</p>
                  </div>
                  <div className="inline-actions">
                    <Badge variant={statusVariant(lsp.status)}>{lsp.status}</Badge>
                    <Badge variant={lsp.webhookSubscription?.enabled ? 'success' : 'warning'}>
                      {lsp.webhookSubscription?.enabled ? 'Webhook on' : 'Webhook off'}
                    </Badge>
                  </div>
                  <span className="helper-copy">
                    {lsp.webhookSubscription?.eventTypes.length
                      ? `${lsp.webhookSubscription.eventTypes.length} events`
                      : 'Ready for webhook setup'}
                  </span>
                </div>
              ))}
              {!lsps.length ? <div className="empty-state">No LSPs found.</div> : null}
            </div>
          ) : null}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <div className="section-eyebrow">Webhook subscriptions</div>
          <CardTitle>Per-tenant delivery config</CardTitle>
          <CardDescription>
            Configure which LSP events are delivered and where they should be posted.
          </CardDescription>
        </CardHeader>
        <CardContent>
          {!lsps.length ? <div className="empty-state">Create an LSP before configuring webhooks.</div> : null}
          {lsps.length ? (
            <form className="form-grid" onSubmit={handleWebhookSave}>
              <div className="field-stack">
                <label htmlFor="webhook-lsp">Tenant</label>
                <select
                  id="webhook-lsp"
                  className="ui-input"
                  value={selectedLspId}
                  onChange={(event) => setSelectedLspId(event.target.value)}
                >
                  {lsps.map((lsp) => (
                    <option key={lsp.id} value={lsp.id}>
                      {lsp.code} - {lsp.name}
                    </option>
                  ))}
                </select>
              </div>
              <div className="inline-actions">
                <Badge variant={selectedWebhookSubscription?.enabled ? 'success' : 'warning'}>
                  {selectedWebhookSubscription?.enabled ? 'Enabled' : 'Disabled'}
                </Badge>
                <Badge>{selectedWebhookSubscription?.eventTypes.length ?? 0} events</Badge>
              </div>
              <div className="field-stack">
                <label htmlFor="webhook-enabled">Delivery enabled</label>
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
                <p className="helper-copy">Required when delivery is enabled.</p>
              </div>
              <div className="field-stack">
                <label htmlFor="webhook-secret">Signing secret</label>
                <Input
                  id="webhook-secret"
                  value={webhookSigningSecret}
                  onChange={(event) => setWebhookSigningSecret(event.target.value)}
                  placeholder="Shared signing secret"
                />
                <p className="helper-copy">Store the secret used to verify outbound requests.</p>
              </div>
              <div className="field-stack" style={{ gridColumn: '1 / -1' }}>
                <div className="inline-actions" style={{ justifyContent: 'space-between' }}>
                  <label>Event types</label>
                  <div className="inline-actions">
                    <Button type="button" variant="outline" onClick={selectAllWebhookEventTypes}>
                      Select all
                    </Button>
                    <Button type="button" variant="outline" onClick={clearWebhookEventTypes}>
                      Clear
                    </Button>
                  </div>
                </div>
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
                <p className="helper-copy">
                  Keep the list tight. These events define the outbound webhook contract for this tenant.
                </p>
              </div>
              {webhookError ? <div className="empty-state">{webhookError}</div> : null}
              {webhookSuccess ? <div className="empty-state">{webhookSuccess}</div> : null}
              <Button
                disabled={
                  webhookSaving ||
                  !selectedLsp ||
                  (webhookEnabled && !webhookEventTypes.length) ||
                  (webhookEnabled &&
                    (!webhookEndpointUrl.trim() ||
                      !webhookSigningSecret.trim() ||
                      !selectedLspId))
                }
                type="submit"
              >
                {webhookSaving ? 'Saving...' : 'Save webhook subscription'}
              </Button>
            </form>
          ) : null}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <div className="section-eyebrow">Create LSP</div>
          <CardTitle>Add tenant</CardTitle>
          <CardDescription>Bind this form directly to the admin tenant creation API.</CardDescription>
        </CardHeader>
        <CardContent>
          <form className="form-grid" onSubmit={handleCreate}>
            <div className="field-stack">
              <label htmlFor="code">Tenant code</label>
              <Input
                id="code"
                value={code}
                onChange={(event) => setCode(event.target.value.toUpperCase())}
                placeholder="APEX"
              />
            </div>
            <div className="field-stack">
              <label htmlFor="name">Tenant name</label>
              <Input
                id="name"
                value={name}
                onChange={(event) => setName(event.target.value)}
                placeholder="Apex Finance"
              />
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
            {error ? <div className="empty-state">{error}</div> : null}
            <Button disabled={submitting} type="submit">
              {submitting ? 'Creating...' : 'Create tenant'}
            </Button>
          </form>
        </CardContent>
      </Card>
    </div>
  )
}
