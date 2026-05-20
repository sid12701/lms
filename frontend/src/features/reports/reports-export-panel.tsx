import { BlueLoader } from '@/components/app/blue-loader'
import { PageSection } from '@/components/app/page-section'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'

type ReportsExportPanelProps = {
  loading: boolean
  submitting: boolean
  recipientEmail: string
  onRecipientEmailChange: (value: string) => void
  onGenerate: () => void
}

export function ReportsExportPanel({
  loading,
  submitting,
  recipientEmail,
  onRecipientEmailChange,
  onGenerate,
}: ReportsExportPanelProps) {
  return (
    <PageSection
      eyebrow="Background export"
      title="Generate expanded CSV"
      description="Queue a full MIS export with per-EMI breakdowns for background processing. Optionally receive an email notification when it is ready."
      contentClassName="space-y-4"
    >
      {loading ? (
        <BlueLoader
          title="Loading export controls"
          description="Checking MIS export readiness."
          compact
        />
      ) : (
        <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_auto] lg:items-end">
          <div className="space-y-2">
            <label
              htmlFor="report-recipient-email"
              className="text-[0.68rem] font-semibold uppercase tracking-[0.16em] text-muted-foreground"
            >
              Notification email
            </label>
            <Input
              id="report-recipient-email"
              type="email"
              value={recipientEmail}
              placeholder="ops.reporting@example.com"
              onChange={(event) => onRecipientEmailChange(event.target.value)}
            />
          </div>
          <Button type="button" className="h-11 px-5" onClick={onGenerate} disabled={submitting}>
            {submitting ? 'Queueing...' : 'Generate portfolio MIS'}
          </Button>
        </div>
      )}
    </PageSection>
  )
}
