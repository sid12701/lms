import { MetricCard } from '@/components/app/metric-card'
import type { MisPortfolioSummary } from '@/features/api/lms-api'
import { getSummaryMetrics } from './reports-model'

type ReportsSummaryGridProps = {
  summary: MisPortfolioSummary | null
}

export function ReportsSummaryGrid({ summary }: ReportsSummaryGridProps) {
  return (
    <div className="grid gap-4 xl:grid-cols-4">
      {getSummaryMetrics(summary).map((metric) => (
        <MetricCard key={metric.label} label={metric.label} value={metric.value} />
      ))}
    </div>
  )
}
