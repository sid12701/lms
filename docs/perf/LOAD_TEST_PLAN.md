# LMS Load & Performance Test Plan

**Target:** Validate platform capacity for **~100,000 new loan originations per day** with full lifecycle, admin operations, reporting, and UI under realistic mixed load.

**Status:** Harness built (`scripts/perf/`). Initial controlled runs executed 2026-06-14 against local backend + Supabase Postgres. **Not production-ready at target volume** — see [PERFORMANCE_REPORT.md](./PERFORMANCE_REPORT.md).

---

## 1. Executive summary

| Item | Value |
|------|-------|
| Target volume | 100,000 loans/day (~1.16 loans/sec average) |
| Peak origination (3.5×) | ~4.05 loans/sec → **~82 API RPS** blended |
| Burst origination (10×) | ~11.6 loans/sec → **~211 API RPS** |
| Daily API estimate | ~2.0M HTTP calls (origination + repayments + ops) |
| Harness | Python orchestrator + k6 script + Playwright UI sampler |
| Initial readiness | **~32%** toward 100k loans/day (see report) |

---

## 2. Load model and assumptions

### 2.1 Volume tiers

Computed by `scripts/perf/load_model.py`:

| Tier | Loans/sec | Blended API RPS | Use case |
|------|----------:|----------------:|----------|
| **Average** | 1.16 | 24 | 24h sustained mean |
| **Peak** | 4.05 | 82 | Business-hours concentration (3.5×) |
| **Burst** | 11.57 | 211 | 15-minute origination spike (10×) |
| **Sustained peak** | 4.05 | 79 | 2-hour elevated window |

### 2.2 API calls per loan (lifecycle)

| Phase | Calls | Notes |
|-------|------:|-------|
| Auth (LSP token) | 1 | Cached in production clients |
| Create application | 1 | Idempotency-Key required |
| Document upload | 8 | KYC pack (PAN, Aadhaar, bank stmt, etc.) |
| Status polling (LSP + ops) | 6–30 | Auto-approval worker latency |
| Disbursement | 2–4 | Initiate + poll + mock/provider |
| Repayment (12 EMI) | 24 | 12× (schedule read + payment POST) |
| Webhooks (async) | ~4 | Outbox delivery, 60s fixed delay |
| Admin reads (amortized) | 5 | Dashboard, list, audit |
| Reports (amortized) | 0.01 | Async MIS + poll + download |

**Origination day (first 24h):** ~18 API calls/loan  
**Full lifecycle:** ~45 API calls/loan

### 2.3 Non-origination load

| Source | Daily volume | RPS @ avg |
|--------|-------------:|----------:|
| Repayments (30-day active book) | ~100k payments | ~2.3 |
| Ops dashboard reads (25 users) | ~72k | ~0.8 |
| MIS / report requests | 50 × 8 calls | ~0.005 |

### 2.4 SLO targets (proposed)

| Metric | Target @ peak | Target @ burst |
|--------|---------------|----------------|
| Loan create p95 | < 2s | < 5s |
| Document upload p95 | < 3s | < 8s |
| Payment POST p95 | < 1s | < 3s |
| Dashboard overview p95 | < 3s | < 8s |
| Error rate | < 0.5% | < 2% |
| Actuator readiness | UP | UP |

---

## 3. Scope — workflows tested (not isolated endpoints)

### 3.1 LSP API workflows

| Workflow | Endpoints | Script |
|----------|-----------|--------|
| Token exchange | `POST /api/v1/auth/token` | `workflows.py` |
| Loan origination | `POST /api/v1/lsp/loan-applications` | ✓ |
| Document upload | `POST .../documents` × 8 | ✓ |
| Status read | `GET .../loan-applications/{id}` | ✓ |
| Repayment | `GET .../repayment-schedule`, `POST .../payments` | ✓ |
| Idempotency | Same key replay + mismatch | `concurrency` scenario |
| Product catalog | `GET /api/v1/lsp/products` | Postman folder 5 |

### 3.2 Admin / ops workflows

| Workflow | Endpoints |
|----------|-----------|
| Login + RBAC | `POST /api/v1/auth/login`, metadata, whoami |
| Approval polling | `GET /api/v1/internal/ops/loan-applications/{id}` |
| Disbursement | `POST .../disbursement-requests`, mock-outcome |
| Dashboard | `GET /api/v1/internal/home/overview` |
| Loan queue | `GET /api/v1/internal/ops/loan-applications` |
| Reports | preview, async request, poll, download |
| Alerts | `GET /api/v1/internal/alerts` |
| Audit | `GET /api/v1/internal/admin/audit-events` |

### 3.3 Background workers (observed indirectly)

| Worker | Config | Interval |
|--------|--------|----------|
| Disbursement | `app.disbursement.worker` | 30s fixed delay |
| Webhook delivery | `app.webhooks.delivery` | 60s, batch 20 |
| Report processing | `app.reports.processing` | 15s, batch 10 |
| Alert rules | `app.alert-rules.scheduler` | 5 min |

