import { useEffect, useState, type FormEvent } from 'react'
import { BlueLoader } from '@/components/app/blue-loader'
import { Badge } from '../../components/ui/badge'
import { Button } from '../../components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../../components/ui/card'
import { Input } from '../../components/ui/input'
import {
  type LoanApplicationStatus,
  type LoanApplicationDetailRecord,
  type LoanApplicationRecord,
  type LoanInvalidationReason,
  type LoanInvalidationReasonOptionRecord,
} from '../api/lms-api'
import {
  getExternalLspLoanApplication,
  invalidateExternalLspLoanApplication,
  listExternalLspLoanApplications,
  listExternalLspLoanInvalidationReasons,
} from '../api/loan-applications-api'
import { useAuth } from '../auth/auth-context'

function statusLabel(status: LoanApplicationStatus) {
  switch (status) {
    case 'INITIALIZED':
      return 'Initialized'
    case 'AWAITING_APPROVAL':
      return 'Awaiting approval'
    case 'APPROVED_PENDING_DISBURSAL':
      return 'Application approved - pending for disbursal'
    case 'REJECTED':
      return 'Rejected'
    case 'PAYMENT_REINITIATION':
      return 'Payment re-initiation'
    case 'INVALID':
      return 'Invalid'
    case 'DISBURSED':
      return 'Disbursed'
    case 'UNDER_REPAYMENT':
      return 'Under repayment'
    case 'CLOSED':
      return 'Closed'
  }
}

function statusVariant(status: LoanApplicationStatus): 'default' | 'warning' | 'success' | 'destructive' {
  switch (status) {
    case 'INITIALIZED':
    case 'APPROVED_PENDING_DISBURSAL':
    case 'PAYMENT_REINITIATION':
      return 'warning'
    case 'INVALID':
    case 'REJECTED':
      return 'destructive'
    case 'DISBURSED':
    case 'UNDER_REPAYMENT':
      return 'success'
    case 'AWAITING_APPROVAL':
    case 'CLOSED':
    default:
      return 'default'
  }
}

function escapeCsv(value: string | number | null | undefined) {
  if (value == null) {
    return ''
  }
  const text = String(value)
  if (!text.includes(',') && !text.includes('"') && !text.includes('\n')) {
    return text
  }
  return `"${text.replaceAll('"', '""')}"`
}

function downloadCsv(filename: string, rows: Array<Array<string | number>>) {
  const csv = rows.map((row) => row.map(escapeCsv).join(',')).join('\n')
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  URL.revokeObjectURL(url)
}

