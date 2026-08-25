import { describe, expect, it, vi, afterEach } from 'vitest'
import { classNames, endOfMonth, formatMonthLabel, formatMoney, formatPercent, startOfMonth, today } from './format'

afterEach(() => vi.useRealTimers())

describe('formatMoney', () => {
  it('falls back to a grouped number for a currency Intl does not know', () => {
    // Throwing mid-render because someone typed an unusual ISO code would take the page down.
    expect(formatMoney(1234.5, 'XYZ')).toContain('XYZ')
    expect(formatMoney(1234.5, 'XYZ')).toContain('1')
  })

  it('formats a known currency without throwing', () => {
    expect(() => formatMoney(1234.5, 'DZD')).not.toThrow()
    expect(() => formatMoney(0, 'USD')).not.toThrow()
  })
})

describe('today', () => {
  it('uses the local calendar, not UTC', () => {
    // 23:30 on the 24th in a UTC+1 zone is 22:30 UTC on the same day - but an evening late
    // enough to cross the line would be reported as the next day by toISOString(). This is
    // the same off-by-one-day trap the API sidesteps by storing occurredOn as a DATE.
    vi.useFakeTimers()
    vi.setSystemTime(new Date(2026, 7, 24, 23, 30, 0))

    expect(today()).toBe('2026-08-24')
  })

  it('pads single-digit months and days', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date(2026, 0, 5, 12, 0, 0))
    expect(today()).toBe('2026-01-05')
  })
})

describe('month boundaries', () => {
  it('finds the first and last day, including a leap February', () => {
    expect(startOfMonth(new Date(2026, 7, 15))).toBe('2026-08-01')
    expect(endOfMonth(new Date(2026, 7, 15))).toBe('2026-08-31')
    expect(endOfMonth(new Date(2026, 1, 10))).toBe('2026-02-28')
    expect(endOfMonth(new Date(2024, 1, 10))).toBe('2024-02-29')
  })
})

describe('formatMonthLabel', () => {
  it('turns an ISO year-month into something readable', () => {
    expect(formatMonthLabel('2026-08')).toMatch(/26/)
  })
})

describe('formatPercent', () => {
  it('keeps one decimal, matching the figure the API computes', () => {
    expect(formatPercent(93.14)).toBe('93.1%')
    expect(formatPercent(100)).toBe('100.0%')
  })
})

describe('classNames', () => {
  it('drops falsy entries so a conditional class does not emit "false"', () => {
    expect(classNames('a', false, undefined, null, 'b')).toBe('a b')
  })
})
