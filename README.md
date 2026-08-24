# FinTrack — Personal Finance API

Personal finance tracking: income and expenses, per-category monthly budgets, and a
dashboard over the result. Built as a depth project — the goal is production habits
(migrations, real error contracts, integration tests against real infrastructure), not
feature count.

**Status: Phase 1 in progress.** Authentication is complete and tested end to end.
Transactions, budgets, dashboard and CSV export are next. See
[docs/roadmap.md](docs/roadmap.md).

---

## Stack

| Layer | Technology |
|---|---|
| API | Java 21, Spring Boot 4.0, Spring Web MVC, Spring Data JPA, Spring Security 7 |
| Database | PostgreSQL 16, Flyway migrations |
| Auth | JWT access tokens (jjwt), rotating opaque refresh tokens, BCrypt |
| Docs | springdoc-openapi (Swagger UI) |
| Tests | JUnit 5, AssertJ, MockMvc, Testcontainers |
| Delivery | Docker multi-stage build, Docker Compose |

Frontend (React + TypeScript + Tailwind) lands later in Phase 1.

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

| What | Where |
|---|---|
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

Currently 20 tests: 8 covering JWT issuing and verification, 11 covering the auth flow
end to end, plus a context-load check.

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

---

## Layout

```
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
