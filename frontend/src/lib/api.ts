import type { ApiErrorBody, ErrorCode, TokenResponse } from './types'

/**
 * The HTTP layer.
 *
 * Responsibilities, in order of subtlety:
 *  1. attach the access token
 *  2. unwrap the API's error envelope into a typed exception
 *  3. transparently refresh an expired access token and retry, exactly once
 *
 * Point 3 is where the real care is. See {@link refreshAccessToken}.
 */

const BASE_URL = '/api/v1'

/** Where the refresh token lives between page loads. */
const REFRESH_TOKEN_KEY = 'fintrack.refreshToken'

/**
 * The access token is held in memory only.
 *
 * Keeping it out of localStorage means an XSS payload cannot simply read it back, and it
 * expires in fifteen minutes regardless. The refresh token does live in localStorage, which
 * is a real and deliberate trade-off: the alternative that actually closes the XSS hole is
 * an httpOnly cookie, which needs a server-side change and belongs with the rest of the
 * hardening work in Phase 6. Storing it in memory instead would sign the user out on every
 * page refresh.
 */
let accessToken: string | null = null

/** Called by the auth slice so the client and the store never disagree about the session. */
export function setAccessToken(token: string | null) {
  accessToken = token
}

export function getRefreshToken(): string | null {
  return localStorage.getItem(REFRESH_TOKEN_KEY)
}

export function setRefreshToken(token: string | null) {
  if (token) localStorage.setItem(REFRESH_TOKEN_KEY, token)
  else localStorage.removeItem(REFRESH_TOKEN_KEY)
}

/** Thrown for any non-2xx response, carrying the API's own error code and field details. */
export class ApiError extends Error {
  // Declared and assigned explicitly rather than as constructor parameter properties: the
  // build runs with erasableSyntaxOnly, which forbids TypeScript syntax that emits runtime
  // code, so that types can be stripped without a transform.
  readonly code: ErrorCode
  readonly status: number
  readonly details?: Record<string, unknown>

  constructor(
    code: ErrorCode,
    message: string,
    status: number,
    details?: Record<string, unknown>,
  ) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.status = status
    this.details = details
  }

  /** Field-level validation messages, ready to hand to react-hook-form. */
  fieldErrors(): Record<string, string> {
    if (!this.details) return {}
    return Object.fromEntries(
      Object.entries(this.details)
        .filter(([, value]) => typeof value === 'string')
        .map(([field, value]) => [field, value as string]),
    )
  }
}

/** Notifies the app that the session is gone and the user must sign in again. */
type SessionExpiredHandler = () => void
let onSessionExpired: SessionExpiredHandler = () => {}

export function setSessionExpiredHandler(handler: SessionExpiredHandler) {
  onSessionExpired = handler
}

/**
 * In-flight refresh, shared by every caller in this tab.
 *
 * This is not an optimisation. The API rotates refresh tokens and treats a second
 * presentation of an already-redeemed token as theft, revoking every session for the user.
 * Two concurrent refreshes with the same token therefore do not merely waste a request -
 * the first succeeds, the second is rejected as reuse, and the rejection kills the token the
 * first one just issued. The user is signed out by their own client.
 *
 * That is not hypothetical: React StrictMode double-invokes effects in development, so the
 * session restore on mount fires twice, and without this guard the second call replays a
 * spent token every single page load.
 */
let refreshInFlight: Promise<TokenResponse> | null = null

/** Cross-tab serialisation. Two tabs restoring at once would collide the same way. */
const REFRESH_LOCK = 'fintrack.refresh'

async function performRefresh(): Promise<TokenResponse> {
  // Read the token *inside* the lock, never from an enclosing closure. A tab that queued
  // behind another must use the token its predecessor just wrote, not the one it captured
  // before waiting - otherwise the lock only reorders the reuse, it does not prevent it.
  const refreshToken = getRefreshToken()
  if (!refreshToken) throw new ApiError('UNAUTHENTICATED', 'No refresh token', 401)

  const response = await fetch(`${BASE_URL}/auth/refresh`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ refreshToken }),
  })

  if (!response.ok) {
    // Spent, expired or revoked. Nothing to retry.
    setAccessToken(null)
    setRefreshToken(null)
    onSessionExpired()
    throw new ApiError('TOKEN_INVALID', 'Session expired', 401)
  }

  const tokens: TokenResponse = await response.json()
  setAccessToken(tokens.accessToken)
  // Rotation: the old token is already dead server-side, so the replacement is persisted
  // before the lock is released and the next waiter reads it.
  setRefreshToken(tokens.refreshToken)
  return tokens
}

