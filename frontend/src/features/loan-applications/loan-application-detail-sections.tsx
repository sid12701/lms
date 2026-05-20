import type { ReactNode } from 'react'
import { Clock, Download } from 'lucide-react'
import { Link } from 'react-router-dom'
import { ContentState } from '@/components/app/content-state'
import { PageSection } from '@/components/app/page-section'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { cn } from '@/lib/utils'
import type {
  LoanApplicationDetailRecord,
  LoanApplicationDocumentPlaceholderRecord,
  LoanRepaymentScheduleInstallmentRecord,
} from '../api/lms-api'
import { formatTimestamp } from './loan-application-formatters'
import {
  describeLastUpdated,
  formatMonthYearLabel,
  getApplicationInfoEntries,
  getBorrowerInitials,
  getBorrowerProfileEntries,
  getInstallmentBadge,
  getLoanAccountBadge,
  getOverviewMetrics,
  loanDocumentStatusLabel,
  loanDocumentStatusVariant,
  type LoanDetailAction,
} from './loan-application-detail-model'

type LoanDetailActionBarProps = {
  loan: LoanApplicationDetailRecord
  actions: LoanDetailAction[]
  actionError: string
  activeActionId: string | null
  onAction: (action: LoanDetailAction) => void
}

type LoanOverviewSectionProps = {
  loan: LoanApplicationDetailRecord
}

type LoanRepaymentScheduleSectionProps = {
  schedule: LoanRepaymentScheduleInstallmentRecord[]
}

type LoanBorrowerProfileSectionProps = {
  loan: LoanApplicationDetailRecord
}

type LoanDocumentsSectionProps = {
  documents: LoanApplicationDocumentPlaceholderRecord[]
  downloadingZip: boolean
  onDownloadAll: () => void
}

type LoanApplicationInfoCardProps = {
  loan: LoanApplicationDetailRecord
}

function DetailMetric({
  label,
  value,
}: {
  label: string
  value: ReactNode
}) {
  return (
    <div className="space-y-1 rounded-xl border border-border/60 bg-muted/25 p-4">
      <p className="text-[0.68rem] font-semibold uppercase tracking-[0.16em] text-muted-foreground">
        {label}
      </p>
      <div className="font-heading text-xl font-bold tracking-[-0.02em] text-primary">
        {value}
      </div>
    </div>
  )
}

function DetailKeyValueList({
  entries,
  valueClassName,
  labelClassName,
}: {
  entries: Array<{ label: string; value: ReactNode }>
  valueClassName?: string
  labelClassName?: string
}) {
  return (
    <div className="space-y-3">
      {entries.map((entry) => (
        <div key={entry.label} className="flex items-start justify-between gap-3">
          <span className={cn('text-xs text-muted-foreground', labelClassName)}>{entry.label}</span>
          <span className={cn('text-xs font-medium text-right text-foreground', valueClassName)}>
            {entry.value}
          </span>
        </div>
      ))}
    </div>
  )
}

export function LoanDetailActionBar({
  loan,
  actions,
  actionError,
  activeActionId,
  onAction,
}: LoanDetailActionBarProps) {
  return (
    <Card className="border-border/50 bg-card/95">
      <CardContent className="flex flex-col gap-4 p-5 lg:flex-row lg:items-center lg:justify-between">
        <div className="flex items-center gap-2 text-sm text-muted-foreground">
          <Clock className="size-4" />
          <span>{describeLastUpdated(loan)}</span>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          {actionError ? <span className="text-xs text-destructive">{actionError}</span> : null}
          {actions.map((action) => (
            <Button
              key={action.id}
              type="button"
              variant={action.variant === 'destructive' ? 'destructive' : 'default'}
              disabled={Boolean(activeActionId)}
              onClick={() => onAction(action)}
            >
              {activeActionId === action.id ? action.pendingLabel : action.label}
            </Button>
          ))}
        </div>
      </CardContent>
    </Card>
  )
}

export function LoanOverviewSection({ loan }: LoanOverviewSectionProps) {
  const accountBadge = loan.loanAccount ? getLoanAccountBadge(loan.loanAccount.status) : null

  return (
    <PageSection
      title="Loan overview"
      description="Core application and servicing metrics for this borrower."
      actions={
        accountBadge ? <Badge variant={accountBadge.variant}>{accountBadge.label}</Badge> : undefined
      }
    >
      <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
        {getOverviewMetrics(loan).map((metric) => (
          <DetailMetric key={metric.label} label={metric.label} value={metric.value} />
        ))}
      </div>
    </PageSection>
  )
}