### 3.4 UI flows (Playwright)

| Page | Spec |
|------|------|
| Login | `frontend/e2e/perf-under-load.spec.ts` |
| Home dashboard | Navigation timing budget |
| Loan applications list | networkidle wait |
| Reports MIS | domcontentloaded |

### 3.5 Negative / failure paths

- Unauthenticated dashboard access (expect 401)
- Empty loan create body (expect 4xx)
- Idempotency key replay vs body mismatch (expect 409)
- Concurrent disbursement requests (race harness)

---

## 4. Test scenarios

| Scenario | Command | Purpose |
|----------|---------|---------|
| Baseline | `python run.py baseline` | Single-thread full lifecycle |
| Load | `python run.py load --concurrency N --duration S` | Sustained mixed workload |
| Stress | `python run.py stress` | Stepwise concurrency until degradation |
| Spike | `python run.py spike` | Burst then recovery |
| Soak | `python run.py soak` | ≥10 min sustained average |
| Concurrency | `python run.py concurrency` | Disburse races + idempotency |
| Reporting | `python run.py reporting` | Dashboard + MIS parallel |
| Failure | `python run.py failure` | Negative paths |
| k6 load | `k6 run scripts/perf/k6/loan_lifecycle.js` | High-concurrency HTTP |
| UI | `npx playwright test e2e/perf-under-load.spec.ts` | Page timing under API load |

### Environment prerequisites

1. `infra/docker compose up -d` (Postgres local or Supabase in `.env`)
2. Backend `local` profile; for load tests:
   - `APP_RATE_LIMIT_ENABLED=false`
   - `APP_STORAGE_DOCUMENTS_PROVIDER=FILE_SYSTEM`
3. `scripts/perf/config.env` from `config.example.env`
4. `python fixtures.py --export` — isolated PERF tenant

**Do not run stress/spike against production or shared Supabase without coordination.**

---

## 5. Metrics collection

| Metric | Source | Tool |
|--------|--------|------|
| RPS, p50/p90/p95/p99 | Harness JSON | `scripts/perf/run.py` |
| Error rate, timeouts | Per-request samples | `_common.MetricsCollector` |
| Actuator health | `/actuator/health` | `collect_metrics.py` |
| DB connections, slow queries | `pg_stat_activity`, `pg_stat_statements` | `collect_metrics.py` (+ psycopg2) |
| CPU / memory | Host / container | **Gap:** Prometheus (#217) not deployed |
| Queue backlog | Webhook outbox, report requests | Manual SQL / admin API |
| UI page load | Playwright console timings | `perf-under-load.spec.ts` |
| Chrome DevTools LCP | Optional CDP trace | Chrome DevTools MCP |

### Recommended additional tooling

| Tool | Priority | Purpose |
|------|----------|---------|
| **k6** | P0 | Primary load generator with thresholds |
| **Grafana + Prometheus** | P0 | App + JVM + Hikari metrics (#217, #219) |
| **pg_stat_statements + auto_explain** | P0 | Slow query attribution |
| **OpenTelemetry** | P1 | Trace disbursement/report workers |
| **Locust / Artillery** | P2 | Alternative if team prefers Python/JS |
| **JMeter** | P3 | Enterprise reporting (heavier setup) |

---

## 6. Endpoints inventory (load-relevant)

### Auth
- `POST /api/v1/auth/login`, `/token`, `/refresh`, `/logout`

### LSP write (rate-limited per LSP: 60/min default)
- `POST /api/v1/lsp/loan-applications`
- `POST /api/v1/lsp/loan-applications/{id}/documents`
- `POST /api/v1/lsp/loans/{id}/payments`

### LSP read
- `GET /api/v1/lsp/loan-applications`, `/{id}`, `/external/{lspLoanId}`
- `GET /api/v1/lsp/loans/{id}`, `/repayment-schedule`, `/payments`
- `GET /api/v1/lsp/products`

### Ops / admin
- `GET/POST /api/v1/internal/ops/loan-applications/**`
- `GET /api/v1/internal/home/overview`
- `GET/POST /api/v1/internal/reports/**`
- `GET /api/v1/internal/alerts`
- `GET /api/v1/internal/admin/audit-events`

---

## 7. Production readiness criteria

Platform is **ready for 100k loans/day** when ALL hold for 2+ hours at **peak tier (82 RPS blended)**:

1. Error rate < 0.5%
2. Loan create p95 < 2s; upload p95 < 3s
3. Dashboard p95 < 3s (snapshot-based per #211/#212)
4. Actuator readiness UP; Hikari pending < 10% of pool
5. No duplicate disbursements under concurrency test
6. Report async pipeline: download success > 99% after poll window
7. UI navigation < 5s p95 with concurrent API load

---

## 8. References

- Postman E2E: `postman/README.md`
- E2E edge scripts: `scripts/e2e/`
- Scalability tracker: `scalability-execution-tracker.md` (issues #197–#237)
- Harness README: `scripts/perf/README.md`
