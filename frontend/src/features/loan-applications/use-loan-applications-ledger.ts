import { useCallback, useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { queryKeys } from '../api/query-keys'
import {
  type LoanProductRecord,
  type LspOptionRecord,
} from '../api/lms-api'
import { listLspOptions } from '../api/admin-api'
import { listLoanProducts } from '../api/products-api'
import { listLoanApplications } from '../api/loan-applications-api'
import {
  buildLoanLedgerQuery,
  calculateLoanLedgerMetrics,
  initialLoanLedgerFilters,
  type LoanLedgerFilterState,
} from './loan-applications-ledger-model'

export function useLoanApplicationsLedger() {
  const [filters, setFilters] = useState<LoanLedgerFilterState>(initialLoanLedgerFilters)
  const [refreshCounter, setRefreshCounter] = useState(0)

  const lspQuery = useQuery({
    queryKey: queryKeys.lspOptions,
    queryFn: listLspOptions,
  })
  const productQuery = useQuery({
    queryKey: queryKeys.loanProducts,
    queryFn: listLoanProducts,
  })
  const applicationsQuery = useQuery({
    queryKey: queryKeys.loanApplications(filters, refreshCounter),
    queryFn: () => listLoanApplications(buildLoanLedgerQuery(filters)),
  })

  const applications = applicationsQuery.data ?? []
  const lsps: LspOptionRecord[] = lspQuery.data ?? []
  const products: LoanProductRecord[] = productQuery.data ?? []
  const activeLsps = useMemo(() => lsps.filter((item) => item.status === 'ACTIVE'), [lsps])
  const activeProducts = useMemo(
    () => products.filter((item) => item.status === 'ACTIVE'),
    [products],
  )
  const metrics = useMemo(() => calculateLoanLedgerMetrics(applications), [applications])

  const refreshLedger = useCallback(() => {
    setRefreshCounter((value) => value + 1)
  }, [])

  const resetFilters = useCallback(() => {
    setFilters(initialLoanLedgerFilters)
  }, [])

  const updateFilters = useCallback((patch: Partial<LoanLedgerFilterState>) => {
    setFilters((current) => ({ ...current, ...patch }))
  }, [])

  const loading = lspQuery.isLoading || productQuery.isLoading || applicationsQuery.isLoading
  const queryError = lspQuery.error ?? productQuery.error ?? applicationsQuery.error
  const error = queryError instanceof Error ? queryError.message : ''

  return {
    applications,
    activeLsps,
    activeProducts,
    filters,
    loading,
    error,
    metrics,
    updateFilters,
    resetFilters,
    refreshLedger,
  }
}
