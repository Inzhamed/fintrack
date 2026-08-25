/** Mirrors the API's DTOs. Kept hand-written and small rather than generated from OpenAPI,
 *  so the shapes the UI actually consumes stay obvious at a glance. */

export type EntryType = 'EXPENSE' | 'INCOME'
export type BudgetStatus = 'ON_TRACK' | 'WARNING' | 'EXCEEDED'

/** Machine-readable codes from the API's single error envelope. Branch on these, never on
 *  the message, which is free to change or be translated. */
export type ErrorCode =
  | 'VALIDATION_FAILED'
  | 'MALFORMED_REQUEST'
  | 'INVALID_CREDENTIALS'
  | 'UNAUTHENTICATED'
  | 'TOKEN_EXPIRED'
  | 'TOKEN_INVALID'
  | 'FORBIDDEN'
  | 'NOT_FOUND'
  | 'EMAIL_ALREADY_REGISTERED'
  | 'DUPLICATE_RESOURCE'
  | 'CONFLICT'
  | 'INTERNAL_ERROR'

export interface ApiErrorBody {
  error: {
    code: ErrorCode
    message: string
    /** Field-level messages, keyed by field name, on validation failures. */
    details?: Record<string, unknown>
    path: string
    timestamp: string
  }
}

export interface User {
  id: string
  email: string
  name: string
  role: string
  baseCurrency: string
  createdAt?: string
}

export interface TokenResponse {
  accessToken: string
  refreshToken: string
  tokenType: string
  expiresIn: number
  user: User
}

export interface Category {
  id: string
  name: string
  type: EntryType
  color?: string
  icon?: string
  /** True for the seeded defaults, which are read-only. */
  global: boolean
}

export interface Transaction {
  id: string
  type: EntryType
  amount: number
  currency: string
  description?: string
  merchant?: string
  occurredOn: string
  category?: { id: string; name: string; color?: string; icon?: string }
  createdAt?: string
  updatedAt?: string
}

export interface Page<T> {
  data: T[]
  meta: { page: number; size: number; total: number; totalPages: number; hasNext: boolean }
}

export interface BudgetItem {
  id: string
  category: { id: string; name: string; color?: string; icon?: string }
  limitAmount: number
  alertThreshold: number
  spent: number
  remaining: number
  percentUsed: number
  status: BudgetStatus
}

export interface Budget {
  id: string
  year: number
  month: number
  currency: string
  periodStart: string
  periodEnd: string
  items: BudgetItem[]
  totals: {
    totalLimit: number
    totalSpent: number
    totalRemaining: number
    percentUsed: number
    uncategorisedSpend: number
    unbudgetedSpend: number
    totalIncome: number
    itemsWarning: number
    itemsExceeded: number
  }
}

export interface Summary {
  from: string
  to: string
  currency: string
  totalIncome: number
  totalExpense: number
  balance: number
  /** Absent when the period had no income - "kept 0% of nothing" is not a real figure. */
  savingsRate?: number
  incomeCount: number
  expenseCount: number
  averageDailyExpense: number
}

export interface CategoryBreakdown {
  from: string
  to: string
  type: EntryType
  total: number
  slices: {
    /** Absent on the "Uncategorised" slice. */
    categoryId?: string
    categoryName: string
    color?: string
    amount: number
    share: number
    transactionCount: number
  }[]
}

export interface Cashflow {
  from: string
  to: string
  currency: string
  points: {
    label: string
    year: number
    month: number
    income: number
    expense: number
    net: number
  }[]
  netTotal: number
}

export interface Dashboard {
  currentMonth: Summary
  previousMonth: Summary
  topExpenseCategories: CategoryBreakdown
  cashflow: Cashflow
  recentTransactions: Transaction[]
  alerts: BudgetItem[]
  budgetSet: boolean
}

export interface TransactionFilters {
  from?: string
  to?: string
  type?: EntryType
  categoryId?: string
  uncategorised?: boolean
  minAmount?: number
  maxAmount?: number
  search?: string
  page?: number
  size?: number
  sort?: string
}
