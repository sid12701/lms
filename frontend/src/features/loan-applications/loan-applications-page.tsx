import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { Badge } from '../../components/ui/badge'
import { Button } from '../../components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../../components/ui/card'
import { Input } from '../../components/ui/input'
import {
  createLoanApplication,
  listLoanApplications,
  listLoanProducts,
  listLsps,
  type LoanApplicationRecord,
  type LoanProductRecord,
  type LspRecord,
} from '../api/lms-api'

type IntakeFormState = {
  lspId: string
  productId: string
  externalLoanId: string
  sourceChannel: string
  borrowerPan: string
  borrowerFullName: string
  borrowerMobile: string
  borrowerEmail: string
  requestedAmount: string
  tenureMonths: string
}

type ListFilterState = {
  lspId: string
  productId: string
  status: string
  sourceChannel: string
  query: string
}

const initialFormState: IntakeFormState = {
  lspId: '',
  productId: '',
  externalLoanId: '',
  sourceChannel: 'PARTNER_PORTAL',
  borrowerPan: '',
  borrowerFullName: '',
  borrowerMobile: '',
  borrowerEmail: '',
  requestedAmount: '50000',
  tenureMonths: '12',
}

const initialFilterState: ListFilterState = {
  lspId: '',
  productId: '',
  status: '',
  sourceChannel: '',
  query: '',
}

function currencyLabel(value: number) {
  return new Intl.NumberFormat('en-IN', {
    style: 'currency',
    currency: 'INR',
    maximumFractionDigits: 0,
  }).format(value)
}

