# Roadmap

Every phase ends with something that runs and can be demonstrated. If work stops after
any phase, what exists still stands on its own.

## Phase 1 — MVP  *(complete)*

- [x] Flyway schema: users, refresh tokens, categories, transactions, budgets, budget items
- [x] Seeded default categories (12 expense, 5 income)
- [x] JWT auth: register, login, refresh with rotation, logout, logout-everywhere
- [x] Single error contract via one `@RestControllerAdvice`
- [x] Swagger UI, actuator health probes
- [x] Docker multi-stage build + Compose
- [x] Unit tests + Testcontainers integration tests (52 passing)
- [x] Transaction CRUD with filtering, search and pagination
- [x] Category CRUD (user-owned, alongside the read-only global defaults)
- [x] Monthly budgets with per-category limits and progress tracking
- [x] Analytics: summary, spend by category, monthly cashflow, composite dashboard
- [x] CSV export (RFC 4180, UTF-8 BOM, formula-injection safe)
- [x] React + TypeScript + Tailwind frontend (Redux Toolkit, TanStack Query, Recharts)

## Phase 2 — Real-time and analytics  *(complete)*

- [x] Redis caching for analytics, evicted per user on write
- [x] Redis-backed rate limiting on the unauthenticated auth endpoints
- [x] WebSocket budget-threshold alerts, surfaced live in the UI
- [x] Bills and reminders on a scheduler, with a persisted notification feed
- [x] Receipt upload to S3-compatible storage (MinIO), served by presigned URL

## Phase 3 — Testing and CI/CD  *(complete, pending a remote)*

- [x] JaCoCo coverage across both suites, gated at 80% instruction / 65% branch
- [x] Frontend unit tests (Vitest), 25 covering the api client and formatting
- [x] Playwright E2E, 9 journeys against the real stack including the live alert
- [x] GitHub Actions: backend, frontend and E2E jobs
- [x] Security workflow: Trivy image scanning and CodeQL
- [x] Release workflow: GHCR publishing with build provenance
- [x] Dependabot, grouped so updates do not arrive as unreadable PR noise

The workflows are written and their YAML validated, but they have never executed - the
repository has no remote yet. Treat them as unproven until a first run goes green.

## Phase 4 — Kubernetes and Terraform  *(in progress)*

- [x] Helm chart: API, web, Postgres and MinIO StatefulSets, Redis, ingress, HPAs, probes
- [x] Per-environment values: `local` (k3d) and `production`
- [x] Deployed and verified on a local k3d cluster (k3s v1.31.4): every pod ready, the app
      served through the Traefik ingress, nginx proxying to the API
- [x] Fixed what that first real deploy exposed and no static check had caught:
      `lombok.config` missing from the API image, and the nginx upstream hardcoded to the
      Compose service name
- [x] Terraform module for an Oracle Always Free ARM node: VCN, subnet, gateway, route
      table, security list, instance, cloud-init installing k3s
- [ ] Multi-arch images: the target node is ARM, the release workflow builds amd64 only
- [ ] Remote Terraform state
- [ ] LocalStack-backed AWS module (S3 + IAM)
- [ ] `terraform apply` against the real account, k3s running on the node
- [ ] Domain and TLS through cert-manager: a public URL

## Phase 5 — Observability and events

Prometheus, Grafana, structured logging with correlation ids. Then Redpanda for
event-driven budget recalculation, and one worker extracted as a separate service.

## Phase 6 — Hardening and AI

TOTP 2FA, OWASP headers, GDPR export and delete. Then LLM-assisted transaction
categorisation with a rules fallback, and monthly summaries.
