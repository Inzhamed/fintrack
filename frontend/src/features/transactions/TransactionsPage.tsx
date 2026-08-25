import { useMemo, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { Badge, Button, Card, EmptyState, ErrorNotice, Input, Select, Skeleton } from '../../components/ui'
import { useCategories, useDeleteTransaction, useTransactions, exportTransactions } from '../../lib/queries'
import { useAppDispatch } from '../../app/store'
import { toast } from '../../app/uiSlice'
import { classNames, formatDate, formatMoney } from '../../lib/format'
import type { EntryType, TransactionFilters, Transaction } from '../../lib/types'
import { TransactionFormModal } from './TransactionForm'

const PAGE_SIZE = 20

/**
 * Filters live in the URL rather than in component state, so a filtered view can be
 * bookmarked, shared, and survives a reload or the browser's back button.
 */
function useFilters(): [TransactionFilters, (next: Partial<TransactionFilters>) => void] {
  const [params, setParams] = useSearchParams()

  const filters = useMemo<TransactionFilters>(() => {
    const raw = Object.fromEntries(params.entries())
    return {
      from: raw.from || undefined,
      to: raw.to || undefined,
      type: (raw.type as EntryType) || undefined,
      categoryId: raw.categoryId || undefined,
      uncategorised: raw.uncategorised === 'true' || undefined,
      search: raw.search || undefined,
      page: raw.page ? Number(raw.page) : 0,
      size: PAGE_SIZE,
    }
  }, [params])

  function update(next: Partial<TransactionFilters>) {
    const merged = { ...filters, ...next }
    // Any change to a filter invalidates the current page number - staying on page 3 of a
    // result set that now has one page shows an empty table.
    if (!('page' in next)) merged.page = 0

    const clean = new URLSearchParams()
    for (const [key, value] of Object.entries(merged)) {
      if (value === undefined || value === '' || value === false) continue
      if (key === 'size') continue
      if (key === 'page' && value === 0) continue
      clean.set(key, String(value))
    }
    setParams(clean, { replace: true })
  }

  return [filters, update]
}

export function TransactionsPage() {
  const dispatch = useAppDispatch()
  const [filters, setFilters] = useFilters()
  const [editing, setEditing] = useState<Transaction | null>(null)
  const [formOpen, setFormOpen] = useState(false)
  const [exporting, setExporting] = useState(false)

  const { data, isPending, isError, error, refetch, isPlaceholderData } = useTransactions(filters)
  const { data: categories = [] } = useCategories()
  const deleteTransaction = useDeleteTransaction()

  async function handleExport() {
    setExporting(true)
    try {
      await exportTransactions(filters)
      dispatch(toast('Export downloaded'))
    } catch {
      dispatch(toast('Could not export. Please try again.', 'error'))
    } finally {
      setExporting(false)
    }
  }

  async function handleDelete(transaction: Transaction) {
    if (!confirm(`Delete "${transaction.description || 'this transaction'}"? This cannot be undone.`)) return
    try {
      await deleteTransaction.mutateAsync(transaction.id)
      dispatch(toast('Transaction deleted'))
    } catch {
      dispatch(toast('Could not delete the transaction.', 'error'))
    }
  }

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h1 className="text-xl font-semibold text-slate-900">Transactions</h1>
        <div className="flex gap-2">
          <Button variant="secondary" onClick={handleExport} loading={exporting}>
            Export CSV
          </Button>
          <Button
            onClick={() => {
              setEditing(null)
              setFormOpen(true)
            }}
          >
            Add transaction
          </Button>
        </div>
      </div>

      <Card>
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-5">
          <div>
            <label htmlFor="search" className="mb-1 block text-xs font-medium text-slate-600">
              Search
            </label>
            <Input
              id="search"
              // Keyed on the filter so the box resyncs when the URL changes beneath it;
              // defaultValue alone is read once at mount and then goes stale.
              key={filters.search ?? ''}
              placeholder="Description or merchant"
              defaultValue={filters.search ?? ''}
              // Committed on blur or Enter rather than on every keystroke, so typing does
              // not fire a request per character.
              onBlur={(event) => setFilters({ search: event.target.value || undefined })}
              onKeyDown={(event) => {
                if (event.key === 'Enter') setFilters({ search: event.currentTarget.value || undefined })
              }}
            />
          </div>
          <div>
            <label htmlFor="type" className="mb-1 block text-xs font-medium text-slate-600">
              Type
            </label>
            <Select
              id="type"
              value={filters.type ?? ''}
              onChange={(event) => setFilters({ type: (event.target.value as EntryType) || undefined })}
            >
              <option value="">All</option>
              <option value="EXPENSE">Expenses</option>
              <option value="INCOME">Income</option>
            </Select>
          </div>
          <div>
            <label htmlFor="category" className="mb-1 block text-xs font-medium text-slate-600">
              Category
            </label>
            <Select
              id="category"
              value={filters.uncategorised ? 'none' : filters.categoryId ?? ''}
              onChange={(event) => {
                const value = event.target.value
                // categoryId and uncategorised are mutually exclusive - the API rejects both.
                if (value === 'none') setFilters({ uncategorised: true, categoryId: undefined })
                else setFilters({ categoryId: value || undefined, uncategorised: undefined })
              }}
            >
              <option value="">All</option>
              <option value="none">Uncategorised</option>
              {categories.map((category) => (
                <option key={category.id} value={category.id}>
                  {category.name}
                </option>
              ))}
            </Select>
          </div>
          <div>
            <label htmlFor="from" className="mb-1 block text-xs font-medium text-slate-600">
              From
            </label>
            <Input
              id="from"
              type="date"
              value={filters.from ?? ''}
              onChange={(event) => setFilters({ from: event.target.value || undefined })}
            />
          </div>
          <div>
            <label htmlFor="to" className="mb-1 block text-xs font-medium text-slate-600">
              To
            </label>
            <Input
              id="to"
              type="date"
              value={filters.to ?? ''}
              onChange={(event) => setFilters({ to: event.target.value || undefined })}
            />
          </div>
        </div>
      </Card>

      {isError ? (
        <ErrorNotice
          message={error instanceof Error ? error.message : 'Could not load transactions.'}
          onRetry={refetch}
        />
      ) : isPending ? (
        <Card>
          <div className="space-y-2">
            {Array.from({ length: 6 }).map((_, index) => (
              <Skeleton key={index} className="h-10" />
            ))}
          </div>
        </Card>
      ) : data.data.length === 0 ? (
        <Card>
          <EmptyState
            title="No transactions match"
            hint="Try widening the date range or clearing the filters."
          />
        </Card>
      ) : (
        <Card className={classNames(isPlaceholderData && 'opacity-60 transition-opacity')}>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-slate-200 text-left text-xs uppercase tracking-wide text-slate-500">
                  <th scope="col" className="pb-2 pr-3 font-medium">Date</th>
                  <th scope="col" className="pb-2 pr-3 font-medium">Description</th>
                  <th scope="col" className="pb-2 pr-3 font-medium">Category</th>
                  <th scope="col" className="pb-2 pr-3 text-right font-medium">Amount</th>
                  <th scope="col" className="pb-2"><span className="sr-only">Actions</span></th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {data.data.map((transaction) => (
                  <tr key={transaction.id} className="group">
                    <td className="whitespace-nowrap py-2.5 pr-3 text-slate-600">
                      {formatDate(transaction.occurredOn)}
                    </td>
                    <td className="max-w-xs py-2.5 pr-3">
                      <p className="truncate text-slate-800">
                        {transaction.description || <span className="text-slate-400">—</span>}
                      </p>
                      {transaction.merchant && (
                        <p className="truncate text-xs text-slate-500">{transaction.merchant}</p>
                      )}
                    </td>
                    <td className="py-2.5 pr-3">
                      {transaction.category ? (
                        <span className="inline-flex items-center gap-1.5">
                          <span
                            aria-hidden="true"
                            className="size-2 rounded-full"
                            style={{ backgroundColor: transaction.category.color ?? '#cbd5e1' }}
                          />
                          <span className="text-slate-700">{transaction.category.name}</span>
                        </span>
                      ) : (
                        <Badge tone="slate">Uncategorised</Badge>
                      )}
                    </td>
                    <td
                      className={classNames(
                        'whitespace-nowrap py-2.5 pr-3 text-right font-medium tabular-nums',
                        transaction.type === 'INCOME' ? 'text-green-700' : 'text-slate-900',
                      )}
                    >
                      {transaction.type === 'INCOME' ? '+' : '−'}
                      {formatMoney(transaction.amount, transaction.currency)}
                    </td>
                    <td className="py-2.5 text-right">
                      {/* Visible on focus as well as hover, so the row is reachable by keyboard. */}
                      <div className="flex justify-end gap-1 opacity-0 transition-opacity focus-within:opacity-100 group-hover:opacity-100">
                        <button
                          onClick={() => {
                            setEditing(transaction)
                            setFormOpen(true)
                          }}
                          className="rounded px-2 py-1 text-xs font-medium text-slate-600 hover:bg-slate-100"
                        >
                          Edit
                        </button>
                        <button
                          onClick={() => handleDelete(transaction)}
                          className="rounded px-2 py-1 text-xs font-medium text-red-600 hover:bg-red-50"
                        >
                          Delete
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <footer className="mt-4 flex items-center justify-between border-t border-slate-100 pt-3 text-sm">
            <p className="text-slate-500">
              {data.meta.total} transaction{data.meta.total === 1 ? '' : 's'}
              {data.meta.totalPages > 1 && ` · page ${data.meta.page + 1} of ${data.meta.totalPages}`}
            </p>
            {data.meta.totalPages > 1 && (
              <div className="flex gap-2">
                <Button
                  variant="secondary"
                  disabled={data.meta.page === 0}
                  onClick={() => setFilters({ page: data.meta.page - 1 })}
                >
                  Previous
                </Button>
                <Button
                  variant="secondary"
                  disabled={!data.meta.hasNext}
                  onClick={() => setFilters({ page: data.meta.page + 1 })}
                >
                  Next
                </Button>
              </div>
            )}
          </footer>
        </Card>
      )}

      <TransactionFormModal
        open={formOpen}
        transaction={editing}
        onClose={() => {
          setFormOpen(false)
          setEditing(null)
        }}
      />
    </div>
  )
}
