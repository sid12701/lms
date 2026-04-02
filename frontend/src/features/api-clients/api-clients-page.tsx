import { useEffect, useState, type FormEvent } from 'react'
import { Badge } from '../../components/ui/badge'
import { Button } from '../../components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../../components/ui/card'
import { Input } from '../../components/ui/input'
import {
  createApiClient,
  getAdminMetadata,
  listApiClients,
  listLsps,
  apiClientStatusOptions,
  type AdminMetadata,
  type ApiClientRecord,
  type ApiClientStatus,
  type LspRecord,
} from '../api/lms-api'

function statusVariant(status: ApiClientStatus): 'success' | 'warning' {
  return status === 'ACTIVE' ? 'success' : 'warning'
}

function shortSecret(secret: string) {
  if (secret.length <= 8) {
    return secret
  }

  return `${secret.slice(0, 4)}...${secret.slice(-4)}`
}

export function ApiClientsPage() {
  const [clients, setClients] = useState<ApiClientRecord[]>([])
  const [lsps, setLsps] = useState<LspRecord[]>([])
  const [metadata, setMetadata] = useState<AdminMetadata | null>(null)
  const [name, setName] = useState('')
  const [lspId, setLspId] = useState('')
  const [status, setStatus] = useState<ApiClientStatus | ''>('')
  const [loading, setLoading] = useState(true)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState('')
  const [createdSecret, setCreatedSecret] = useState<{ clientName: string; clientId: string; clientSecret: string } | null>(null)
  const statusOptions = metadata?.apiClientStatuses ?? apiClientStatusOptions

  useEffect(() => {
    let cancelled = false

    async function loadClients() {
      setLoading(true)
      setError('')

      try {
        const [metadataResponse, lspResponse, clientResponse] = await Promise.all([
          getAdminMetadata(),
          listLsps(),
          listApiClients(),
        ])

        if (!cancelled) {
          setMetadata(metadataResponse)
          setLsps(lspResponse)
          setClients(clientResponse)
          setLspId((current) => current || lspResponse[0]?.id || '')
          setStatus((current) => current || (metadataResponse.apiClientStatuses?.[0] as ApiClientStatus | '') || 'ACTIVE')
        }
      } catch (loadError) {
        const message = loadError instanceof Error ? loadError.message : 'Unable to load API clients.'
        if (!cancelled) {
          setError(message)
        }
      } finally {
        if (!cancelled) {
          setLoading(false)
        }
      }
    }

    void loadClients()

    return () => {
      cancelled = true
    }
  }, [])

  async function handleCreate(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!name.trim()) {
      return
    }

    const nextStatus = status || (metadata?.apiClientStatuses?.[0] as ApiClientStatus | undefined) || 'ACTIVE'
    const nextLspId = lspId || lsps[0]?.id || null

    setSubmitting(true)
    setError('')

    try {
      const created = await createApiClient({
        name,
        lspId: nextLspId,
        status: nextStatus,
      })

      setClients((current) => [created, ...current.filter((item) => item.id !== created.id)])
      setCreatedSecret({
        clientName: created.name,
        clientId: created.clientId,
        clientSecret: created.clientSecret,
      })
      setName('')
      setLspId(nextLspId ?? '')
      setStatus(nextStatus)
    } catch (createError) {
      const message = createError instanceof Error ? createError.message : 'Unable to create API client.'
      setError(message)
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="users-layout">
      <Card className="list-card">
        <CardHeader>
          <div className="section-eyebrow">API client administration</div>
          <CardTitle>LSP integration credentials</CardTitle>
          <CardDescription>
            Phase 2 target: issue and track machine credentials for LSP integrations from the same console.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <div className="inline-actions" style={{ marginBottom: '1rem' }}>
            <Badge>{clients.length} clients</Badge>
            <Badge variant="warning">{lsps.length} LSPs</Badge>
          </div>
          {loading ? <div className="empty-state">Loading API clients...</div> : null}
          {error ? <div className="empty-state">{error}</div> : null}
          {!loading && !error ? (
            <div className="table-grid">
              {clients.map((client) => (
                <div className="table-row" key={client.id}>
                  <div>
                    <strong>{client.name}</strong>
                    <p className="helper-copy">{client.clientId}</p>
                  </div>
                  <Badge variant={statusVariant(client.status)}>{client.status}</Badge>
                  <span>{client.lspName}</span>
                  <span className="helper-copy">{client.lastUsedAt ? 'Used recently' : 'Never used'}</span>
                </div>
              ))}
              {!clients.length ? <div className="empty-state">No API clients found.</div> : null}
            </div>
          ) : null}
        </CardContent>
      </Card>

      <div className="form-stack">
        <Card className="content-card">
          <CardHeader>
            <div className="section-eyebrow">External LSP API</div>
            <CardTitle>First live partner contract</CardTitle>
            <CardDescription>
              Newly created clients can now mint partner tokens and call the first LSP-scoped loan intake APIs.
            </CardDescription>
          </CardHeader>
          <CardContent>
            <div className="helper-copy" style={{ marginBottom: '0.75rem' }}>
              Token: <code>POST /api/v1/auth/token</code> with <code>grantType=client_credentials</code>
            </div>
            <div className="helper-copy" style={{ marginBottom: '0.75rem' }}>
              Endpoints: <code>POST /api/v1/lsp/loan-applications</code>,{' '}
              <code>GET /api/v1/lsp/loan-applications</code>,{' '}
              <code>GET /api/v1/lsp/loan-applications/external/{'{externalLoanId}'}</code>
            </div>
            <pre className="secret-panel" style={{ whiteSpace: 'pre-wrap', margin: 0 }}>
{`{
  "grantType": "client_credentials",
  "clientId": "cli_xxx",
  "clientSecret": "issued-once-secret"
}`}
            </pre>
          </CardContent>
        </Card>

        {createdSecret ? (
          <Card className="content-card">
            <CardHeader>
              <div className="section-eyebrow">Secret issued once</div>
              <CardTitle>{createdSecret.clientName}</CardTitle>
              <CardDescription>
                Copy this secret now. It will not be shown again in the list view.
              </CardDescription>
            </CardHeader>
            <CardContent>
              <div className="secret-panel">
                <div className="secret-panel__row">
                  <span>Client ID</span>
                  <strong>{createdSecret.clientId}</strong>
                </div>
                <div className="secret-panel__row">
                  <span>Secret</span>
                  <strong>{createdSecret.clientSecret}</strong>
                </div>
                <div className="helper-copy">Preview: {shortSecret(createdSecret.clientSecret)}</div>
                <Button type="button" variant="ghost" onClick={() => setCreatedSecret(null)}>
                  Acknowledge and hide secret
                </Button>
              </div>
            </CardContent>
          </Card>
        ) : null}

        <Card>
          <CardHeader>
            <div className="section-eyebrow">Create API client</div>
            <CardTitle>Add integration credential</CardTitle>
            <CardDescription>Bind this form directly to the admin API client creation API.</CardDescription>
          </CardHeader>
          <CardContent>
            <form className="form-grid" onSubmit={handleCreate}>
              <div className="field-stack">
                <label htmlFor="clientName">Client name</label>
                <Input
                  id="clientName"
                  value={name}
                  onChange={(event) => setName(event.target.value)}
                  placeholder="Apex Finance webhook client"
                />
              </div>
              <div className="field-stack">
                <label htmlFor="lspId">LSP</label>
                <select
                  id="lspId"
                  className="ui-input"
                  value={lspId}
                  onChange={(event) => setLspId(event.target.value)}
                  disabled={!lsps.length}
                >
                  <option value="">Select an LSP</option>
                  {lsps.map((option) => (
                    <option key={option.id} value={option.id}>
                      {option.name}
                    </option>
                  ))}
                </select>
              </div>
              <div className="field-stack">
                <label htmlFor="status">Status</label>
                <select
                  id="status"
                  className="ui-input"
                  value={status}
                  onChange={(event) => setStatus(event.target.value as ApiClientStatus)}
                  disabled={!statusOptions.length}
                >
                  <option value="">Select a status</option>
                  {statusOptions.map((option) => (
                    <option key={option} value={option}>
                      {option}
                    </option>
                  ))}
                </select>
              </div>
              {error ? <div className="empty-state">{error}</div> : null}
              <Button disabled={submitting} type="submit">
                {submitting ? 'Creating...' : 'Create API client'}
              </Button>
            </form>
          </CardContent>
        </Card>
      </div>
    </div>
  )
}
