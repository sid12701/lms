import { requestJson } from './http-client'
import type { HomeOverviewResponse } from './lms-api'

export function getHomeOverview() {
  return requestJson<HomeOverviewResponse>('/api/v1/internal/home/overview')
}
