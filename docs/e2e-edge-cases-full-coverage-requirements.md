# E2E Edge Cases — Full Coverage Requirements

**Goal:** Clear all **83 blocked** edge cases in `e2e-test-matrix.xlsx` with deterministic Pass/Fail (no Blocked rows remaining).

**Baseline:** Newman happy path passes (run 5: 82/82 assertions). API runner pass 1 cleared 17 edge cases. **83 remain.**

**Related artifacts:**

| Artifact | Purpose |
|----------|---------|
| `postman/LMS-E2E-Testing-Admin-and-LSP.postman_collection.json` | Happy path + folder 14 samples |
| `postman/LMS-E2E-Local.postman_environment.json` | Base env (extended for edge runs) |
| `scripts/e2e/config.example.env` | Edge-run configuration |
| `scripts/e2e/fixtures.py` | Shared fixture factory (multi-LSP, roles, loan states) |
| `scripts/e2e/run_coverage.py` | Orchestrator for all phases |
| `scripts/e2e/phases/*.py` | Phase-specific API test modules |
| `scripts/e2e/ui-checklist.json` | Chrome DevTools UI cases EC-083–090, EC-096–097, EC-111 |
| `.e2e-runs/` | JSON results + env exports per phase |

---

## 1. Runtime prerequisites

### 1.1 Services (must be up before any phase)

| Service | URL | Notes |
|---------|-----|-------|
| Backend | `http://localhost:8080` | `mvnw spring-boot:run -Plocal` |
| Frontend | `http://127.0.0.1:5173` | `npm run dev` (UI phases only) |
| Postgres | Docker / Supabase | Seed + migrations applied |
| Redis | Docker | Rate limit + session store |
| MinIO / filesystem | Per `application-local` | Document uploads |
| RabbitMQ | Docker | Disbursement worker (EC-049, EC-027) |

### 1.2 Tooling

| Tool | Version | Used for |
|------|---------|----------|
| Node + `npx newman` | ≥18 | Happy path regression |
| Python 3.11+ | `requests`, `openpyxl` | Edge API phases |
| Chrome + Cursor Chrome DevTools MCP | Latest | UI phases |
| `curl` / `httpx` | Optional | Webhook mock probes |

### 1.3 Configuration overrides for edge testing

Set in `scripts/e2e/config.env` (copy from `config.example.env`):

```properties
# Rate limits — lower for EC-074..076 (default auth=10/min in application.yml)
APP_RATE_LIMIT_AUTH_PER_MINUTE=5
APP_RATE_LIMIT_LSP_WRITE_PER_MINUTE=10

# Document storage
APP_LOAN_DOCUMENT_STORAGE_MODE=FILE_SYSTEM

# Webhook mock (Phase 5)
E2E_WEBHOOK_MOCK_URL=http://localhost:9090/webhook
E2E_WEBHOOK_MOCK_5XX_URL=http://localhost:9090/status/500
E2E_WEBHOOK_MOCK_4XX_URL=http://localhost:9090/status/404

# SSRF probe (must be rejected at subscription time)
E2E_WEBHOOK_SSRF_URL=http://169.254.169.254/latest/meta-data

# IP allowlist (Phase 3) — runner IP must NOT be in list
E2E_RUNNER_IP=127.0.0.1
```

Restart backend after changing rate limits or profiles.

---

## 2. Fixture catalog

Run once per session (or per phase) via `python scripts/e2e/fixtures.py --export .e2e-runs/edge-fixtures.json`.

