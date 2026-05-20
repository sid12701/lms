import { ApiError, requestJson } from './http-client'
import type {
  ApiClientTokenResponse,
  AuthTokenResponse,
  SystemContext,
} from './lms-api'

export function loginWithPassword(username: string, password: string) {
  return requestJson<AuthTokenResponse>(
    '/api/v1/auth/login',
    {
      method: 'POST',
      body: JSON.stringify({ username, password }),
    },
    { authenticated: false },
  )
}

export function loginWithApiClientCredentials(clientId: string, clientSecret: string) {
  return requestJson<ApiClientTokenResponse>(
    '/api/v1/auth/token',
    {
      method: 'POST',
      body: JSON.stringify({
        clientId,
        clientSecret,
      }),
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

export function refreshAccessToken() {
  return requestJson<AuthTokenResponse>(
    '/api/v1/auth/refresh',
    {
      method: 'POST',
    },
    { authenticated: false },
  )
}

export function logoutSession() {
  return requestJson<void>(
    '/api/v1/auth/logout',
    {
      method: 'POST',
    },
    { authenticated: false },
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
