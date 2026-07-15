# LMS Platform — Full Scalability Audit Report

**Date:** 2026-06-14  
**Auditor scope:** End-to-end 360° review (read-only; no code changes)  
**Reference docs:** `scalability-execution-tracker.md`, `docs/perf/PERFORMANCE_REPORT.md`, `docs/perf/LOAD_TEST_PLAN.md`  
**Note:** `docs/scalability-doc.md` was **not found** in the repository. This audit synthesizes the execution tracker (which references `scalability-assessment-2026-06-10.md`, also absent from the tree), live codebase review, and 2026-06-14 perf harness results.

---

## 1. Executive summary

### Verdict

The LMS platform is **not production-ready** for the stated target of **100,000 loans/day** (~1M+ loans/month, ~82 blended API RPS at peak) with **10+ concurrent LSP tenants**. Controlled perf testing on 2026-06-14 measured **~32% readiness**; actuator health returned **503 DOWN** after modest concurrency (8 workers) with Hikari `maximum-pool-size=5`.

### What works today

| Strength | Evidence |
|----------|----------|
| Multi-tenant isolation foundation | Dual datasource routing, PostgreSQL RLS, `SET LOCAL app.current_lsp_id`, fail-closed tenant context |
| Webhook outbox | `FOR UPDATE SKIP LOCKED` claim, lease TTL, bounded batch (20), thread pool (10) |
| Report queue claiming | Postgres `SKIP LOCKED` on `report_request` |
| Payment same-key idempotency | DB unique on `idempotency_key`, fingerprint conflict → 409 |
| Disbursement application lock | `findByIdForUpdate` on `LoanApplication` in command service |
| Rate limiting infrastructure | Redis + Bucket4j, per-LSP write buckets, `Retry-After` on 429 |
| Perf harness | `scripts/perf/` load model, workflows, k6, baseline metrics captured |

### Critical blockers (P0 — money, availability, or tenant-wide outage risk)