| Fixture ID | Creates | Enables |
|------------|---------|---------|
| `F01_happy_lsp` | LSP + product + API client + UI user | Baseline (Newman run 5) |
| `F02_second_lsp` | LSP B + client B | EC-012, EC-044, EC-071 |
| `F03_product_admin` | User with PRODUCT_ADMIN | EC-015 |
| `F04_ops_user` | User with OPS_USER | EC-014 (N/A verify), EC-064, EC-069 |
| `F05_inactive_lsp` | LSP status INACTIVE + client | EC-009, EC-019 |
| `F06_inactive_product` | Product INACTIVE + mapping | EC-018 |
| `F07_inactive_mapping` | Active LSP + inactive mapping | EC-020 |
| `F08_initialized_loan` | Loan INITIALIZED (no docs) | EC-046, EC-058, transitions |
| `F09_awaiting_approval` | Loan AWAITING_APPROVAL (partial docs) | EC-033 path to REJECTED |
| `F10_disbursed_loan` | DISBURSED + schedule | EC-052–057, EC-107–109 |
| `F11_under_repayment` | 1 payment posted | EC-098 |
| `F12_closed_loan` | 12 payments → CLOSED | EC-034, EC-057, EC-060 |
| `F13_rejected_loan` | Auto-reject via rule failure | EC-033, EC-100 |
| `F14_foreclosed_loan` | Foreclosure execute | EC-035, EC-060 |
| `F15_ip_allowlist_blocked` | Allowlist excluding 127.0.0.1 | EC-010, EC-011 |
| `F16_rotated_api_client` | Client with grace-period secrets | EC-008 |

---

## 3. Execution phases

Run in order; later phases reuse fixture JSON.

```
Phase 0: Newman happy path (regression gate)
Phase 1: API negatives (41 cases)     → scripts/e2e/phases/phase1_api_negatives.py
Phase 2: Auth, session, RBAC (11)     → phase2_auth_rbac.py
Phase 3: Infra — IP allowlist (3)     → phase3_infra.py + manual/proxy
Phase 4: Multi-tenant (3)             → phase4_multitenant.py (needs F02)
Phase 5: Webhooks (7)                 → phase5_webhooks.py + mock server
Phase 6: Rate limits (3)              → phase6_rate_limits.py
Phase 7: Lifecycle assertions (6)     → phase7_lifecycle.py
Phase 8: UI Chrome DevTools (10)      → ui-checklist.json + MCP
Phase 9: Data / ADR / regression (4)  → phase9_data_adr.py
```

**Orchestrator:**

```bash
python scripts/e2e/run_coverage.py --phase all
python scripts/e2e/run_coverage.py --phase 1 --fixtures .e2e-runs/edge-fixtures.json
```

Each phase writes `.e2e-runs/phase-N-results.json` and updates `e2e-test-matrix.xlsx`.

---

## 4. Case-by-case requirements

### Phase 1 — API negatives (41 cases) — **no extra infra**

| ID | Fixture | Action | Pass criteria |
|----|---------|--------|---------------|
| EC-027 | F08 | POST same Idempotency-Key twice | Same `applicationId` or 200 idempotent |
| EC-032 | — | POST body >10MB | 400/413 |
| EC-033 | F13 | POST transition from REJECTED | 400 invalid transition |
| EC-034 | F12 | POST transition from CLOSED | 400 |
| EC-035 | F14 | POST transition from FORECLOSED | 400 |
| EC-036 | — | POST transition from INVALID | 400 (after invalidate) |
| EC-037 | F10 | POST invalidate after DISBURSED | 400 |
| EC-038 | F08 | Invalidate idempotency replay | Same result |
| EC-039 | F09 | Transition without reasonCode | 400 |
| EC-041 | F08 | Upload >10MB PDF | 422 DOCUMENT_TOO_LARGE |
| EC-042 | F08 | Upload unknown documentType | 400 |
| EC-045 | F08 | Download missing doc type | 404 |
| EC-046 | F08 | POST disbursement on INITIALIZED | 400 |
| EC-047 | — | Disburse without schedule | 400 (if applicable) |
| EC-048 | F10 | Bank mismatch on disburse | 400/422 |
| EC-052 | F10 | Payment without targetInstallmentId | 400 |
| EC-053 | F10 | Payment amount < EMI | 400 |
| EC-054 | F10 | Second payment same installment | 400/409 (409 DUPLICATE via payment fingerprint) |
| EC-055 | F10 | Payment idempotency replay | 200 same receipt |
| EC-056 | F10 | channel=INVALID | 400 |
| EC-057 | F12 | Payment on CLOSED loan | 400 |
| EC-058 | F08 | Foreclosure quote pre-disburse | 400 |
| EC-059 | F10 | Execute expired quote | 400 |
| EC-060 | F12 | Foreclosure on CLOSED | 400 |
| EC-072 | — | PATCH bank invalid IFSC | 400 |
| EC-077 | — | Acknowledge random alert UUID | 404 |
| EC-078 | — | Acknowledge note >500 chars | 400 |
| EC-079 | — | Double acknowledge | 400/409 |
| EC-093 | — | audit-events?streams=INVALID | 400 |
| EC-094 | — | INTAKE audit payload scan | No raw 12-digit aadhaar |
| EC-095 | F10 | Download doc → access audit row | Audit entry exists |
| EC-098 | F11 | After payment 1 status | UNDER_REPAYMENT |
| EC-099 | F12 | After payment 12 | CLOSED transition row |
| EC-103 | F10 | Disbursement response | processingFee deducted (ADR 0004) |
| EC-106 | F10 | Holder name fuzzy mismatch | 400 or warning per policy |
| EC-107 | — | Invalid schedule submit | 400 |
| EC-108 | F10 | Schedule update post-disburse | 400 |
| EC-109 | F10 | Parallel disbursement POSTs | ≥1× 200; repeats 200 (idempotent) or 409; never 5xx |
| EC-110 | — | GET audit by correlationId | Events linked |
| EC-070 | — | MIS future date range | 200 empty rows |

