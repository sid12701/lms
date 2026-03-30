const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080'

export const roleOptions = [
  'SYSTEM_ADMIN',
  'OPS_USER',
  'PRODUCT_ADMIN',
  'LSP_UI_READ',
  'LSP_UI_WRITE',
  'LSP_API_CLIENT',
] as const

export const lspStatusOptions = ['ACTIVE', 'INACTIVE'] as const

export const userStatusOptions = ['ACTIVE', 'INACTIVE'] as const
export const apiClientStatusOptions = ['ACTIVE', 'INACTIVE'] as const

export type RoleCode = (typeof roleOptions)[number]
export type LspStatus = (typeof lspStatusOptions)[number]
export type UserStatus = (typeof userStatusOptions)[number]
export type ApiClientStatus = (typeof apiClientStatusOptions)[number]

export type AuthTokenResponse = {
  accessToken: string
  tokenType: string
  expiresInSeconds: number
}

export type AdminMetadata = {
  roleCodes: string[]
  lspStatuses: string[]
  userStatuses: string[]
  apiClientStatuses?: string[]
}

export type SystemContext = {
  application: string
  activeProfiles: string[]
  username: string
  roles: string[]
  correlationId: string | null
}

export type AuthSession = {
  accessToken: string
  user: AuthUser
}

export type AuthUser = {
  username: string
  primaryRole: string
  scope: string
  application: string
  activeProfiles: string[]
  correlationId: string | null
}

export type LspRecord = {
  id: string
  code: string
  name: string
  status: LspStatus
}

export type UserRecord = {
  id: string
  username: string
  email: string
  status: UserStatus
  lspId: string | null
  lspName: string
  roles: string[]
}

export type ApiClientRecord = {
  id: string
  clientId: string
  name: string
  lspId: string | null
  lspName: string
  status: ApiClientStatus
  createdAt: string
  lastUsedAt: string | null
}

export type ApiClientCreateResponse = ApiClientRecord & {
  clientSecret: string
}

const SESSION_STORAGE_KEY = 'lms.auth.session'

function buildUrl(path: string) {
  return `${API_BASE_URL}${path}`
}

function readJson<T>(raw: string | null): T | null {
  if (!raw) {
    return null
  }

  try {
    return JSON.parse(raw) as T
  } catch {
    return null
  }
}

export function loadStoredSession() {
  if (typeof window === 'undefined') {
    return null
  }

  return readJson<AuthSession>(window.localStorage.getItem(SESSION_STORAGE_KEY))
}

export function saveStoredSession(session: AuthSession) {
  if (typeof window === 'undefined') {
    return
  }

  window.localStorage.setItem(SESSION_STORAGE_KEY, JSON.stringify(session))
}

export function clearStoredSession() {
  if (typeof window === 'undefined') {
    return
  }

  window.localStorage.removeItem(SESSION_STORAGE_KEY)
}

export function getStoredAccessToken() {
  return loadStoredSession()?.accessToken ?? null
}

async function requestJson<T>(
  path: string,
  init: RequestInit = {},
  options: { authenticated?: boolean; accessToken?: string } = {},
) {
  const authenticated = options.authenticated ?? true
  const headers = new Headers(init.headers)
  const accessToken = authenticated ? options.accessToken ?? getStoredAccessToken() : null

  if (accessToken) {
    headers.set('Authorization', `Bearer ${accessToken}`)
  }

  if (init.body && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }

  const response = await fetch(buildUrl(path), {
    ...init,
    headers,
  })

  if (!response.ok) {
    const errorBody = await response.text()
    throw new Error(errorBody || `Request failed with status ${response.status}`)
  }

  if (response.status === 204) {
    return undefined as T
  }

  const contentType = response.headers.get('content-type') ?? ''
  if (!contentType.includes('application/json')) {
    return (await response.text()) as T
  }

  return (await response.json()) as T
}

export function loginWithPassword(username: string, password: string) {
  return requestJson<AuthTokenResponse>(
    '/api/v1/auth/token',
    {
      method: 'POST',
      body: JSON.stringify({ username, password }),
    },
    { authenticated: false },
  )
}

export function refreshAccessToken(accessToken: string) {
  return requestJson<AuthTokenResponse>(
    '/api/v1/auth/refresh',
    {
      method: 'POST',
    },
    { accessToken },
  )
}

export function getSystemContext(accessToken?: string) {
  return requestJson<SystemContext>(
    '/api/v1/internal/system/context',
    {},
    accessToken ? { accessToken } : {},
  )
}

export function getAdminMetadata() {
  return requestJson<AdminMetadata>('/api/v1/internal/admin/metadata')
}

export function listLsps() {
  return requestJson<LspRecord[]>('/api/v1/internal/admin/lsps')
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
