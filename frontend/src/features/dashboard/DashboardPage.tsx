import { Link } from 'react-router-dom'
import {
  Bar,
  BarChart,
  Cell,
  Legend,
  Pie,
  PieChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import { Badge, Card, EmptyState, ErrorNotice, Skeleton } from '../../components/ui'
import { useDashboard } from '../../lib/queries'
import { classNames, formatMonthLabel, formatMoney, formatPercent } from '../../lib/format'
import type { BudgetItem, Summary } from '../../lib/types'

export function DashboardPage() {
  const { data, isPending, isError, error, refetch } = useDashboard()

  if (isPending) return <DashboardSkeleton />
  if (isError) {
    return <ErrorNotice message={error instanceof Error ? error.message : 'Could not load the dashboard.'} onRetry={refetch} />
  }

  const { currentMonth, previousMonth, topExpenseCategories, cashflow, recentTransactions, alerts, budgetSet } = data
  const currency = currentMonth.currency

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold text-slate-900">This month</h1>
        <p className="text-sm text-slate-500">
          {new Date(`${currentMonth.from}T00:00:00`).toLocaleDateString(undefined, {
            month: 'long',
            year: 'numeric',
          })}
        </p>
      </div>

      <div className="grid gap-4 sm:grid-cols-3">
        <StatTile
          label="Income"
          value={formatMoney(currentMonth.totalIncome, currency)}
          previous={previousMonth.totalIncome}
          current={currentMonth.totalIncome}
          // More income is good news, so a rise is green.
          higherIsBetter
        />
        <StatTile
          label="Expenses"
          value={formatMoney(currentMonth.totalExpense, currency)}
          previous={previousMonth.totalExpense}
          current={currentMonth.totalExpense}
          higherIsBetter={false}
        />
        <BalanceTile summary={currentMonth} />
      </div>

      {alerts.length > 0 && (
        <Card title="Budget alerts" action={<Link to="/budget" className="text-xs font-medium text-brand-600 hover:underline">Manage budget</Link>}>
          <ul className="space-y-2">
            {alerts.map((item) => (
              <AlertRow key={item.id} item={item} currency={currency} />
            ))}
          </ul>
        </Card>
      )}

      <div className="grid gap-4 lg:grid-cols-2">
        <Card title="Income vs expenses">
          {cashflow.points.every((point) => point.income === 0 && point.expense === 0) ? (
            <EmptyState title="Nothing to chart yet" hint="Add a few transactions and the trend appears here." />
          ) : (
            <div className="h-64">
              <ResponsiveContainer width="100%" height="100%">
                <BarChart data={cashflow.points} margin={{ top: 8, right: 8, bottom: 0, left: 8 }}>
                  <XAxis
                    dataKey="label"
                    tickFormatter={formatMonthLabel}
                    tick={{ fontSize: 12, fill: '#64748b' }}
                    axisLine={false}
                    tickLine={false}
                  />
                  <YAxis
                    tick={{ fontSize: 12, fill: '#64748b' }}
                    axisLine={false}
                    tickLine={false}
                    width={70}
                    // Compact ticks, or six-figure dinar amounts crowd the axis out.
                    tickFormatter={(value: number) =>
                      new Intl.NumberFormat(undefined, { notation: 'compact' }).format(value)
                    }
                  />
                  <Tooltip
                    formatter={(value) => formatMoney(Number(value), currency)}
                    labelFormatter={(label) => formatMonthLabel(String(label))}
                    contentStyle={{ fontSize: 12, borderRadius: 8 }}
                  />
                  <Legend wrapperStyle={{ fontSize: 12 }} />
                  <Bar dataKey="income" name="Income" fill="#16a34a" radius={[4, 4, 0, 0]} />
                  <Bar dataKey="expense" name="Expenses" fill="#dc2626" radius={[4, 4, 0, 0]} />
                </BarChart>
              </ResponsiveContainer>
            </div>
          )}
        </Card>

        <Card title="Where it went">
          {topExpenseCategories.slices.length === 0 ? (
            <EmptyState title="No spending recorded this month" />
          ) : (
            <div className="flex flex-col gap-4 sm:flex-row sm:items-center">
              <div className="h-48 w-full sm:w-1/2">
                <ResponsiveContainer width="100%" height="100%">
                  <PieChart>
                    <Pie
                      data={topExpenseCategories.slices}
                      dataKey="amount"
                      nameKey="categoryName"
                      innerRadius="55%"
                      outerRadius="85%"
                      paddingAngle={2}
                    >
                      {topExpenseCategories.slices.map((slice) => (
                        <Cell
                          key={slice.categoryId ?? 'uncategorised'}
                          fill={slice.color ?? '#94a3b8'}
                        />
                      ))}
                    </Pie>
                    <Tooltip
                      formatter={(value) => formatMoney(Number(value), currency)}
                      contentStyle={{ fontSize: 12, borderRadius: 8 }}
                    />
                  </PieChart>
                </ResponsiveContainer>
              </div>
              <ul className="flex-1 space-y-2">
                {topExpenseCategories.slices.slice(0, 6).map((slice) => (
                  <li key={slice.categoryId ?? 'uncategorised'} className="flex items-center gap-2 text-sm">
                    <span
                      aria-hidden="true"
                      className="size-2.5 shrink-0 rounded-full"
                      style={{ backgroundColor: slice.color ?? '#94a3b8' }}
                    />
                    <span className="flex-1 truncate text-slate-700">{slice.categoryName}</span>
                    <span className="tabular-nums text-slate-500">{formatPercent(slice.share)}</span>
                    <span className="w-24 text-right tabular-nums text-slate-900">
                      {formatMoney(slice.amount, currency)}
                    </span>
                  </li>
                ))}
              </ul>
            </div>
          )}
        </Card>
      </div>

      <Card
        title="Recent activity"
        action={
          <Link to="/transactions" className="text-xs font-medium text-brand-600 hover:underline">
            View all
          </Link>
        }
      >
        {recentTransactions.length === 0 ? (
          <EmptyState
            title="No transactions yet"
            hint="Record your first income or expense to get started."
            action={
              <Link to="/transactions" className="text-sm font-medium text-brand-600 hover:underline">
                Add a transaction
              </Link>
            }
          />
        ) : (
          <ul className="divide-y divide-slate-100">
            {recentTransactions.map((transaction) => (
              <li key={transaction.id} className="flex items-center gap-3 py-2.5">
                <span
                  aria-hidden="true"
                  className="size-2.5 shrink-0 rounded-full"
                  style={{ backgroundColor: transaction.category?.color ?? '#cbd5e1' }}
                />
                <div className="min-w-0 flex-1">
                  <p className="truncate text-sm text-slate-800">
                    {transaction.description || transaction.merchant || 'Untitled'}
                  </p>
                  <p className="text-xs text-slate-500">
                    {transaction.category?.name ?? 'Uncategorised'} · {transaction.occurredOn}
                  </p>
                </div>
                <span
                  className={classNames(
                    'shrink-0 text-sm font-medium tabular-nums',
                    transaction.type === 'INCOME' ? 'text-green-700' : 'text-slate-900',
                  )}
                >
                  {transaction.type === 'INCOME' ? '+' : '−'}
                  {formatMoney(transaction.amount, transaction.currency)}
                </span>
              </li>
            ))}
          </ul>
        )}
      </Card>

      {!budgetSet && (
        <Card>
          <EmptyState
            title="No budget set for this month"
            hint="Set per-category limits and FinTrack will warn you before you go over."
            action={
              <Link to="/budget" className="text-sm font-medium text-brand-600 hover:underline">
                Set a budget
              </Link>
            }
          />
        </Card>
      )}
    </div>
  )
}

