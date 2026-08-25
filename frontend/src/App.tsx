import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { Suspense, lazy, useEffect } from 'react'
import { Provider } from 'react-redux'
import { BrowserRouter, Route, Routes } from 'react-router-dom'
import { store, useAppDispatch } from './app/store'
import { RequireAuth } from './app/Layout'
import { LoginPage, RegisterPage } from './features/auth/AuthPages'
import { restoreSession, sessionExpired } from './features/auth/authSlice'
import { TransactionsPage } from './features/transactions/TransactionsPage'
import { BudgetPage } from './features/budgets/BudgetPage'
import { Skeleton } from './components/ui'

// The dashboard is the only screen that charts anything, and Recharts is roughly half
// the bundle. Splitting it out keeps that weight off the login screen, which is the one
// page every visitor loads and the one that needs none of it.
const DashboardPage = lazy(() =>
  import('./features/dashboard/DashboardPage').then((m) => ({ default: m.DashboardPage })),
)
import { ApiError, setSessionExpiredHandler } from './lib/api'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // The API layer already refreshes an expired token and retries once. Retrying a 4xx
      // on top of that would just repeat a request the server has definitively refused.
      retry: (failureCount, error) =>
        !(error instanceof ApiError && error.status < 500) && failureCount < 2,
      staleTime: 30_000,
      refetchOnWindowFocus: false,
    },
  },
})

/** Bridges the api module's session-expiry callback into Redux, and restores the session
 *  once on mount. Both need a dispatch, so they live inside the Provider. */
function SessionBridge({ children }: { children: React.ReactNode }) {
  const dispatch = useAppDispatch()

  useEffect(() => {
    setSessionExpiredHandler(() => {
      dispatch(sessionExpired())
      // Cached data belongs to the user who just lost their session; dropping it stops the
      // next person signing in on this browser from seeing a flash of someone else's data.
      queryClient.clear()
    })
    dispatch(restoreSession())
  }, [dispatch])

  return <>{children}</>
}

export default function App() {
  return (
    <Provider store={store}>
      <QueryClientProvider client={queryClient}>
        <BrowserRouter>
          <SessionBridge>
            <Routes>
              <Route path="/login" element={<LoginPage />} />
              <Route path="/register" element={<RegisterPage />} />
              <Route element={<RequireAuth />}>
                <Route
                  index
                  element={
                    <Suspense fallback={<DashboardFallback />}>
                      <DashboardPage />
                    </Suspense>
                  }
                />
                <Route path="/transactions" element={<TransactionsPage />} />
                <Route path="/budget" element={<BudgetPage />} />
              </Route>
              <Route path="*" element={<NotFound />} />
            </Routes>
          </SessionBridge>
        </BrowserRouter>
      </QueryClientProvider>
    </Provider>
  )
}

/** Matches the dashboard's own skeleton, so the chunk loading is invisible. */
function DashboardFallback() {
  return (
    <div className="space-y-6">
      <Skeleton className="h-8 w-40" />
      <div className="grid gap-4 sm:grid-cols-3">
        <Skeleton className="h-28" />
        <Skeleton className="h-28" />
        <Skeleton className="h-28" />
      </div>
      <div className="grid gap-4 lg:grid-cols-2">
        <Skeleton className="h-80" />
        <Skeleton className="h-80" />
      </div>
    </div>
  )
}

function NotFound() {
  return (
    <div className="flex min-h-screen flex-col items-center justify-center gap-2 text-center">
      <p className="text-2xl font-semibold text-slate-800">Page not found</p>
      <a href="/" className="text-sm font-medium text-brand-600 hover:underline">
        Back to the dashboard
      </a>
    </div>
  )
}
