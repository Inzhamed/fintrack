import { createAsyncThunk, createSlice, type PayloadAction } from '@reduxjs/toolkit'
import {
  api,
  setAccessToken,
  setRefreshToken,
  getRefreshToken,
  refreshSession,
  ApiError,
} from '../../lib/api'
import type { TokenResponse, User } from '../../lib/types'

/**
 * Session state.
 *
 * Redux holds who the user is; the tokens themselves live in the api module, which is what
 * actually attaches and refreshes them. Duplicating the access token into the store would
 * mean two places to keep in sync, and would put a credential into the Redux devtools.
 */
interface AuthState {
  user: User | null
  /** 'restoring' covers the first paint, before we know whether a stored session is valid. */
  status: 'restoring' | 'anonymous' | 'authenticated'
  error: string | null
}

const initialState: AuthState = {
  user: null,
  status: getRefreshToken() ? 'restoring' : 'anonymous',
  error: null,
}

function applyTokens(tokens: TokenResponse) {
  setAccessToken(tokens.accessToken)
  setRefreshToken(tokens.refreshToken)
}

export const login = createAsyncThunk<
  User,
  { email: string; password: string },
  { rejectValue: string }
>('auth/login', async (credentials, { rejectWithValue }) => {
  try {
    const tokens = await api.post<TokenResponse>('/auth/login', credentials)
    applyTokens(tokens)
    return tokens.user
  } catch (error) {
    return rejectWithValue(
      error instanceof ApiError ? error.message : 'Could not sign in. Please try again.',
    )
  }
})

export const register = createAsyncThunk<
  User,
  { email: string; password: string; name: string },
  { rejectValue: string }
>('auth/register', async (details, { rejectWithValue }) => {
  try {
    const tokens = await api.post<TokenResponse>('/auth/register', details)
    applyTokens(tokens)
    return tokens.user
  } catch (error) {
    return rejectWithValue(
      error instanceof ApiError ? error.message : 'Could not create the account.',
    )
  }
})

/**
 * Re-establishes a session on page load from the stored refresh token.
 *
 * Runs before the first authenticated render so the app does not flash the login screen at
 * a user who is in fact signed in.
 */
export const restoreSession = createAsyncThunk<User | null>('auth/restore', async () => {
  if (!getRefreshToken()) return null
  try {
    // Goes through refreshSession, not a raw POST: StrictMode invokes this effect twice, and
    // two calls with the same token would be read as reuse and revoke the whole session.
    // refreshSession collapses concurrent callers onto one request.
    const tokens = await refreshSession()
    return tokens.user
  } catch {
    // Expired, revoked, or the server has forgotten it. Start clean.
    setAccessToken(null)
    setRefreshToken(null)
    return null
  }
})

export const logout = createAsyncThunk('auth/logout', async () => {
  const refreshToken = getRefreshToken()
  // Best effort: tell the server to revoke the session, but never block sign-out on it.
  // A user who clicks "sign out" must end up signed out even if the request fails.
  if (refreshToken) {
    try {
      await api.post('/auth/logout', { refreshToken })
    } catch {
      /* ignored on purpose */
    }
  }
  setAccessToken(null)
  setRefreshToken(null)
})

const authSlice = createSlice({
  name: 'auth',
  initialState,
  reducers: {
    /** Called by the api layer when a refresh fails mid-session. */
    sessionExpired(state) {
      state.user = null
      state.status = 'anonymous'
      state.error = 'Your session expired. Please sign in again.'
    },
    clearError(state) {
      state.error = null
    },
    setUser(state, action: PayloadAction<User>) {
      state.user = action.payload
    },
  },
  extraReducers: (builder) => {
    builder
      .addCase(restoreSession.fulfilled, (state, action) => {
        state.user = action.payload
        state.status = action.payload ? 'authenticated' : 'anonymous'
      })
      .addCase(restoreSession.rejected, (state) => {
        state.status = 'anonymous'
      })
      .addCase(logout.fulfilled, (state) => {
        state.user = null
        state.status = 'anonymous'
        state.error = null
      })
      // login and register both end in the same place, so they share their handlers.
      .addMatcher(
        (action) => action.type.endsWith('/pending') && action.type.startsWith('auth/'),
        (state) => {
          state.error = null
        },
      )
      .addMatcher(
        (action) =>
          (action.type === login.fulfilled.type || action.type === register.fulfilled.type),
        (state, action: PayloadAction<User>) => {
          state.user = action.payload
          state.status = 'authenticated'
          state.error = null
        },
      )
      .addMatcher(
        (action) =>
          (action.type === login.rejected.type || action.type === register.rejected.type),
        (state, action: PayloadAction<string | undefined>) => {
          state.status = 'anonymous'
          state.error = action.payload ?? 'Something went wrong.'
        },
      )
  },
})

export const { sessionExpired, clearError, setUser } = authSlice.actions
export default authSlice.reducer
