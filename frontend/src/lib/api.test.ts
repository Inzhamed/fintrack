import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  ApiError,
  api,
  getRefreshToken,
  refreshSession,
  setAccessToken,
  setRefreshToken,
  setSessionExpiredHandler,
  toQuery,
} from './api'

/** Builds a fetch Response without going near the network. */
function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function tokenPair(suffix: string) {
  return {
    accessToken: `access-${suffix}`,
    refreshToken: `refresh-${suffix}`,
    tokenType: 'Bearer',
    expiresIn: 900,
    user: { id: 'u1', email: 'a@b.com', name: 'A', role: 'USER', baseCurrency: 'DZD' },
  }
}

describe('toQuery', () => {
  it('drops empty and false values so the URL carries only real filters', () => {
    expect(toQuery({ type: 'EXPENSE', search: '', page: 0, uncategorised: false }))
      .toBe('?type=EXPENSE&page=0')
  })

  it('returns an empty string rather than a bare question mark', () => {
    expect(toQuery({ a: undefined, b: null, c: '' })).toBe('')
  })

  it('keeps a true boolean, which is the only way a flag filter is expressed', () => {
    expect(toQuery({ uncategorised: true })).toBe('?uncategorised=true')
  })
})

describe('ApiError', () => {
  it('exposes field messages in the shape react-hook-form expects', () => {
    const error = new ApiError('VALIDATION_FAILED', 'failed', 400, {
      amount: 'must be positive',
      occurredOn: 'cannot be in the future',
      // Non-string details are context, not field messages, and must not reach setError.
      allowed: ['a', 'b'],
    })

    expect(error.fieldErrors()).toEqual({
      amount: 'must be positive',
      occurredOn: 'cannot be in the future',
    })
  })

  it('is empty when the failure carried no details', () => {
    expect(new ApiError('NOT_FOUND', 'gone', 404).fieldErrors()).toEqual({})
  })
})

describe('request', () => {
  beforeEach(() => {
    setAccessToken(null)
    setRefreshToken(null)
    setSessionExpiredHandler(() => {})
  })

  it('unwraps the API error envelope into a typed exception', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(
      jsonResponse({ error: { code: 'DUPLICATE_RESOURCE', message: 'Already exists', details: {} } }, 409),
    ))

    await expect(api.get('/categories')).rejects.toMatchObject({
      code: 'DUPLICATE_RESOURCE',
      message: 'Already exists',
      status: 409,
    })
  })

  it('falls back to a generic error when the body is not the envelope', async () => {
    // A proxy or gateway failure never carries the API's own shape.
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('<html>502</html>', { status: 502 })))

    await expect(api.get('/categories')).rejects.toMatchObject({
      code: 'INTERNAL_ERROR',
      status: 502,
    })
  })

  it('attaches the access token when there is one', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ ok: true }))
    vi.stubGlobal('fetch', fetchMock)
    setAccessToken('the-token')

    await api.get('/transactions')

    expect(fetchMock.mock.calls[0][1].headers.Authorization).toBe('Bearer the-token')
  })

  it('returns undefined for a 204 rather than trying to parse an empty body', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 204 })))
    await expect(api.delete('/transactions/1')).resolves.toBeUndefined()
  })
})

