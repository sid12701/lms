import { useEffect, useState, type FormEvent } from 'react'
import { Badge } from '../../components/ui/badge'
import { Button } from '../../components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../../components/ui/card'
import { Input } from '../../components/ui/input'
import {
  createLsp,
  listLsps,
  lspStatusOptions,
  type LspRecord,
  type LspStatus,
} from '../api/lms-api'

function statusVariant(status: LspStatus): 'success' | 'warning' {
  if (status === 'ACTIVE') {
    return 'success'
  }

  return 'warning'
}

export function LspAdminPage() {
  const [lsps, setLsps] = useState<LspRecord[]>([])
  const [code, setCode] = useState('')
  const [name, setName] = useState('')
  const [status, setStatus] = useState<LspStatus>('ACTIVE')
  const [loading, setLoading] = useState(true)
  const [submitting, setSubmitting] = useState(false)
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

  async function handleCreate(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!code.trim() || !name.trim()) {
      return
    }

    setSubmitting(true)
    setError('')

    try {
      const created = await createLsp({
        code,
        name,
        status,
      })

      setLsps((current) => [created, ...current.filter((item) => item.id !== created.id)])
      setCode('')
      setName('')
      setStatus('ACTIVE')
    } catch (createError) {
      const message = createError instanceof Error ? createError.message : 'Unable to create LSP.'
      setError(message)
    } finally {
      setSubmitting(false)
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
            <Badge variant="warning">{lspStatusOptions.length} statuses</Badge>
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
                  <Badge variant={statusVariant(lsp.status)}>{lsp.status}</Badge>
                  <span className="helper-copy">Ready for Phase 2</span>
                </div>
              ))}
              {!lsps.length ? <div className="empty-state">No LSPs found.</div> : null}
            </div>
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
              >
                {lspStatusOptions.map((option) => (
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
