import { NavLink, Navigate, Outlet, useLocation } from 'react-router-dom'
import { useEffect } from 'react'
import { useAppDispatch, useAppSelector } from './store'
import { logout } from '../features/auth/authSlice'
import { dismissToast } from './uiSlice'
import { classNames } from '../lib/format'
import { Skeleton } from '../components/ui'

/**
 * Guards everything behind it.
 *
 * The 'restoring' state matters: on a hard refresh the app holds a refresh token but does
 * not yet know whether it is still valid. Rendering the login page during that window would
 * flash it at a user who is in fact signed in, so the shell waits instead.
 */
export function RequireAuth() {
  const status = useAppSelector((state) => state.auth.status)
  const location = useLocation()

  if (status === 'restoring') {
    return (
      <div className="mx-auto max-w-6xl space-y-4 p-6">
        <Skeleton className="h-8 w-48" />
        <div className="grid gap-4 sm:grid-cols-3">
          <Skeleton className="h-28" />
          <Skeleton className="h-28" />
          <Skeleton className="h-28" />
        </div>
        <Skeleton className="h-64" />
      </div>
    )
  }

  if (status === 'anonymous') {
    // Remember where they were headed, so signing in returns them there.
    return <Navigate to="/login" replace state={{ from: location.pathname }} />
  }

  return <AppShell />
}

const navItems = [
  { to: '/', label: 'Dashboard', end: true },
  { to: '/transactions', label: 'Transactions', end: false },
  { to: '/budget', label: 'Budget', end: false },
]

function AppShell() {
  const dispatch = useAppDispatch()
  const user = useAppSelector((state) => state.auth.user)

  return (
    <div className="min-h-screen">
      <header className="border-b border-slate-200 bg-white">
        <div className="mx-auto flex max-w-6xl items-center justify-between gap-4 px-4 py-3">
          <div className="flex items-center gap-6">
            <span className="text-lg font-bold text-slate-900">FinTrack</span>
            <nav className="flex gap-1">
              {navItems.map((item) => (
                <NavLink
                  key={item.to}
                  to={item.to}
                  end={item.end}
                  className={({ isActive }) =>
                    classNames(
                      'rounded-lg px-3 py-1.5 text-sm font-medium transition-colors',
                      isActive ? 'bg-brand-50 text-brand-700' : 'text-slate-600 hover:bg-slate-100',
                    )
                  }
                >
                  {item.label}
                </NavLink>
              ))}
            </nav>
          </div>
          <div className="flex items-center gap-3">
            <span className="hidden text-sm text-slate-500 sm:inline">{user?.name}</span>
            <button
              onClick={() => dispatch(logout())}
              className="rounded-lg px-3 py-1.5 text-sm font-medium text-slate-600 hover:bg-slate-100"
            >
              Sign out
            </button>
          </div>
        </div>
      </header>

      <main className="mx-auto max-w-6xl p-4 sm:p-6">
        <Outlet />
      </main>

      <Toasts />
    </div>
  )
}

/** Non-blocking feedback, stacked bottom-right, each dismissing itself after a few seconds. */
function Toasts() {
  const toasts = useAppSelector((state) => state.ui.toasts)
  const dispatch = useAppDispatch()

  useEffect(() => {
    if (toasts.length === 0) return
    const timers = toasts.map((item) =>
      setTimeout(() => dispatch(dismissToast(item.id)), 4000),
    )
    // Clearing on unmount stops a dismissal firing against a store that has moved on.
    return () => timers.forEach(clearTimeout)
  }, [toasts, dispatch])

  if (toasts.length === 0) return null

  const tones = {
    success: 'bg-green-600',
    error: 'bg-red-600',
    info: 'bg-slate-800',
  }

  return (
    <div className="fixed bottom-4 right-4 z-50 flex flex-col gap-2" aria-live="polite">
      {toasts.map((item) => (
        <button
          key={item.id}
          onClick={() => dispatch(dismissToast(item.id))}
          className={classNames(
            'rounded-lg px-4 py-2 text-left text-sm text-white shadow-lg',
            tones[item.tone],
          )}
        >
          {item.message}
        </button>
      ))}
    </div>
  )
}
