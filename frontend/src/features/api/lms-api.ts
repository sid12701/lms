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
export const loanProductStatusOptions = ['DRAFT', 'ACTIVE', 'INACTIVE'] as const

export type RoleCode = (typeof roleOptions)[number]
export type LspStatus = (typeof lspStatusOptions)[number]
export type UserStatus = (typeof userStatusOptions)[number]
export type ApiClientStatus = (typeof apiClientStatusOptions)[number]
export type LoanProductStatus = (typeof loanProductStatusOptions)[number]

export type AuthTokenResponse = {
  accessToken: string
  tokenType: string
  expiresInSeconds: number
  passwordChangeRequired?: boolean
}

export type AdminMetadata = {
  roleCodes: string[]
  lspStatuses: string[]
  userStatuses: string[]
  apiClientStatuses?: string[]
  loanProductStatuses?: string[]
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
  mustChangePassword: boolean
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

export type ResetPasswordResponse = {
  id: string
  username: string
  temporaryPassword: string
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

export type LoanProductRecord = {
  id: string
  code: string
  name: string
  minPrincipal: number
  maxPrincipal: number
  interestRate: number
  processingFeeRate: number
  minTenureMonths: number
  maxTenureMonths: number
  status: LoanProductStatus
}

export type LoanProductCreateResponse = LoanProductRecord

export type ProductLspMappingRecord = {
  productId: string
  lspIds: string[]
}

export type ProductAuditEventRecord = {
  id: string
  productId: string
  action: string
  actorUsername: string
  summary: string
  correlationId: string | null
  createdAt: string
}

export type LoanApplicationRecord = {
  id: string
  borrowerId: string
  borrowerFullName: string
  borrowerPan: string
  borrowerMobile: string
  borrowerEmail: string | null
  lspId: string
  lspCode: string
  lspName: string
  productId: string
  productCode: string
  productName: string
  externalLoanId: string
  sourceChannel: string
  requestedAmount: number
  tenureMonths: number
  status: string
  createdAt: string
}

export type LoanApplicationIntakeAuditRecord = {
  id: string
  loanApplicationId: string
  actorUsername: string
  correlationId: string | null
  payloadJson: string
  createdAt: string
}

export class ApiError extends Error {
  status: number
  body: string
  code: string | null

  constructor(message: string, status: number, body: string, code: string | null) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.body = body
    this.code = code
  }
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

function readResponseError(body: string): { message: string; code: string | null } {
  if (!body.trim()) {
    return { message: 'Request failed.', code: null }
  }

  try {
    const parsed = JSON.parse(body) as Record<string, unknown>
    const message =
      typeof parsed.message === 'string'
        ? parsed.message
        : typeof parsed.error === 'string'
          ? parsed.error
          : typeof parsed.detail === 'string'
            ? parsed.detail
            : body

    const code =
      typeof parsed.code === 'string'
        ? parsed.code
        : typeof parsed.errorCode === 'string'
          ? parsed.errorCode
          : typeof parsed.error === 'string'
            ? parsed.error
            : null

    return { message, code }
  } catch {
    return { message: body, code: null }
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
    const { message, code } = readResponseError(errorBody)
    throw new ApiError(message || `Request failed with status ${response.status}`, response.status, errorBody, code)
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

export function completePasswordChange(payload: { newPassword: string }) {
  return requestJson<AuthTokenResponse>(
    '/api/v1/auth/password',
    {
      method: 'POST',
      body: JSON.stringify(payload),
    },
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

export function isPasswordChangeRequired(error: unknown) {
  if (!(error instanceof ApiError)) {
    return false
  }

  return (
    error.status === 428 ||
    error.status === 409 ||
    error.code === 'PASSWORD_CHANGE_REQUIRED' ||
    error.code === 'PASSWORD_RESET_REQUIRED' ||
    error.body.includes('PASSWORD_CHANGE_REQUIRED') ||
    error.body.includes('PASSWORD_RESET_REQUIRED')
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

export function listLoanProducts() {
  return requestJson<LoanProductRecord[]>('/api/v1/internal/admin/products')
}

export function listProductLspMappings() {
  return requestJson<ProductLspMappingRecord[]>('/api/v1/internal/admin/product-lsp-mappings')
}

export function listProductAuditEvents(productId: string) {
  return requestJson<ProductAuditEventRecord[]>(
    `/api/v1/internal/admin/products/${productId}/audit-events`,
  )
}

export function createLoanProduct(payload: {
  code: string
  name: string
  minPrincipal: number
  maxPrincipal: number
  interestRate: number
  processingFeeRate: number
  minTenureMonths: number
  maxTenureMonths: number
  status?: LoanProductStatus
}) {
  return requestJson<LoanProductCreateResponse>('/api/v1/internal/admin/products', {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

export function updateLoanProduct(
  productId: string,
  payload: {
    code: string
    name: string
    minPrincipal: number
    maxPrincipal: number
    interestRate: number
    processingFeeRate: number
    minTenureMonths: number
    maxTenureMonths: number
    status?: LoanProductStatus
  },
) {
  return requestJson<LoanProductRecord>(`/api/v1/internal/admin/products/${productId}`, {
    method: 'PUT',
    body: JSON.stringify(payload),
  })
}

export function saveProductLspMappings(
  productId: string,
  payload: {
    lspIds: string[]
  },
) {
  return requestJson<void>(`/api/v1/internal/admin/product-lsp-mappings/${productId}`, {
    method: 'PUT',
    body: JSON.stringify(payload),
  })
}

export function listLoanApplications(filters?: {
  lspId?: string
  productId?: string
  status?: string
  sourceChannel?: string
  query?: string
}) {
  const params = new URLSearchParams()

  if (filters?.lspId) {
    params.set('lspId', filters.lspId)
  }

  if (filters?.productId) {
    params.set('productId', filters.productId)
  }

  if (filters?.status) {
    params.set('status', filters.status)
  }

  if (filters?.sourceChannel) {
    params.set('sourceChannel', filters.sourceChannel)
  }

  if (filters?.query) {
    params.set('q', filters.query)
  }

  const queryString = params.toString()
  const path = queryString
    ? `/api/v1/internal/ops/loan-applications?${queryString}`
    : '/api/v1/internal/ops/loan-applications'

  return requestJson<LoanApplicationRecord[]>(path)
}

export function createLoanApplication(payload: {
  lspId: string
  productId: string
  externalLoanId: string
  sourceChannel: string
  borrowerPan: string
  borrowerFullName: string
  borrowerMobile: string
  borrowerEmail?: string
  requestedAmount: number
  tenureMonths: number
}) {
  return requestJson<LoanApplicationRecord>('/api/v1/internal/ops/loan-applications', {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

export function listLoanApplicationIntakeAudits(applicationId: string) {
  return requestJson<LoanApplicationIntakeAuditRecord[]>(
    `/api/v1/internal/ops/loan-applications/${applicationId}/intake-audits`,
  )
}
