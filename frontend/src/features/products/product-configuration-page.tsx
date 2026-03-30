import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { Badge } from '../../components/ui/badge'
import { Button } from '../../components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../../components/ui/card'
import { Input } from '../../components/ui/input'
import {
  createLoanProduct,
  getAdminMetadata,
  listLoanProducts,
  updateLoanProduct,
  type AdminMetadata,
  type LoanProductRecord,
  type LoanProductStatus,
} from '../api/lms-api'

function statusVariant(status: LoanProductStatus): 'success' | 'warning' | 'destructive' {
  if (status === 'ACTIVE') {
    return 'success'
  }

  if (status === 'DRAFT') {
    return 'warning'
  }

  return 'destructive'
}

type ProductFormState = {
  code: string
  name: string
  minPrincipal: string
  maxPrincipal: string
  interestRate: string
  processingFeeRate: string
  minTenureMonths: string
  maxTenureMonths: string
  status: LoanProductStatus | ''
}

const initialFormState: ProductFormState = {
  code: '',
  name: '',
  minPrincipal: '5000',
  maxPrincipal: '250000',
  interestRate: '18.50',
  processingFeeRate: '2.25',
  minTenureMonths: '6',
  maxTenureMonths: '24',
  status: '',
}

function buildFormState(product: LoanProductRecord | null, fallbackStatus: LoanProductStatus | ''): ProductFormState {
  if (!product) {
    return {
      ...initialFormState,
      status: fallbackStatus,
    }
  }

  return {
    code: product.code,
    name: product.name,
    minPrincipal: String(product.minPrincipal),
    maxPrincipal: String(product.maxPrincipal),
    interestRate: String(product.interestRate),
    processingFeeRate: String(product.processingFeeRate),
    minTenureMonths: String(product.minTenureMonths),
    maxTenureMonths: String(product.maxTenureMonths),
    status: product.status,
  }
}

function currencyLabel(value: number) {
  return new Intl.NumberFormat('en-IN', {
    style: 'currency',
    currency: 'INR',
    maximumFractionDigits: 0,
  }).format(value)
}

