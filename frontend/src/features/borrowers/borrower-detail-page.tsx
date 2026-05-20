import type { ReactNode } from 'react'
import { ArrowLeft } from 'lucide-react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { ContentState } from '@/components/app/content-state'
import { PageHeader } from '@/components/app/page-header'
import { PageSection } from '@/components/app/page-section'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { cn } from '@/lib/utils'
import type { BorrowerDetailRecord, BorrowerLoanRecord } from '../api/borrowers-api'
import { getBorrowerInitials } from '../loan-applications/loan-application-detail-model'
import {
  currencyLabel,
  formatBorrowerMobile,
  formatBorrowerPan,
  formatDateLabel,
  formatEmploymentType,
  formatIncomeLabel,
  formatTimestamp,
} from '../loan-applications/loan-application-formatters'
import { loanAccountStatusLabel, loanAccountStatusVariant } from '../loan-applications/loan-application-workflow'
import type { LoanAccountStatus } from '../api/lms-api'
import { useBorrowerDetail } from './use-borrower-detail'

const OPEN_LOAN_STATUSES: readonly LoanAccountStatus[] = [
  'PENDING_DISBURSEMENT',
  'DISBURSEMENT_REQUESTED',
  'DISBURSED',
  'DISBURSEMENT_PENDING_RECONCILIATION',
]

function DetailField({ label, value }: { label: string; value: ReactNode }) {
  return (
    <div className="space-y-1">
      <p className="text-[0.68rem] font-semibold uppercase tracking-[0.16em] text-muted-foreground">
        {label}
      </p>
      <p className="text-sm font-medium text-foreground">{value ?? 'Not provided'}</p>
    </div>
  )
}

function formatAddress(borrower: BorrowerDetailRecord) {
  const lines = [
    borrower.addressLine1,
    borrower.addressLine2,
    [borrower.city, borrower.state].filter(Boolean).join(', '),
    borrower.addressZipCode,
  ].filter((segment) => segment && segment.trim().length > 0)

  if (lines.length === 0) {
    return 'Not provided'
  }

  return (
    <span className="whitespace-pre-line">{lines.join('\n')}</span>
  )
}

function isOpenLoan(status: string | null) {
  if (!status) {
    return false
  }
  return OPEN_LOAN_STATUSES.includes(status as LoanAccountStatus)
}

function summarizeLoans(loans: BorrowerLoanRecord[]) {
  const activeCount = loans.filter((loan) => isOpenLoan(loan.status)).length
  const closedCount = loans.length - activeCount
  return { activeCount, closedCount }
}

