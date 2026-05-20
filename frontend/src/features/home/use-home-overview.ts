import { useQuery } from '@tanstack/react-query'
import { queryKeys } from '@/features/api/query-keys'
import type { HomeOverviewResponse } from '@/features/api/lms-api'
import { getHomeOverview } from '@/features/api/home-api'

type UseHomeOverviewResult = {
  overview: HomeOverviewResponse | null
  loading: boolean
  error: string
}

export function useHomeOverview(enabled: boolean): UseHomeOverviewResult {
  const overviewQuery = useQuery({
    queryKey: queryKeys.homeOverview,
    queryFn: getHomeOverview,
    enabled,
  })

  return {
    overview: overviewQuery.data ?? null,
    loading: overviewQuery.isLoading,
    error: overviewQuery.error instanceof Error ? overviewQuery.error.message : '',
  }
}