export function LspLoansPage() {
  const { user } = useAuth()
  const canInvalidate = user?.roles.includes('LSP_UI_WRITE') ?? false
  const [query, setQuery] = useState('')
  const [submittedQuery, setSubmittedQuery] = useState('')
  const [loans, setLoans] = useState<LoanApplicationRecord[]>([])
  const [selectedLoanId, setSelectedLoanId] = useState('')
  const [selectedLoan, setSelectedLoan] = useState<LoanApplicationDetailRecord | null>(null)
  const [invalidationReasons, setInvalidationReasons] = useState<LoanInvalidationReasonOptionRecord[]>([])
  const [selectedReasonCode, setSelectedReasonCode] = useState<LoanInvalidationReason | ''>('')
  const [reasonText, setReasonText] = useState('')
  const [loadingReasons, setLoadingReasons] = useState(false)
  const [invalidating, setInvalidating] = useState(false)
  const [loadingList, setLoadingList] = useState(true)
  const [loadingDetail, setLoadingDetail] = useState(false)
  const [error, setError] = useState('')
  const [invalidationError, setInvalidationError] = useState('')

  useEffect(() => {
    if (!canInvalidate) {
      setInvalidationReasons([])
      return
    }

    let cancelled = false

    async function loadInvalidationReasons() {
      setLoadingReasons(true)

      try {
        const response = await listExternalLspLoanInvalidationReasons()
        if (!cancelled) {
          setInvalidationReasons(response)
        }
      } catch (loadError) {
        if (!cancelled) {
          setInvalidationError(
            loadError instanceof Error ? loadError.message : 'Unable to load invalidation reasons.',
          )
        }
      } finally {
        if (!cancelled) {
          setLoadingReasons(false)
        }
      }
    }

    void loadInvalidationReasons()

    return () => {
      cancelled = true
    }
  }, [canInvalidate])

  useEffect(() => {
    let cancelled = false

    async function loadLoans() {
      setLoadingList(true)
      setError('')

      try {
        const response = await listExternalLspLoanApplications({
          query: submittedQuery || undefined,
        })
        if (!cancelled) {
          setLoans(response)
          setSelectedLoanId((current) => {
            if (current && response.some((loan) => loan.id === current)) {
              return current
            }
            return response[0]?.id ?? ''
          })
        }
      } catch (loadError) {
        if (!cancelled) {
          setError(loadError instanceof Error ? loadError.message : 'Unable to load loans.')
          setLoans([])
          setSelectedLoanId('')
        }
      } finally {
        if (!cancelled) {
          setLoadingList(false)
        }
      }
    }

    void loadLoans()

    return () => {
      cancelled = true
    }
  }, [submittedQuery])

  useEffect(() => {
    let cancelled = false

    async function loadLoanDetail() {
      if (!selectedLoanId) {
        setSelectedLoan(null)
        return
      }

      setLoadingDetail(true)
      setError('')

      try {
        const response = await getExternalLspLoanApplication(selectedLoanId)
        if (!cancelled) {
          setSelectedLoan(response)
        }
      } catch (loadError) {
        if (!cancelled) {
          setError(loadError instanceof Error ? loadError.message : 'Unable to load loan detail.')
          setSelectedLoan(null)
        }
      } finally {
        if (!cancelled) {
          setLoadingDetail(false)
        }
      }
    }

    void loadLoanDetail()

    return () => {
      cancelled = true
    }
  }, [selectedLoanId])

  useEffect(() => {
    setSelectedReasonCode('')
    setReasonText('')
    setInvalidationError('')
  }, [selectedLoanId])

  function handleSearch(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setSubmittedQuery(query.trim())
  }

  const selectedReason = invalidationReasons.find((reason) => reason.code === selectedReasonCode)
  const requiresReasonText = Boolean(selectedReason?.requiresText || selectedReason?.requiresDetail)

  async function handleInvalidate(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()

    if (!selectedLoan || !selectedReasonCode) {
      setInvalidationError('Select an invalidation reason to continue.')
      return
    }

    const normalizedReasonText = reasonText.trim()
    if (requiresReasonText && !normalizedReasonText) {
      setInvalidationError('Reason details are required for the selected option.')
      return
    }

    setInvalidationError('')
    setInvalidating(true)

    try {
      const updatedLoan = await invalidateExternalLspLoanApplication(
        selectedLoan.id,
        {
          reasonCode: selectedReasonCode,
          reasonText: requiresReasonText ? normalizedReasonText || undefined : undefined,
        },
        globalThis.crypto.randomUUID(),
      )

      setSelectedLoan(updatedLoan)
      setLoans((current) =>
        current.map((loan) => (loan.id === updatedLoan.id ? { ...loan, status: updatedLoan.status } : loan)),
      )
    } catch (requestError) {
      setInvalidationError(
        requestError instanceof Error ? requestError.message : 'Unable to mark the loan invalid.',
      )
    } finally {
      setInvalidating(false)
    }
  }

  function handleExport() {
    downloadCsv('lsp-loans-report.csv', [
      [
        'Application ID',
        'External Loan ID',
        'Borrower',
        'Mobile',
        'Product',
        'Status',
        'Requested Amount',
        'Tenure Months',
        'Created At',
      ],
      ...loans.map((loan) => [
        loan.id,
        loan.externalLoanId,
        loan.borrowerFullName,
        loan.borrowerMobile,
        loan.productName,
        loan.status,
        loan.requestedAmount,
        loan.tenureMonths,
        loan.createdAt,
      ]),
    ])
  }

  return (
    <div className="users-layout">
      <Card className="list-card">
        <CardHeader>
          <div className="section-eyebrow">Tenant scope</div>
          <CardTitle>{user?.lspName ?? user?.scope ?? 'My loans'}</CardTitle>
          <CardDescription>
            Read-only visibility into loans belonging to the authenticated LSP, with export support for reporting.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <form className="inline-actions" onSubmit={handleSearch} style={{ marginBottom: '1rem' }}>
            <Input
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              placeholder="Search by external loan id, borrower, PAN, or mobile"
            />
            <Button type="submit">Search</Button>
            <Button type="button" variant="ghost" onClick={handleExport} disabled={!loans.length}>
              Export CSV
            </Button>
          </form>
          <div className="inline-actions" style={{ marginBottom: '1rem' }}>
            <Badge>{loans.length} loans</Badge>
            {user?.lspName ? <Badge variant="warning">{user.lspName}</Badge> : null}
          </div>
          {loadingList ? (
            <BlueLoader
              title="Loading my loans"
              description="Fetching tenant-scoped applications and latest servicing state."
              compact
            />
          ) : null}
          {error ? <div className="empty-state">{error}</div> : null}
          {!loadingList && !error ? (
            <div className="table-grid">
              {loans.map((loan) => (
                <button
                  key={loan.id}
                  className="table-row"
                  type="button"
                  onClick={() => setSelectedLoanId(loan.id)}
                  style={{
                    textAlign: 'left',
                    border: loan.id === selectedLoanId ? '1px solid rgba(31, 41, 55, 0.2)' : undefined,
                    background: loan.id === selectedLoanId ? 'rgba(15, 23, 42, 0.03)' : undefined,
                  }}
                >
                  <div>
                    <strong>{loan.borrowerFullName}</strong>
                    <p className="helper-copy">{loan.externalLoanId}</p>
                  </div>
                  <Badge variant={statusVariant(loan.status)}>{statusLabel(loan.status)}</Badge>
                  <span>{loan.requestedAmount.toLocaleString('en-IN')}</span>
                  <span className="helper-copy">{new Date(loan.createdAt).toLocaleString()}</span>
                </button>
              ))}
              {!loans.length ? <div className="empty-state">No loans match the current filter.</div> : null}
            </div>
          ) : null}
        </CardContent>
      </Card>

      <Card className="content-card">
        <CardHeader>
          <div className="section-eyebrow">Loan detail</div>
          <CardTitle>{selectedLoan?.externalLoanId ?? 'Select a loan'}</CardTitle>
          <CardDescription>
            Application, servicing, and repayment state visible to the authenticated tenant.
          </CardDescription>
        </CardHeader>
        <CardContent>
          {loadingDetail ? (
            <BlueLoader
              title="Loading loan detail"
              description="Preparing borrower, repayment, and activity details."
              compact
            />
          ) : null}
          {!loadingDetail && !selectedLoan ? (
            <div className="empty-state">Choose a loan from the list to inspect its current state.</div>
          ) : null}
          {!loadingDetail && selectedLoan ? (
            <div className="form-grid">
              <div className="field-stack">
                <label>Borrower</label>
                <div>{selectedLoan.borrowerFullName}</div>
                <div className="helper-copy">{selectedLoan.borrowerMobile}</div>
              </div>
              <div className="field-stack">
                <label>Status</label>
                <div className="inline-actions">
                  <Badge variant={statusVariant(selectedLoan.status)}>{statusLabel(selectedLoan.status)}</Badge>
                  <span className="helper-copy">{selectedLoan.sourceChannel}</span>
                </div>
              </div>
              <div className="field-stack">
                <label>Requested amount</label>
                <div>{selectedLoan.requestedAmount.toLocaleString('en-IN')}</div>
              </div>
              <div className="field-stack">
                <label>Tenure</label>
                <div>{selectedLoan.tenureMonths} months</div>
              </div>
              <div className="field-stack">
                <label>Product</label>
                <div>{selectedLoan.productName}</div>
              </div>
              <div className="field-stack">
                <label>Loan account</label>
                <div>{selectedLoan.loanAccount?.accountNumber ?? 'Not created yet'}</div>
                <div className="helper-copy">{selectedLoan.loanAccount?.status ?? 'Awaiting approval'}</div>
              </div>
              <div className="field-stack">
                <label>Delinquency</label>
                <div>{selectedLoan.loanAccount?.delinquency?.bucket ?? 'CURRENT'}</div>
                <div className="helper-copy">
                  Max DPD {selectedLoan.loanAccount?.delinquency?.maxDaysPastDue ?? 0} · Overdue amount{' '}
                  {selectedLoan.loanAccount?.delinquency?.overdueAmount?.toLocaleString('en-IN') ?? '0'}
                </div>
              </div>
              <div className="field-stack">
                <label>Repayment schedule</label>
                <div>{selectedLoan.loanAccount?.repaymentSchedule?.installmentCount ?? 0} installments</div>
                <div className="helper-copy">
                  EMI {selectedLoan.loanAccount?.repaymentSchedule?.installmentAmount?.toLocaleString('en-IN') ?? '0'}
                </div>
              </div>
              <div className="field-stack">
                <label>Latest activity</label>
                <div>{selectedLoan.lastActivity?.summary ?? 'No activity yet'}</div>
                <div className="helper-copy">
                  {selectedLoan.lastActivity
                    ? `${selectedLoan.lastActivity.actorUsername ?? 'system'} · ${new Date(selectedLoan.lastActivity.occurredAt).toLocaleString()}`
                    : 'Activity appears after intake, review, or servicing events.'}
                </div>
              </div>

              {selectedLoan.invalidatedAt ? (
                <div className="field-stack">
                  <label>Invalidation</label>
                  <div>
                    {selectedLoan.invalidReasonText
                      ? `${selectedLoan.invalidReasonCode} - ${selectedLoan.invalidReasonText}`
                      : selectedLoan.invalidReasonCode ?? 'Invalidated'}
                  </div>
                  <div className="helper-copy">
                    {(selectedLoan.invalidatedByUsername ?? 'system') +
                      ' | ' +
                      new Date(selectedLoan.invalidatedAt).toLocaleString()}
                  </div>
                </div>
              ) : null}

              {canInvalidate && selectedLoan.status !== 'INVALID' ? (
                <form
                  className="field-stack"
                  style={{ gridColumn: '1 / -1' }}
                  onSubmit={(event) => void handleInvalidate(event)}
                >
                  <label htmlFor="loan-invalidation-reason">Reason</label>
                  <select
                    id="loan-invalidation-reason"
                    className="ui-input"
                    value={selectedReasonCode}
                    onChange={(event) =>
                      setSelectedReasonCode(event.target.value as LoanInvalidationReason | '')
                    }
                    disabled={loadingReasons || invalidating}
                  >
                    <option value="">Select a reason</option>
                    {invalidationReasons.map((reason) => (
                      <option key={reason.code} value={reason.code}>
                        {reason.label}
                      </option>
                    ))}
                  </select>

                  {requiresReasonText ? (
                    <>
                      <label htmlFor="loan-invalidation-reason-text">Reason details</label>
                      <textarea
                        id="loan-invalidation-reason-text"
                        className="ui-input"
                        rows={4}
                        value={reasonText}
                        onChange={(event) => setReasonText(event.target.value)}
                        placeholder="Capture the invalidation detail."
                        disabled={invalidating}
                      />
                    </>
                  ) : null}

                  {invalidationError ? <div className="helper-copy">{invalidationError}</div> : null}

                  <div className="inline-actions">
                    <Button type="submit" disabled={loadingReasons || invalidating || !selectedReasonCode}>
                      {invalidating ? 'Marking invalid...' : 'Mark invalid'}
                    </Button>
                  </div>
                </form>
              ) : null}
            </div>
          ) : null}
        </CardContent>
      </Card>
    </div>
  )
}
