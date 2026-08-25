import type { APIRequestContext } from '@playwright/test'
import { API_URL } from './fixtures'

/**
 * Helpers that are tolerant of an account which already has data.
 *
 * Tests share one account per worker, so nothing here may assume it is the first to run.
 */

export function auth(accessToken: string) {
  return { Authorization: `Bearer ${accessToken}` }
}

export async function categoryId(
  request: APIRequestContext, accessToken: string, name: string,
): Promise<string> {
  const response = await request.get(`${API_URL}/api/v1/categories`, { headers: auth(accessToken) })
  const categories = await response.json()
  const match = categories.find((c: { name: string }) => c.name === name)
  if (!match) throw new Error(`No seeded category named ${name}`)
  return match.id
}

export async function addTransaction(
  request: APIRequestContext, accessToken: string, data: Record<string, unknown>,
) {
  return request.post(`${API_URL}/api/v1/transactions`, { headers: auth(accessToken), data })
}

/**
 * Ensures the current month's budget has a limit for one category.
 *
 * Creating the budget outright would fail with a 409 for the second test that needs one,
 * since a month can only have a single budget - so this creates it if missing and then adds
 * the item if missing.
 */
export async function ensureBudgetItem(
  request: APIRequestContext, accessToken: string, category: string, limitAmount: number,
): Promise<void> {
  const headers = auth(accessToken)
  const now = new Date()
  const year = now.getFullYear()
  const month = now.getMonth() + 1

  const created = await request.post(`${API_URL}/api/v1/budgets`, {
    headers,
    data: { year, month, items: [{ categoryId: category, limitAmount }] },
  })
  if (created.status() === 201) return

  // Already exists: fetch it and add the line, unless it is already there.
  const existing = await (
    await request.get(`${API_URL}/api/v1/budgets/${year}/${month}`, { headers })
  ).json()

  const alreadyBudgeted = existing.items.some(
    (item: { category: { id: string } }) => item.category.id === category,
  )
  if (alreadyBudgeted) return

  await request.post(`${API_URL}/api/v1/budgets/${existing.id}/items`, {
    headers,
    data: { categoryId: category, limitAmount },
  })
}
