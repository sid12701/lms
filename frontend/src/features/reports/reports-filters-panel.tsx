import { PageSection } from '@/components/app/page-section'
import { DatePicker } from '@/components/ui/date-picker'
import { Button } from '@/components/ui/button'
import type { LspOptionRecord } from '@/features/api/lms-api'

type ReportsFiltersPanelProps = {
  lsps: LspOptionRecord[]
  selectedLspId: string
  disbursalDateFrom: string
  disbursalDateTo: string
  loading: boolean
  previewing: boolean
  error: string
  success: string
  onSelectedLspChange: (value: string) => void
  onDisbursalDateFromChange: (value: string) => void
  onDisbursalDateToChange: (value: string) => void
  onApply: () => void
}

const fieldLabelClassName =
  'text-[0.68rem] font-semibold uppercase tracking-[0.16em] text-muted-foreground'
const nativeSelectClassName =
  'h-11 w-full rounded-lg border border-transparent bg-input px-3 text-sm text-foreground shadow-[inset_0_0_0_1px_rgba(198,197,212,0.18)] outline-none transition-[background-color,box-shadow,border-color] focus:bg-background focus:shadow-[inset_0_0_0_1px_rgba(0,6,102,0.4),0_0_0_3px_rgba(0,6,102,0.08)] disabled:cursor-not-allowed disabled:opacity-50'

function FeedbackBanner({
  tone,
  message,
}: {
  tone: 'success' | 'error'
  message: string
}) {
  if (!message) {
    return null
  }

  return (
    <div
      className={
        tone === 'success'
          ? 'rounded-xl border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm text-emerald-800'
          : 'rounded-xl border border-destructive/20 bg-destructive/5 px-4 py-3 text-sm text-destructive'
      }
    >
      {message}
    </div>
  )
}

export function ReportsFiltersPanel({
  lsps,
  selectedLspId,
  disbursalDateFrom,
  disbursalDateTo,
  loading,
  previewing,
  error,
  success,
  onSelectedLspChange,
  onDisbursalDateFromChange,
  onDisbursalDateToChange,
  onApply,
}: ReportsFiltersPanelProps) {
  return (
    <PageSection
      title="Portfolio filters"
      description="Filter the MIS ledger by partner and disbursal window before exporting or queuing background reports."
      contentClassName="space-y-4"
    >
      <div className="grid gap-4 xl:grid-cols-[minmax(0,1.1fr)_minmax(0,1.4fr)_auto] xl:items-end">
        <div className="space-y-2">
          <label htmlFor="report-lsp" className={fieldLabelClassName}>
            LSP
          </label>
          <select
            id="report-lsp"
            className={nativeSelectClassName}
            value={selectedLspId}
            onChange={(event) => onSelectedLspChange(event.target.value)}
            disabled={loading}
          >
            <option value="">All LSPs</option>
            {lsps.map((lsp) => (
              <option key={lsp.id} value={lsp.id}>
                {lsp.code} - {lsp.name}
              </option>
            ))}
          </select>
        </div>

        <div className="space-y-2">
          <p className={fieldLabelClassName}>Date range</p>
          <div className="grid gap-3 md:grid-cols-2">
            <DatePicker
              id="report-disbursal-from"
              value={disbursalDateFrom}
              onChange={onDisbursalDateFromChange}
              className="h-11 rounded-lg px-3 text-sm"
              placeholder="Disbursal from"
              disabled={loading}
            />
            <DatePicker
              id="report-disbursal-to"
              value={disbursalDateTo}
              onChange={onDisbursalDateToChange}
              className="h-11 rounded-lg px-3 text-sm"
              placeholder="Disbursal to"
              disabled={loading}
            />
          </div>
        </div>

        <Button
          type="button"
          className="h-11 px-5"
          onClick={onApply}
          disabled={loading || previewing}
        >
          {previewing ? 'Loading...' : 'Apply Filters'}
        </Button>
      </div>

      <FeedbackBanner tone="error" message={error} />
      <FeedbackBanner tone="success" message={success} />
    </PageSection>
  )
}
