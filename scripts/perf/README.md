# LMS Load & Performance Test Harness

Reusable harness for validating **~100,000 loans/day** capacity. Complements the Postman collection (`postman/LMS.postman_collection.json`) and E2E scripts (`scripts/e2e/`).

## Prerequisites

1. **Infrastructure** (Docker):
   ```bash
   cd infra && docker compose up -d
   ```
2. **Backend** (`local` profile, rate limits off for load tests):
   ```bash
   cd backend
   # In repo-root .env: APP_RATE_LIMIT_ENABLED=false, APP_STORAGE_DOCUMENTS_PROVIDER=FILE_SYSTEM
   set SPRING_PROFILES_ACTIVE=local
   mvnw spring-boot:run
   ```
3. **Python 3.11+** with `requests`, `openpyxl` (optional: `psycopg2-binary` for DB metrics).
4. **Optional:** [k6](https://k6.io) for high-concurrency HTTP, Newman for Postman regression.
5. **Optional:** Frontend for UI perf — `cd frontend && npm run dev`.

## Quick start

```bash
cd scripts/perf
copy config.example.env config.env
# Edit config.env if needed

python fixtures.py --export          # provision tenant
python run.py --list                 # load model + scenarios
python run.py baseline               # single-thread lifecycle smoke
python run.py load --concurrency 15 --duration 180
python collect_metrics.py            # actuator + optional Postgres
python generate_report.py            # merge JSON → docs/perf/PERFORMANCE_REPORT.md
```

## Scenarios

| Scenario | Purpose | Default |
|----------|---------|---------|
| `baseline` | Full lifecycle smoke, idempotency check | 1 worker |
| `load` | Mixed origination + admin reads + reports | 10 VUs, 5 min |
| `stress` | Stepwise concurrency until >25% errors | 10→80 workers |
| `spike` | Burst origination then recovery reads | burst_factor × 2 |
| `soak` | Sustained average load | 5 VUs, ≥10 min |
| `concurrency` | Duplicate disburse + idempotency | 8 parallel |
| `reporting` | Dashboard + async MIS | 6 workers × 12 iters |
| `failure` | Auth/body errors, negative paths | — |
| `all` | baseline + load + concurrency + reporting | abbreviated |

## k6 (recommended for production-like load)

```bash
# After fixtures.py --export, read clientId/secret/lspId/productId from .perf-runs/fixtures.json
k6 run scripts/perf/k6/loan_lifecycle.js \
  -e BASE_URL=http://localhost:8080 \
  -e CLIENT_ID=... -e CLIENT_SECRET=... \
  -e LSP_ID=... -e PRODUCT_ID=... \
  -e K6_SCENARIO=load
```

## Playwright UI validation

Run **while** API load is active in another terminal:

```bash
cd frontend
set PERF_ADMIN_PASSWORD=your-admin-password
npx playwright test e2e/perf-under-load.spec.ts
```

## Outputs

| Path | Content |
|------|---------|
| `.perf-runs/fixtures.json` | Tenant + API client credentials |
| `.perf-runs/reports/*.json` | Per-scenario metrics (RPS, percentiles, by endpoint/workflow) |
| `.perf-runs/infra-snapshot.json` | Actuator health + optional DB stats |
| `docs/perf/LOAD_TEST_PLAN.md` | Full plan and load model |
| `docs/perf/PERFORMANCE_REPORT.md` | Aggregated results |

## Safety rules

- **Never** point at production without explicit approval.
- Use synthetic borrowers only (`perf*@example.com`, generated PAN/Aadhaar).
- Disable or raise rate limits only in dedicated load environments.
- Do not run `stress`/`spike` against shared dev DBs without coordination.

## Additional tools (recommended)

| Tool | Role |
|------|------|
| **k6** | Primary HTTP load generator with thresholds |
| **Grafana + Prometheus** | App metrics (#217 in scalability tracker) |
| **pg_stat_statements** | Slow query analysis |
| **OpenTelemetry** | Distributed traces across workers |
| **Chrome DevTools** | UI LCP/CLS under load (via Playwright + CDP) |

See `docs/perf/LOAD_TEST_PLAN.md` for the full methodology and production readiness criteria.
