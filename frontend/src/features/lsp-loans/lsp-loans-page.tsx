import { useEffect, useState, type FormEvent } from 'react'
import { Badge } from '../../components/ui/badge'
import { Button } from '../../components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../../components/ui/card'
import { Input } from '../../components/ui/input'
import {
  getExternalLspLoanApplication,
  listExternalLspLoanApplications,
  type LoanApplicationDetailRecord,
  type LoanApplicationRecord,
} from '../api/lms-api'
import { useAuth } from '../auth/auth-context'

function statusVariant(status: string): 'default' | 'warning' | 'success' {
  if (status === 'APPROVED') {
    return 'success'
  }

  if (status === 'HOLD' || status === 'REJECTED') {
    return 'warning'
  }

  return 'default'
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
  const [query, setQuery] = useState('')
  const [submittedQuery, setSubmittedQuery] = useState('')
  const [loans, setLoans] = useState<LoanApplicationRecord[]>([])
  const [selectedLoanId, setSelectedLoanId] = useState('')
  const [selectedLoan, setSelectedLoan] = useState<LoanApplicationDetailRecord | null>(null)
  const [loadingList, setLoadingList] = useState(true)
  const [loadingDetail, setLoadingDetail] = useState(false)
  const [error, setError] = useState('')

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

  function handleSearch(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setSubmittedQuery(query.trim())
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
          {loadingList ? <div className="empty-state">Loading my loans...</div> : null}
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
                  <Badge variant={statusVariant(loan.status)}>{loan.status}</Badge>
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
          {loadingDetail ? <div className="empty-state">Loading loan detail...</div> : null}
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
                  <Badge variant={statusVariant(selectedLoan.status)}>{selectedLoan.status}</Badge>
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
            </div>
          ) : null}
        </CardContent>
      </Card>
    </div>
  )
}
