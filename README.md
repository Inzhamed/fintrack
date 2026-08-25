# FinTrack — Personal Finance API

Personal finance tracking: income and expenses, per-category monthly budgets, and a
dashboard over the result. Built as a depth project — the goal is production habits
(migrations, real error contracts, integration tests against real infrastructure), not
feature count.

**Status: Phase 1 complete.** A working full-stack app - sign in, record income and
expenses, set monthly budgets, watch the dashboard, export to CSV. Phase 2 adds Redis,
WebSocket budget alerts and bill reminders. See [docs/roadmap.md](docs/roadmap.md).

---

## Stack

| Layer | Technology |
|---|---|
| API | Java 21, Spring Boot 4.0, Spring Web MVC, Spring Data JPA, Spring Security 7 |
| Database | PostgreSQL 16, Flyway migrations |
| Auth | JWT access tokens (jjwt), rotating opaque refresh tokens, BCrypt |
| Docs | springdoc-openapi (Swagger UI) |
| Tests | JUnit 5, AssertJ, MockMvc, Testcontainers |
| Frontend | React 19, TypeScript, Vite 8, Tailwind 4, Redux Toolkit, TanStack Query, Recharts |
| Delivery | Docker multi-stage builds, Docker Compose, nginx |

---

## Running it

**Prerequisites:** Docker, and JDK 21 if you want to run the API outside a container.

```bash
cp .env.example .env
```

Then set two values in `.env` — neither has a default, and the application refuses to
start without them:

- `DB_PASSWORD` — anything, locally
- `JWT_SECRET` — generate with `openssl rand -base64 48`

Everything in containers:

```bash
docker compose up --build
```

Or just the database, running the API from your IDE or the command line:

```bash
docker compose up -d db
```

```bash
cd backend && ./mvnw spring-boot:run
```

```bash
cd frontend && npm install && npm run dev
```

The dev server proxies `/api` to the backend, so the browser stays on one origin and
CORS never enters the picture.

| What | Where |
|---|---|
| App | http://localhost:5173 |
| API | http://localhost:8080/api/v1 |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| Health | http://localhost:8080/actuator/health |

---

## Tests

```bash
cd backend && ./mvnw verify
```

Unit tests (`*Test`) run in `test` and need nothing but a JVM. Integration tests (`*IT`)
run in `verify` and start a real PostgreSQL 16 through Testcontainers, applying the real
Flyway migrations — so the suite catches SQL that an in-memory database would have
accepted. Docker must be running for that half.

Currently 52 tests: 8 on JWT issuing and verification, 11 on the auth flow, 11 on
transactions and categories, 10 on budget progress, 11 on analytics, 9 on CSV export,
plus a context-load check.

---

## Endpoints

All routes below `/api/v1` require `Authorization: Bearer <accessToken>` except the auth
ones marked public.

| Method | Path | Notes |
|---|---|---|
| POST | `/auth/register` `/auth/login` `/auth/refresh` `/auth/logout` | public |
| POST | `/auth/logout-all` | revokes every session |
| GET | `/auth/me` | the current account |
| GET | `/categories` | globals + the caller's own; `?type=EXPENSE\|INCOME` |
| POST · PATCH · DELETE | `/categories` `/categories/{id}` | globals are read-only; delete takes `?force=true` |
| GET | `/transactions` | filters below, paginated |
| POST · GET · PATCH · DELETE | `/transactions` `/transactions/{id}` | |
| GET | `/budgets` `/budgets/{year}/{month}` | the month view carries full progress |
| POST | `/budgets` `/budgets/{id}/items` | |
| PATCH · DELETE | `/budget-items/{id}` | |
| DELETE | `/budgets/{id}` | |
| GET | `/dashboard` | the whole landing screen in one call |
| GET | `/analytics/summary` `/analytics/by-category` `/analytics/cashflow` | range defaults to the current month |
| GET | `/transactions/export` | CSV; takes the same filters as the list |

Transaction filters combine with AND: `from`, `to`, `type`, `categoryId`,
`uncategorised`, `minAmount`, `maxAmount`, `search` (description or merchant,
case-insensitive), plus `page`, `size` and `sort` — for example:

