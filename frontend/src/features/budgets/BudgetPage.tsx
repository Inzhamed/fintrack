import { useState } from 'react'
import { Badge, Button, Card, EmptyState, ErrorNotice, Field, Input, Modal, Select, Skeleton } from '../../components/ui'
import {
  useAddBudgetItem,
  useBudget,
  useCategories,
  useCreateBudget,
  useDeleteBudgetItem,
  useUpdateBudgetItem,
} from '../../lib/queries'
import { useAppDispatch } from '../../app/store'
import { toast } from '../../app/uiSlice'
import { ApiError } from '../../lib/api'
import { classNames, formatMoney, formatPercent } from '../../lib/format'
import type { Budget, BudgetItem, BudgetStatus } from '../../lib/types'

const statusTone: Record<BudgetStatus, 'green' | 'amber' | 'red'> = {
  ON_TRACK: 'green',
  WARNING: 'amber',
  EXCEEDED: 'red',
}

const statusLabel: Record<BudgetStatus, string> = {
  ON_TRACK: 'On track',
  WARNING: 'Close to limit',
  EXCEEDED: 'Over budget',
}

export function BudgetPage() {
  const now = new Date()
  const [year, setYear] = useState(now.getFullYear())
  const [month, setMonth] = useState(now.getMonth() + 1)
  const [addOpen, setAddOpen] = useState(false)

  const { data, isPending, isError, error, refetch } = useBudget(year, month)
  const createBudget = useCreateBudget()
  const dispatch = useAppDispatch()

  // A month with no budget is a 404 from the API, which is an ordinary state here rather
  // than a failure - it is the prompt to create one.
  const notFound = isError && error instanceof ApiError && error.code === 'NOT_FOUND'

  function shiftMonth(delta: number) {
    const next = new Date(year, month - 1 + delta, 1)
    setYear(next.getFullYear())
    setMonth(next.getMonth() + 1)
  }

  async function handleCreate() {
    try {
      await createBudget.mutateAsync({ year, month })
      dispatch(toast('Budget created. Add some category limits.'))
    } catch (err) {
      dispatch(toast(err instanceof ApiError ? err.message : 'Could not create the budget.', 'error'))
    }
  }

  const periodLabel = new Date(year, month - 1, 1).toLocaleDateString(undefined, {
    month: 'long',
    year: 'numeric',
  })

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h1 className="text-xl font-semibold text-slate-900">Budget</h1>
        <div className="flex items-center gap-2">
          <Button variant="secondary" onClick={() => shiftMonth(-1)} aria-label="Previous month">
            ‹
          </Button>
          <span className="min-w-40 text-center text-sm font-medium text-slate-700">{periodLabel}</span>
          <Button variant="secondary" onClick={() => shiftMonth(1)} aria-label="Next month">
            ›
          </Button>
        </div>
      </div>

      {isPending ? (
        <Card>
          <div className="space-y-3">
            <Skeleton className="h-16" />
            <Skeleton className="h-16" />
            <Skeleton className="h-16" />
          </div>
        </Card>
      ) : notFound ? (
        <Card>
          <EmptyState
            title={`No budget set for ${periodLabel}`}
            hint="Create one, then set a limit per category. FinTrack warns you at 80% by default."
            action={
              <Button onClick={handleCreate} loading={createBudget.isPending}>
                Create budget
              </Button>
            }
          />
        </Card>
      ) : isError ? (
        <ErrorNotice message={error instanceof Error ? error.message : 'Could not load the budget.'} onRetry={refetch} />
      ) : (
        <>
          <BudgetTotals budget={data} />

          <Card
            title="Category limits"
            action={<Button onClick={() => setAddOpen(true)}>Add a limit</Button>}
          >
            {data.items.length === 0 ? (
              <EmptyState
                title="No limits yet"
                hint="Add a category limit to start tracking against it."
              />
            ) : (
              <ul className="space-y-4">
                {data.items.map((item) => (
                  <BudgetItemRow key={item.id} item={item} currency={data.currency} />
                ))}
              </ul>
            )}
          </Card>

          <AddLimitModal
            open={addOpen}
            budget={data}
            onClose={() => setAddOpen(false)}
          />
        </>
      )}
    </div>
  )
}

