import {
  buildLoanLedgerQuery,
  calculateLoanLedgerMetrics,
  formatCompactLoanAmount,
  getLoanLedgerAvatarClassName,
  getLoanLedgerBorrowerInitials,
  getLoanLedgerStatusVariant,
  getShortLoanId,
  initialLoanLedgerFilters,
} from './loan-applications-ledger-model'

describe('loan-applications-ledger-model', () => {
  it('builds API query params from visible ledger filters', () => {
    expect(
      buildLoanLedgerQuery({
        ...initialLoanLedgerFilters,
        lspId: 'lsp-1',
        query: '  asha  ',
      }),
    ).toEqual({
      lspId: 'lsp-1',
      productId: undefined,
      status: undefined,
      query: 'asha',
    })
  })

  it('formats compact portfolio amounts for summary metrics', () => {
    expect(formatCompactLoanAmount(25_000)).toBe('Rs 25.0 K')
    expect(formatCompactLoanAmount(4_50_000)).toBe('Rs 4.50 L')
    expect(formatCompactLoanAmount(3_20_00_000)).toBe('Rs 3.20 Cr')
  })

  it('calculates summary metrics from the application queue', () => {
    expect(
      calculateLoanLedgerMetrics([
        {
          id: 'loan-1',
          borrowerId: 'borrower-1',
          borrowerFullName: 'Asha Rao',
          borrowerPan: 'ABCDE1234F',
          borrowerMobile: '9999999999',
          borrowerEmail: 'asha@example.com',
          borrowerDateOfBirth: null,
          borrowerCity: 'Mumbai',
          borrowerState: 'Maharashtra',
          borrowerEmploymentType: 'SALARIED',
          borrowerMonthlyIncome: 80000,
          lspId: 'lsp-1',
          lspCode: 'ABC',
          lspName: 'ABC Finance',
          productId: 'product-1',
          productCode: 'PL',
          productName: 'Prime Loan',
          externalLoanId: 'EXT-1',
          sourceChannel: 'MOBILE_APP',
          requestedAmount: 500000,
          tenureMonths: 24,
          status: 'DISBURSED',
          createdAt: '2026-04-01T00:00:00Z',
        },
        {
          id: 'loan-2',
          borrowerId: 'borrower-2',
          borrowerFullName: 'Riya Singh',
          borrowerPan: 'FGHIJ5678K',
          borrowerMobile: '8888888888',
          borrowerEmail: 'riya@example.com',
          borrowerDateOfBirth: null,
          borrowerCity: 'Delhi',
          borrowerState: 'Delhi',
          borrowerEmploymentType: 'SELF_EMPLOYED',
          borrowerMonthlyIncome: 95000,
          lspId: 'lsp-2',
          lspCode: 'XYZ',
          lspName: 'XYZ Capital',
          productId: 'product-2',
          productCode: 'BL',
          productName: 'Business Loan',
          externalLoanId: 'EXT-2',
          sourceChannel: 'BRANCH',
          requestedAmount: 300000,
          tenureMonths: 18,
          status: 'UNDER_REPAYMENT',
          createdAt: '2026-04-02T00:00:00Z',
        },
        {
          id: 'loan-3',
          borrowerId: 'borrower-3',
          borrowerFullName: 'Kabir Mehta',
          borrowerPan: 'LMNOP9012Q',
          borrowerMobile: '7777777777',
          borrowerEmail: null,
          borrowerDateOfBirth: null,
          borrowerCity: 'Pune',
          borrowerState: 'Maharashtra',
          borrowerEmploymentType: 'SALARIED',
          borrowerMonthlyIncome: 70000,
          lspId: 'lsp-3',
          lspCode: 'RST',
          lspName: 'RST Finance',
          productId: 'product-3',
          productCode: 'EL',
          productName: 'Education Loan',
          externalLoanId: 'EXT-3',
          sourceChannel: 'WEB',
          requestedAmount: 250000,
          tenureMonths: 12,
          status: 'AWAITING_APPROVAL',
          createdAt: '2026-04-03T00:00:00Z',
        },
      ]),
    ).toEqual({
      activePortfolioValue: 800000,
      pendingApprovalCount: 1,
      disbursedCount: 1,
    })
  })

  it('maps borrower and status display helpers consistently', () => {
    expect(getLoanLedgerBorrowerInitials('Asha Rao')).toBe('AR')
    expect(getLoanLedgerBorrowerInitials('  ')).toBe('--')
    expect(getLoanLedgerStatusVariant('DISBURSED')).toBe('success')
    expect(getLoanLedgerStatusVariant('REJECTED')).toBe('destructive')
    expect(getShortLoanId('12345678-90ab-cdef')).toBe('BHAW12345678')
  })

  it('cycles avatar tones deterministically', () => {
    expect(getLoanLedgerAvatarClassName(0)).toContain('bg-primary/10')
    expect(getLoanLedgerAvatarClassName(4)).toContain('bg-primary/10')
  })
})