export function ProductConfigurationPage() {
  const [products, setProducts] = useState<LoanProductRecord[]>([])
  const [metadata, setMetadata] = useState<AdminMetadata | null>(null)
  const [editingProductId, setEditingProductId] = useState<string | null>(null)
  const [form, setForm] = useState<ProductFormState>(initialFormState)
  const [loading, setLoading] = useState(true)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState('')

  const statusOptions = useMemo(
    () => metadata?.loanProductStatuses ?? ['DRAFT', 'ACTIVE', 'INACTIVE'],
    [metadata],
  )

  useEffect(() => {
    let cancelled = false

    async function loadProducts() {
      setLoading(true)
      setError('')

      try {
        const [metadataResponse, productResponse] = await Promise.all([
          getAdminMetadata(),
          listLoanProducts(),
        ])

        if (!cancelled) {
          setMetadata(metadataResponse)
          setProducts(productResponse)
          setForm((current) =>
            current.status
              ? current
              : {
                  ...current,
                  status: (metadataResponse.loanProductStatuses?.[0] as LoanProductStatus | '') || 'DRAFT',
                },
          )
        }
      } catch (loadError) {
        const message = loadError instanceof Error ? loadError.message : 'Unable to load loan products.'
        if (!cancelled) {
          setError(message)
        }
      } finally {
        if (!cancelled) {
          setLoading(false)
        }
      }
    }

    void loadProducts()

    return () => {
      cancelled = true
    }
  }, [])

  function resetForm() {
    setEditingProductId(null)
    setForm(buildFormState(null, (statusOptions[0] as LoanProductStatus | '') || 'DRAFT'))
  }

  function startEdit(product: LoanProductRecord) {
    setEditingProductId(product.id)
    setForm(buildFormState(product, product.status))
    setError('')
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!form.code.trim() || !form.name.trim()) {
      return
    }

    const nextStatus = (form.status || statusOptions[0] || 'DRAFT') as LoanProductStatus
    const payload = {
      code: form.code,
      name: form.name,
      minPrincipal: Number(form.minPrincipal),
      maxPrincipal: Number(form.maxPrincipal),
      interestRate: Number(form.interestRate),
      processingFeeRate: Number(form.processingFeeRate),
      minTenureMonths: Number(form.minTenureMonths),
      maxTenureMonths: Number(form.maxTenureMonths),
      status: nextStatus,
    }

    setSubmitting(true)
    setError('')

    try {
      const saved = editingProductId
        ? await updateLoanProduct(editingProductId, payload)
        : await createLoanProduct(payload)

      setProducts((current) => {
        const next = [saved, ...current.filter((item) => item.id !== saved.id)]
        return next.sort((left, right) => left.code.localeCompare(right.code))
      })
      resetForm()
    } catch (submitError) {
      const message =
        submitError instanceof Error ? submitError.message : 'Unable to save loan product.'
      setError(message)
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="users-layout">
      <Card className="list-card">
        <CardHeader>
          <div className="section-eyebrow">Product configuration</div>
          <CardTitle>Loan product catalog</CardTitle>
          <CardDescription>
            Phase 3 foundation: configure principal bands, pricing, tenure boundaries, and product state.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <div className="inline-actions" style={{ marginBottom: '1rem' }}>
            <Badge>{products.length} products</Badge>
            <Badge variant="warning">{statusOptions.length} statuses</Badge>
          </div>
          {loading ? <div className="empty-state">Loading product catalog...</div> : null}
          {error ? <div className="empty-state">{error}</div> : null}
          {!loading && !error ? (
            <div className="table-grid">
              {products.map((product) => (
                <div className="table-row" key={product.id}>
                  <div>
                    <strong>{product.name}</strong>
                    <p className="helper-copy">{product.code}</p>
                  </div>
                  <Badge variant={statusVariant(product.status)}>{product.status}</Badge>
                  <span>
                    {currencyLabel(product.minPrincipal)} to {currencyLabel(product.maxPrincipal)}
                  </span>
                  <span>
                    {product.interestRate}% rate / {product.processingFeeRate}% fee
                  </span>
                  <span>
                    {product.minTenureMonths} to {product.maxTenureMonths} months
                  </span>
                  <Button type="button" variant="ghost" onClick={() => startEdit(product)}>
                    Edit
                  </Button>
                </div>
              ))}
              {!products.length ? <div className="empty-state">No loan products found.</div> : null}
            </div>
          ) : null}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <div className="section-eyebrow">{editingProductId ? 'Edit product' : 'Create product'}</div>
          <CardTitle>{editingProductId ? 'Update configuration' : 'Add loan product'}</CardTitle>
          <CardDescription>
            Use this form to define pricing ranges and activation state for product rollout.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <form className="form-grid" onSubmit={handleSubmit}>
            <div className="field-stack">
              <label htmlFor="product-code">Product code</label>
              <Input
                id="product-code"
                value={form.code}
                onChange={(event) =>
                  setForm((current) => ({ ...current, code: event.target.value.toUpperCase() }))
                }
                placeholder="SALARY-PLUS"
              />
            </div>
            <div className="field-stack">
              <label htmlFor="product-name">Product name</label>
              <Input
                id="product-name"
                value={form.name}
                onChange={(event) => setForm((current) => ({ ...current, name: event.target.value }))}
                placeholder="Salary Plus"
              />
            </div>
            <div className="field-stack">
              <label htmlFor="min-principal">Minimum principal</label>
              <Input
                id="min-principal"
                type="number"
                min="0"
                step="0.01"
                value={form.minPrincipal}
                onChange={(event) =>
                  setForm((current) => ({ ...current, minPrincipal: event.target.value }))
                }
              />
            </div>
            <div className="field-stack">
              <label htmlFor="max-principal">Maximum principal</label>
              <Input
                id="max-principal"
                type="number"
                min="0"
                step="0.01"
                value={form.maxPrincipal}
                onChange={(event) =>
                  setForm((current) => ({ ...current, maxPrincipal: event.target.value }))
                }
              />
            </div>
            <div className="field-stack">
              <label htmlFor="interest-rate">Interest rate %</label>
              <Input
                id="interest-rate"
                type="number"
                min="0"
                step="0.01"
                value={form.interestRate}
                onChange={(event) =>
                  setForm((current) => ({ ...current, interestRate: event.target.value }))
                }
              />
            </div>
            <div className="field-stack">
              <label htmlFor="processing-fee-rate">Processing fee %</label>
              <Input
                id="processing-fee-rate"
                type="number"
                min="0"
                step="0.01"
                value={form.processingFeeRate}
                onChange={(event) =>
                  setForm((current) => ({ ...current, processingFeeRate: event.target.value }))
                }
              />
            </div>
            <div className="field-stack">
              <label htmlFor="min-tenure">Minimum tenure (months)</label>
              <Input
                id="min-tenure"
                type="number"
                min="1"
                step="1"
                value={form.minTenureMonths}
                onChange={(event) =>
                  setForm((current) => ({ ...current, minTenureMonths: event.target.value }))
                }
              />
            </div>
            <div className="field-stack">
              <label htmlFor="max-tenure">Maximum tenure (months)</label>
              <Input
                id="max-tenure"
                type="number"
                min="1"
                step="1"
                value={form.maxTenureMonths}
                onChange={(event) =>
                  setForm((current) => ({ ...current, maxTenureMonths: event.target.value }))
                }
              />
            </div>
            <div className="field-stack">
              <label htmlFor="product-status">Status</label>
              <select
                id="product-status"
                className="ui-input"
                value={form.status}
                onChange={(event) =>
                  setForm((current) => ({
                    ...current,
                    status: event.target.value as LoanProductStatus,
                  }))
                }
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
            <div className="inline-actions">
              <Button disabled={submitting} type="submit">
                {submitting ? 'Saving...' : editingProductId ? 'Save changes' : 'Create product'}
              </Button>
              {editingProductId ? (
                <Button type="button" variant="ghost" onClick={resetForm}>
                  Cancel edit
                </Button>
              ) : null}
            </div>
          </form>
        </CardContent>
      </Card>
    </div>
  )
}