export function LoanRepaymentScheduleSection({
  schedule,
}: LoanRepaymentScheduleSectionProps) {
  return (
    <PageSection
      title="Repayment schedule"
      description="Installment timing, dues, and delinquency tracking for the current account."
      contentClassName="space-y-4"
    >
      {schedule.length === 0 ? (
        <ContentState
          title="No repayment schedule"
          description="A repayment schedule has not been generated for this application yet."
          className="border-border/60 shadow-none"
        />
      ) : (
        <div className="overflow-hidden rounded-xl border border-border/70">
          <Table>
            <TableHeader>
              <TableRow className="bg-muted/60 hover:bg-muted/60">
                {['EMI #', 'Due Date', 'Principal', 'Interest', 'EMI Amount', 'DPD', 'Status'].map(
                  (heading) => (
                    <TableHead
                      key={heading}
                      className="px-4 py-3 text-[0.68rem] font-semibold uppercase tracking-[0.16em] text-muted-foreground"
                    >
                      {heading}
                    </TableHead>
                  ),
                )}
              </TableRow>
            </TableHeader>
            <TableBody>
              {schedule.map((installment) => {
                const badge = getInstallmentBadge(installment.status)

                return (
                  <TableRow key={installment.id} className="hover:bg-muted/40">
                    <TableCell className="px-4 py-3 text-sm font-semibold text-primary">
                      {String(installment.installmentNumber).padStart(2, '0')}
                    </TableCell>
                    <TableCell className="px-4 py-3 text-xs text-muted-foreground">
                      {formatMonthYearLabel(installment.dueDate)}
                    </TableCell>
                    <TableCell className="px-4 py-3 text-xs text-foreground">
                      {installment.principalDue.toLocaleString('en-IN', {
                        style: 'currency',
                        currency: 'INR',
                        maximumFractionDigits: 0,
                      })}
                    </TableCell>
                    <TableCell className="px-4 py-3 text-xs text-foreground">
                      {installment.interestDue.toLocaleString('en-IN', {
                        style: 'currency',
                        currency: 'INR',
                        maximumFractionDigits: 0,
                      })}
                    </TableCell>
                    <TableCell className="px-4 py-3 text-sm font-semibold text-primary">
                      {installment.installmentAmount.toLocaleString('en-IN', {
                        style: 'currency',
                        currency: 'INR',
                        maximumFractionDigits: 0,
                      })}
                    </TableCell>
                    <TableCell
                      className={cn(
                        'px-4 py-3 text-xs',
                        installment.daysPastDue > 0 ? 'font-semibold text-destructive' : 'text-muted-foreground',
                      )}
                    >
                      {installment.daysPastDue > 0 ? `${installment.daysPastDue}d` : '--'}
                    </TableCell>
                    <TableCell className="px-4 py-3">
                      <Badge variant={badge.variant}>{badge.label}</Badge>
                    </TableCell>
                  </TableRow>
                )
              })}
            </TableBody>
          </Table>
        </div>
      )}
    </PageSection>
  )
}

export function LoanBorrowerProfileSection({
  loan,
}: LoanBorrowerProfileSectionProps) {
  return (
    <PageSection title="Borrower profile">
      <div className="space-y-5">
        <div className="flex items-center gap-3">
          <div className="flex size-14 items-center justify-center rounded-full bg-primary/10 font-heading text-xl font-bold text-primary">
            {getBorrowerInitials(loan.borrowerFullName)}
          </div>
          <div className="space-y-1">
            {loan.borrowerId ? (
              <Link
                to={`/borrowers/${loan.borrowerId}`}
                className="text-base font-semibold text-foreground underline-offset-4 hover:text-primary hover:underline"
              >
                {loan.borrowerFullName}
              </Link>
            ) : (
              <p className="text-base font-semibold text-foreground">{loan.borrowerFullName}</p>
            )}
            <Badge variant="default">KYC on file</Badge>
          </div>
        </div>

        <DetailKeyValueList entries={getBorrowerProfileEntries(loan)} />
      </div>
    </PageSection>
  )
}

export function LoanDocumentsSection({
  documents,
  downloadingZip,
  onDownloadAll,
}: LoanDocumentsSectionProps) {
  const hasDownloadableDocuments = documents.some(
    (document) => document.lmsManagedContent && Boolean(document.storageKey),
  )

  return (
    <PageSection
      title="Verification documents"
      actions={
        hasDownloadableDocuments ? (
          <Button
            type="button"
            variant="outline"
            size="sm"
            disabled={downloadingZip}
            onClick={onDownloadAll}
            className="gap-1.5"
          >
            <Download className="size-4" />
            {downloadingZip ? 'Downloading...' : 'Download All as ZIP'}
          </Button>
        ) : undefined
      }
    >
      {documents.length === 0 ? (
        <p className="text-sm text-muted-foreground">No documents on record.</p>
      ) : (
        <div className="space-y-3">
          {!hasDownloadableDocuments ? (
            <p className="text-xs text-muted-foreground">
              Document metadata exists, but no LMS-managed file content is available for ZIP download.
            </p>
          ) : null}

          <div className="divide-y divide-border/70">
            {documents.map((document) => (
              <div
                key={document.id}
                className="flex items-center justify-between gap-3 py-3 first:pt-0 last:pb-0"
              >
                <div className="min-w-0 space-y-1">
                  <p className="truncate text-sm font-medium text-foreground">
                    {document.documentDisplayName}
                  </p>
                  {document.uploadedAt ? (
                    <p className="text-[0.68rem] text-muted-foreground">
                      {formatTimestamp(document.uploadedAt)}
                    </p>
                  ) : null}
                </div>
                <Badge
                  variant={loanDocumentStatusVariant(document.status)}
                  className="shrink-0"
                >
                  {loanDocumentStatusLabel(document.status)}
                </Badge>
              </div>
            ))}
          </div>
        </div>
      )}
    </PageSection>
  )
}

export function LoanApplicationInfoCard({
  loan,
}: LoanApplicationInfoCardProps) {
  return (
    <Card className="overflow-hidden border border-primary/10 bg-white shadow-[0_18px_40px_rgba(0,6,102,0.1)]">
      <CardContent className="space-y-4 p-6">
        <p className="text-[0.68rem] font-semibold uppercase tracking-[0.18em] text-[#000666]">
          Application info
        </p>
        <DetailKeyValueList
          entries={getApplicationInfoEntries(loan)}
          labelClassName="text-[#5e6680]"
          valueClassName="text-[#000666]"
        />
      </CardContent>
    </Card>
  )
}
