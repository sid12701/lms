import { ContentState } from '@/components/app/content-state'
import { PageSection } from '@/components/app/page-section'
import { Button } from '@/components/ui/button'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import type { MisPreviewRow } from '@/features/api/lms-api'
import { cn } from '@/lib/utils'
import {
  formatReportCurrency,
  generateReportPageNumbers,
  resolvePreviewStatusTone,
} from './reports-model'
import { ReportStatusBadge } from './report-status-badge'

type ReportsPreviewSectionProps = {
  initialLoadDone: boolean
  previewing: boolean
  rows: MisPreviewRow[]
  totalElements: number
  currentPage: number
  totalPages: number
  maxInstallments: number
  showingFrom: number
  showingTo: number
  loading: boolean
  submitting: boolean
  onExport: () => void
  onQueue: () => void
  onPageChange: (page: number) => void
}

const tableHeadClassName =
  'bg-muted/60 px-4 py-3 text-[0.68rem] font-semibold uppercase tracking-[0.16em] text-muted-foreground'
const tableCellClassName = 'px-4 py-3 text-sm text-foreground'

function InstallmentCell({
  installment,
}: {
  installment: MisPreviewRow['installments'][number] | undefined
}) {
  if (!installment) {
    return <TableCell className={tableCellClassName}>--</TableCell>
  }

  return (
    <TableCell
      className={cn(
        tableCellClassName,
        'space-y-1 whitespace-normal',
        installment.received && 'text-emerald-800',
      )}
    >
      <div>{installment.dueDate}</div>
      <div>
        {formatReportCurrency(installment.paidAmount)} /{' '}
        {formatReportCurrency(installment.installmentAmount)}
      </div>
    </TableCell>
  )
}

