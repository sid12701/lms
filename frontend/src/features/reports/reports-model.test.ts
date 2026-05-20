import {
  buildReportFilters,
  formatCompactPortfolioAmount,
  formatReportCurrency,
  formatReportDateRange,
  generateReportPageNumbers,
  hasInvalidReportDateRange,
  resolvePreviewStatusTone,
  resolveReportRequestTone,
} from './reports-model'

describe('reports-model', () => {
  it('builds API filters from the current draft state', () => {
    expect(
      buildReportFilters({
        selectedLspId: 'lsp-1',
        disbursalDateFrom: '2026-01-01',
        disbursalDateTo: '',
      }),
    ).toEqual({
      lspId: 'lsp-1',
      disbursalDateFrom: '2026-01-01',
      disbursalDateTo: undefined,
    })
  })

  it('detects invalid date ranges', () => {
    expect(hasInvalidReportDateRange('2026-02-01', '2026-01-31')).toBe(true)
    expect(hasInvalidReportDateRange('2026-01-01', '2026-02-01')).toBe(false)
  })

  it('formats preview values and compact summary values', () => {
    expect(formatReportCurrency(125000)).toBe('1,25,000')
    expect(formatReportCurrency(null)).toBe('--')
    expect(formatCompactPortfolioAmount(2_50_00_000)).toBe('Rs 2.5 Cr')
    expect(formatCompactPortfolioAmount(9_50_000)).toBe('Rs 9.5 L')
  })

  it('generates condensed pagination around the current page', () => {
    expect(generateReportPageNumbers(5, 10)).toEqual([1, '...', 4, 5, 6, '...', 10])
    expect(generateReportPageNumbers(2, 5)).toEqual([1, 2, 3, 4, 5])
  })

  it('maps statuses to enterprise badge tones', () => {
    expect(resolvePreviewStatusTone('ACTIVE')).toBe('success')
    expect(resolvePreviewStatusTone('DELINQUENT')).toBe('danger')
    expect(resolveReportRequestTone('COMPLETED')).toBe('success')
    expect(resolveReportRequestTone('FAILED')).toBe('danger')
    expect(resolveReportRequestTone('PROCESSING')).toBe('warning')
  })

  it('formats report date ranges with sensible fallbacks', () => {
    expect(formatReportDateRange('2026-01-01', '2026-01-31')).toBe('2026-01-01 to 2026-01-31')
    expect(formatReportDateRange(null, '')).toBe('Start to End')
  })
})