### Phase 2 — Auth, session, RBAC (11 cases)

| ID | Fixture | Action | Pass criteria |
|----|---------|--------|---------------|
| EC-007 | — | Login → revoke-sessions → reuse access token | 401 |
| EC-008 | F16 | rotate-secret → token with old secret in grace | 200 then 401 after grace |
| EC-009 | F05 | /auth/token inactive LSP client | 401/403 |
| EC-015 | F03 | PRODUCT_ADMIN on /admin/lsps | 403 |
| EC-016 | — | Disable self (last SYSTEM_ADMIN) | 400 |
| EC-017 | — | reset-password → login → must change | passwordChangeRequired |
| EC-024 | — | Intake missing employment fields | 400 + rejection reason |
| EC-064 | F04 | OPS_USER webhook redrive | 403 |
| EC-069 | F04 | OPS_USER MIS download | 403 |
| EC-080 | F10 | Trigger same alert rule twice | Deduped count |

### Phase 3 — IP allowlist (3 cases)

| ID | Fixture | Action | Pass criteria |
|----|---------|--------|---------------|
| EC-010 | F15 | LSP API token from blocked IP | 403 |
| EC-011 | F15 | LSP UI login from blocked IP | 403 |
| EC-050 | — | mock-outcome with `local` profile off | 404/403 |

**Setup:** `PUT /admin/lsps/{id}/ip-allowlist` with CIDR **not** containing `127.0.0.1`. For local testing, use a second machine, Docker network alias, or reverse proxy that presents a non-allowed `X-Forwarded-For`.

### Phase 4 — Multi-tenant (3 cases)

| ID | Fixture | Action | Pass criteria |
|----|---------|--------|---------------|
| EC-012 | F02 | LSP B token → LSP A applicationId | 403/404 |
| EC-044 | F02 | LSP B download LSP A document | 403 |
| EC-071 | F02 | LSP B borrower lookup LSP A PAN | 404/empty |

### Phase 5 — Webhooks (7 cases)

**Requires mock HTTP server** on port 9090 (see `scripts/e2e/webhook-mock/README.md`).

| ID | Action | Pass criteria |
|----|--------|---------------|
| EC-061 | Subscribe `https://nonexistent.invalid` | 400 at subscribe |
| EC-062 | Subscribe mock 500 URL, dispatch | RETRYABLE_FAILURE in outbox |
| EC-063 | Subscribe mock 404 URL | PERMANENT_FAILURE |
| EC-065 | (Subscriber) missing signature header | Documented N/A or consumer test |
| EC-066 | Subscribe `http://169.254.169.254/...` | 400 SSRF blocked |
| EC-105 | Loan lifecycle | Webhook sequence monotonic per aggregate |

### Phase 6 — Rate limits (3 cases)

