import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, toQuery } from './api'
import type {
  Budget,
  Cashflow,
  Category,
  CategoryBreakdown,
  Dashboard,
  EntryType,
  Page,
  Summary,
  Transaction,
  TransactionFilters,
} from './types'

/**
 * Query keys, centralised.
 *
 * Every key is derived from this object rather than written inline, so an invalidation and
 * the query it is meant to invalidate cannot drift apart - the classic cause of a mutation
 * that succeeds while the list on screen keeps showing stale rows.
 */
export const keys = {
  categories: (type?: EntryType) => ['categories', type ?? 'all'] as const,
  transactions: (filters: TransactionFilters) => ['transactions', filters] as const,
  transaction: (id: string) => ['transactions', 'detail', id] as const,
  dashboard: () => ['dashboard'] as const,
  summary: (from?: string, to?: string) => ['analytics', 'summary', from, to] as const,
  byCategory: (from?: string, to?: string, type?: EntryType) =>
    ['analytics', 'by-category', from, to, type] as const,
  cashflow: (from?: string, to?: string) => ['analytics', 'cashflow', from, to] as const,
  budgets: () => ['budgets'] as const,
  budget: (year: number, month: number) => ['budgets', year, month] as const,
}

/**
 * Anything derived from transactions - the dashboard, every analytic, every budget's
 * progress - is stale the moment a transaction changes. Rather than have each mutation
 * remember that list, they all call this.
 */
function invalidateDerived(queryClient: ReturnType<typeof useQueryClient>) {
  queryClient.invalidateQueries({ queryKey: ['transactions'] })
  queryClient.invalidateQueries({ queryKey: ['analytics'] })
  queryClient.invalidateQueries({ queryKey: ['dashboard'] })
  queryClient.invalidateQueries({ queryKey: ['budgets'] })
}

// --- categories ---------------------------------------------------------------------

export function useCategories(type?: EntryType) {
  return useQuery({
    queryKey: keys.categories(type),
    queryFn: ({ signal }) => api.get<Category[]>(`/categories${toQuery({ type })}`, signal),
    // Categories change rarely; refetching them on every screen is wasted traffic.
    staleTime: 5 * 60 * 1000,
  })
}

export function useCreateCategory() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: { name: string; type: EntryType; color?: string; icon?: string }) =>
      api.post<Category>('/categories', body),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['categories'] })
    },
  })
}

export function useDeleteCategory() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, force }: { id: string; force?: boolean }) =>
      api.delete<void>(`/categories/${id}${toQuery({ force })}`),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['categories'] })
      invalidateDerived(queryClient)
    },
  })
}

// --- transactions -------------------------------------------------------------------

export function useTransactions(filters: TransactionFilters) {
  return useQuery({
    queryKey: keys.transactions(filters),
    queryFn: ({ signal }) =>
      api.get<Page<Transaction>>(`/transactions${toQuery({ ...filters })}`, signal),
    // Keeps the previous page visible while the next one loads, so paging does not blank
    // the table and jump the scroll position.
    placeholderData: (previous) => previous,
  })
}

export function useCreateTransaction() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: Record<string, unknown>) => api.post<Transaction>('/transactions', body),
    onSuccess: () => invalidateDerived(queryClient),
  })
}

export function useUpdateTransaction() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, body }: { id: string; body: Record<string, unknown> }) =>
      api.patch<Transaction>(`/transactions/${id}`, body),
    onSuccess: () => invalidateDerived(queryClient),
  })
}

export function useDeleteTransaction() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: string) => api.delete<void>(`/transactions/${id}`),
    onSuccess: () => invalidateDerived(queryClient),
  })
}

// --- analytics ----------------------------------------------------------------------

export function useDashboard() {
  return useQuery({
    queryKey: keys.dashboard(),
    queryFn: ({ signal }) => api.get<Dashboard>('/dashboard', signal),
  })
}

export function useSummary(from?: string, to?: string) {
  return useQuery({
    queryKey: keys.summary(from, to),
    queryFn: ({ signal }) => api.get<Summary>(`/analytics/summary${toQuery({ from, to })}`, signal),
  })
}

export function useCategoryBreakdown(from?: string, to?: string, type?: EntryType) {
  return useQuery({
    queryKey: keys.byCategory(from, to, type),
    queryFn: ({ signal }) =>
      api.get<CategoryBreakdown>(`/analytics/by-category${toQuery({ from, to, type })}`, signal),
  })
}

export function useCashflow(from?: string, to?: string) {
  return useQuery({
    queryKey: keys.cashflow(from, to),
    queryFn: ({ signal }) =>
      api.get<Cashflow>(`/analytics/cashflow${toQuery({ from, to })}`, signal),
  })
}

// --- budgets ------------------------------------------------------------------------

export function useBudget(year: number, month: number) {
  return useQuery({
    queryKey: keys.budget(year, month),
    queryFn: ({ signal }) => api.get<Budget>(`/budgets/${year}/${month}`, signal),
    // A month with no budget is a 404, which is an ordinary state here, not a failure.
    // Retrying it would delay the "create a budget" prompt for no reason.
    retry: false,
  })
}

export function useCreateBudget() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: {
      year: number
      month: number
      items?: { categoryId: string; limitAmount: number; alertThreshold?: number }[]
    }) => api.post<Budget>('/budgets', body),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['budgets'] })
      queryClient.invalidateQueries({ queryKey: ['dashboard'] })
    },
  })
}

export function useAddBudgetItem() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({
      budgetId,
      body,
    }: {
      budgetId: string
      body: { categoryId: string; limitAmount: number; alertThreshold?: number }
    }) => api.post<Budget>(`/budgets/${budgetId}/items`, body),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['budgets'] })
      queryClient.invalidateQueries({ queryKey: ['dashboard'] })
    },
  })
}

export function useUpdateBudgetItem() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({
      id,
      body,
    }: {
      id: string
      body: { limitAmount?: number; alertThreshold?: number }
    }) => api.patch(`/budget-items/${id}`, body),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['budgets'] })
      queryClient.invalidateQueries({ queryKey: ['dashboard'] })
    },
  })
}

export function useDeleteBudgetItem() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: string) => api.delete<void>(`/budget-items/${id}`),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['budgets'] })
      queryClient.invalidateQueries({ queryKey: ['dashboard'] })
    },
  })
}

/** Triggers the CSV download with whatever filters the list is currently showing. */
export function exportTransactions(filters: TransactionFilters) {
  const { page: _page, size: _size, sort: _sort, ...rest } = filters
  return api.download(`/transactions/export${toQuery(rest)}`, 'fintrack-transactions.csv')
}
