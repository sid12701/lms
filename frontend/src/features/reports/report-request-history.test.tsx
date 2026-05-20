import { fireEvent, render, screen } from '@testing-library/react'
import { ReportRequestHistory } from './report-request-history'

const requests = [
  {
    id: 'request-1',
    reportType: 'PORTFOLIO_MIS',
    status: 'COMPLETED',
    requestedByUsername: 'ops.admin',
    lspId: 'lsp-1',
    lspCode: 'ABC',
    lspName: 'ABC Finance',
    disbursalDateFrom: '2026-01-01',
    disbursalDateTo: '2026-01-31',
    notificationEmail: 'ops@example.com',
    notificationSentAt: null,
    notificationErrorMessage: null,
    fileName: 'portfolio-mis.csv',
    mediaType: 'text/csv',
    errorMessage: null,
    completedAt: '2026-02-01T10:00:00Z',
    createdAt: '2026-02-01T09:00:00Z',
    updatedAt: '2026-02-01T10:00:00Z',
  },
  {
    id: 'request-2',
    reportType: 'PORTFOLIO_MIS',
    status: 'PROCESSING',
    requestedByUsername: 'ops.user',
    lspId: null,
    lspCode: null,
    lspName: null,
    disbursalDateFrom: null,
    disbursalDateTo: null,
    notificationEmail: null,
    notificationSentAt: null,
    notificationErrorMessage: null,
    fileName: null,
    mediaType: null,
    errorMessage: null,
    completedAt: null,
    createdAt: '2026-02-02T09:00:00Z',
    updatedAt: '2026-02-02T09:30:00Z',
  },
] as const

describe('ReportRequestHistory', () => {
  it('renders requests and disables download until a report completes', () => {
    const onRefresh = vi.fn()
    const onDownload = vi.fn()

    render(
      <ReportRequestHistory
        requests={[...requests]}
        refreshing={false}
        onRefresh={onRefresh}
        onDownload={onDownload}
      />,
    )

    expect(screen.getByText('Generated requests')).toBeInTheDocument()
    expect(screen.getByText('2 requests')).toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: 'Download' })).toHaveLength(2)
    expect(screen.getAllByRole('button', { name: 'Download' })[1]).toBeDisabled()

    fireEvent.click(screen.getByRole('button', { name: 'Refresh' }))
    fireEvent.click(screen.getAllByRole('button', { name: 'Download' })[0])

    expect(onRefresh).toHaveBeenCalledTimes(1)
    expect(onDownload).toHaveBeenCalledWith('request-1')
  })

  it('shows the empty state when no requests exist', () => {
    render(
      <ReportRequestHistory
        requests={[]}
        refreshing={false}
        onRefresh={() => {}}
        onDownload={() => {}}
      />,
    )

    expect(screen.getByText(/No report requests have been queued yet/i)).toBeInTheDocument()
  })
})
