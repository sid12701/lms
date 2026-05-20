import { getStoredAccessToken } from './session-storage'

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080'

type QueryParamValue = string | number | boolean | null | undefined

let onUnauthorizedRefresh: (() => Promise<string | null>) | null = null
const inFlightJsonRequests = new Map<string, Promise<unknown>>()

export function setRefreshCallback(callback: () => Promise<string | null>) {
  onUnauthorizedRefresh = callback
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

function buildUrl(path: string) {
  return `${API_BASE_URL}${path}`
}

function readResponseError(body: string): { message: string; code: string | null } {
  if (!body.trim()) {
    return { message: 'Request failed.', code: null }
  }

  try {
    const parsed = JSON.parse(body) as Record<string, unknown>
    const errors = Array.isArray(parsed.errors)
      ? (parsed.errors as Array<Record<string, unknown>>)
      : []
    const primaryError = errors[0]
    let message = body
    if (typeof parsed.message === 'string') {
      message = parsed.message
    } else if (typeof parsed.errorSource === 'string') {
      message = parsed.errorSource
    } else if (typeof primaryError?.errorSource === 'string') {
      message = primaryError.errorSource
    } else if (typeof parsed.error === 'string') {
      message = parsed.error
    } else if (typeof parsed.detail === 'string') {
      message = parsed.detail
    }

    let code: string | null = null
    if (typeof parsed.code === 'string') {
      code = parsed.code
    } else if (typeof parsed.errorReason === 'string') {
      code = parsed.errorReason
    } else if (typeof parsed.errorCode === 'string') {
      code = parsed.errorCode
    } else if (typeof primaryError?.errorReason === 'string') {
      code = primaryError.errorReason
    } else if (typeof primaryError?.errorCode === 'string') {
      code = primaryError.errorCode
    } else if (typeof parsed.error === 'string') {
      code = parsed.error
    }

    return { message, code }
  } catch {
    return { message: body, code: null }
  }
}

function readFilenameFromContentDisposition(contentDisposition: string | null) {
  if (!contentDisposition) {
    return null
  }

  const utf8Match = contentDisposition.match(/filename\*=UTF-8''([^;]+)/i)
  if (utf8Match?.[1]) {
    return decodeURIComponent(utf8Match[1])
  }

  const quotedMatch = contentDisposition.match(/filename="([^"]+)"/i)
  if (quotedMatch?.[1]) {
    return quotedMatch[1]
  }

  const simpleMatch = contentDisposition.match(/filename=([^;]+)/i)
  return simpleMatch?.[1]?.trim() ?? null
}

export function buildQueryPath(path: string, params: Record<string, QueryParamValue>) {
  const searchParams = new URLSearchParams()

  Object.entries(params).forEach(([key, value]) => {
    if (value == null || value === '') {
      return
    }

    searchParams.set(key, String(value))
  })

  const queryString = searchParams.toString()
  return queryString ? `${path}?${queryString}` : path
}

export async function requestJson<T>(
  path: string,
  init: RequestInit = {},
  options: { authenticated?: boolean; accessToken?: string; _retried?: boolean } = {},
): Promise<T> {
  const dedupeKey = buildJsonDedupeKey(path, init, options)
  if (dedupeKey) {
    const existing = inFlightJsonRequests.get(dedupeKey) as Promise<T> | undefined
    if (existing) {
      return existing
    }

    const promise: Promise<T> = performJsonRequest<T>(path, init, options).finally(() => {
      inFlightJsonRequests.delete(dedupeKey)
    })
    inFlightJsonRequests.set(dedupeKey, promise)
    return promise
  }

  return performJsonRequest(path, init, options)
}

function buildJsonDedupeKey(
  path: string,
  init: RequestInit,
  options: { authenticated?: boolean; accessToken?: string; _retried?: boolean },
) {
  const method = (init.method ?? 'GET').toUpperCase()
  if (method !== 'GET' || init.body || options._retried) {
    return null
  }

  const authenticated = options.authenticated ?? true
  const accessToken = authenticated ? options.accessToken ?? getStoredAccessToken() : null
  return `${authenticated ? 'auth' : 'anon'}:${accessToken ?? ''}:${path}`
}

async function performJsonRequest<T>(
  path: string,
  init: RequestInit = {},
  options: { authenticated?: boolean; accessToken?: string; _retried?: boolean } = {},
): Promise<T> {
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
    credentials: 'include',
  })

  if (response.status === 401 && authenticated && !options._retried && onUnauthorizedRefresh) {
    const newToken = await onUnauthorizedRefresh()
    if (newToken) {
      return requestJson<T>(path, init, { ...options, accessToken: newToken, _retried: true })
    }
  }

  if (!response.ok) {
    const errorBody = await response.text()
    const { message, code } = readResponseError(errorBody)
    throw new ApiError(
      message || `Request failed with status ${response.status}`,
      response.status,
      errorBody,
      code,
    )
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

export async function requestBlob(
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

  const response = await fetch(buildUrl(path), {
    ...init,
    headers,
    credentials: 'include',
  })

  if (!response.ok) {
    const errorBody = await response.text()
    const { message, code } = readResponseError(errorBody)
    throw new ApiError(
      message || `Request failed with status ${response.status}`,
      response.status,
      errorBody,
      code,
    )
  }

  return {
    blob: await response.blob(),
    filename: readFilenameFromContentDisposition(response.headers.get('content-disposition')),
  }
}