/**
 * Refreshes the session, at most once at a time across every caller and every tab.
 *
 * @returns the new token pair and the user it belongs to
 */
export function refreshSession(): Promise<TokenResponse> {
  if (refreshInFlight) return refreshInFlight

  refreshInFlight = (async () => {
    // Web Locks serialises across tabs of the same origin. Where it is unavailable the
    // in-tab guard above still holds, which covers the StrictMode case; only the rarer
    // two-tabs-at-once race is left open.
    if (typeof navigator !== 'undefined' && navigator.locks) {
      return navigator.locks.request(REFRESH_LOCK, () => performRefresh())
    }
    return performRefresh()
  })().finally(() => {
    refreshInFlight = null
  })

  return refreshInFlight
}

async function refreshAccessToken(): Promise<string> {
  const tokens = await refreshSession()
  return tokens.accessToken
}

interface RequestOptions {
  method?: string
  body?: unknown
  /** Set internally to stop a retry from recursing. */
  isRetry?: boolean
  signal?: AbortSignal
}

async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = 'GET', body, isRetry = false, signal } = options

  const headers: Record<string, string> = {}
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  if (accessToken) headers.Authorization = `Bearer ${accessToken}`

  const response = await fetch(`${BASE_URL}${path}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
    signal,
  })

  // One refresh-and-retry per request. The auth endpoints are excluded: a failed login is a
  // wrong password, not an expired session, and retrying it would be nonsense.
  if (response.status === 401 && !isRetry && !path.startsWith('/auth/')) {
    if (getRefreshToken()) {
      await refreshAccessToken()
      return request<T>(path, { ...options, isRetry: true })
    }
    onSessionExpired()
  }

  if (!response.ok) {
    throw await toApiError(response)
  }

  // 204 No Content, and any other empty body.
  if (response.status === 204 || response.headers.get('Content-Length') === '0') {
    return undefined as T
  }
  return (await response.json()) as T
}

async function toApiError(response: Response): Promise<ApiError> {
  try {
    const body: ApiErrorBody = await response.json()
    return new ApiError(
      body.error.code,
      body.error.message,
      response.status,
      body.error.details,
    )
  } catch {
    // A proxy error or a network-level failure never carries the API's envelope.
    return new ApiError('INTERNAL_ERROR', `Request failed (${response.status})`, response.status)
  }
}

export const api = {
  get: <T>(path: string, signal?: AbortSignal) => request<T>(path, { signal }),
  post: <T>(path: string, body?: unknown) => request<T>(path, { method: 'POST', body }),
  patch: <T>(path: string, body?: unknown) => request<T>(path, { method: 'PATCH', body }),
  delete: <T>(path: string) => request<T>(path, { method: 'DELETE' }),

  /**
   * Downloads a file, honouring the filename the server suggests.
   *
   * Kept apart from the JSON helpers because the response is a blob, and because the
   * Content-Disposition header is the only place the generated filename exists.
   */
  async download(path: string, fallbackName: string): Promise<void> {
    const headers: Record<string, string> = {}
    if (accessToken) headers.Authorization = `Bearer ${accessToken}`

    let response = await fetch(`${BASE_URL}${path}`, { headers })

    if (response.status === 401 && getRefreshToken()) {
      const token = await refreshAccessToken()
      response = await fetch(`${BASE_URL}${path}`, {
        headers: { Authorization: `Bearer ${token}` },
      })
    }
    if (!response.ok) throw await toApiError(response)

    const disposition = response.headers.get('Content-Disposition')
    const match = disposition?.match(/filename="?([^"]+)"?/)
    const blob = await response.blob()

    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = match?.[1] ?? fallbackName
    document.body.appendChild(link)
    link.click()
    link.remove()
    // Revoking frees the blob; without it the file stays in memory for the tab's lifetime.
    URL.revokeObjectURL(url)
  },
}

/** Builds a query string, dropping empty values so the URL carries only real filters. */
export function toQuery(params: Record<string, unknown>): string {
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === '') continue
    if (typeof value === 'boolean' && !value) continue
    search.set(key, String(value))
  }
  const query = search.toString()
  return query ? `?${query}` : ''
}