function formatTimestamp(value: string) {
  return new Intl.DateTimeFormat('en-IN', {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date(value))
}

export function LoanApplicationsPage() {
  const [applications, setApplications] = useState<LoanApplicationRecord[]>([])
  const [lsps, setLsps] = useState<LspRecord[]>([])
  const [products, setProducts] = useState<LoanProductRecord[]>([])
  const [form, setForm] = useState<IntakeFormState>(initialFormState)
  const [filters, setFilters] = useState<ListFilterState>(initialFilterState)
  const [loading, setLoading] = useState(true)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState('')

  const activeLsps = useMemo(() => lsps.filter((item) => item.status === 'ACTIVE'), [lsps])
  const activeProducts = useMemo(
    () => products.filter((item) => item.status === 'ACTIVE'),
    [products],
  )
  const sourceChannelOptions = useMemo(
    () =>
      Array.from(new Set(applications.map((item) => item.sourceChannel)))
        .filter(Boolean)
        .sort((left, right) => left.localeCompare(right)),
    [applications],
  )
  const statusOptions = useMemo(
    () =>
      Array.from(new Set(applications.map((item) => item.status)))
        .filter(Boolean)
        .sort((left, right) => left.localeCompare(right)),
    [applications],
  )

  async function loadApplications(nextFilters: ListFilterState) {
    const response = await listLoanApplications({
      lspId: nextFilters.lspId || undefined,
      productId: nextFilters.productId || undefined,
      status: nextFilters.status || undefined,
      sourceChannel: nextFilters.sourceChannel || undefined,
      query: nextFilters.query.trim() || undefined,
    })
    setApplications(response)
  }

  useEffect(() => {
    let cancelled = false

    async function loadPage() {
      setLoading(true)
      setError('')

      try {
        const [applicationResponse, lspResponse, productResponse] = await Promise.all([
          listLoanApplications(),
          listLsps(),
          listLoanProducts(),
        ])

        if (!cancelled) {
          setApplications(applicationResponse)
          setLsps(lspResponse)
          setProducts(productResponse)
          setForm((current) => ({
            ...current,
            lspId: current.lspId || lspResponse.find((item) => item.status === 'ACTIVE')?.id || '',
            productId:
              current.productId || productResponse.find((item) => item.status === 'ACTIVE')?.id || '',
          }))
        }
      } catch (loadError) {
        const message = loadError instanceof Error ? loadError.message : 'Unable to load intake data.'
        if (!cancelled) {
          setError(message)
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
    let cancelled = false

    async function refreshFilteredList() {
      if (loading) {
        return
      }

      try {
        const response = await listLoanApplications({
          lspId: filters.lspId || undefined,
          productId: filters.productId || undefined,
          status: filters.status || undefined,
          sourceChannel: filters.sourceChannel || undefined,
          query: filters.query.trim() || undefined,
        })
        if (!cancelled) {
          setApplications(response)
          setError('')
        }
      } catch (loadError) {
        const message =
          loadError instanceof Error ? loadError.message : 'Unable to filter loan applications.'
        if (!cancelled) {
          setError(message)
        }
      }
    }

    void refreshFilteredList()

    return () => {
      cancelled = true
    }
  }, [filters, loading])

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setSubmitting(true)
    setError('')

    try {
      await createLoanApplication({
        lspId: form.lspId,
        productId: form.productId,
        externalLoanId: form.externalLoanId,
        sourceChannel: form.sourceChannel,
        borrowerPan: form.borrowerPan.toUpperCase(),
        borrowerFullName: form.borrowerFullName,
        borrowerMobile: form.borrowerMobile,
        borrowerEmail: form.borrowerEmail || undefined,
        requestedAmount: Number(form.requestedAmount),
        tenureMonths: Number(form.tenureMonths),
      })

      await loadApplications(filters)
      setForm((current) => ({
        ...initialFormState,
        lspId: current.lspId,
        productId: current.productId,
        sourceChannel: current.sourceChannel,
      }))
    } catch (submitError) {
      const message =
        submitError instanceof Error ? submitError.message : 'Unable to create loan application.'
      setError(message)
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="users-layout">
      <Card className="list-card">
        <CardHeader>
          <div className="section-eyebrow">Loan intake</div>
          <CardTitle>Received applications</CardTitle>
          <CardDescription>
            Phase 4 foundation: capture borrower-linked intake records and review newly received applications.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <div className="inline-actions" style={{ marginBottom: '1rem' }}>
            <Badge>{applications.length} applications</Badge>
            <Badge variant="warning">{activeLsps.length} active LSPs</Badge>
            <Badge variant="success">{activeProducts.length} active products</Badge>
          </div>
          <div className="form-grid" style={{ marginBottom: '1rem' }}>
            <div className="field-stack">
              <label htmlFor="filter-lsp">Filter by LSP</label>
              <select
                id="filter-lsp"
                className="ui-input"
                value={filters.lspId}
                onChange={(event) =>
                  setFilters((current) => ({ ...current, lspId: event.target.value }))
                }
              >
                <option value="">All LSPs</option>
                {activeLsps.map((lsp) => (
                  <option key={lsp.id} value={lsp.id}>
                    {lsp.code} - {lsp.name}
                  </option>
                ))}
              </select>
            </div>
            <div className="field-stack">
              <label htmlFor="filter-product">Filter by product</label>
              <select
                id="filter-product"
                className="ui-input"
                value={filters.productId}
                onChange={(event) =>
                  setFilters((current) => ({ ...current, productId: event.target.value }))
                }
              >
                <option value="">All products</option>
                {activeProducts.map((product) => (
                  <option key={product.id} value={product.id}>
                    {product.code} - {product.name}
                  </option>
                ))}
              </select>
            </div>
            <div className="field-stack">
              <label htmlFor="filter-query">Search</label>
              <Input
                id="filter-query"
                value={filters.query}
                onChange={(event) =>
                  setFilters((current) => ({ ...current, query: event.target.value }))
                }
                placeholder="Borrower, PAN, mobile, external loan id"
              />
            </div>
            <div className="field-stack">
              <label htmlFor="filter-status">Filter by status</label>
              <select
                id="filter-status"
                className="ui-input"
                value={filters.status}
                onChange={(event) =>
                  setFilters((current) => ({ ...current, status: event.target.value }))
                }
              >
                <option value="">All statuses</option>
                {statusOptions.map((status) => (
                  <option key={status} value={status}>
                    {status}
                  </option>
                ))}
              </select>
            </div>
            <div className="field-stack">
              <label htmlFor="filter-source-channel">Filter by source</label>
              <select
                id="filter-source-channel"
                className="ui-input"
                value={filters.sourceChannel}
                onChange={(event) =>
                  setFilters((current) => ({ ...current, sourceChannel: event.target.value }))
                }
              >
                <option value="">All sources</option>
                {sourceChannelOptions.map((sourceChannel) => (
                  <option key={sourceChannel} value={sourceChannel}>
                    {sourceChannel}
                  </option>
                ))}
              </select>
            </div>
          </div>
          {loading ? <div className="empty-state">Loading loan applications...</div> : null}
          {error ? <div className="empty-state">{error}</div> : null}
          {!loading && !error ? (
            <div className="table-grid">
              {applications.map((application) => (
                <div className="table-row" key={application.id}>
                  <div>
                    <strong>{application.borrowerFullName}</strong>
                    <p className="helper-copy">
                      {application.borrowerPan} · {application.borrowerMobile}
                    </p>
                  </div>
                  <Badge variant="warning">{application.status}</Badge>
                  <span>{currencyLabel(application.requestedAmount)}</span>
                  <span>{application.tenureMonths} months</span>
                  <span className="helper-copy">
                    {application.lspCode} · {application.productCode}
                  </span>
                  <span className="helper-copy">{application.externalLoanId}</span>
                  <span className="helper-copy">{formatTimestamp(application.createdAt)}</span>
                </div>
              ))}
              {!applications.length ? (
                <div className="empty-state">No loan applications matched the current filters.</div>
              ) : null}
            </div>
          ) : null}
        </CardContent>
      </Card>

      <div className="form-stack">
        <Card>
          <CardHeader>
            <div className="section-eyebrow">Create intake</div>
            <CardTitle>Register a loan application</CardTitle>
            <CardDescription>
              Use this internal intake form to create the borrower and application foundation for Phase 4.
            </CardDescription>
          </CardHeader>
          <CardContent>
            <form className="form-grid" onSubmit={handleSubmit}>
              <div className="field-stack">
                <label htmlFor="intake-lsp">LSP</label>
                <select
                  id="intake-lsp"
                  className="ui-input"
                  value={form.lspId}
                  onChange={(event) =>
                    setForm((current) => ({ ...current, lspId: event.target.value }))
                  }
                >
                  <option value="">Select an LSP</option>
                  {activeLsps.map((lsp) => (
                    <option key={lsp.id} value={lsp.id}>
                      {lsp.code} - {lsp.name}
                    </option>
                  ))}
                </select>
              </div>
              <div className="field-stack">
                <label htmlFor="intake-product">Product</label>
                <select
                  id="intake-product"
                  className="ui-input"
                  value={form.productId}
                  onChange={(event) =>
                    setForm((current) => ({ ...current, productId: event.target.value }))
                  }
                >
                  <option value="">Select a product</option>
                  {activeProducts.map((product) => (
                    <option key={product.id} value={product.id}>
                      {product.code} - {product.name}
                    </option>
                  ))}
                </select>
              </div>
              <div className="field-stack">
                <label htmlFor="external-loan-id">External loan id</label>
                <Input
                  id="external-loan-id"
                  value={form.externalLoanId}
                  onChange={(event) =>
                    setForm((current) => ({ ...current, externalLoanId: event.target.value }))
                  }
                  placeholder="EXT-1001"
                />
              </div>
              <div className="field-stack">
                <label htmlFor="source-channel">Source channel</label>
                <Input
                  id="source-channel"
                  value={form.sourceChannel}
                  onChange={(event) =>
                    setForm((current) => ({
                      ...current,
                      sourceChannel: event.target.value.toUpperCase(),
                    }))
                  }
                  placeholder="PARTNER_PORTAL"
                />
              </div>
              <div className="field-stack">
                <label htmlFor="borrower-pan">Borrower PAN</label>
                <Input
                  id="borrower-pan"
                  value={form.borrowerPan}
                  onChange={(event) =>
                    setForm((current) => ({
                      ...current,
                      borrowerPan: event.target.value.toUpperCase(),
                    }))
                  }
                  placeholder="ABCDE1234F"
                />
              </div>
              <div className="field-stack">
                <label htmlFor="borrower-name">Borrower name</label>
                <Input
                  id="borrower-name"
                  value={form.borrowerFullName}
                  onChange={(event) =>
                    setForm((current) => ({ ...current, borrowerFullName: event.target.value }))
                  }
                  placeholder="Anika Sharma"
                />
              </div>
              <div className="field-stack">
                <label htmlFor="borrower-mobile">Borrower mobile</label>
                <Input
                  id="borrower-mobile"
                  value={form.borrowerMobile}
                  onChange={(event) =>
                    setForm((current) => ({ ...current, borrowerMobile: event.target.value }))
                  }
                  placeholder="9999999999"
                />
              </div>
              <div className="field-stack">
                <label htmlFor="borrower-email">Borrower email</label>
                <Input
                  id="borrower-email"
                  value={form.borrowerEmail}
                  onChange={(event) =>
                    setForm((current) => ({ ...current, borrowerEmail: event.target.value }))
                  }
                  placeholder="anika@example.com"
                />
              </div>
              <div className="field-stack">
                <label htmlFor="requested-amount">Requested amount</label>
                <Input
                  id="requested-amount"
                  type="number"
                  min="0"
                  step="0.01"
                  value={form.requestedAmount}
                  onChange={(event) =>
                    setForm((current) => ({ ...current, requestedAmount: event.target.value }))
                  }
                />
              </div>
              <div className="field-stack">
                <label htmlFor="tenure-months">Tenure months</label>
                <Input
                  id="tenure-months"
                  type="number"
                  min="1"
                  step="1"
                  value={form.tenureMonths}
                  onChange={(event) =>
                    setForm((current) => ({ ...current, tenureMonths: event.target.value }))
                  }
                />
              </div>
              {error ? <div className="empty-state">{error}</div> : null}
              <div className="inline-actions">
                <Button disabled={submitting} type="submit">
                  {submitting ? 'Creating...' : 'Create application'}
                </Button>
              </div>
            </form>
          </CardContent>
        </Card>
      </div>
    </div>
  )
}
