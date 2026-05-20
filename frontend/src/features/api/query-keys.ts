import type { LoanLedgerFilterState } from '@/features/loan-applications/loan-applications-ledger-model'
import type { ReportFilters } from '@/features/reports/reports-model'

export const queryKeys = {
  adminMetadata: ['admin', 'metadata'] as const,
  apiClients: ['admin', 'api-clients'] as const,
  homeOverview: ['home', 'overview'] as const,
  loanProducts: ['admin', 'loan-products'] as const,
  lspDirectory: ['admin', 'lsps'] as const,
  lspOptions: ['admin', 'lsp-options'] as const,
  productLspMappings: ['admin', 'product-lsp-mappings'] as const,
  reportRequests: ['reports', 'requests'] as const,
  users: ['admin', 'users'] as const,
  loanApplications: (filters: LoanLedgerFilterState, refreshCounter: number) =>
    ['loan-applications', filters, refreshCounter] as const,
  portfolioMisPreview: (filters: ReportFilters, page: number, size: number) =>
    ['reports', 'portfolio-mis-preview', filters, page, size] as const,
  portfolioMisSummary: (filters: ReportFilters) => ['reports', 'portfolio-mis-summary', filters] as const,
  productAuditEvents: (productId: string | null | undefined) =>
    ['admin', 'product-audit-events', productId ?? null] as const,
}
