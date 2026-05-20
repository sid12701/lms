import type { ComponentProps, ReactNode } from 'react'
import { Link } from 'react-router-dom'
import {
  CheckCircle2,
  Eye,
  Filter,
  RefreshCw,
  ShieldCheck,
} from 'lucide-react'
import { ContentState } from '@/components/app/content-state'
import { PageSection } from '@/components/app/page-section'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { cn } from '@/lib/utils'
import {
  loanApplicationStatusOptions,
  type LoanApplicationRecord,
  type LoanApplicationStatus,
  type LoanProductRecord,
  type LspOptionRecord,
} from '../api/lms-api'
import { currencyLabel } from './loan-application-formatters'
import {
  getLoanLedgerAvatarClassName,
  getLoanLedgerBorrowerInitials,
  getLoanLedgerStatusVariant,
  getShortLoanId,
  type LoanLedgerFilterState,
} from './loan-applications-ledger-model'
import { loanStatusLabel } from './loan-application-workflow'

const ledgerFieldLabelClassName =
  'text-[0.68rem] font-semibold uppercase tracking-[0.16em] text-muted-foreground'
const ledgerSelectClassName =
  'h-11 w-full rounded-lg border border-transparent bg-input px-3 text-sm text-foreground shadow-[inset_0_0_0_1px_rgba(198,197,212,0.18)] outline-none transition-[background-color,box-shadow,border-color] focus:bg-background focus:shadow-[inset_0_0_0_1px_rgba(0,6,102,0.4),0_0_0_3px_rgba(0,6,102,0.08)]'
const ledgerTableHeadClassName =
  'px-4 py-3 text-[0.68rem] font-semibold uppercase tracking-[0.16em] text-muted-foreground'

type LoanLedgerSelectProps = ComponentProps<'select'>

type LoanLedgerFilterSectionProps = {
  filters: LoanLedgerFilterState
  activeLsps: LspOptionRecord[]
  activeProducts: LoanProductRecord[]
  onFilterChange: (patch: Partial<LoanLedgerFilterState>) => void
  onReset: () => void
}

type LoanLedgerTableSectionProps = {
  applications: LoanApplicationRecord[]
  loading: boolean
  error: string
}

function LoanLedgerSelect({ className, ...props }: LoanLedgerSelectProps) {
  return <select className={cn(ledgerSelectClassName, className)} {...props} />
}

function LoanLedgerSignalCard({
  icon,
  title,
  description,
  toneClassName,
}: {
  icon: ReactNode
  title: string
  description: string
  toneClassName: string
}) {
  return (
    <Card className="border-border/50 bg-card/95">
      <CardContent className="flex items-start gap-4 p-5">
        <span
          className={cn('inline-flex size-10 items-center justify-center rounded-full', toneClassName)}
        >
          {icon}
        </span>
        <div className="space-y-1.5">
          <h3 className="text-sm font-semibold text-foreground">{title}</h3>
          <p className="text-sm leading-6 text-muted-foreground">{description}</p>
        </div>
      </CardContent>
    </Card>
  )
}

export function LoanLedgerFilterSection({
  filters,
  activeLsps,
  activeProducts,
  onFilterChange,
  onReset,
}: LoanLedgerFilterSectionProps) {
  return (
    <PageSection
      title="Portfolio filters"
      description="Narrow the queue by partner, product, status, or borrower identifiers."
    >
      <div className="grid gap-4 lg:grid-cols-[auto_repeat(3,minmax(0,1fr))_minmax(0,1.2fr)_auto] lg:items-end">
        <div className="inline-flex h-11 items-center gap-2 rounded-lg border border-border/60 bg-muted/40 px-3 text-sm font-semibold text-muted-foreground">
          <Filter className="size-4" />
          Filters
        </div>

        <div className="space-y-2">
          <label htmlFor="loan-ledger-status" className={ledgerFieldLabelClassName}>
            Status
          </label>
          <LoanLedgerSelect
            id="loan-ledger-status"
            value={filters.status}
            onChange={(event) => onFilterChange({ status: event.target.value })}
          >
            <option value="">All statuses</option>
            {loanApplicationStatusOptions.map((status) => (
              <option key={status} value={status}>
                {loanStatusLabel(status)}
              </option>
            ))}
          </LoanLedgerSelect>
        </div>

        <div className="space-y-2">
          <label htmlFor="loan-ledger-lsp" className={ledgerFieldLabelClassName}>
            LSP
          </label>
          <LoanLedgerSelect
            id="loan-ledger-lsp"
            value={filters.lspId}
            onChange={(event) => onFilterChange({ lspId: event.target.value })}
          >
            <option value="">All LSPs</option>
            {activeLsps.map((lsp) => (
              <option key={lsp.id} value={lsp.id}>
                {lsp.code} - {lsp.name}
              </option>
            ))}
          </LoanLedgerSelect>
        </div>

        <div className="space-y-2">
          <label htmlFor="loan-ledger-product" className={ledgerFieldLabelClassName}>
            Product
          </label>
          <LoanLedgerSelect
            id="loan-ledger-product"
            value={filters.productId}
            onChange={(event) => onFilterChange({ productId: event.target.value })}
          >
            <option value="">All products</option>
            {activeProducts.map((product) => (
              <option key={product.id} value={product.id}>
                {product.code} - {product.name}
              </option>
            ))}
          </LoanLedgerSelect>
        </div>

        <div className="space-y-2">
          <label htmlFor="loan-ledger-search" className={ledgerFieldLabelClassName}>
            Search
          </label>
          <Input
            id="loan-ledger-search"
            value={filters.query}
            onChange={(event) => onFilterChange({ query: event.target.value })}
            placeholder="Search borrower, PAN, or external ID..."
          />
        </div>

        <Button type="button" variant="ghost" onClick={onReset}>
          Clear all
        </Button>
      </div>
    </PageSection>
  )
}

