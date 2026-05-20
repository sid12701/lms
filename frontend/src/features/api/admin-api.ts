import { requestJson } from './http-client'
import type {
  AdminMetadata,
  ApiClientCreateResponse,
  ApiClientRecord,
  ApiClientStatus,
  LspDetailRecord,
  LspOptionRecord,
  LspRecord,
  LspStatus,
  ResetPasswordResponse,
  RoleCode,
  UserRecord,
  UserStatus,
  WebhookEventType,
} from './lms-api'

export function getAdminMetadata() {
  return requestJson<AdminMetadata>('/api/v1/internal/admin/metadata')
}

export function listLsps() {
  return requestJson<LspRecord[]>('/api/v1/internal/admin/lsps')
}

export function listLspOptions() {
  return requestJson<LspOptionRecord[]>('/api/v1/internal/admin/lsp-options')
}

export function getLspDetail(lspId: string) {
  return requestJson<LspDetailRecord>(`/api/v1/internal/admin/lsps/${lspId}`)
}

export function createLsp(payload: {
  code: string
  name: string
  status?: LspStatus
}) {
  return requestJson<LspRecord>('/api/v1/internal/admin/lsps', {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

export function updateLspWebhookSubscription(
  lspId: string,
  payload: {
    enabled: boolean
    endpointUrl?: string
    signingSecret?: string
    eventTypes: WebhookEventType[]
  },
) {
  return requestJson<LspRecord>(`/api/v1/internal/admin/lsps/${lspId}/webhook-subscription`, {
    method: 'PUT',
    body: JSON.stringify(payload),
  })
}

export function listUsers() {
  return requestJson<UserRecord[]>('/api/v1/internal/admin/users')
}

export function createUser(payload: {
  username: string
  email: string
  password: string
  status?: UserStatus
  lspId?: string | null
  roles: RoleCode[]
}) {
  return requestJson<UserRecord>('/api/v1/internal/admin/users', {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

export function resetUserPassword(userId: string) {
  return requestJson<ResetPasswordResponse>(`/api/v1/internal/admin/users/${userId}/reset-password`, {
    method: 'POST',
  })
}

export function listApiClients() {
  return requestJson<ApiClientRecord[]>('/api/v1/internal/admin/api-clients')
}

export function createApiClient(payload: {
  name: string
  lspId?: string | null
  status?: ApiClientStatus
}) {
  return requestJson<ApiClientCreateResponse>('/api/v1/internal/admin/api-clients', {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}
