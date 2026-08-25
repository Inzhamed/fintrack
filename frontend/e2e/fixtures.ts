import { test as base, expect, request as playwrightRequest, type Page } from '@playwright/test'

export const API_URL = process.env.E2E_API_URL ?? 'http://localhost:8080'

export interface TestUser {
  email: string
  password: string
  accessToken: string
}

/**
 * One account per worker, not per test.
 *
 * Registration is rate limited to a handful per hour per address, deliberately, and every
 * test here runs from the same one. Creating an account per test exhausts that quota within
 * a few tests and the rest then fail on the fixture rather than on anything they were
 * checking - the suite would be failing against its own protection instead of testing the
 * application.
 *
 * A worker-scoped account also matches how a real person uses the app: one sign-in, many
 * actions. Tests therefore label the data they create uniquely rather than assuming they
 * start from an empty account.
 */
export const test = base.extend<
  { signedInPage: Page },
  { user: TestUser }
>({
  user: [
    async ({}, use, workerInfo) => {
      const email = `e2e-w${workerInfo.workerIndex}-${Date.now()}@example.com`
      const password = 'correct horse battery'

      const context = await playwrightRequest.newContext()
      const response = await context.post(`${API_URL}/api/v1/auth/register`, {
        data: { email, password, name: 'E2E User' },
      })

      if (response.status() === 429) {
        throw new Error(
          'Registration is rate limited. Restart the API with RATELIMIT_REGISTER_LIMIT raised, ' +
          'or wait for the window to lapse. See the E2E section of the README.',
        )
      }
      expect(response.status(), 'registration should succeed').toBe(201)

      const body = await response.json()
      await context.dispose()

      await use({ email, password, accessToken: body.accessToken })
    },
    { scope: 'worker' },
  ],

  /** A page that has already signed in through the real form. */
  signedInPage: async ({ page, user }, use) => {
    await page.goto('/login')
    await page.getByLabel('Email').fill(user.email)
    await page.getByLabel('Password').fill(user.password)
    await page.getByRole('button', { name: 'Sign in' }).click()
    await expect(page.getByRole('heading', { name: 'This month' })).toBeVisible()

    await use(page)
  },
})

/** A label unique to one test, so a shared account cannot let two tests interfere. */
export function unique(prefix: string): string {
  return `${prefix}-${Math.random().toString(36).slice(2, 9)}`
}

export { expect }
