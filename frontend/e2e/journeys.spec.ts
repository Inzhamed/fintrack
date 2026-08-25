import { expect, test, unique } from './fixtures'
import { addTransaction, categoryId, ensureBudgetItem } from './helpers'

/**
 * The journeys a person actually performs, driven through the real UI against the real API.
 *
 * Deliberately few. E2E is the slowest and most brittle layer, so it covers the paths whose
 * value is in the *integration* - a form that reaches the API, a dashboard that reflects a
 * write, a session that survives a reload, an alert that arrives unprompted. Anything
 * narrower is already covered faster by the unit and integration suites.
 *
 * Tests share one account per worker, so each labels its own data uniquely rather than
 * assuming an empty account.
 */

test('record an expense through the form and see it reach the dashboard', async ({ signedInPage: page }) => {
  const label = unique('Weekly shop')

  await page.getByRole('link', { name: 'Transactions' }).click()
  await page.getByRole('button', { name: 'Add transaction' }).click()

  // Scoped to the dialog: the page behind it has its own Category and Type controls for
  // filtering, so an unscoped label matches two elements and Playwright refuses to guess.
  const form = page.getByRole('dialog', { name: 'Add transaction' })
  await form.getByLabel('Amount').fill('1275.50')
  await form.getByLabel('Category').selectOption({ label: 'Groceries' })
  await form.getByLabel('Description').fill(label)
  await form.getByLabel('Merchant').fill('Ardis')
  await form.getByRole('button', { name: 'Add transaction' }).click()

  const row = page.getByRole('row', { name: new RegExp(label) })
  await expect(row).toBeVisible()
  await expect(row).toContainText('Ardis')
  await expect(row).toContainText('Groceries')

  // The write must propagate to everything derived from it, not only the list it was made on.
  await page.getByRole('link', { name: 'Dashboard' }).click()
  await expect(page.getByText(label)).toBeVisible()
})

test('filters live in the URL and survive a reload', async ({ signedInPage: page, user }) => {
  const expenseLabel = unique('Findable')
  const incomeLabel = unique('HiddenByFilter')

  await addTransaction(page.request, user.accessToken, {
    type: 'EXPENSE', amount: 500, description: expenseLabel,
  })
  await addTransaction(page.request, user.accessToken, {
    type: 'INCOME', amount: 9000, description: incomeLabel,
  })

  await page.goto('/transactions')
  await page.getByLabel('Type').selectOption('EXPENSE')

  await expect(page.getByText(expenseLabel)).toBeVisible()
  await expect(page.getByText(incomeLabel)).toHaveCount(0)
  await expect(page).toHaveURL(/type=EXPENSE/)

  // A filtered view has to be shareable and survive the back button, which is the whole
  // reason the filters are in the query string rather than in component state.
  await page.reload()
  await expect(page.getByText(expenseLabel)).toBeVisible()
  await expect(page.getByText(incomeLabel)).toHaveCount(0)
})

test('a budget shows progress and warns as spend approaches the limit', async ({ signedInPage: page, user }) => {
  const health = await categoryId(page.request, user.accessToken, 'Health')
  await ensureBudgetItem(page.request, user.accessToken, health, 1000)

  // 85% of the limit: past the 80% alert threshold, still under the limit itself.
  await addTransaction(page.request, user.accessToken, {
    type: 'EXPENSE', amount: 850, categoryId: health, description: unique('Pharmacy'),
  })

  await page.goto('/budget')

  const bar = page.getByRole('progressbar', { name: /Health/ })
  await expect(bar).toHaveAttribute('aria-valuenow', '85')
  await expect(page.getByText('Close to limit').first()).toBeVisible()
})

test('a live alert arrives without the page being reloaded', async ({ signedInPage: page, user }) => {
  const education = await categoryId(page.request, user.accessToken, 'Education')
  await ensureBudgetItem(page.request, user.accessToken, education, 1000)

  // The socket has to be subscribed before the write, or the push has nowhere to land and is
  // dropped - documented behaviour, not a bug, so the test must not race it.
  //
  // The listener is attached before navigating rather than awaited afterwards: the CONNECTED
  // frame can arrive before a post-hoc waitForEvent registers, and that call would then wait
  // for a second frame that never comes.
  let stompConnected = false
  page.on('websocket', (socket) => {
    socket.on('framereceived', (frame) => {
      // A STOMP frame leads with its command. CONNECTED means the server accepted the token
      // on the CONNECT frame; the client subscribes immediately afterwards.
      if (String(frame.payload).startsWith('CONNECTED')) stompConnected = true
    })
  })

  await page.goto('/transactions')
  await expect.poll(() => stompConnected, { timeout: 15_000 }).toBe(true)

  // Written from outside the browser, so a toast can only appear if the server pushed it
  // over the WebSocket. A local echo would pass a much weaker version of this test.
  await addTransaction(page.request, user.accessToken, {
    type: 'EXPENSE', amount: 1200, categoryId: education, description: unique('Course'),
  })

  // Scoped to the live region: an unscoped text match also finds the hidden <option> for
  // Education in the page's own category filter, which is never visible and never will be.
  const toasts = page.locator('[aria-live="polite"]')
  await expect(toasts.getByRole('button', { name: /Education/ }))
    .toBeVisible({ timeout: 15_000 })
})

test('a signed-in session survives a full reload', async ({ signedInPage: page }) => {
  await page.reload()

  // The refresh token is restored and exchanged before the first authenticated render, so
  // the login form must never flash at someone who is in fact signed in.
  await expect(page.getByRole('heading', { name: 'This month' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Sign in' })).toHaveCount(0)
})

test('a protected route redirects to sign-in and returns you afterwards', async ({ page, user }) => {
  await page.goto('/budget')
  await expect(page.getByRole('heading', { name: 'Sign in' })).toBeVisible()

  await page.getByLabel('Email').fill(user.email)
  await page.getByLabel('Password').fill(user.password)
  await page.getByRole('button', { name: 'Sign in' }).click()

  // Sent back to where they were headed, not dumped on the dashboard.
  await expect(page).toHaveURL(/\/budget/)
})

test('signing out clears the session for the next person', async ({ signedInPage: page }) => {
  await page.getByRole('button', { name: 'Sign out' }).click()
  await expect(page.getByRole('heading', { name: 'Sign in' })).toBeVisible()

  // Navigating straight back must not restore a session from a token left behind.
  await page.goto('/transactions')
  await expect(page.getByRole('heading', { name: 'Sign in' })).toBeVisible()
})

test('a wrong password is refused without revealing whether the account exists', async ({ page, user }) => {
  await page.goto('/login')
  await page.getByLabel('Email').fill(user.email)
  await page.getByLabel('Password').fill('definitely wrong')
  await page.getByRole('button', { name: 'Sign in' }).click()

  await expect(page.getByRole('alert')).toContainText('Invalid email or password')
  await expect(page.getByRole('heading', { name: 'This month' })).toHaveCount(0)
})

test('the form reports validation errors beside the field that caused them', async ({ signedInPage: page }) => {
  await page.goto('/transactions')
  await page.getByRole('button', { name: 'Add transaction' }).click()

  const form = page.getByRole('dialog', { name: 'Add transaction' })

  // Submitting empty: the client-side schema catches the missing amount before any request.
  await form.getByRole('button', { name: 'Add transaction' }).click()
  await expect(form.getByText('Amount is required')).toBeVisible()

  await form.getByLabel('Amount').fill('12.345')
  await form.getByRole('button', { name: 'Add transaction' }).click()
  await expect(form.getByText('Use at most 2 decimal places')).toBeVisible()
})