export function ReportsPreviewSection({
  initialLoadDone,
  previewing,
  rows,
  totalElements,
  currentPage,
  totalPages,
  maxInstallments,
  showingFrom,
  showingTo,
  loading,
  submitting,
  onExport,
  onQueue,
  onPageChange,
}: ReportsPreviewSectionProps) {
  const description = initialLoadDone
    ? `Showing ${showingFrom}-${showingTo} of ${totalElements.toLocaleString('en-IN')} results.`
    : 'Loading MIS data...'

  return (
    <PageSection
      title="Loan transaction ledger"
      description={description}
      actions={
        <>
          <Button type="button" variant="outline" onClick={onExport} disabled={loading}>
            Export to Excel
          </Button>
          <Button type="button" onClick={onQueue} disabled={loading || submitting}>
            {submitting ? 'Queueing...' : 'Queue export'}
          </Button>
        </>
      }
      contentClassName="space-y-4"
    >
      {!initialLoadDone || previewing ? (
        <ContentState
          title="Loading MIS data"
          description="Refreshing the portfolio ledger and current installment schedule."
          tone="loading"
        />
      ) : null}

      {initialLoadDone && !previewing && rows.length === 0 ? (
        <ContentState
          title="No matching loan accounts"
          description="No loan accounts match the currently selected partner and date range."
        />
      ) : null}

      {initialLoadDone && !previewing && rows.length > 0 ? (
        <>
          <div className="overflow-hidden rounded-xl border border-border/70">
            <Table className="min-w-[180rem]">
              <TableHeader>
                <TableRow className="hover:bg-transparent">
                  <TableHead className={tableHeadClassName}>Loan ID</TableHead>
                  <TableHead className={tableHeadClassName}>Borrower</TableHead>
                  <TableHead className={tableHeadClassName}>LSP</TableHead>
                  <TableHead className={tableHeadClassName}>Product</TableHead>
                  <TableHead className={cn(tableHeadClassName, 'text-right')}>Amount</TableHead>
                  <TableHead className={tableHeadClassName}>Status</TableHead>
                  <TableHead className={tableHeadClassName}>Disbursal Date</TableHead>
                  <TableHead className={tableHeadClassName}>DPD</TableHead>
                  <TableHead className={tableHeadClassName}>Year</TableHead>
                  <TableHead className={tableHeadClassName}>LSP Loan ID</TableHead>
                  <TableHead className={cn(tableHeadClassName, 'text-right')}>Processing Fee</TableHead>
                  <TableHead className={cn(tableHeadClassName, 'text-right')}>Disbursal Amt</TableHead>
                  <TableHead className={tableHeadClassName}>Interest %</TableHead>
                  <TableHead className={tableHeadClassName}>Tenure</TableHead>
                  <TableHead className={cn(tableHeadClassName, 'text-right')}>EMI Amt</TableHead>
                  <TableHead className={cn(tableHeadClassName, 'text-right')}>Overdue Amt</TableHead>
                  <TableHead className={tableHeadClassName}>Closure Date</TableHead>
                  <TableHead className={tableHeadClassName}>Foreclosure Date</TableHead>
                  <TableHead className={cn(tableHeadClassName, 'text-right')}>Foreclosed Amt</TableHead>
                  <TableHead className={tableHeadClassName}>PAN</TableHead>
                  <TableHead className={tableHeadClassName}>Aadhar</TableHead>
                  <TableHead className={tableHeadClassName}>Gender</TableHead>
                  <TableHead className={tableHeadClassName}>State</TableHead>
                  <TableHead className={tableHeadClassName}>Zip</TableHead>
                  <TableHead className={tableHeadClassName}>IFSC</TableHead>
                  <TableHead className={tableHeadClassName}>Bank Account</TableHead>
                  <TableHead className={tableHeadClassName}>Profession</TableHead>
                  <TableHead className={cn(tableHeadClassName, 'text-right')}>Income</TableHead>
                  {Array.from({ length: maxInstallments }, (_, index) => (
                    <TableHead key={`emi-head-${index + 1}`} className={tableHeadClassName}>
                      EMI {index + 1}
                    </TableHead>
                  ))}
                </TableRow>
              </TableHeader>
              <TableBody>
                {rows.map((row) => (
                  <TableRow key={row.applicationId}>
                    <TableCell className={cn(tableCellClassName, 'font-semibold text-primary')}>
                      {row.accountNumber}
                    </TableCell>
                    <TableCell className={tableCellClassName}>{row.customerName}</TableCell>
                    <TableCell className={cn(tableCellClassName, 'text-muted-foreground')}>
                      {row.lspName}
                    </TableCell>
                    <TableCell className={cn(tableCellClassName, 'text-muted-foreground')}>
                      {row.productName}
                    </TableCell>
                    <TableCell className={cn(tableCellClassName, 'text-right font-medium')}>
                      {formatReportCurrency(row.principalAmount)}
                    </TableCell>
                    <TableCell className={tableCellClassName}>
                      <ReportStatusBadge tone={resolvePreviewStatusTone(row.loanStatusDisplay)}>
                        {row.loanStatusDisplay}
                      </ReportStatusBadge>
                    </TableCell>
                    <TableCell className={cn(tableCellClassName, 'text-muted-foreground')}>
                      {row.disbursalDate || '--'}
                    </TableCell>
                    <TableCell
                      className={cn(
                        tableCellClassName,
                        row.daysPastDue > 0 ? 'font-semibold text-destructive' : 'text-muted-foreground',
                      )}
                    >
                      {row.daysPastDue}
                    </TableCell>
                    <TableCell className={cn(tableCellClassName, 'text-muted-foreground')}>
                      {row.loanYear ?? '--'}
                    </TableCell>
                    <TableCell className={cn(tableCellClassName, 'text-muted-foreground')}>
                      {row.externalLoanId}
                    </TableCell>
                    <TableCell className={cn(tableCellClassName, 'text-right font-medium')}>
                      {formatReportCurrency(row.processingFeeAmount)}
                    </TableCell>
                    <TableCell className={cn(tableCellClassName, 'text-right font-medium')}>
                      {formatReportCurrency(row.disbursalAmount)}
                    </TableCell>
                    <TableCell className={tableCellClassName}>{row.interestRate}%</TableCell>
                    <TableCell className={tableCellClassName}>{row.tenureMonths}m</TableCell>
                    <TableCell className={cn(tableCellClassName, 'text-right font-medium')}>
                      {formatReportCurrency(row.perEmiAmount)}
                    </TableCell>
                    <TableCell className={cn(tableCellClassName, 'text-right font-medium')}>
                      {formatReportCurrency(row.overdueAmount)}
                    </TableCell>
                    <TableCell className={cn(tableCellClassName, 'text-muted-foreground')}>
                      {row.normalClosureDate || '--'}
                    </TableCell>
                    <TableCell className={cn(tableCellClassName, 'text-muted-foreground')}>
                      {row.foreclosureDate || '--'}
                    </TableCell>
                    <TableCell className={cn(tableCellClassName, 'text-right font-medium')}>
                      {formatReportCurrency(row.foreclosedRepaidAmount)}
                    </TableCell>
                    <TableCell className={tableCellClassName}>{row.panNumber}</TableCell>
                    <TableCell className={tableCellClassName}>{row.aadharNumber || '--'}</TableCell>
                    <TableCell className={tableCellClassName}>{row.gender || '--'}</TableCell>
                    <TableCell className={tableCellClassName}>{row.borrowerState || '--'}</TableCell>
                    <TableCell className={tableCellClassName}>{row.zipCode || '--'}</TableCell>
                    <TableCell className={tableCellClassName}>{row.ifscCode || '--'}</TableCell>
                    <TableCell className={tableCellClassName}>{row.bankAccountNumber || '--'}</TableCell>
                    <TableCell className={tableCellClassName}>{row.profession || '--'}</TableCell>
                    <TableCell className={cn(tableCellClassName, 'text-right font-medium')}>
                      {formatReportCurrency(row.income)}
                    </TableCell>
                    {Array.from({ length: maxInstallments }, (_, index) => (
                      <InstallmentCell
                        key={`${row.applicationId}-emi-${index + 1}`}
                        installment={row.installments[index]}
                      />
                    ))}
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>

          <div className="flex flex-col gap-4 border-t border-border/70 pt-4 md:flex-row md:items-center md:justify-between">
            <p className="text-sm font-medium text-muted-foreground">
              Page {currentPage} of {totalPages}
            </p>
            <div className="flex flex-wrap items-center gap-2">
              <Button
                type="button"
                variant="outline"
                size="sm"
                disabled={currentPage === 1}
                onClick={() => onPageChange(currentPage - 1)}
              >
                Previous
              </Button>
              {generateReportPageNumbers(currentPage, totalPages).map((pageNumber, index) =>
                pageNumber === '...' ? (
                  <span
                    key={`ellipsis-${index}`}
                    className="px-2 text-sm font-medium text-muted-foreground"
                  >
                    ...
                  </span>
                ) : (
                  <Button
                    key={pageNumber}
                    type="button"
                    variant={pageNumber === currentPage ? 'default' : 'outline'}
                    size="sm"
                    className="min-w-9"
                    onClick={() => onPageChange(pageNumber)}
                  >
                    {pageNumber}
                  </Button>
                ),
              )}
              <Button
                type="button"
                variant="outline"
                size="sm"
                disabled={currentPage === totalPages}
                onClick={() => onPageChange(currentPage + 1)}
              >
                Next
              </Button>
            </div>
          </div>
        </>
      ) : null}
    </PageSection>
  )
}
