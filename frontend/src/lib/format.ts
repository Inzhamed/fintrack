/** Presentation helpers. Formatting lives here so a currency or a date is never
 *  hand-assembled at a call site and drifts from the rest of the app. */

/**
 * Money, using the browser's locale rules for grouping.
 *
 * Falls back to a plain grouped number when the currency code is not one Intl knows -
 * throwing a RangeError mid-render because someone typed an unusual ISO code would take the
 * whole page down.
 */
export function formatMoney(amount: number, currency = 'DZD'): string {
  try {
    return new Intl.NumberFormat(undefined, {
      style: 'currency',
      currency,
      maximumFractionDigits: 2,
    }).format(amount)
  } catch {
    return `${new Intl.NumberFormat(undefined, { maximumFractionDigits: 2 }).format(amount)} ${currency}`
  }
}

/** Signed money, for figures where the direction is the point. */
export function formatSigned(amount: number, currency = 'DZD'): string {
  const formatted = formatMoney(Math.abs(amount), currency)
  if (amount > 0) return `+${formatted}`
  if (amount < 0) return `-${formatted}`
  return formatted
}

/** "5 Aug 2026" - unambiguous, unlike any all-numeric format. */
export function formatDate(iso: string): string {
  return new Date(`${iso}T00:00:00`).toLocaleDateString(undefined, {
    day: 'numeric',
    month: 'short',
    year: 'numeric',
  })
}

/** "August 2026", from a "2026-08" label. */
export function formatMonthLabel(label: string): string {
  const [year, month] = label.split('-').map(Number)
  return new Date(year, month - 1, 1).toLocaleDateString(undefined, {
    month: 'short',
    year: '2-digit',
  })
}

export function formatPercent(value: number): string {
  return `${value.toFixed(1)}%`
}

/** Today as YYYY-MM-DD in the *local* calendar.
 *
 *  toISOString() would convert to UTC first, which puts an evening in Algiers on the
 *  following day - exactly the bug the API avoids by storing occurredOn as a DATE. */
export function today(): string {
  const now = new Date()
  const month = String(now.getMonth() + 1).padStart(2, '0')
  const day = String(now.getDate()).padStart(2, '0')
  return `${now.getFullYear()}-${month}-${day}`
}

export function startOfMonth(date = new Date()): string {
  const month = String(date.getMonth() + 1).padStart(2, '0')
  return `${date.getFullYear()}-${month}-01`
}

export function endOfMonth(date = new Date()): string {
  const last = new Date(date.getFullYear(), date.getMonth() + 1, 0)
  const month = String(last.getMonth() + 1).padStart(2, '0')
  return `${last.getFullYear()}-${month}-${String(last.getDate()).padStart(2, '0')}`
}

export function classNames(...values: (string | false | null | undefined)[]): string {
  return values.filter(Boolean).join(' ')
}
