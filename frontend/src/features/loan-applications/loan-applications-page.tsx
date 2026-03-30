import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { ArrowRight, CheckCircle2, FileText } from 'lucide-react'
import { Badge } from '../../components/ui/badge'
import { Button } from '../../components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../../components/ui/card'
import { Input } from '../../components/ui/input'
import {
  createLoanApplication,
  getLoanApplication,
  listLoanApplicationIntakeAudits,
  listLoanApplicationStatusTransitions,
  listLoanApplications,
  listLoanProducts,
  listLsps,
  loanApplicationStatusOptions,
  transitionLoanApplicationStatus,
  type LoanApplicationIntakeAuditRecord,
  type LoanApplicationRecord,
  type LoanApplicationStatus,
  type LoanApplicationStatusTransitionRecord,
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

type TransitionAction = {
  targetStatus: LoanApplicationStatus
  label: string
  description: string
  variant: 'primary' | 'secondary' | 'outline'
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

const workflowProgression: Array<{
  status: LoanApplicationStatus | 'DECISION'
  label: string
  description: string
}> = [
  { status: 'RECEIVED', label: 'Received', description: 'Captured from intake and ready for review.' },
  {
    status: 'UNDER_REVIEW',
    label: 'Under review',
    description: 'Ops is validating the case and supporting data.',
  },
  {
    status: 'DECISION',
    label: 'Decision',
    description: 'Approved or rejected after review.',
  },
]

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

function loanStatusLabel(status: LoanApplicationStatus) {
  switch (status) {
    case 'RECEIVED':
      return 'Received'
    case 'UNDER_REVIEW':
      return 'Under review'
    case 'APPROVED':
      return 'Approved'
    case 'REJECTED':
      return 'Rejected'
  }
}

function loanStatusVariant(
  status: LoanApplicationStatus,
): 'default' | 'success' | 'warning' | 'destructive' {
  switch (status) {
    case 'RECEIVED':
      return 'warning'
    case 'UNDER_REVIEW':
      return 'default'
    case 'APPROVED':
      return 'success'
    case 'REJECTED':
      return 'destructive'
  }
}

function statusProgressIndex(status: LoanApplicationStatus) {
  switch (status) {
    case 'RECEIVED':
      return 1
    case 'UNDER_REVIEW':
      return 2
    case 'APPROVED':
    case 'REJECTED':
      return 3
  }
}

function getTransitionActions(status: LoanApplicationStatus): TransitionAction[] {
  switch (status) {
    case 'RECEIVED':
      return [
        {
          targetStatus: 'UNDER_REVIEW',
          label: 'Move to review',
          description: 'Promote the case into the review queue.',
          variant: 'secondary',
        },
      ]
    case 'UNDER_REVIEW':
      return [
        {
          targetStatus: 'APPROVED',
          label: 'Approve',
          description: 'Mark the case as approved and ready for downstream processing.',
          variant: 'primary',
        },
        {
          targetStatus: 'REJECTED',
          label: 'Reject',
          description: 'Close the case and capture the decision note.',
          variant: 'outline',
        },
      ]
    case 'APPROVED':
    case 'REJECTED':
      return []
  }
}

function sortByCreatedAtDesc<T extends { createdAt: string }>(records: T[]) {
  return [...records].sort((left, right) => Date.parse(right.createdAt) - Date.parse(left.createdAt))
}

function formatPayloadJson(payloadJson: string) {
  try {
    return JSON.stringify(JSON.parse(payloadJson), null, 2)
  } catch {
    return payloadJson
  }
}

function formatNote(note: string | null) {
  const trimmed = note?.trim()
  return trimmed ? trimmed : 'No note recorded.'
}

export function LoanApplicationsPage() {
  const [applications, setApplications] = useState<LoanApplicationRecord[]>([])
  const [selectedLoan, setSelectedLoan] = useState<LoanApplicationRecord | null>(null)
  const [statusHistory, setStatusHistory] = useState<LoanApplicationStatusTransitionRecord[]>([])
  const [intakeAudits, setIntakeAudits] = useState<LoanApplicationIntakeAuditRecord[]>([])
  const [lsps, setLsps] = useState<LspRecord[]>([])
  const [products, setProducts] = useState<LoanProductRecord[]>([])
  const [form, setForm] = useState<IntakeFormState>(initialFormState)
  const [filters, setFilters] = useState<ListFilterState>(initialFilterState)
  const [selectedApplicationId, setSelectedApplicationId] = useState('')
  const [loading, setLoading] = useState(true)
  const [detailLoading, setDetailLoading] = useState(false)
  const [auditLoading, setAuditLoading] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [transitioning, setTransitioning] = useState(false)
  const [pageError, setPageError] = useState('')
  const [workflowError, setWorkflowError] = useState('')
  const [auditError, setAuditError] = useState('')
  const [transitionNote, setTransitionNote] = useState('')

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
  const selectedApplicationFromList =
    applications.find((application) => application.id === selectedApplicationId) ?? null
  const visibleSelectedApplication = selectedLoan ?? selectedApplicationFromList
  const latestAudit = intakeAudits[0] ?? null
  const selectedStatusHistory = useMemo(() => sortByCreatedAtDesc(statusHistory), [statusHistory])
  const transitionActions = useMemo(
    () =>
      visibleSelectedApplication
        ? getTransitionActions(visibleSelectedApplication.status as LoanApplicationStatus)
        : [],
    [visibleSelectedApplication],
  )
  const currentProgressIndex = visibleSelectedApplication
    ? statusProgressIndex(visibleSelectedApplication.status as LoanApplicationStatus)
    : 0

  async function loadApplications(nextFilters: ListFilterState) {
    const response = await listLoanApplications({
      lspId: nextFilters.lspId || undefined,
      productId: nextFilters.productId || undefined,
      status: nextFilters.status || undefined,
      sourceChannel: nextFilters.sourceChannel || undefined,
      query: nextFilters.query.trim() || undefined,
    })

    setApplications(response)
    setSelectedApplicationId((current) =>
      response.some((application) => application.id === current) ? current : response[0]?.id ?? '',
    )

    return response
  }

  async function refreshSelectedLoan(applicationId: string) {
    const [detailResponse, historyResponse] = await Promise.all([
      getLoanApplication(applicationId),
      listLoanApplicationStatusTransitions(applicationId),
    ])

    setSelectedLoan(detailResponse)
    setStatusHistory(sortByCreatedAtDesc(historyResponse))
  }

  useEffect(() => {
    let cancelled = false

    async function loadPage() {
      setLoading(true)
      setPageError('')

      try {
        const [applicationResponse, lspResponse, productResponse] = await Promise.all([
          listLoanApplications(),
          listLsps(),
          listLoanProducts(),
        ])

        if (!cancelled) {
          setApplications(applicationResponse)
          setSelectedApplicationId(applicationResponse[0]?.id ?? '')
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
          setPageError(message)
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

    async function loadSelectedLoan() {
      if (!selectedApplicationId) {
        setSelectedLoan(null)
        setStatusHistory([])
        setWorkflowError('')
        return
      }

      setSelectedLoan(null)
      setStatusHistory([])
      setDetailLoading(true)
      setWorkflowError('')

      try {
        const [detailResponse, historyResponse] = await Promise.all([
          getLoanApplication(selectedApplicationId),
          listLoanApplicationStatusTransitions(selectedApplicationId),
        ])

        if (!cancelled) {
          setSelectedLoan(detailResponse)
          setStatusHistory(sortByCreatedAtDesc(historyResponse))
        }
      } catch (loadError) {
        const message =
          loadError instanceof Error ? loadError.message : 'Unable to load loan detail.'
        if (!cancelled) {
          setSelectedLoan(null)
          setStatusHistory([])
          setWorkflowError(message)
        }
      } finally {
        if (!cancelled) {
          setDetailLoading(false)
        }
      }
    }

    void loadSelectedLoan()

    return () => {
      cancelled = true
    }
  }, [selectedApplicationId])

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
          setPageError('')
          setSelectedApplicationId((current) =>
            response.some((application) => application.id === current) ? current : response[0]?.id ?? '',
          )
        }
      } catch (loadError) {
        const message =
          loadError instanceof Error ? loadError.message : 'Unable to filter loan applications.'
        if (!cancelled) {
          setPageError(message)
        }
      }
    }

    void refreshFilteredList()

    return () => {
      cancelled = true
    }
  }, [filters, loading])

  useEffect(() => {
    let cancelled = false

    async function loadIntakeAudit() {
      if (!selectedApplicationId) {
        setIntakeAudits([])
        setAuditError('')
        return
      }

      setAuditLoading(true)
      setAuditError('')

      try {
        const response = await listLoanApplicationIntakeAudits(selectedApplicationId)
        if (!cancelled) {
          setIntakeAudits(sortByCreatedAtDesc(response))
        }
      } catch (loadError) {
        const message =
          loadError instanceof Error ? loadError.message : 'Unable to load intake audit.'
        if (!cancelled) {
          setIntakeAudits([])
          setAuditError(message)
        }
      } finally {
        if (!cancelled) {
          setAuditLoading(false)
        }
      }
    }

    void loadIntakeAudit()

    return () => {
      cancelled = true
    }
  }, [selectedApplicationId])

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setSubmitting(true)
    setPageError('')

    try {
      const created = await createLoanApplication({
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
      setSelectedApplicationId(created.id)
      setForm((current) => ({
        ...initialFormState,
        lspId: current.lspId,
        productId: current.productId,
        sourceChannel: current.sourceChannel,
      }))
    } catch (submitError) {
      const message =
        submitError instanceof Error ? submitError.message : 'Unable to create loan application.'
      setPageError(message)
    } finally {
      setSubmitting(false)
    }
  }

  async function handleStatusTransition(targetStatus: LoanApplicationStatus) {
    if (!selectedApplicationId) {
      return
    }

    setTransitioning(true)
    setWorkflowError('')

    try {
      await transitionLoanApplicationStatus(selectedApplicationId, {
        targetStatus,
        note: transitionNote.trim() || undefined,
      })

      setTransitionNote('')
      await refreshSelectedLoan(selectedApplicationId)
      await loadApplications(filters)
    } catch (submitError) {
      const message =
        submitError instanceof Error ? submitError.message : 'Unable to update loan status.'
      setWorkflowError(message)
    } finally {
      setTransitioning(false)
    }
  }

  return (
    <div className="users-layout">
      <Card className="list-card">
        <CardHeader>
          <div className="section-eyebrow">Loan intake</div>
          <CardTitle>Received applications</CardTitle>
          <CardDescription>
            Phase 4 foundation: capture borrower-linked intake records and review newly received
            applications.
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
                {loanApplicationStatusOptions.map((status) => (
                  <option key={status} value={status}>
                    {loanStatusLabel(status)}
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
          {pageError ? <div className="empty-state">{pageError}</div> : null}
          {!loading && !pageError ? (
            <div className="table-grid">
              {applications.map((application) => (
                <button
                  className="table-row"
                  key={application.id}
                  onClick={() => setSelectedApplicationId(application.id)}
                  style={{
                    cursor: 'pointer',
                    border:
                      application.id === selectedApplicationId
                        ? '1px solid var(--color-accent)'
                        : undefined,
                    background:
                      application.id === selectedApplicationId
                        ? 'color-mix(in srgb, var(--color-accent) 10%, transparent)'
                        : undefined,
                    textAlign: 'left',
                    width: '100%',
                  }}
                  type="button"
                >
                  <div>
                    <strong>{application.borrowerFullName}</strong>
                    <p className="helper-copy">
                      {application.borrowerPan} - {application.borrowerMobile}
                    </p>
                  </div>
                  <Badge variant={loanStatusVariant(application.status as LoanApplicationStatus)}>
                    {loanStatusLabel(application.status as LoanApplicationStatus)}
                  </Badge>
                  <span>{currencyLabel(application.requestedAmount)}</span>
                  <span>{application.tenureMonths} months</span>
                  <span className="helper-copy">
                    {application.lspCode} - {application.productCode}
                  </span>
                  <span className="helper-copy">{application.externalLoanId}</span>
                  <span className="helper-copy">{formatTimestamp(application.createdAt)}</span>
                </button>
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
              Use this internal intake form to create the borrower and application foundation for
              Phase 4.
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
              {pageError ? <div className="empty-state">{pageError}</div> : null}
              <div className="inline-actions">
                <Button disabled={submitting} type="submit">
                  {submitting ? 'Creating...' : 'Create application'}
                </Button>
              </div>
            </form>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <div className="section-eyebrow">Loan workflow</div>
            <CardTitle>Selected loan detail and status</CardTitle>
            <CardDescription>
              Review the selected application, move it through the first review lifecycle, and
              inspect the status history.
            </CardDescription>
          </CardHeader>
          <CardContent>
            {visibleSelectedApplication ? (
              <div className="loan-workflow">
                <div className="loan-workflow__summary">
                  <div>
                    <div className="inline-actions">
                      <Badge variant={loanStatusVariant(visibleSelectedApplication.status as LoanApplicationStatus)}>
                        {loanStatusLabel(visibleSelectedApplication.status as LoanApplicationStatus)}
                      </Badge>
                      <Badge>{visibleSelectedApplication.externalLoanId}</Badge>
                    </div>
                    <h3>{visibleSelectedApplication.borrowerFullName}</h3>
                    <p className="helper-copy">
                      {visibleSelectedApplication.lspCode} - {visibleSelectedApplication.productCode}
                      {' '}| Source {visibleSelectedApplication.sourceChannel}
                    </p>
                  </div>
                  <div className="loan-workflow__headline-metrics">
                    <div className="loan-workflow__metric">
                      <span>Amount</span>
                      <strong>{currencyLabel(visibleSelectedApplication.requestedAmount)}</strong>
                    </div>
                    <div className="loan-workflow__metric">
                      <span>Tenure</span>
                      <strong>{visibleSelectedApplication.tenureMonths} months</strong>
                    </div>
                    <div className="loan-workflow__metric">
                      <span>Created</span>
                      <strong>{formatTimestamp(visibleSelectedApplication.createdAt)}</strong>
                    </div>
                  </div>
                </div>

                <div className="loan-status-lane" aria-label="Loan status progression">
                  {workflowProgression.map((step) => {
                    const stepIndex =
                      step.status === 'RECEIVED' ? 1 : step.status === 'UNDER_REVIEW' ? 2 : 3
                    const isCurrent = currentProgressIndex === stepIndex
                    const isComplete = currentProgressIndex > stepIndex

                    return (
                      <div
                        key={step.status}
                        className={[
                          'loan-status-lane__step',
                          isCurrent ? 'loan-status-lane__step--current' : '',
                          isComplete ? 'loan-status-lane__step--complete' : '',
                        ]
                          .filter(Boolean)
                          .join(' ')}
                      >
                        <div className="loan-status-lane__step-number">
                          {step.status === 'DECISION' ? '3' : statusProgressIndex(step.status)}
                        </div>
                        <div>
                          <strong>{step.label}</strong>
                          <p className="helper-copy">{step.description}</p>
                        </div>
                        {step.status === 'DECISION' ? (
                          <div className="inline-actions">
                            <Badge variant="success">Approved</Badge>
                            <Badge variant="destructive">Rejected</Badge>
                          </div>
                        ) : null}
                      </div>
                    )
                  })}
                </div>

                <div className="loan-detail-grid">
                  <div className="loan-detail-field">
                    <span>Application id</span>
                    <strong>{visibleSelectedApplication.id}</strong>
                  </div>
                  <div className="loan-detail-field">
                    <span>Borrower PAN</span>
                    <strong>{visibleSelectedApplication.borrowerPan}</strong>
                  </div>
                  <div className="loan-detail-field">
                    <span>Borrower mobile</span>
                    <strong>{visibleSelectedApplication.borrowerMobile}</strong>
                  </div>
                  <div className="loan-detail-field">
                    <span>Borrower email</span>
                    <strong>{visibleSelectedApplication.borrowerEmail || 'Not provided'}</strong>
                  </div>
                  <div className="loan-detail-field">
                    <span>LSP</span>
                    <strong>
                      {visibleSelectedApplication.lspCode} - {visibleSelectedApplication.lspName}
                    </strong>
                  </div>
                  <div className="loan-detail-field">
                    <span>Product</span>
                    <strong>
                      {visibleSelectedApplication.productCode} - {visibleSelectedApplication.productName}
                    </strong>
                  </div>
                  <div className="loan-detail-field">
                    <span>Requested amount</span>
                    <strong>{currencyLabel(visibleSelectedApplication.requestedAmount)}</strong>
                  </div>
                  <div className="loan-detail-field">
                    <span>Tenure</span>
                    <strong>{visibleSelectedApplication.tenureMonths} months</strong>
                  </div>
                </div>

                <div className="loan-transition-panel">
                  <div className="loan-transition-panel__header">
                    <div>
                      <h4>Transition action</h4>
                      <p className="helper-copy">
                        Add an optional note before updating the status.
                      </p>
                    </div>
                    <Badge variant={loanStatusVariant(visibleSelectedApplication.status as LoanApplicationStatus)}>
                      {loanStatusLabel(visibleSelectedApplication.status as LoanApplicationStatus)}
                    </Badge>
                  </div>
                  <div className="field-stack">
                    <label htmlFor="status-note">Decision note</label>
                    <textarea
                      id="status-note"
                      className="ui-textarea"
                      value={transitionNote}
                      onChange={(event) => setTransitionNote(event.target.value)}
                      placeholder="Add context for the review decision. This is optional."
                      rows={4}
                    />
                  </div>
                  {workflowError ? <div className="empty-state">{workflowError}</div> : null}
                  <div className="loan-transition-actions">
                    {transitionActions.map((action) => (
                      <Button
                        key={action.targetStatus}
                        disabled={transitioning}
                        onClick={() => void handleStatusTransition(action.targetStatus)}
                        type="button"
                        variant={action.variant}
                        className={
                          action.targetStatus === 'REJECTED'
                            ? 'loan-transition-button--reject'
                            : undefined
                        }
                      >
                        {action.targetStatus === 'APPROVED' ? (
                          <CheckCircle2 size={16} />
                        ) : action.targetStatus === 'REJECTED' ? (
                          <FileText size={16} />
                        ) : (
                          <ArrowRight size={16} />
                        )}
                        {transitioning ? 'Updating...' : action.label}
                      </Button>
                    ))}
                    {!transitionActions.length ? (
                      <div className="empty-state">This loan is in a terminal state.</div>
                    ) : null}
                  </div>
                </div>

                <div className="loan-history">
                  <div className="loan-history__header">
                    <div className="section-eyebrow">Status history</div>
                    <Badge>{selectedStatusHistory.length} events</Badge>
                  </div>
                  {detailLoading ? <div className="empty-state">Loading loan detail...</div> : null}
                  {!detailLoading && !selectedStatusHistory.length ? (
                    <div className="empty-state">No status transitions recorded yet.</div>
                  ) : null}
                  {!detailLoading && selectedStatusHistory.length ? (
                    <div className="loan-history__list">
                      {selectedStatusHistory.map((transition) => (
                        <div className="loan-history__item" key={transition.id}>
                          <div>
                            <strong>
                              {loanStatusLabel(transition.fromStatus)} - {loanStatusLabel(transition.toStatus)}
                            </strong>
                            <p className="helper-copy">{formatTimestamp(transition.createdAt)}</p>
                            <p className="helper-copy">{formatNote(transition.note)}</p>
                            <p className="helper-copy">
                              Correlation ID: {transition.correlationId ?? 'Not captured'}
                            </p>
                          </div>
                          <div className="inline-actions">
                            <Badge variant={loanStatusVariant(transition.toStatus)}>
                              {loanStatusLabel(transition.toStatus)}
                            </Badge>
                            <Badge variant="warning">{transition.actorUsername}</Badge>
                          </div>
                        </div>
                      ))}
                    </div>
                  ) : null}
                </div>
              </div>
            ) : (
              <div className="empty-state">Select a loan application to inspect its detail view.</div>
            )}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <div className="section-eyebrow">Troubleshooting</div>
            <CardTitle>Selected intake audit</CardTitle>
            <CardDescription>
              Inspect the persisted raw intake payload captured when the selected application was
              created.
            </CardDescription>
          </CardHeader>
          <CardContent>
            {visibleSelectedApplication ? (
              <div className="form-grid">
                <div className="inline-actions">
                  <Badge>{visibleSelectedApplication.externalLoanId}</Badge>
                  <Badge variant={loanStatusVariant(visibleSelectedApplication.status as LoanApplicationStatus)}>
                    {loanStatusLabel(visibleSelectedApplication.status as LoanApplicationStatus)}
                  </Badge>
                </div>
                <div className="helper-copy">
                  Borrower: {visibleSelectedApplication.borrowerFullName} -{' '}
                  {visibleSelectedApplication.borrowerPan}
                </div>
                <div className="helper-copy">
                  Product: {visibleSelectedApplication.productCode} - Source:{' '}
                  {visibleSelectedApplication.sourceChannel}
                </div>
                {auditLoading ? <div className="empty-state">Loading intake audit...</div> : null}
                {auditError ? <div className="empty-state">{auditError}</div> : null}
                {!auditLoading && !auditError && latestAudit ? (
                  <>
                    <div className="helper-copy">
                      Captured {formatTimestamp(latestAudit.createdAt)} by {latestAudit.actorUsername}
                    </div>
                    <div className="helper-copy">
                      Correlation ID: {latestAudit.correlationId ?? 'Not captured'}
                    </div>
                    <pre
                      className="helper-copy"
                      style={{
                        margin: 0,
                        maxHeight: '22rem',
                        overflow: 'auto',
                        padding: '1rem',
                        borderRadius: '1rem',
                        background: 'var(--color-panel-muted)',
                        whiteSpace: 'pre-wrap',
                        wordBreak: 'break-word',
                      }}
                    >
                      {formatPayloadJson(latestAudit.payloadJson)}
                    </pre>
                  </>
                ) : null}
                {!auditLoading && !auditError && !latestAudit ? (
                  <div className="empty-state">
                    No intake audit is available for the selected application.
                  </div>
                ) : null}
              </div>
            ) : (
              <div className="empty-state">Select a loan application to inspect its intake audit.</div>
            )}
          </CardContent>
        </Card>
      </div>
    </div>
  )
}
