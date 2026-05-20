import { ArrowLeft } from 'lucide-react'
import { useNavigate, useParams } from 'react-router-dom'
import { ContentState } from '@/components/app/content-state'
import { PageHeader } from '@/components/app/page-header'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { useAuth } from '../auth/auth-context'
import { getLoanStatusBadge } from './loan-application-detail-model'
import {
  LoanApplicationInfoCard,
  LoanBorrowerProfileSection,
  LoanDetailActionBar,
  LoanDocumentsSection,
  LoanOverviewSection,
  LoanRepaymentScheduleSection,
} from './loan-application-detail-sections'
import { currencyLabel } from './loan-application-formatters'
import { useLoanApplicationDetail } from './use-loan-application-detail'

export function LoanApplicationDetailPage() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const { user } = useAuth()

  const {
    loan,
    schedule,
    documents,
    loading,
    error,
    actionError,
    activeActionId,
    downloadingZip,
    actions,
    executeAction,
    handleDownloadAllDocuments,
  } = useLoanApplicationDetail({
    loanId: id,
    roles: user?.roles,
  })

  if (loading) {
    return (
      <ContentState
        title="Loading loan application"
        description="Fetching borrower, servicing, and document details."
        tone="loading"
      />
    )
  }

  if (error || !loan) {
    return (
      <ContentState
        title="Unable to load loan application"
        description={error || 'Loan application not found.'}
        tone="error"
      />
    )
  }

  const statusBadge = getLoanStatusBadge(loan.status)

  return (
    <div className="space-y-6">
      <Button
        type="button"
        variant="ghost"
        size="sm"
        className="w-fit gap-1.5 px-0 text-muted-foreground hover:bg-transparent hover:text-primary"
        onClick={() => navigate('/loan-applications')}
      >
        <ArrowLeft className="size-4" />
        Back to Loans
      </Button>

      <PageHeader
        eyebrow="Loan application"
        title={`Loan ${loan.id}`}
        description={`${loan.borrowerFullName} | ${loan.productName} | ${currencyLabel(loan.requestedAmount)}`}
        actions={<Badge variant={statusBadge.variant}>{statusBadge.label}</Badge>}
      />

      <LoanDetailActionBar
        loan={loan}
        actions={actions}
        actionError={actionError}
        activeActionId={activeActionId}
        onAction={(action) => void executeAction(action)}
      />

      <div className="grid gap-5 xl:grid-cols-12">
        <div className="grid gap-5 xl:col-span-8">
          <LoanOverviewSection loan={loan} />
          <LoanRepaymentScheduleSection schedule={schedule} />
        </div>

        <div className="grid gap-5 xl:col-span-4">
          <LoanBorrowerProfileSection loan={loan} />
          <LoanDocumentsSection
            documents={documents}
            downloadingZip={downloadingZip}
            onDownloadAll={() => void handleDownloadAllDocuments()}
          />
          <LoanApplicationInfoCard loan={loan} />
        </div>
      </div>
    </div>
  )
}
