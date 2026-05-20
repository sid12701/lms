import { buildQueryPath, requestJson } from './http-client'
import type { OpsAlertRecord, OpsAlertStatus } from './lms-api'

export function listOpsAlerts(filters?: { status?: OpsAlertStatus }) {
  return requestJson<OpsAlertRecord[]>(
    buildQueryPath('/api/v1/internal/alerts', {
      status: filters?.status,
    }),
  )
}

export function acknowledgeOpsAlert(alertId: string) {
  return requestJson<OpsAlertRecord>(`/api/v1/internal/alerts/${alertId}/acknowledge`, {
    method: 'POST',
  })
}