function BudgetTotals({ budget }: { budget: Budget }) {
  const { totals, currency } = budget
  const untracked = totals.uncategorisedSpend + totals.unbudgetedSpend

  return (
    <div className="grid gap-4 sm:grid-cols-3">
      <Card>
        <p className="text-xs font-medium uppercase tracking-wide text-slate-500">Budgeted</p>
        <p className="mt-1 text-2xl font-semibold tabular-nums text-slate-900">
          {formatMoney(totals.totalLimit, currency)}
        </p>
        <p className="mt-1 text-xs text-slate-500">
          {formatMoney(totals.totalSpent, currency)} spent · {formatPercent(totals.percentUsed)}
        </p>
      </Card>
      <Card>
        <p className="text-xs font-medium uppercase tracking-wide text-slate-500">Remaining</p>
        <p
          className={classNames(
            'mt-1 text-2xl font-semibold tabular-nums',
            totals.totalRemaining >= 0 ? 'text-green-700' : 'text-red-700',
          )}
        >
          {formatMoney(totals.totalRemaining, currency)}
        </p>
        <p className="mt-1 text-xs text-slate-500">
          {totals.itemsExceeded} over · {totals.itemsWarning} near the limit
        </p>
      </Card>
      <Card>
        <p className="text-xs font-medium uppercase tracking-wide text-slate-500">Not tracked</p>
        <p className="mt-1 text-2xl font-semibold tabular-nums text-slate-900">
          {formatMoney(untracked, currency)}
        </p>
        {/* Surfaced rather than hidden: every limit can read healthy while this leaks. */}
        <p className="mt-1 text-xs text-slate-500">
          {formatMoney(totals.uncategorisedSpend, currency)} uncategorised ·{' '}
          {formatMoney(totals.unbudgetedSpend, currency)} unbudgeted
        </p>
      </Card>
    </div>
  )
}

function BudgetItemRow({ item, currency }: { item: BudgetItem; currency: string }) {
  const dispatch = useAppDispatch()
  const updateItem = useUpdateBudgetItem()
  const deleteItem = useDeleteBudgetItem()
  const [editing, setEditing] = useState(false)
  const [limit, setLimit] = useState(String(item.limitAmount))

  const barColor =
    item.status === 'EXCEEDED' ? 'bg-red-500' : item.status === 'WARNING' ? 'bg-amber-500' : 'bg-green-500'

  async function save() {
    const value = Number(limit)
    if (!(value > 0)) {
      dispatch(toast('Limit must be greater than zero.', 'error'))
      return
    }
    try {
      await updateItem.mutateAsync({ id: item.id, body: { limitAmount: value } })
      dispatch(toast('Limit updated'))
      setEditing(false)
    } catch {
      dispatch(toast('Could not update the limit.', 'error'))
    }
  }

  async function remove() {
    if (!confirm(`Remove the limit for ${item.category.name}? Its transactions are kept.`)) return
    try {
      await deleteItem.mutateAsync(item.id)
      dispatch(toast('Limit removed'))
    } catch {
      dispatch(toast('Could not remove the limit.', 'error'))
    }
  }

  return (
    <li>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <span className="flex items-center gap-2">
          <span
            aria-hidden="true"
            className="size-2.5 rounded-full"
            style={{ backgroundColor: item.category.color ?? '#cbd5e1' }}
          />
          <span className="text-sm font-medium text-slate-800">{item.category.name}</span>
          <Badge tone={statusTone[item.status]}>{statusLabel[item.status]}</Badge>
        </span>

        <span className="flex items-center gap-2 text-sm tabular-nums text-slate-600">
          {editing ? (
            <>
              <Input
                value={limit}
                onChange={(event) => setLimit(event.target.value)}
                inputMode="decimal"
                className="w-28"
                aria-label={`Limit for ${item.category.name}`}
              />
              <Button onClick={save} loading={updateItem.isPending}>Save</Button>
              <Button variant="ghost" onClick={() => { setEditing(false); setLimit(String(item.limitAmount)) }}>
                Cancel
              </Button>
            </>
          ) : (
            <>
              <span>
                {formatMoney(item.spent, currency)} of {formatMoney(item.limitAmount, currency)}
              </span>
              <Button variant="ghost" onClick={() => setEditing(true)}>Edit</Button>
              <Button variant="ghost" onClick={remove} className="text-red-600 hover:bg-red-50">
                Remove
              </Button>
            </>
          )}
        </span>
      </div>

      <div className="mt-2 flex items-center gap-3">
        <div
          className="h-2 flex-1 overflow-hidden rounded-full bg-slate-200"
          role="progressbar"
          aria-valuenow={Math.min(item.percentUsed, 100)}
          aria-valuemin={0}
          aria-valuemax={100}
          aria-label={`${item.category.name} budget used`}
        >
          {/* Capped at 100% so an overspend does not overflow its track; the badge and the
              negative remaining figure carry the "how far over" information. */}
          <div
            className={classNames('h-full rounded-full transition-all', barColor)}
            style={{ width: `${Math.min(item.percentUsed, 100)}%` }}
          />
        </div>
        <span className="w-14 text-right text-xs tabular-nums text-slate-500">
          {formatPercent(item.percentUsed)}
        </span>
        <span
          className={classNames(
            'w-28 text-right text-xs tabular-nums',
            item.remaining < 0 ? 'text-red-600' : 'text-slate-500',
          )}
        >
          {item.remaining < 0 ? 'over by ' : 'left '}
          {formatMoney(Math.abs(item.remaining), currency)}
        </span>
      </div>
    </li>
  )
}