function StatTile({
  label,
  value,
  current,
  previous,
  higherIsBetter,
}: {
  label: string
  value: string
  current: number
  previous: number
  higherIsBetter: boolean
}) {
  // A change against a zero baseline has no meaningful percentage, and a movement that
  // rounds to 0.0% is not a movement - showing an arrow for either would assert something
  // untrue about the month.
  const raw = previous === 0 ? null : ((current - previous) / previous) * 100
  const change = raw === null || Math.abs(raw) < 0.05 ? null : raw
  const good = change === null ? null : higherIsBetter ? change >= 0 : change <= 0

  return (
    <Card>
      <p className="text-xs font-medium uppercase tracking-wide text-slate-500">{label}</p>
      <p className="mt-1 text-2xl font-semibold tabular-nums text-slate-900">{value}</p>
      {change !== null && (
        <p className={classNames('mt-1 text-xs', good ? 'text-green-600' : 'text-red-600')}>
          {change >= 0 ? '▲' : '▼'} {Math.abs(change).toFixed(1)}% vs last month
        </p>
      )}
    </Card>
  )
}

function BalanceTile({ summary }: { summary: Summary }) {
  const positive = summary.balance >= 0
  return (
    <Card>
      <p className="text-xs font-medium uppercase tracking-wide text-slate-500">Balance</p>
      <p
        className={classNames(
          'mt-1 text-2xl font-semibold tabular-nums',
          positive ? 'text-green-700' : 'text-red-700',
        )}
      >
        {formatMoney(summary.balance, summary.currency)}
      </p>
      {/* savingsRate is absent, not zero, when there was no income - so it is simply not shown. */}
      {summary.savingsRate !== undefined && summary.savingsRate !== null && (
        <p className="mt-1 text-xs text-slate-500">
          Saved {formatPercent(summary.savingsRate)} of income
        </p>
      )}
    </Card>
  )
}

function AlertRow({ item, currency }: { item: BudgetItem; currency: string }) {
  const exceeded = item.status === 'EXCEEDED'
  return (
    <li className="flex items-center gap-3">
      <span className="min-w-0 flex-1 truncate text-sm text-slate-700">{item.category.name}</span>
      <span className="text-xs tabular-nums text-slate-500">
        {formatMoney(item.spent, currency)} of {formatMoney(item.limitAmount, currency)}
      </span>
      <Badge tone={exceeded ? 'red' : 'amber'}>
        {formatPercent(item.percentUsed)}
      </Badge>
    </li>
  )
}

function DashboardSkeleton() {
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