```
GET /api/v1/transactions?from=2026-08-01&to=2026-08-31&type=EXPENSE&minAmount=1000&sort=amount,desc
```

Lists return `{"data": [...], "meta": {page, size, total, totalPages, hasNext}}`.

---

## Design notes

Decisions that are load-bearing, and the reasoning behind them.

**Flyway owns the schema; Hibernate validates it.** `ddl-auto=validate` means the
application refuses to start if the entities and the migrations have drifted apart. This
is not theoretical — it caught a `CHAR(3)` versus `varchar(3)` mismatch on the very first
run of this project, which would otherwise have surfaced as blank-padded currency codes
comparing unequal much later.

**Amounts are always positive; `type` carries the direction.** Storing signed amounts
means every aggregate has to remember the convention, and the first place that forgets
produces a plausible-looking wrong number.

**`occurred_on` is a `DATE`, not a timestamp.** A purchase made at 23:00 in Algiers
belongs to that day no matter where the reader is. Storing an instant invites
off-by-one-day bugs in every monthly total.

**One error shape, always.** A single `@RestControllerAdvice` renders every failure as
`{"error": {"code", "message", "details", "path", "timestamp"}}`. Clients branch on the
stable `code`, never on the message. Framework-level failures — unreadable JSON, missing
parameters, auth rejections — go through the same handler, so there is never a second
shape to special-case.

**Access tokens are short-lived and unrevocable; refresh tokens are long-lived and
revocable.** Checking a denylist on every request would put a datastore read back on the
hot path and give up the only real benefit of a stateless token. Revocation lives at the
refresh layer instead.

**Refresh tokens are random strings, not JWTs, and only their SHA-256 hash is stored.** A
database leak yields digests that cannot be presented to the refresh endpoint. Plain
SHA-256 rather than BCrypt is deliberate: the input is 256 bits of uniform randomness, so
there is no search space to slow down, and the lookup needs an indexed exact match.

**Refresh tokens rotate, and reuse revokes the whole family.** Redeeming a token spends
it. If a spent token is presented again, two parties hold copies, so every session for
that user is revoked. The revocation commits in its own transaction — doing it inline
would be undone by the rollback from the rejection itself, which is a bug this project
shipped for about ten minutes before a test caught it.

**Failed logins are indistinguishable.** A wrong password and an unregistered address
return byte-identical responses, so the endpoint cannot be used to enumerate accounts.
There is a test asserting exactly that.

**Ownership is a query predicate, not a post-load check.** Every read is scoped by user id
in the query itself. Loading by id and then comparing the owner is one forgotten branch
away from a leak, and it answers differently for "exists but not yours" than for "does not
exist". Cross-user access returns 404, never 403, so the response never confirms that an
id exists.

**A transaction's category must point the same way it does.** Categories and transactions
share one `EntryType`, so an expense filed under "Salary" is rejected by the domain rather
than quietly skewing every report that groups by category.

**Budget progress is computed server-side, in a fixed number of queries.** A month's view
costs three statements no matter how many categories are budgeted — one fetch-join for the
budget and its items, one grouped expense aggregate, one income sum — verified by counting
statements at 3 items and again at 7. Deriving "82%" in each client is three chances to
round it differently from the alert that fires at 80%.

**The budget reports what it is not tracking.** `uncategorisedSpend` and `unbudgetedSpend`
are first-class fields, because a budget that silently ignores part of the month's outgo
is worse than none — every tracked line can read healthy while money leaks past them.

**PATCH bodies distinguish absent from null.** Omitting a field leaves it unchanged, so
clearing a transaction's category needs its own `clearCategory` flag. That flag is a
`Boolean`, not a `boolean`: absent is a third state a primitive cannot carry — and Jackson
3 enables `FAIL_ON_NULL_FOR_PRIMITIVES` by default, so a primitive would reject every
partial update outright.

