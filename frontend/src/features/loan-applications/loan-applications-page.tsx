import { RefreshCw } from 'lucide-react'
import { MetricCard } from '@/components/app/metric-card'
import { PageHeader } from '@/components/app/page-header'
import { Button } from '@/components/ui/button'
import { formatCompactLoanAmount } from './loan-applications-ledger-model'
import {
  LoanLedgerFilterSection,
  LoanLedgerTableSection,
} from './loan-applications-ledger-sections'
import { useLoanApplicationsLedger } from './use-loan-applications-ledger'

export function LoanApplicationsPage() {
  const {
    applications,
    activeLsps,
    activeProducts,
    filters,
    loading,
    error,
    metrics,
    updateFilters,
    resetFilters,
    refreshLedger,
  } = useLoanApplicationsLedger()

  return (
    <div className="grid gap-6">
      <PageHeader
        eyebrow="Loan ledger"
        title="Loan applications"
        description="Live operational queue for internal loan applications and borrower workflows."
        actions={
          <Button type="button" variant="outline" className="gap-2" onClick={refreshLedger}>
            <RefreshCw className="size-4" />
            Refresh
          </Button>
        }
      />

      <div className="grid gap-4 xl:grid-cols-4">
        <MetricCard
          label="Queue size"
          value={applications.length.toLocaleString('en-IN')}
          detail="Applications currently loaded with the active filter set."
        />
        <MetricCard
          label="Portfolio value"
          value={formatCompactLoanAmount(metrics.activePortfolioValue)}
          detail="Requested amount across disbursed and repayment-stage applications."
        />
        <MetricCard
          label="Awaiting approval"
          value={metrics.pendingApprovalCount.toLocaleString('en-IN')}
          detail="Applications currently pending an approval decision."
        />
        <MetricCard
          label="Disbursed"
          value={metrics.disbursedCount.toLocaleString('en-IN')}
          detail="Applications that have already moved to disbursed status."
        />
      </div>

      <LoanLedgerFilterSection
        filters={filters}
        activeLsps={activeLsps}
        activeProducts={activeProducts}
        onFilterChange={updateFilters}
        onReset={resetFilters}
      />

      <LoanLedgerTableSection applications={applications} loading={loading} error={error} />
    </div>
  )
}