1. **Connection pool exhaustion** — default/local Hikari max 5 per pool; no production sizing or `statement_timeout` (#201).
2. **Disbursement worker architecture** — single mega-transaction for entire backlog; no `SKIP LOCKED` claim; provider call inside DB transaction (#203, #204).
3. **Dashboard live O(portfolio) aggregates** — 32s p95 measured; full-book scan on every page view (#211, #212).
4. **LSP write rate limit (60/min)** vs origination burst (~11+ writes/loan) — partners cannot complete a single loan origination within the cap at peak (#229).
5. **Payment installment locking gap** — different idempotency keys can double-apply to same EMI (#206).
6. **LSP idempotency execute-before-claim** — concurrent duplicates can both execute business logic (#202).
7. **No table partitioning / retention** on audit and idempotency tables at 50–150M rows/year (#208, #209).
8. **No production observability** — Prometheus/Grafana, domain queue metrics, structured logging not deployed (#217–#219).

### Business impact if launched at target volume without fixes

| Risk | Impact |
|------|--------|
| Double disbursement (real bank adapter) | Direct financial loss, regulatory breach |
| Orphan / duplicate payments | Book integrity corruption, expensive reconciliation |
| Platform-wide outage from one bad query or dashboard load | All LSPs down simultaneously — SLA breach across book |
| One LSP spike blocks others | Partner churn, reputational damage |
| Inaccurate MIS / dashboard under load | Ops decisions on stale or wrong portfolio numbers |
| Audit explorer unbounded search | Accidental full-table scan → production incident |

---

## 2. Audit scope and load assumptions

### Tenant and traffic scenarios audited

| Scenario | Description |
|----------|-------------|
| Baseline | 10+ LSP tenants, normal mixed load |
| Single-LSP spike | One partner bursts origination/settlement while others are normal |
| Multi-LSP spike | Several partners spike together (e.g. settlement morning) |
| Platform-wide spike | All LSPs spike simultaneously |
| Sustained load | 100K loans/day average, 3.5× peak concentration, 10× burst windows |

### Volume model (`scripts/perf/load_model.py`, `LOAD_TEST_PLAN.md`)

| Tier | Loans/sec | Blended API RPS | Notes |
|------|----------:|----------------:|-------|
| Average (24h) | 1.16 | ~24 | 100K loans/day |
| Peak (3.5×) | 4.05 | **~82** | Business-hours concentration |
| Burst (10×, 15 min) | 11.57 | **~211** | Origination spike |
| Repayments (30-day book) | — | ~2.3+ | On top of origination |
| Ops dashboard (25 users) | — | ~0.8 | Amortized |

**Annual scale context** (from execution tracker): ~1M loans/yr (headroom 2M), 6–12M repayments/yr, 50–150M audit rows/yr, 3–10 LSPs (any may be high-volume). This audit assumes **10+ LSPs** and **100K loans/day** as requested.

### SLO targets (proposed in load plan; not met today)

| Metric | Target @ peak |
|--------|---------------|
| Loan create p95 | < 2s (observed **7.3s**) |
| Payment POST p95 | < 1s |
| Dashboard p95 | < 3s (observed **32.3s**) |
| Error rate | < 0.5% (observed **28.6%** under reporting scenario) |
| Actuator readiness | UP (observed **503** post-load) |

---

## 3. Findings catalog

Severity: **P0** (critical) · **P1** (high) · **P2** (medium) · **P3** (low)

Each finding includes: affected modules, business impact, and mapped remediation (GitHub issue where tracked).

---

### 3.1 API throughput and latency

#### F-API-01 · P0 · Connection pool starvation dominates tail latency

| Field | Detail |
|-------|--------|
| **Affected** | `TenantIsolationDataSourceConfig.java`, `application-local.yml`, all API + worker paths |
| **Current** | Local profile: `maximum-pool-size: 5` per pool (admin + tenant ≈ 10 JVM connections). No production Hikari overrides in `application.yml`. Supabase session pooler ~15 client cap documented in `backend/README.md`. |
| **Evidence** | `PERFORMANCE_REPORT.md`: health 503 after 8 concurrent workers; origination p95 7.3s |
| **Business impact** | Any sustained peak causes cascading timeouts; all tenants affected |
| **Fix** | #201 explicit pool sizing per deployment role; Postgres `statement_timeout`; separate report-worker DB role |

#### F-API-02 · P0 · LSP write rate limit incompatible with origination volume

| Field | Detail |
|-------|--------|
| **Affected** | `application.yml` (`lsp-write`: 60/min per LSP), `RateLimitFilter.java`, `KeyStrategy.java` |
| **Current** | Single bucket for all `POST/PUT/PATCH/DELETE` under `/api/v1/lsp/**`. One loan ≈ 11+ writes (create, ~8 docs, schedule, bank-check). Peak ~0.4 loans/sec/LSP needs ~4–5 writes/sec vs cap **1 write/sec**. |
| **Business impact** | Mass 429s during normal origination; partners cannot onboard at contracted volume |
| **Fix** | #229 per-LSP overrides + read lane; tiered limits (create vs doc vs payment); document contract |

#### F-API-03 · P1 · Per-request JWT session DB validation on every LSP call

| Field | Detail |
|-------|--------|
| **Affected** | `ApiClientJwtSessionValidator.java`, `SecurityConfig.java` (managed user validator) |
| **Current** | `apiClientRepository.findByClientId` via admin pool on **every** LSP JWT request (~2M calls/day at target) |
| **Business impact** | Admin pool contention; added fixed latency before business logic |
| **Fix** | Short-TTL in-memory cache of `tvApiClient` / `tvLsp` token versions; invalidate on credential rotation |

#### F-API-04 · P1 · Loan create latency ~7s p95

| Field | Detail |
|-------|--------|
| **Affected** | `LspLoanApplicationApiController.java`, borrower/application persistence, admin-scope create path |
| **Current** | Baseline harness: p95 7,339ms; target <2s |
| **Business impact** | Partner timeouts → duplicate retries → idempotency pressure and support load |
| **Fix** | Pool sizing (#201); natural-key replay (#236); profile hot path queries |

#### F-API-05 · P1 · No read-rate limits on LSP GET endpoints

| Field | Detail |
|-------|--------|
| **Affected** | Most `GET /api/v1/lsp/**` except document download (120/min) |
| **Current** | Aggressive status polling unbounded |
| **Business impact** | One poller can amplify DB load; noisy-neighbor on shared DB |
| **Fix** | #229 read lane (300/min default) |

#### F-API-06 · P2 · Document upload holds DB connection during object storage I/O

| Field | Detail |
|-------|--------|
| **Affected** | `LoanDocumentService.persistStoredDocumentForLsp` (`@Transactional` wraps `store()` + checklist update) |
| **Current** | R2/MinIO round trip inside transaction; 3–6M uploads/yr |
| **Business impact** | Upload burst ties up tenant pool; amplifies F-API-01 |
| **Fix** | #225 store bytes first, short tx for metadata |

#### F-API-07 · P2 · Auth token rate limit 10/min per IP

| Field | Detail |
|-------|--------|
| **Affected** | `application.yml` auth-token rule, `KeyStrategy.clientIp()` uses `getRemoteAddr()` not forwarded IP |
| **Business impact** | Shared NAT egress across integrations; mis-keyed limits behind load balancer |
| **Fix** | Align IP resolution with `ClientIpAddresses.resolve()`; consider per-client-id bucket for token |

#### F-API-08 · P3 · Ops alert on every 429

| Field | Detail |
|-------|--------|
| **Affected** | `RateLimitFilter.java` → `OpsAlertEmitters.emitRateLimitBreach` |
| **Business impact** | Alert storm when limits are too low (likely at launch) |
| **Fix** | Sample/dedupe rate-limit alerts; fix limits first (#229) |

---

### 3.2 Database scalability

#### F-DB-01 · P0 · No statement_timeout / idle_in_transaction timeout

| Field | Detail |
|-------|--------|
| **Affected** | App config, Postgres roles |
| **Current** | One pathological query or stuck transaction can hold pool slots indefinitely |
| **Business impact** | Single tenant report or audit search → full platform outage |
| **Fix** | #201 |

#### F-DB-02 · P0 · No table partitioning on high-growth tables

| Field | Detail |
|-------|--------|
| **Affected** | Audit tables, `webhook_event_delivery_attempt`, `lsp_api_idempotency_record`, eventually `loan_payment_transaction` |
| **Current** | Monolithic tables; no `PARTITION BY` in migrations |
| **Business impact** | Vacuum/index bloat; explorer and retention become multi-week projects at 100M+ rows |
| **Fix** | #208 (pre-launch), #209 retention sweeps |

#### F-DB-03 · P1 · Dashboard and MIS full-portfolio queries

| Field | Detail |
|-------|--------|
| **Affected** | `LoanAccountRepository.findHomeDashboardAccountSnapshots`, `PortfolioMisReadRepository.summarize`, `AdminReportingService` CSV export |
| **Current** | O(accounts × installments) aggregation per request; correlated EXISTS for PAR30; full export in memory |
| **Business impact** | Admin UI unusable at 500K+ accounts; OOM risk on year-long MIS |
| **Fix** | #211–#212 snapshots; #227 streaming reports |

#### F-DB-04 · P1 · Disbursement worker `findByStatus` unbounded

| Field | Detail |
|-------|--------|
| **Affected** | `LoanDisbursementWorkerService.processStatus` → `loanApplicationRepository.findByStatus` |
| **Current** | Loads all pending rows every 30s; no LIMIT/SKIP LOCKED |
| **Business impact** | Memory and query cost grow with backlog; multi-pod duplicate work |
| **Fix** | #203 claim N=25 via SKIP LOCKED |

#### F-DB-05 · P1 · RLS child-table policy overhead at payment volume

| Field | Detail |
|-------|--------|
| **Affected** | `V41__tenant_isolation_rls.sql` — `tenant_owns_loan_account()` EXISTS on installments/payments |
| **Current** | Per-row policy evaluation on 6–12M payments/yr |
| **Business impact** | Measurable insert latency; acceptable if pools sized; problematic combined with F-API-01 |
| **Fix** | Monitor via #217; consider denormalized `lsp_id` on child tables long-term |

#### F-DB-06 · P2 · Audit explorer missing partition-friendly date guard

| Field | Detail |
|-------|--------|
| **Affected** | `AuditExplorerRepository.java`, `AuditExplorerController.java` |
| **Current** | Optional `since`/`until`; 8-way UNION ALL; OFFSET pagination; COUNT(*) wrapper |
| **Business impact** | "Search everything" is an outage button at 50–150M audit rows/yr |
| **Fix** | #214 mandatory window (7d default, 90d max); keyset pagination |

#### F-DB-07 · P2 · REPORT_ACCESS LSP filter via JSONB regex

| Field | Detail |
|-------|--------|
| **Affected** | `AuditExplorerRepository.reportAccessFilterPayloadLspIdExpression` |
| **Current** | Not indexable |
| **Business impact** | Slow LSP-scoped audit research |
| **Fix** | #214 indexed `lsp_id` column on audit rows where applicable |

#### F-DB-08 · P2 · Idempotency table unbounded growth

| Field | Detail |
|-------|--------|
| **Affected** | `lsp_api_idempotency_record` (full response JSON in TEXT) |
| **Current** | No TTL purge |
| **Business impact** | Tens of GB PII storage; index bloat; 90-day replay cliff when #209 lands |
| **Fix** | #209; prefer natural-key replay for loan create (#236) |

#### F-DB-09 · P2 · Supabase session pooler ~15 connection ceiling

| Field | Detail |
|-------|--------|
| **Affected** | Deployment architecture |
| **Current** | Cannot scale API pods with 20–50 connections each on shared pooler |
| **Business impact** | Hard ceiling on horizontal scale until dedicated Postgres or transaction pooler |
| **Fix** | Infrastructure decision; document in ADR; #198 staging topology |

---

### 3.3 Multi-tenant isolation and noisy-neighbor risks

#### F-TEN-01 · P2 · Isolation model is sound; shared-resource pools are the weak point

| Field | Detail |
|-------|--------|
| **Affected** | `TenantRoutingDataSource`, `TenantAwareDataSource`, `V41`/`V45` RLS, `AuthenticationTenantScopeFilter`, `V71` report_request RLS |
| **Current** | Data isolation at SQL layer is well-designed and tested (`TenantIsolationPostgresIntegrationTest`) |
| **Business impact** | **Data leakage risk: low** at application layer; **performance leakage: high** via shared DB pool, Redis, webhook thread pool |
| **Fix** | Per-tenant rate limits (#229), webhook per-LSP cap (#230), pool/timeout (#201) |

#### F-TEN-02 · P1 · No per-LSP resource quotas (writes, reads, webhooks)

| Field | Detail |
|-------|--------|
| **Affected** | Static global limits only |
| **Current** | Whale LSP at 5K loans/day ≈ 64 writes/min sustained — above 60/min default (per tracker D4 math) |
| **Business impact** | High-volume partner either throttled or, if limits raised globally, small partners starved |
| **Fix** | #229 DB-backed per-LSP overrides |

#### F-TEN-03 · P1 · Shared webhook dispatch pool (10 threads, no per-LSP cap)

| Field | Detail |
|-------|--------|
| **Affected** | `WebhookDispatchConfig.java`, `WebhookOutboxDispatchWorker.java` (batch 20, 60s delay) |
| **Current** | ~0.33 events/sec/instance; need ~7+ instances for 2.3 events/sec average; one dead partner endpoint can saturate pool |
| **Business impact** | One LSP's integration failure delays all partners' webhooks |
| **Fix** | #230 per-LSP in-flight cap; scale workers/pods |

#### F-TEN-04 · P2 · Alert evaluation scans full portfolio × pod count

| Field | Detail |
|-------|--------|
| **Affected** | `AlertRuleEvaluationWorker.java` — all `UNDER_REPAYMENT`, all `DISBURSEMENT_RETRY` |
| **Current** | Every pod runs scheduler; dedupe on alert insert only |
| **Business impact** | DB read amplification; 5-min O(N) scans compete with API |
| **Fix** | #211 materialized KPIs; leader election or single worker pod (D1 topology) |

#### F-TEN-05 · P3 · LSP write rate limit skipped if JWT lacks lspId

| Field | Detail |
|-------|--------|
| **Affected** | `KeyStrategy.LSP` returns empty buckets when `lspId == null` |
| **Business impact** | Misconfigured token could bypass LSP limits |
| **Fix** | Fail closed: reject or fall back to IP limit |

---

### 3.4 Failure isolation

#### F-ISO-01 · P0 · Redis unavailable → rate limit behavior undefined

| Field | Detail |
|-------|--------|
| **Affected** | `RateLimitFilter`, `RateLimitConfig` |
| **Current** | No documented fail-open/fail-closed policy; Redis down likely 500s on limited paths |
| **Business impact** | Redis HA incident becomes platform outage for all LSP traffic |
| **Fix** | #223 fail-open for business traffic, fail-closed for auth |

#### F-ISO-02 · P1 · No circuit breaker on external dependencies

| Field | Detail |
|-------|--------|
| **Affected** | Disbursement adapter, R2 storage, Redis, email (reports) |
| **Current** | Timeouts exist in some paths; no bulkhead/circuit breaker pattern in codebase |
| **Business impact** | Slow bank or storage degrades entire worker batch (disbursement mega-tx) |
| **Fix** | #204 parallel pool + intent pattern; resilience4j or equivalent for adapters |

#### F-ISO-03 · P1 · All schedulers run on every pod

| Field | Detail |
|-------|--------|
| **Affected** | `LoanDisbursementWorker`, `WebhookOutboxDispatchWorker`, `ReportRequestProcessingWorker`, `AlertRuleSchedulerWorker` |
| **Current** | `@EnableScheduling` on all instances; only webhooks/reports have DB claim |
| **Business impact** | Disbursement and alert workers multiply work and race without claim |
| **Fix** | D1 topology (2 API + 1 worker with flags); #203 disbursement claim |

#### F-ISO-04 · P2 · Report worker holds one transaction for full batch

| Field | Detail |
|-------|--------|
| **Affected** | `ReportRequestService.processPendingRequests` |
| **Current** | Claim + generate + upload + email in single `@Transactional` |
| **Business impact** | Long report blocks connection; failure rolls back entire batch |
| **Fix** | #213 tx split + retry lease |

---

### 3.5 Disbursement and repayment data consistency

#### F-MNY-01 · P0 · Provider call inside database transaction

| Field | Detail |
|-------|--------|
| **Affected** | `LoanDisbursementCommandService.initiateDisbursement` |
| **Current** | `loanDisbursementAdapter.requestDisbursement` before commit; mock adapter masks risk |
| **Business impact** | Crash after bank success, before commit → retry may **double-pay** at real bank |
| **Fix** | #204 intent row + call outside tx + sweeper — **implemented 2026-07-13 (Spec S3)**; see `docs/implementation-log.md` |

> **Remediation note (2026-07-13):** `disbursement_intent` workflow landed behind `app.disbursement.intent-workflow.enabled`. This historical finding describes pre-S3 behavior.

#### F-MNY-02 · P0 · Disbursement worker single mega-transaction

| Field | Detail |
|-------|--------|
| **Affected** | `LoanDisbursementWorkerService.processPendingDisbursements` — self-invocation merges per-loan `@Transactional` into outer tx |
| **Current** | 2K backlog = hours-long transaction, one failure rolls back all |
| **Business impact** | Worker unsafe for horizontal scale; connection held for entire batch |
| **Fix** | #203 separate executor bean, per-loan tx |

#### F-MNY-03 · P0 · No disbursement work claiming for multi-instance

| Field | Detail |
|-------|--------|
| **Affected** | `LoanDisbursementWorkerService` |
| **Current** | Multiple pods process same `APPROVED_PENDING_DISBURSAL` set; mitigated only by app row lock at initiate |
| **Business impact** | Wasted work; race window before lock; unsafe with real adapter latency |
| **Fix** | #203 `FOR UPDATE SKIP LOCKED` claim |

#### F-MNY-04 · P1 · Payment claim in REQUIRES_NEW separate from allocation

| Field | Detail |
|-------|--------|
| **Affected** | `IdempotencyClaimService.claimLoanPaymentRow`, `LoanRepaymentCommandService` |
| **Current** | Payment row can commit before installment allocation; outer tx failure → orphan RECEIVED payment |
| **Business impact** | Book shows payment without allocation; LSP told success incorrectly |
| **Fix** | #205 same-transaction claim + allocation |

#### F-MNY-05 · P1 · No installment row lock for concurrent different-key payments

| Field | Detail |
|-------|--------|
| **Affected** | `LoanRepaymentCommandService.resolveTargetInstallment` |
| **Current** | No `SELECT FOR UPDATE` on installment; no `@Version` on installment entity |
| **Business impact** | Settlement batch + retries → silent double-payment on same EMI |
| **Fix** | #206 |

#### F-MNY-06 · P1 · LSP idempotency executes before claim

| Field | Detail |
|-------|--------|
| **Affected** | `LspApiIdempotencyService.execute` line 55: `action.get()` before `claimLspApiIdempotencyRecord` |
| **Current** | Concurrent duplicate requests can both mutate state |
| **Business impact** | Duplicate foreclosure, invalidation edge cases; retry storms at volume |
| **Fix** | #202 claim-before-execute |

#### F-MNY-07 · P1 · Loan create idempotency optional; natural key not replay-friendly

| Field | Detail |
|-------|--------|
| **Affected** | `LspLoanApplicationApiController` create path |
| **Current** | Without `Idempotency-Key`, no dedupe; duplicate external id → 400 not 409/200 replay |
| **Business impact** | ~1M creates/yr × daily timeout retries → partner errors and support tickets |
| **Fix** | #236 natural-key `(lsp_id, external_loan_id)` replay |

#### F-MNY-08 · P2 · No payment bounce/reversal at scale

| Field | Detail |
|-------|--------|
| **Affected** | Payment status enum, allocation rollback |
| **Current** | BOUNCED/REVERSED not implemented |
| **Business impact** | 5–15% NACH bounce on 6–12M payments → book overstates collections within month 1 |
| **Fix** | #222 |

#### F-MNY-09 · P2 · Disbursement retry budget via non-atomic count

| Field | Detail |
|-------|--------|
| **Affected** | `loanDisbursementRequestLogRepository.countByLoanAccount_Id` in worker |
| **Current** | Racy across workers |
| **Business impact** | Extra provider calls before status moves to RETRY |
| **Fix** | #203 atomic attempt counter on claim row |

#### F-MNY-10 · P2 · Bulk repayment ingestion absent

| Field | Detail |
|-------|--------|
| **Affected** | LSP payment API only (per-EMI POST) |
| **Current** | 5K settlement rows at 60/min = ~83 minutes |
| **Business impact** | Settlement mornings cannot complete in window |
| **Fix** | #231 payment inbox + drain worker |

---

### 3.6 Reporting accuracy under high volume

#### F-RPT-01 · P0 · Dashboard serves live aggregates, not point-in-time snapshots

| Field | Detail |
|-------|--------|
| **Affected** | `HomeDashboardService.getSummary`, `GET /api/v1/internal/home/overview` |
| **Current** | Full portfolio scan + in-memory LSP breakdown; N+1 approval TAT (`computeAvgApprovalTatHours`) |
| **Evidence** | p95 **32,291ms** under reporting scenario |
| **Business impact** | Ops decisions on numbers that are slow, inconsistent, and not reproducible |
| **Fix** | #211–#212 nightly KPI snapshot + "Data as of" UI |

#### F-RPT-02 · P1 · MIS CSV full hydration in memory

| Field | Detail |
|-------|--------|
| **Affected** | `AdminReportingService`, `ReportRequestService.generatePortfolioMisCsv` |
| **Current** | `findAccountsForExport` + `byte[]` CSV in heap |
| **Business impact** | OOM on year-long MIS at 1M loans; inaccurate timing if generated under concurrent writes |
| **Fix** | #227 streaming; #213 consistent snapshot boundary in job |

#### F-RPT-03 · P1 · Report async pipeline race and no retry lease

| Field | Detail |
|-------|--------|
| **Affected** | `ReportRequestProcessingWorker` (15s delay, batch 10) |
| **Current** | Harness 75% download errors (poll vs 15s worker); stuck FAILED silent |
| **Business impact** | Partners/ops missing MIS; false "ready" states |
| **Fix** | #213 retry + lease; align poll timeouts in clients |

#### F-RPT-04 · P2 · Sync report download proxies bytes through JVM

| Field | Detail |
|-------|--------|
| **Affected** | `ReportAdminController` download endpoints |
| **Current** | No presigned URLs |
| **Business impact** | Heap and bandwidth double-hop; blocks LSP self-serve API |
| **Fix** | #228 time-limited URLs |

#### F-RPT-05 · P2 · PAR30 summary uses correlated EXISTS per account

| Field | Detail |
|-------|--------|
| **Affected** | `PortfolioMisReadRepository.summarize` |
| **Current** | Full scan on wide date ranges |
| **Business impact** | MIS preview timeout under load |
| **Fix** | #211 precomputed delinquency buckets |

#### F-RPT-06 · P3 · No nightly per-LSP MIS enqueuer

| Field | Detail |
|-------|--------|
| **Affected** | Report scheduling |
| **Current** | Manual ops trigger |
| **Business impact** | Ops bottleneck at 10 partners |
| **Fix** | #232 |

---

### 3.7 Audit logging reliability

#### F-AUD-01 · P1 · Sustained high append rate without partition/retention

| Field | Detail |
|-------|--------|
| **Affected** | `LoanApplicationLifecycleService`, `AuthAuditService`, `ReportAccessAuditService`, `LspAuditEventService`, webhook delivery attempts |
| **Current** | 50–150M rows/yr projected; no drop/archival |
| **Business impact** | Compliance evidence at risk if DB degrades; storage cost unbounded |
| **Fix** | #208 partition; #209 retention; cold archive (Phase 4) |

#### F-AUD-02 · P1 · Audit explorer 8-stream UNION without mandatory date bound

| Field | Detail |
|-------|--------|
| **Affected** | `AuditExplorerRepository.search`, `GET /api/v1/internal/admin/audit-events` |
| **Current** | All 8 streams default; OFFSET deep pages |
| **Business impact** | Incident investigation tool becomes outage vector |
| **Fix** | #214 |

#### F-AUD-03 · P2 · Incomplete audit surface in explorer

| Field | Detail |
|-------|--------|
| **Affected** | `auth_event_audit`, `lsp_audit_event`, `webhook_event_delivery_attempt` not in UNION |
| **Current** | Separate or no unified view |
| **Business impact** | Ops must run ad-hoc SQL for webhook/auth forensics at scale |
| **Fix** | Extend explorer with guardrails; or link to report pipeline (#233) |

#### F-AUD-04 · P2 · Audit writes synchronous in request path

| Field | Detail |
|-------|--------|
| **Affected** | Lifecycle and document access audit inserts |
| **Current** | Same transaction as business action (correct for consistency) |
| **Business impact** | Extra write latency per request; acceptable if DB healthy; amplifies under load |
| **Fix** | Monitor insert p95; partition reduces index contention |

---

### 3.8 Background jobs and queue scalability

| Worker | Interval | Batch | Multi-instance safe? | Throughput @ 100K/day | Issue |
|--------|----------|-------|----------------------|----------------------|-------|
| Disbursement | 30s fixed delay | ∞ (all pending) | **No** | Insufficient at peak | #203–#204 |
| Webhook dispatch | 60s | 20 | **Yes** (SKIP LOCKED) | ~0.33/s/instance — need scale-out | #230 |
| Report processing | 15s | 10 | **Yes** (PG) | 40 jobs/hr/instance | #213, #227 |
| Alert rules | 300s | Full scan | Partial (dedupe) | O(portfolio) × pods | #211 |

#### F-WKR-01 · P1 · Fixed-delay semantics amplify backlog

All workers use `fixedDelay` (next run starts after previous completes). Slow disbursement batch pushes next tick — backlog grows super-linearly.

#### F-WKR-02 · P2 · RabbitMQ in infra but unused (#226)

`infra/docker-compose.yml` includes RabbitMQ; no consumers in disbursement/payment path. Operational surface without benefit.

#### F-WKR-03 · P2 · No domain queue depth metrics

Cannot alert on oldest-pending webhook/report/disbursement age until #234.

---

### 3.9 Rate limiting, backpressure, retries, idempotency, timeouts

| Mechanism | Status | Gap |
|-----------|--------|-----|
| Per-LSP write limit | 60/min global | Too low; no per-endpoint tiers (#229) |
| Per-LSP read limit | None | Poll storms (#229) |
| `Retry-After` on 429 | Yes | Good |
| Payment idempotency | DB unique key | Installment lock missing (#206) |
| LSP API idempotency | Partial endpoints | Execute-before-claim (#202); optional on create |
| Disbursement idempotency | App status gate | No provider reference uniqueness (#204) |
| Webhook retry | Exponential backoff | Good; per-LSP isolation missing (#230) |
| Report retry | None | #213 |
| HTTP client timeouts | Partial | Bank adapter needs explicit bulkhead (#210 ADR) |
| Conflict → 409 | Partial | Unique violations still 500 globally (#207) |

---

### 3.10 Security under scale

#### F-SEC-01 · P1 · No API client credential lockout

| Field | Detail |
|-------|--------|
| **Affected** | LSP API client auth |
| **Current** | AppUser has lockout (V94); ApiClient does not |
| **Business impact** | Distributed credential stuffing against machine surface |
| **Fix** | #224 |

#### F-SEC-02 · P2 · CORS hardcoded to localhost

| Field | Detail |
|-------|--------|
| **Affected** | `SecurityConfig.java` |
| **Current** | `localhost:5173/4200` only |
| **Business impact** | Production ops UI blocked; emergency misconfig risk |
| **Fix** | #237 |

#### F-SEC-03 · P2 · Upload malware scanning absent

| Field | Detail |
|-------|--------|
| **Affected** | Document upload path |
| **Current** | 3–6M partner files served to ops browsers |
| **Business impact** | Malware delivery channel at scale |
| **Fix** | #221 (after #225) |

#### F-SEC-04 · P2 · Rate limit Redis SPOF (#223)

See F-ISO-01.

#### F-SEC-05 · P3 · IP allowlist cached 60s; rate limit IP from wrong header

Allowlist uses forwarded IP; rate limit auth may not — inconsistent abuse detection.

---

### 3.11 Uptime, resilience, monitoring, alerting, recovery

#### F-OPS-01 · P0 · No Prometheus/metrics endpoint exposed

| Field | Detail |
|-------|--------|
| **Affected** | `management.endpoints.web.exposure.include: health,info` only |
| **Current** | Micrometer used in `GlobalExceptionHandler`, `LoanAutoApprovalGateService` but no scrape target |
| **Business impact** | Cannot detect pool exhaustion, queue stall, or per-LSP degradation before partner complaint |
| **Fix** | #217, #219 |

#### F-OPS-02 · P0 · Alerts stored but not delivered

| Field | Detail |
|-------|--------|
| **Affected** | `OpsAlertService`, `OpsAlertController` |
| **Current** | Detection exists; no email/webhook fan-out (#235) |
| **Business impact** | Stuck disbursement, KPI failure, partition missing — invisible to on-call |
| **Fix** | #235, #220 runbooks |

#### F-OPS-03 · P1 · No structured JSON logging / MDC in production

| Field | Detail |
|-------|--------|
| **Affected** | Logging config |
| **Current** | `correlationId` exists; limited `lspId`/loan context in logs |
| **Business impact** | 2am incident scoping across 10 partners impractical |
| **Fix** | #218 |

#### F-OPS-04 · P1 · No runbooks or alert path verification

| Field | Detail |
|-------|--------|
| **Affected** | Operations docs |
| **Current** | Failure modes documented in assessment tracker only |
| **Business impact** | MTTR measured in hours not minutes |
| **Fix** | #220 |

#### F-OPS-05 · P2 · Health probe lacks dependency granularity

| Field | Detail |
|-------|--------|
| **Affected** | Actuator health |
| **Current** | 503 observed under load; unclear if DB pool vs Redis |
| **Business impact** | K8s kills healthy-ish pods or keeps unhealthy ones |
| **Fix** | #217 custom health contributors; liveness vs readiness split |

#### F-OPS-06 · P2 · No synthetic portfolio or multi-instance test bed

| Field | Detail |
|-------|--------|
| **Affected** | Test infrastructure |
| **Current** | Small-N dev data; perf on local single instance |
| **Business impact** | Regressions found in production not CI |
| **Fix** | #197–#200 |

---

### 3.12 Production readiness gaps (additional)

| Gap | Severity | Notes |
|-----|----------|-------|
| Single-instance perf only | P1 | #198 two-instance stack not built |
| Stress/spike/soak scenarios not run | P1 | Aborted in perf report |
| No read replica / CQRS | P2 | Phase 4 trigger-driven |
| No LSP self-serve reports API | P2 | #215 |
| ICICI / real bank adapter not production-hardened | P0 at bank go-live | #210 ADR; depends on #203–#204 |
| Auto-approval gate under load | P2 | `LoanAutoApprovalGateService` sync in upload path — metered but adds DB work per doc |
| `open-in-view: false` | ✓ Good | Avoids lazy-load leaks |
| Idempotency replay test passes | ✓ Good | `race.idempotency_replay` in perf report |

---

## 4. Scenario analysis

### 4.1 Ten LSPs, normal sustained load (~82 RPS peak blended)

**Expected failure mode today:** Connection pool exhaustion within minutes → rising latencies → 503 health → cascading 500s. Dashboard and reporting unusable. Webhook backlog grows (~2.3 events/sec needed vs ~0.33/instance). Disbursement worker sequential bottleneck cannot clear 8K/day peak disbursements with 30s tick.

### 4.2 One LSP spikes (origination or settlement)

| Vector | Isolation today? | Outcome |
|--------|----------------|---------|
| Write burst | Per-LSP bucket only | Spike LSP hits 429; may retry aggressively → **more** load |
| Read polling | No read limit | Spike LSP increases DB CPU for all |
| Webhooks | Shared 10-thread pool | Dead webhook endpoint for spike LSP blocks others (#230) |
| DB connections | Shared pools | Upload burst holds connections (#225) → all tenants throttle |

**Verdict:** **Poor failure isolation.** One noisy partner degrades platform.

### 4.3 Multiple LSPs spike together

Settlement morning: repayment POSTs + bulk files (no bulk API) + webhook fan-out. Payment races on same installment (#206) likely. Rate limits cause widespread 429s. Alert scheduler multiplies full-portfolio scans. **High probability of coordinated outage** without #205–#206, #229, #231.

### 4.4 All LSPs spike simultaneously

Platform-wide spike (~211 RPS burst) exceeds capacity by **~55×** vs observed degradation at ~1.5 RPS. Redis, Postgres, and JVM threads saturate together. No backpressure except 429 — no admission control at gateway. **Complete service degradation expected**; financial correctness at risk on disbursement (#203–#204) and payments (#206).

---

## 5. API and module inventory (load-relevant)

### LSP API (partner-facing)

| Endpoint group | Methods | Rate limit | Idempotency | Primary risk |
|----------------|---------|------------|-------------|--------------|
| `/api/v1/auth/token` | POST | 10/min IP | — | NAT/shared IP |
| `/api/v1/lsp/loan-applications` | POST, GET | 60/min write | Optional header / #236 natural key | Create latency, 429 |
| `.../documents` | POST | 60/min write | None | Tx holds connection |
| `/api/v1/lsp/loans/{id}/payments` | POST | 60/min write | DB key | Installment race |
| `/api/v1/lsp/loans/**` | GET | None | — | Poll storm |
| `/api/v1/lsp/products` | GET | None | — | Low |

**Controllers:** `LspLoanApplicationApiController`, `LspLoanApiController`, `LspBorrowerApiController`, `LspProductApiController`  
**Filters:** `LspApiPayloadSizeFilter` (10MB), `LspSurfaceIpAllowlistFilter`, `RateLimitFilter`

### Ops / admin API

| Endpoint | Risk |
|----------|------|
| `GET /api/v1/internal/home/overview` | **Critical** — live aggregates |
| `GET/POST /api/v1/internal/reports/**` | Memory, long tx |
| `GET /api/v1/internal/admin/audit-events` | UNION full scan |
| `POST .../disbursement-requests` | Row lock contention |
| `GET /api/v1/internal/alerts` | Lower risk |

### Background modules

| Module | File |
|--------|------|
| Disbursement | `LoanDisbursementWorker`, `LoanDisbursementWorkerService`, `LoanDisbursementCommandService` |
| Payments | `LoanRepaymentCommandService`, `LoanForeclosureCommandService` |
| Webhooks | `WebhookOutboxDispatchWorker`, `WebhookOutboxService` |
| Reports | `ReportRequestProcessingWorker`, `ReportRequestService`, `AdminReportingService` |
| Alerts | `AlertRuleSchedulerWorker`, `AlertRuleEvaluationWorker`, `OpsAlertService` |
| Dashboard | `HomeDashboardService` |

### Data layer

| Component | File |
|-----------|------|
| Tenant routing | `TenantIsolationDataSourceConfig`, `TenantAwareDataSource`, `AdminScopedTransactionExecutor` |
| RLS | `V41`, `V45`, `V71` migrations |
| Hot queries | `LoanAccountRepository`, `PortfolioMisReadRepository`, `AuditExplorerRepository` |

---

## 6. Prioritized remediation roadmap

Aligned with `scalability-execution-tracker.md` phases. **Do not production-launch at 100K/day before Phase 0–1 exit criteria.**

### Immediate (P0 — before any production volume)

| Priority | Issue | Title |
|----------|-------|-------|
| 1 | #201 | Hikari sizing + statement timeouts |
| 2 | #203 | Disbursement claim + per-loan transactions |
| 3 | #204 | Intent rows + provider outside tx |
| 4 | #205, #206 | Payment claim atomicity + installment locking |
| 5 | #202 | Claim-before-execute idempotency |
| 6 | #211, #212 | KPI snapshot + dashboard serves snapshots |
| 7 | #229 | Per-LSP rate limits (reads + writes) |
| 8 | #223 | Redis failure policy |

### High (P1 — before 1M loans/month sustained)

| Issue | Title |
|-------|-------|
| #207 | Conflicts → 409 globally |
| #213, #227 | Report worker tx split + streaming |
| #214 | Audit explorer guardrails |
| #208, #209 | Partition + retention |
| #225 | Upload outside transaction |
| #230 | Webhook per-LSP concurrency cap |
| #217, #234, #235 | Metrics + domain metrics + alert delivery |
| #236 | Loan-create natural-key replay |
| #222 | Payment bounce/reversal |

### Medium (P2 — operational excellence)

#198–#200 test bed, #228 presigned URLs, #231 bulk repayments, #232–#233 reporting automation, #219–#220 Grafana/runbooks, #224 API client lockout, #237 CORS, #226 Rabbit removal.

### Validation gate

Re-run full perf matrix (`scripts/perf/run.py` stress/spike/soak at peak tier) on **two-instance staging (#198)** with **synthetic portfolio (#197)** before go-live sign-off.

---

## 7. Readiness scorecard (2026-06-14)

| Dimension | Score (0–10) | Target for 100K/day | Blocker issues |
|-----------|-------------:|----------------------|----------------|
| API throughput & latency | 3 | 8+ | #201, #229, #225 |
| Database scalability | 3 | 8+ | #201, #208, #211 |
| Tenant isolation (data) | 8 | 9 | — |
| Tenant isolation (performance) | 3 | 8+ | #229, #230 |
| Failure isolation | 4 | 8+ | #223, #203, #230 |
| Disbursement consistency | 4 | 10 | #203, #204 |
| Repayment consistency | 5 | 10 | #205, #206, #222 |
| Reporting accuracy | 3 | 8+ | #211–#213, #227 |
| Audit reliability | 5 | 8+ | #208, #214 |
| Background job scale | 4 | 8+ | #203, #213, #230 |
| Security at scale | 5 | 8+ | #224, #223, #221 |
| Observability & alerting | 2 | 9+ | #217–#220, #235 |
| **Overall** | **~32%** | **≥85%** | Phase 0–1 incomplete |

---

## 8. Conclusion

The platform has a **strong multi-tenant data model** and **mature patterns in webhook and report claiming**, but **money-path workers (disbursement, payment allocation)** and **read-path aggregates (dashboard, MIS, audit explorer)** are architected for low volume. At **100K loans/day with 10+ LSPs**, the system will exhibit **platform-wide outages**, **partner-visible 429 storms**, **incorrect or unavailable reporting**, and **unacceptable financial correctness risk** on disbursement and concurrent repayments.

The existing execution plan (`scalability-execution-tracker.md`, issues **#197–#237**) correctly identifies the remediation sequence. **No new architectural direction is required** — the gap is **implementation and verification**. This audit recommends treating Phase 0–1 as a **hard gate** before production promotion, then re-running the perf harness at peak/burst tiers with multi-instance staging.

---

*Report generated from codebase review and `scripts/perf` results. No application code was modified during this audit.*