function AddLimitModal({ open, budget, onClose }: { open: boolean; budget: Budget; onClose: () => void }) {
  const dispatch = useAppDispatch()
  const addItem = useAddBudgetItem()
  // Only expense categories can be budgeted - the API rejects income ones outright.
  const { data: categories = [] } = useCategories('EXPENSE')
  const [categoryId, setCategoryId] = useState('')
  const [limit, setLimit] = useState('')

  const alreadyBudgeted = new Set(budget.items.map((item) => item.category.id))
  const available = categories.filter((category) => !alreadyBudgeted.has(category.id))

  async function submit(event: React.FormEvent) {
    event.preventDefault()
    const value = Number(limit)
    if (!categoryId) return dispatch(toast('Pick a category.', 'error'))
    if (!(value > 0)) return dispatch(toast('Limit must be greater than zero.', 'error'))

    try {
      await addItem.mutateAsync({ budgetId: budget.id, body: { categoryId, limitAmount: value } })
      dispatch(toast('Limit added'))
      setCategoryId('')
      setLimit('')
      onClose()
    } catch (error) {
      dispatch(toast(error instanceof ApiError ? error.message : 'Could not add the limit.', 'error'))
    }
  }

  return (
    <Modal open={open} title="Add a category limit" onClose={onClose}>
      {available.length === 0 ? (
        <EmptyState title="Every expense category already has a limit" />
      ) : (
        <form onSubmit={submit} className="space-y-4">
          <Field label="Category" htmlFor="budget-category">
            <Select id="budget-category" value={categoryId} onChange={(event) => setCategoryId(event.target.value)}>
              <option value="">Choose a category</option>
              {available.map((category) => (
                <option key={category.id} value={category.id}>
                  {category.name}
                </option>
              ))}
            </Select>
          </Field>
          <Field label="Monthly limit" htmlFor="budget-limit" hint="You will be warned at 80% of this.">
            <Input
              id="budget-limit"
              inputMode="decimal"
              placeholder="0.00"
              value={limit}
              onChange={(event) => setLimit(event.target.value)}
            />
          </Field>
          <div className="flex justify-end gap-2 pt-2">
            <Button type="button" variant="secondary" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit" loading={addItem.isPending}>
              Add limit
            </Button>
          </div>
        </form>
      )}
    </Modal>
  )
}