export function LoanLedgerTableSection({
  applications,
  loading,
  error,
}: LoanLedgerTableSectionProps) {
  return (
    <PageSection
      title="Applications"
      description={`Showing ${applications.length.toLocaleString('en-IN')} applications in the active queue.`}
    >
      {loading ? (
        <ContentState
          title="Loading loan applications"
          description="Refreshing the latest borrower, partner, and product records."
          tone="loading"
        />
      ) : error ? (
        <ContentState
          title="Unable to load the loan ledger"
          description={error}
          tone="error"
        />
      ) : applications.length === 0 ? (
        <ContentState
          title="No applications matched the current filters"
          description="Try clearing one or more filters to reopen the working queue."
        />
      ) : (
        <div className="overflow-hidden rounded-xl border border-border/70">
          <Table>
            <TableHeader>
              <TableRow className="bg-muted/60 hover:bg-muted/60">
                <TableHead className={ledgerTableHeadClassName}>Bhawana Loan ID</TableHead>
                <TableHead className={ledgerTableHeadClassName}>External ID</TableHead>
                <TableHead className={ledgerTableHeadClassName}>Borrower</TableHead>
                <TableHead className={ledgerTableHeadClassName}>LSP</TableHead>
                <TableHead className={ledgerTableHeadClassName}>Product</TableHead>
                <TableHead className={ledgerTableHeadClassName}>Amount</TableHead>
                <TableHead className={ledgerTableHeadClassName}>Status</TableHead>
                <TableHead className="px-4 py-3 text-center text-[0.68rem] font-semibold uppercase tracking-[0.16em] text-muted-foreground">
                  Tenure
                </TableHead>
                <TableHead className="px-4 py-3 text-right text-[0.68rem] font-semibold uppercase tracking-[0.16em] text-muted-foreground">
                  Actions
                </TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {applications.map((application, index) => (
                <LoanLedgerTableRow
                  key={application.id}
                  application={application}
                  index={index}
                />
              ))}
            </TableBody>
          </Table>
        </div>
      )}
    </PageSection>
  )
}

function LoanLedgerTableRow({
  application,
  index,
}: {
  application: LoanApplicationRecord
  index: number
}) {
  return (
    <TableRow className="hover:bg-muted/40">
      <TableCell className="px-4 py-4 font-mono text-sm font-semibold">
        <Link
          to={`/loan-applications/${application.id}`}
          className="text-primary underline-offset-4 hover:underline"
          aria-label={`Open loan ${getShortLoanId(application.id)}`}
        >
          {getShortLoanId(application.id)}
        </Link>
      </TableCell>
      <TableCell className="px-4 py-4 text-sm text-muted-foreground">
        {application.externalLoanId || '--'}
      </TableCell>
      <TableCell className="px-4 py-4">
        <div className="flex items-center gap-3">
          <span
            className={cn(
              'inline-flex size-10 items-center justify-center rounded-full text-sm font-semibold',
              getLoanLedgerAvatarClassName(index),
            )}
            aria-hidden="true"
          >
            {getLoanLedgerBorrowerInitials(application.borrowerFullName)}
          </span>
          <div className="space-y-1">
            {application.borrowerId ? (
              <Link
                to={`/borrowers/${application.borrowerId}`}
                className="text-sm font-semibold text-foreground underline-offset-4 hover:text-primary hover:underline"
              >
                {application.borrowerFullName}
              </Link>
            ) : (
              <p className="text-sm font-semibold text-foreground">
                {application.borrowerFullName}
              </p>
            )}
            <p className="text-[0.72rem] text-muted-foreground">
              UUID: {application.id.slice(0, 8)}
            </p>
          </div>
        </div>
      </TableCell>
      <TableCell className="px-4 py-4 font-mono text-sm text-foreground">
        {application.lspCode}
      </TableCell>
      <TableCell className="px-4 py-4 text-sm text-foreground">
        {application.productName || application.productCode}
      </TableCell>
      <TableCell className="px-4 py-4 text-sm font-semibold text-foreground">
        {currencyLabel(application.requestedAmount)}
      </TableCell>
      <TableCell className="px-4 py-4">
        <Badge variant={getLoanLedgerStatusVariant(application.status as LoanApplicationStatus)}>
          {loanStatusLabel(application.status as LoanApplicationStatus)}
        </Badge>
      </TableCell>
      <TableCell className="px-4 py-4 text-center text-sm text-foreground">
        {application.tenureMonths}m
      </TableCell>
      <TableCell className="px-4 py-4 text-right">
        <Link to={`/loan-applications/${application.id}`}>
          <Button type="button" variant="outline" size="sm" className="gap-1.5">
            <Eye className="size-4" />
            View details
          </Button>
        </Link>
      </TableCell>
    </TableRow>
  )
}

export function LoanLedgerOperationsSection() {
  return (
    <div className="grid gap-4 xl:grid-cols-3">
      <LoanLedgerSignalCard
        icon={<CheckCircle2 className="size-5 text-emerald-700" />}
        toneClassName="bg-emerald-100"
        title="API status"
        description="The internal loan ledger endpoint is operational and serving queue data successfully."
      />
      <LoanLedgerSignalCard
        icon={<RefreshCw className="size-5 text-sky-700" />}
        toneClassName="bg-sky-100"
        title="Last reconciled"
        description="Provider reconciliation completes on a rolling cadence to keep the servicing queue current."
      />
      <LoanLedgerSignalCard
        icon={<ShieldCheck className="size-5 text-slate-700" />}
        toneClassName="bg-slate-200"
        title="Identity lock"
        description="Borrower identity masking remains enforced for all non-privileged operational surfaces."
      />
    </div>
  )
}