export function BorrowerDetailPage() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()

  const { borrower, loading, error } = useBorrowerDetail({ borrowerId: id })

  if (loading) {
    return (
      <ContentState
        title="Loading borrower"
        description="Fetching profile and loan history."
        tone="loading"
      />
    )
  }

  if (error || !borrower) {
    return (
      <ContentState
        title="Unable to load borrower"
        description={error || 'Borrower not found.'}
        tone="error"
      />
    )
  }

  const { activeCount, closedCount } = summarizeLoans(borrower.loans)

  return (
    <div className="space-y-6">
      <Button
        type="button"
        variant="ghost"
        size="sm"
        className="w-fit gap-1.5 px-0 text-muted-foreground hover:bg-transparent hover:text-primary"
        onClick={() => navigate(-1)}
      >
        <ArrowLeft className="size-4" />
        Back
      </Button>

      <PageHeader
        eyebrow="Borrower"
        title={borrower.fullName}
        description={`PAN ${formatBorrowerPan(borrower.pan)} | ${activeCount} active / ${closedCount} closed loans`}
        actions={
          <div className="flex flex-wrap items-center gap-2">
            <Badge variant={activeCount > 0 ? 'success' : 'default'}>
              {activeCount > 0 ? 'Active borrower' : 'No active loans'}
            </Badge>
            <Badge variant="default">KYC on file</Badge>
          </div>
        }
      />

      <div className="grid gap-5 xl:grid-cols-12">
        <div className="grid gap-5 xl:col-span-8">
          <PageSection
            title="Profile"
            description="Identity, contact, and KYC details captured during onboarding."
          >
            <div className="flex items-start gap-4">
              <div className="flex size-16 items-center justify-center rounded-full bg-primary/10 font-heading text-2xl font-bold text-primary">
                {getBorrowerInitials(borrower.fullName)}
              </div>
              <div className="grid flex-1 gap-4 sm:grid-cols-2">
                <DetailField label="Full name" value={borrower.fullName} />
                <DetailField label="PAN" value={formatBorrowerPan(borrower.pan)} />
                <DetailField label="Mobile" value={formatBorrowerMobile(borrower.mobile)} />
                <DetailField label="Email" value={borrower.email || 'Not provided'} />
                <DetailField label="Date of birth" value={formatDateLabel(borrower.dateOfBirth)} />
                <DetailField label="Gender" value={borrower.gender || 'Not provided'} />
                <DetailField label="Marital status" value={borrower.maritalStatus || 'Not provided'} />
                <DetailField label="Father's name" value={borrower.fatherName || 'Not provided'} />
                <DetailField label="Spouse name" value={borrower.spouseName || 'Not provided'} />
                <DetailField
                  label="Aadhar (masked)"
                  value={borrower.aadharNumberMasked || 'Not provided'}
                />
              </div>
            </div>
          </PageSection>

          <PageSection title="Address">
            <div className="grid gap-4 sm:grid-cols-2">
              <DetailField label="Residential" value={formatAddress(borrower)} />
              <DetailField
                label="City / State"
                value={[borrower.city, borrower.state].filter(Boolean).join(', ') || 'Not provided'}
              />
              <DetailField label="Zip" value={borrower.addressZipCode || 'Not provided'} />
            </div>
          </PageSection>

          <PageSection title="Loans across LSPs" description={`${borrower.loans.length} loan record(s) on file.`}>
            {borrower.loans.length === 0 ? (
              <p className="text-sm text-muted-foreground">No loans on record.</p>
            ) : (
              <div className="overflow-hidden rounded-xl border border-border/70">
                <Table>
                  <TableHeader>
                    <TableRow className="bg-muted/60 hover:bg-muted/60">
                      {['LSP', 'Product', 'Account #', 'Principal', 'Tenure', 'Status', 'Created'].map((heading) => (
                        <TableHead
                          key={heading}
                          className="px-4 py-3 text-[0.68rem] font-semibold uppercase tracking-[0.16em] text-muted-foreground"
                        >
                          {heading}
                        </TableHead>
                      ))}
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {borrower.loans.map((loan) => (
                      <TableRow
                        key={loan.loanAccountId ?? loan.applicationId ?? `${loan.lspCode}-${loan.createdAt}`}
                        className="hover:bg-muted/40"
                      >
                        <TableCell className="px-4 py-3 text-sm text-foreground">
                          <div className="flex flex-col">
                            <span className="font-semibold">{loan.lspName || loan.lspCode || '—'}</span>
                            {loan.lspCode ? (
                              <span className="text-xs text-muted-foreground">{loan.lspCode}</span>
                            ) : null}
                          </div>
                        </TableCell>
                        <TableCell className="px-4 py-3 text-xs text-muted-foreground">
                          {loan.loanProductCode || '—'}
                        </TableCell>
                        <TableCell className="px-4 py-3 text-xs text-muted-foreground">
                          {loan.applicationId ? (
                            <Link
                              to={`/loan-applications/${loan.applicationId}`}
                              className="font-medium text-primary underline-offset-4 hover:underline"
                            >
                              {loan.accountNumber || loan.applicationId.slice(0, 8)}
                            </Link>
                          ) : (
                            loan.accountNumber || '—'
                          )}
                        </TableCell>
                        <TableCell className="px-4 py-3 text-xs font-semibold text-primary">
                          {loan.principalAmount != null ? currencyLabel(loan.principalAmount) : '—'}
                        </TableCell>
                        <TableCell className="px-4 py-3 text-xs text-muted-foreground">
                          {loan.tenureMonths ? `${loan.tenureMonths} mo` : '—'}
                        </TableCell>
                        <TableCell className="px-4 py-3">
                          <Badge
                            variant={loanAccountStatusVariant(loan.status as LoanAccountStatus | null)}
                            className={cn(isOpenLoan(loan.status) ? '' : 'opacity-90')}
                          >
                            {loanAccountStatusLabel(loan.status as LoanAccountStatus | null)}
                          </Badge>
                        </TableCell>
                        <TableCell className="px-4 py-3 text-xs text-muted-foreground">
                          {loan.createdAt ? formatTimestamp(loan.createdAt) : '—'}
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </div>
            )}
          </PageSection>
        </div>

        <div className="grid gap-5 xl:col-span-4">
          <PageSection title="Employment">
            <div className="space-y-3">
              <DetailField label="Type" value={formatEmploymentType(borrower.employmentType)} />
              <DetailField label="Organization" value={borrower.organizationName || 'Not provided'} />
              <DetailField label="Employee ID" value={borrower.employeeId || 'Not provided'} />
              <DetailField
                label="Employment location"
                value={
                  [borrower.employmentCity, borrower.employmentState, borrower.employmentZip]
                    .filter(Boolean)
                    .join(', ') || 'Not provided'
                }
              />
              <DetailField label="Monthly income" value={formatIncomeLabel(borrower.monthlyIncome)} />
              <DetailField label="Annual income" value={formatIncomeLabel(borrower.annualIncome)} />
            </div>
          </PageSection>

          <PageSection title="Banking">
            <div className="space-y-3">
              <DetailField label="Bank" value={borrower.bankName || 'Not provided'} />
              <DetailField label="Account holder" value={borrower.accountHolderName || 'Not provided'} />
              <DetailField
                label="Account number"
                value={borrower.bankAccountNumberMasked || 'Not provided'}
              />
              <DetailField label="IFSC" value={borrower.ifscCode || 'Not provided'} />
            </div>
          </PageSection>

          <PageSection title="Reference">
            <div className="space-y-3">
              <DetailField label="Name" value={borrower.referencePersonName || 'Not provided'} />
              <DetailField
                label="Contact"
                value={borrower.referencePersonNumber || 'Not provided'}
              />
            </div>
          </PageSection>

          <PageSection title="Visibility">
            <p className="text-xs text-muted-foreground">
              Granted to {borrower.visibleLspIds.length} LSP
              {borrower.visibleLspIds.length === 1 ? '' : 's'}.
            </p>
          </PageSection>
        </div>
      </div>
    </div>
  )
}
