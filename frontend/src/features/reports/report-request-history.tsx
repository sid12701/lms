import { PageSection } from '@/components/app/page-section'
import { Button } from '@/components/ui/button'
import type { ReportRequestRecord } from '@/features/api/lms-api'
import {
  formatReportDateRange,
  formatReportDateTime,
  formatRequestMeta,
  resolveReportRequestTone,
} from './reports-model'
import { ReportStatusBadge } from './report-status-badge'

type ReportRequestHistoryProps = {
  requests: ReportRequestRecord[]
  refreshing: boolean
  onRefresh: () => void
  onDownload: (requestId: string) => void
}

export function ReportRequestHistory({
  requests,
  refreshing,
  onRefresh,
  onDownload,
}: ReportRequestHistoryProps) {
  return (
    <PageSection
      eyebrow="Report history"
      title="Generated requests"
      description="Review recent report requests and download completed files."
      actions={
        <>
          <ReportStatusBadge tone="default">{requests.length} requests</ReportStatusBadge>
          <Button type="button" variant="outline" onClick={onRefresh} disabled={refreshing}>
            {refreshing ? 'Refreshing...' : 'Refresh'}
          </Button>
        </>
      }
    >
      {requests.length === 0 ? (
        <p className="text-sm text-muted-foreground">No report requests have been queued yet.</p>
      ) : (
        <div className="divide-y divide-border/70">
          {requests.map((request) => (
            <article
              key={request.id}
              className="grid gap-4 py-4 md:grid-cols-[minmax(0,1.8fr)_auto_auto_auto] md:items-center"
            >
              <div className="space-y-1.5">
                <p className="text-sm font-semibold text-foreground">{request.reportType}</p>
                <p className="text-sm text-muted-foreground">{formatRequestMeta(request)}</p>
                <p className="text-sm text-muted-foreground">
                  {(request.lspName || 'All LSPs') +
                    ' - ' +
                    formatReportDateRange(request.disbursalDateFrom, request.disbursalDateTo)}
                </p>
              </div>
              <ReportStatusBadge tone={resolveReportRequestTone(request.status)}>
                {request.status}
              </ReportStatusBadge>
              <span className="text-sm text-muted-foreground">
                {formatReportDateTime(request.completedAt, 'Awaiting worker')}
              </span>
              <div className="flex justify-start md:justify-end">
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  onClick={() => onDownload(request.id)}
                  disabled={request.status !== 'COMPLETED'}
                >
                  Download
                </Button>
              </div>
            </article>
          ))}
        </div>
      )}
    </PageSection>
  )
}