**Deleting a category never deletes history.** The FK nulls the reference, so affected
transactions become uncategorised instead of disappearing. Because that is a lot of silent
change for one DELETE, it returns 409 until the caller passes `?force=true`.

**The dashboard is one request, not five.** A screen assembled from five round trips shows
five loading states and can render a summary from one moment beside a chart from another.
`/dashboard` returns this month, last month, the category breakdown, a six-month trend,
recent activity and budget alerts from a single consistent snapshot — and its alerts reuse
the breakdown it already computed, so the warnings can never disagree with the chart
beside them.

**Cashflow fills empty months with zeros.** Omitting a month with no activity makes a chart
draw a straight line across the gap, implying spending that never happened.

**Savings rate is null, not zero, when there was no income.** "Kept 0% of nothing" is a
different statement from "kept none of what you earned", and only one of them is true.

**CSV export defuses formula injection.** A description of `=HYPERLINK(...)` or `+15551234`
executes when a spreadsheet opens the file, and descriptions come from whatever the user
typed or a bank import supplied. Dangerous leading characters get an apostrophe prefix at
render time; the stored value is never altered, because only this rendering is unsafe. The
file is UTF-8 with a BOM — without it Excel reads the system codepage and mangles accents —
and uses CRLF and RFC 4180 quoting.

**The export reuses the list endpoint's Specifications.** Same filters, same predicates, so
a download can never disagree with what was on screen when the user clicked it.

---

## Frontend notes

**Redux holds client state; TanStack Query owns server state.** Who is signed in and which
toasts are showing live in Redux. Transactions, budgets and analytics do not — Query
already solves caching, refetching and invalidation, and mirroring server data into Redux
means maintaining that machinery twice and keeping the two in sync by hand.

**One refresh at a time, across every caller and every tab.** The API rotates refresh
tokens and treats a replayed one as theft, revoking every session. So two concurrent
refreshes are not a wasted request — the first succeeds, the second is rejected as reuse,
and that rejection kills the token the first just issued. The client signs the user out by
itself. This is not hypothetical: React StrictMode double-invokes effects, so the session
restore fired twice on every page load and the second call replayed a spent token.
Concurrent callers now collapse onto one promise, and a Web Lock serialises tabs — with the
token read *inside* the lock, since a waiter using the token it captured before queuing
would only reorder the reuse, not prevent it.

**Access token in memory, refresh token in localStorage.** The access token is never
persisted, so XSS cannot read it back, and it expires in fifteen minutes anyway. The
refresh token in localStorage is a deliberate trade-off: the fix that actually closes the
XSS hole is an httpOnly cookie, which needs a server change and belongs with the rest of
the hardening in Phase 6. Keeping it in memory instead would sign the user out on every
reload.

**Filters live in the URL.** A filtered list can be bookmarked, shared and survives the
back button. Any filter change resets the page number, since staying on page 3 of a result
set that now has one page shows an empty table.

**The dashboard is code-split.** Recharts is roughly half the bundle and only the dashboard
charts anything, so it loads on demand — 423 kB initial instead of 785 kB, and the login
page carries none of it.

**Server-side validation messages land on the field that caused them.** The API returns
field-keyed details on a 422; the transaction form maps them back onto the matching input,
so a rule the client does not know about still appears next to the box that broke it.

---

## Layout

```
frontend/
  src/
    app/          store, routing shell, auth guard
    components/   shared UI primitives
    features/     auth, dashboard, transactions, budgets
    lib/          api client, query hooks, types, formatting
  Dockerfile, nginx.conf
backend/
  src/main/java/com/fintrack/api/
    config/       Security, JWT properties
    controller/   HTTP only — bind, validate, delegate
    dto/          Request and response records; entities never cross the boundary
    exception/    ApiException, ErrorCode, the single @RestControllerAdvice
    model/        JPA entities
    repository/   Spring Data interfaces
    security/     JwtService, JwtAuthenticationFilter, UserDetailsService
    service/      Business rules and transaction boundaries
  src/main/resources/db/migration/   Flyway — source code, never gitignored
  src/test/                          Unit tests and Testcontainers integration tests
compose.yaml
```