| ID | Action | Pass criteria |
|----|--------|---------------|
| EC-074 | 6+ POST /auth/login wrong password in 1 min | 429 |
| EC-075 | 6+ POST /auth/token in 1 min | 429 |
| EC-076 | 11+ POST /lsp/loan-applications in 1 min | 429 |

Set `APP_RATE_LIMIT_AUTH_PER_MINUTE=5` before phase. Use `scripts/e2e/phases/phase6_rate_limits.py` burst loop.

### Phase 7 — Negative fixtures / auto-rejection (4 cases)

| ID | Fixture | Pass criteria |
|----|---------|---------------|
| EC-018 | F06 | Intake → REJECTED PRODUCT_INACTIVE |
| EC-019 | F05 | Token or intake fails LSP_INACTIVE |
| EC-020 | F07 | REJECTED LSP_PRODUCT_MAPPING_INACTIVE |
| EC-100 | F13 | `rejection_reason_json` on transition row |

### Phase 8 — UI Chrome DevTools (10 cases)

See `scripts/e2e/ui-checklist.json`. Requires logged-in `ops.admin` session.

| ID | Route | Assertion |
|----|-------|-----------|
| EC-083 | `/loan-applications/{id}` | F5 refresh keeps session |
| EC-085 | `/home`, `/audit`, `/loan-applications` | No `console.error` |
| EC-086 | Block API in DevTools | ErrorState component visible |
| EC-087 | Filter to zero results | Empty state copy shown |
| EC-088 | `/audit` | Last page pagination works |
| EC-089 | `/loan-applications` | Status + LSP filters combine |
| EC-090 | Borrower/loan detail | PII mask toggle works |
| EC-096 | Detail vs API | Status label matches GET ops |
| EC-097 | Webhooks tab vs API | Event count matches |
| EC-111 | Detail page | API status change without reload stays stale (Fail = known bug) |

### Phase 9 — Data volume & regression (4 cases)

| ID | Requirement |
|----|-------------|
| EC-067 | Parse MIS preview JSON — no unmasked aadhaar |
| EC-068 | Seed ≥100 loans OR staging DB; CSV download <30s |
| EC-102 | Reproduce #89 401 loop per issue steps (may be N/A on fixed build) |
| EC-049 | Configure mock adapter failures until retry exhausted |

---

## 5. Known bugs — fix before or during coverage

| ID | Issue | Priority |
|----|-------|----------|
| EC-051 | Missing Idempotency-Key → **500** (expect 400) | P0 |
| UC-053 / EC-111 | UI stale after API change | P1 |
| EC-026 (historical) | Fixed PAN `ABCDE1234F` → 500 (new apps get 409) | P2 |

---

## 6. Acceptance criteria

1. **83 blocked → 0 blocked** in `e2e-test-matrix.xlsx`.
2. Each row has Pass/Fail/N/A, actual behavior, steps, and notes.
3. Newman run 5 still passes after edge phases (no fixture pollution — use dedicated LSP codes `E2E-EDGE-*` or `fixtures.py --cleanup`).
4. `.e2e-runs/coverage-summary.json` lists per-phase pass/fail counts.
5. UI phase documented with screenshots optional in `.e2e-runs/ui/`.

---

## 7. Suggested execution schedule

| Day | Work |
|-----|------|
| 1 | Fix EC-051; run `fixtures.py`; implement Phase 1 runner |
| 2 | Phases 2 + 7 (auth + auto-rejection fixtures) |
| 3 | Phases 4 + 6 (multi-tenant + rate limits) |
| 4 | Phase 5 (webhook mock server) + Phase 3 (IP allowlist) |
| 5 | Phase 8 UI (Chrome MCP) + Phase 9 + matrix finalization |

---

## 8. Commands quick reference

```bash
# Regression gate
npx newman run postman/LMS-E2E-Testing-Admin-and-LSP.postman_collection.json \
  -e postman/LMS-E2E-Local.postman_environment.json

# Fixtures
python scripts/e2e/fixtures.py --export .e2e-runs/edge-fixtures.json

# Full edge coverage
python scripts/e2e/run_coverage.py --phase all

# Single phase
python scripts/e2e/run_coverage.py --phase 1

# Update matrix from results
python scripts/e2e/run_coverage.py --phase 1 --update-matrix
```