describe('refreshSession', () => {
  beforeEach(() => {
    setAccessToken(null)
    setRefreshToken(null)
    setSessionExpiredHandler(() => {})
  })

  /**
   * The regression this file exists for.
   *
   * The API rotates refresh tokens and treats a replayed one as theft, revoking every
   * session. So two concurrent refreshes are not merely wasteful: the first succeeds, the
   * second is rejected as reuse, and that rejection kills the token the first just issued.
   * React StrictMode double-invokes effects, so the session restore fired twice on every
   * page load and signed the user out.
   */
  it('collapses concurrent callers onto a single request', async () => {
    setRefreshToken('refresh-original')

    let resolveFetch: (response: Response) => void = () => {}
    const fetchMock = vi.fn().mockImplementation(
      () => new Promise<Response>((resolve) => { resolveFetch = resolve }),
    )
    vi.stubGlobal('fetch', fetchMock)

    // Three callers, all before the first request has come back.
    const all = Promise.all([refreshSession(), refreshSession(), refreshSession()])
    resolveFetch(jsonResponse(tokenPair('rotated')))
    const results = await all

    expect(fetchMock).toHaveBeenCalledTimes(1)
    // Every caller gets the same result, so none of them retries with the spent token.
    results.forEach((result) => expect(result.accessToken).toBe('access-rotated'))
  })

  it('persists the rotated refresh token immediately', async () => {
    setRefreshToken('refresh-original')
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(tokenPair('rotated'))))

    await refreshSession()

    // The old token is already dead server-side. Failing to store the replacement would sign
    // the user out on the next reload.
    expect(getRefreshToken()).toBe('refresh-rotated')
  })

  it('allows a fresh attempt once the in-flight one settles', async () => {
    setRefreshToken('refresh-original')
    // mockImplementation, not mockResolvedValue: a Response body can only be read once,
    // so returning the same object twice fails on the second call.
    const fetchMock = vi.fn().mockImplementation(async () => jsonResponse(tokenPair('a')))
    vi.stubGlobal('fetch', fetchMock)

    await refreshSession()
    await refreshSession()

    // Deduplication must not become a permanent cache - a later expiry needs its own call.
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('clears the session and notifies when the refresh token is rejected', async () => {
    setRefreshToken('refresh-spent')
    setAccessToken('stale-access')
    const onExpired = vi.fn()
    setSessionExpiredHandler(onExpired)

    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(
      jsonResponse({ error: { code: 'TOKEN_INVALID', message: 'spent' } }, 401),
    ))

    await expect(refreshSession()).rejects.toBeInstanceOf(ApiError)

    expect(getRefreshToken()).toBeNull()
    expect(onExpired).toHaveBeenCalledOnce()
  })

  it('does not call the server when there is no refresh token', async () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)

    await expect(refreshSession()).rejects.toBeInstanceOf(ApiError)
    expect(fetchMock).not.toHaveBeenCalled()
  })
})

describe('automatic retry on 401', () => {
  beforeEach(() => {
    setAccessToken('expired')
    setRefreshToken('refresh-original')
    setSessionExpiredHandler(() => {})
  })

  it('refreshes once and replays the original request', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse({ error: { code: 'UNAUTHENTICATED', message: 'expired' } }, 401))
      .mockResolvedValueOnce(jsonResponse(tokenPair('new')))
      .mockResolvedValueOnce(jsonResponse({ data: [], meta: { total: 0 } }))
    vi.stubGlobal('fetch', fetchMock)

    const result = await api.get<{ meta: { total: number } }>('/transactions')

    expect(result.meta.total).toBe(0)
    expect(fetchMock).toHaveBeenCalledTimes(3)
    // The replay carries the new token, not the expired one it started with.
    expect(fetchMock.mock.calls[2][1].headers.Authorization).toBe('Bearer access-new')
  })

  it('gives up after one retry instead of looping', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse({ error: { code: 'UNAUTHENTICATED', message: 'x' } }, 401))
      .mockResolvedValueOnce(jsonResponse(tokenPair('new')))
      // Still 401 even with a fresh token: something else is wrong, and retrying forever
      // would hammer the server rather than surfacing it.
      .mockImplementation(async () =>
        jsonResponse({ error: { code: 'UNAUTHENTICATED', message: 'x' } }, 401))
    vi.stubGlobal('fetch', fetchMock)

    await expect(api.get('/transactions')).rejects.toBeInstanceOf(ApiError)
    expect(fetchMock).toHaveBeenCalledTimes(3)
  })

  it('never retries an auth endpoint', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse({ error: { code: 'INVALID_CREDENTIALS', message: 'wrong' } }, 401),
    )
    vi.stubGlobal('fetch', fetchMock)

    // A failed login is a wrong password, not an expired session. Refreshing and replaying
    // would submit the same bad credentials again.
    await expect(api.post('/auth/login', { email: 'a@b.com', password: 'wrong' }))
      .rejects.toMatchObject({ code: 'INVALID_CREDENTIALS' })

    expect(fetchMock).toHaveBeenCalledTimes(1)
  })
})
