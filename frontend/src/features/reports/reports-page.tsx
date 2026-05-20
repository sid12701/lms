import { PageHeader } from '@/components/app/page-header'
import { ReportRequestHistory } from './report-request-history'
import { ReportsExportPanel } from './reports-export-panel'
import { ReportsFiltersPanel } from './reports-filters-panel'
import { ReportsPreviewSection } from './reports-preview-section'
import { ReportsSummaryGrid } from './reports-summary-grid'
import { useReportsPage } from './use-reports-page'

export function ReportsPage() {
  const {
    lsps,
    requests,
    selectedLspId,
    setSelectedLspId,
    disbursalDateFrom,
    setDisbursalDateFrom,
    disbursalDateTo,
    setDisbursalDateTo,
    recipientEmail,
    setRecipientEmail,
    loading,
    submitting,
    refreshingRequests,
    error,
    success,
    previewRows,
    summary,
    previewing,
    initialLoadDone,
    currentPage,
    totalElements,
    totalPages,
    maxInstallments,
    showingFrom,
    showingTo,
    handlePreview,
    handlePageChange,
    handleDownloadCsv,
    handleGenerate,
    handleDownloadRequest,
    refreshRequests,
  } = useReportsPage()

  return (
    <div className="grid gap-6">
      <PageHeader
        eyebrow="Internal reporting"
        title="Portfolio MIS"
        description="Live MIS summary, preview, and export workflows backed by the internal reports endpoints."
      />

      <ReportsSummaryGrid summary={summary} />

      <ReportsFiltersPanel
        lsps={lsps}
        selectedLspId={selectedLspId}
        disbursalDateFrom={disbursalDateFrom}
        disbursalDateTo={disbursalDateTo}
        loading={loading}
        previewing={previewing}
        error={error}
        success={success}
        onSelectedLspChange={setSelectedLspId}
        onDisbursalDateFromChange={setDisbursalDateFrom}
        onDisbursalDateToChange={setDisbursalDateTo}
        onApply={() => void handlePreview()}
      />

      <ReportsPreviewSection
        initialLoadDone={initialLoadDone}
        previewing={previewing}
        rows={previewRows}
        totalElements={totalElements}
        currentPage={currentPage}
        totalPages={totalPages}
        maxInstallments={maxInstallments}
        showingFrom={showingFrom}
        showingTo={showingTo}
        loading={loading}
        submitting={submitting}
        onExport={() => void handleDownloadCsv()}
        onQueue={() => void handleGenerate()}
        onPageChange={(page) => void handlePageChange(page)}
      />

      <ReportsExportPanel
        loading={loading}
        submitting={submitting}
        recipientEmail={recipientEmail}
        onRecipientEmailChange={setRecipientEmail}
        onGenerate={() => void handleGenerate()}
      />

      <ReportRequestHistory
        requests={requests}
        refreshing={refreshingRequests}
        onRefresh={() => void refreshRequests()}
        onDownload={(requestId) => void handleDownloadRequest(requestId)}
      />
    </div>
  )
}
