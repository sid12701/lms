import { queryKeys } from './query-keys'
import { initialLoanLedgerFilters } from '@/features/loan-applications/loan-applications-ledger-model'

describe('queryKeys', () => {
  it('uses stable keys for shared reference data', () => {
    expect(queryKeys.lspOptions).toEqual(['admin', 'lsp-options'])
    expect(queryKeys.adminMetadata).toEqual(['admin', 'metadata'])
    expect(queryKeys.loanProducts).toEqual(['admin', 'loan-products'])
  })

  it('includes filters and pagination in data-heavy keys', () => {
    expect(queryKeys.loanApplications(initialLoanLedgerFilters, 2)).toEqual([
      'loan-applications',
      initialLoanLedgerFilters,
      2,
    ])
    expect(queryKeys.portfolioMisPreview({ lspId: 'lsp-1' }, 1, 50)).toEqual([
      'reports',
      'portfolio-mis-preview',
      { lspId: 'lsp-1' },
      1,
      50,
    ])
  })
})
