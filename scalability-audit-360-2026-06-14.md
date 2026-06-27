# Bhawana LMS — 360° Scalability & Production-Readiness Audit (v2)

**Date:** 2026-06-14
**Method:** First-principles source review. Every finding below was verified directly in the named file/line; no code was changed. This audit deliberately re-derives conclusions rather than trusting the prior assessment.
**Status of the requested input file:** `docs/scalability-doc.md` **does not exist** in the repo. The existing scalability material is `scalability-assessment-2026-06-10.md` (v1.4) and `scalability-execution-tracker.md` (root). Both were read in full and are treated as the "previous audit" this review deepens.

---

## 0. What changed since the previous audit — the operating point

The prior assessment (v1.1–v1.4) is genuinely good work, but it is scoped to **~1M loans/_year_** (planning), **~2,700 loans/day average, ~8,000/day peak**, **3–10 LSPs**.

This audit is scoped to the operating point you gave me, which is **~12–37× larger**:

| Dimension | Previous audit | This audit (your stated target) |
|---|---|---|
| Loans/day | ~2.7K avg / ~8K peak | **~100K/day** |
| Loans/month | ~85K | **~1M+/month** |
| Tenants | 3–10 | **10+ concurrent**, any may spike alone, several together, or all at once |
| Repayments/day | ~20–30K | **~300–500K/day** (12M+ active book at steady state) |
| Webhook events/day | ~50–150K | **~500K–1M/day** (5–10 lifecycle events/loan) |
| Audit rows/year | 50–150M | **0.5–2B+/year** |

**This is not the same system review.** Several things the prior doc graded "fine at this scale" or "P1/P2" are **hard ceilings** at 100K/day. Three components have **fixed throughput ceilings in code** that sit *below* the new arrival rate regardless of how big the database is. The headline is no longer "correct at low concurrency, unsafe at high concurrency" — it is **"the background tier and the connection layer cannot physically move 100K/day as written, and one tenant can take down the other nine."**

---

## 1. Executive summary (plain language)

1. **The background-job tier is single-threaded and under-provisioned by 1–2 orders of magnitude.** All four `@Scheduled` workers (disbursement, webhook, reports, alerts) run on **one** thread — Spring's default scheduler pool size is 1 and no `TaskScheduler` is configured (`LmsApplication.java:12` `@EnableScheduling`, no override anywhere). The webhook dispatcher claims **20 events per 60s** → a hard ceiling of **~28,800 deliveries/day per instance**, against an expected **500K–1M/day**. The disbursement worker runs its **entire batch in a single transaction** (self-invocation defeats the per-loan `@Transactional`) over an **unbounded `findByStatus`** — at 100K/day this is a daily worker stall and an OOM risk, not a race nuance.

2. **There is no connection-level tenant isolation, and the pool is unsized.** All tenants share **one** Hikari pool (`tenantPhysicalDataSource`), which has **no production sizing at all** (no `spring.datasource.hikari` block in `application.yml`; only `application-local.yml` sets size; there is **no `application-prod.yml`**). Default = **10 connections** for *every LSP combined*. The pool runs `autoCommit=false`, so **every read holds a real transaction**, and there is **no `statement_timeout` / `idle_in_transaction_session_timeout`**. Net effect: **one tenant's slow query drains the shared pool and 5xx's all ten tenants.** Per-LSP rate limiting (request *count*) does not protect against this — a single expensive query holds a connection for its whole duration irrespective of request rate.

3. **Redis is a hard SPOF on every write, with no failure handling.** `RateLimitFilter.evaluate()` (`RateLimitFilter.java:90-124`) calls Redis synchronously with **no try/catch**; a Redis blip turns into a 500 on every rate-limited path. All rate-limit traffic is multiplexed over a **single shared Lettuce connection** (`RateLimitConfig.java:32-36`).

4. **The read path still runs on the write database, O(N) in the portfolio.** The dashboard loads **every loan account into the JVM on every page view** and aggregates in Java streams, plus an N+1 TAT loop (`HomeDashboardService.java:71-118, 145-183`). At a multi-million active book this is an OOM/timeout per ops page view — and it competes with the disbursement worker for the same admin pool.

5. **Observability is still absent** (only `health,info` exposed; no metrics, no statement-duration logging), so none of the above will be *seen* before a partner reports it.

None of this needs re-platforming — but the prior execution plan's **phasing is wrong for 100K/day**: items it lists as P2/P3 (worker concurrency, pool sizing, scheduler, per-tenant DB fairness) are **launch-blocking** at this volume, and three new throughput findings (N1–N3 below) are not in the plan at all.

---

## 2. Readiness re-score at 100K/day, 10+ tenants

**Overall: 4 / 10** (the prior audit scored 6/10 at 1M/_year_; the same system is less ready against a 12× target).

| Dimension | Score | Basis |
|---|---|---|
| Data-integrity foundations | 8/10 | Genuinely strong; unchanged from prior (uniques, outbox, RLS, optimistic locking) |
| Background/throughput capacity | **2/10** | Single scheduler thread; webhook ceiling ~28.8K/day; disbursement single-tx unbounded batch (N1, N2) |
| Connection-layer & DB scalability | **3/10** | Unsized shared pool, no statement timeout, autoCommit=false hold, no prod profile (N3, N4) |
| Multi-tenant isolation at scale | **3/10** | RLS isolates *rows* well; **nothing isolates compute** — shared pool, shared scheduler, shared Redis connection = noisy-neighbor across all dimensions (N3, N5) |
| Failure isolation | **3/10** | One slow tenant / one dead webhook endpoint / one Redis blip degrades all tenants (N3, N5, R12) |
| Money-movement concurrency safety | 3/10 | Unchanged from prior (R1–R4 still open) |
| Read-path scalability | 3/10 | Dashboard O(N)+N+1, audit UNION ALL, in-request MIS (R5, R8, R11) |
| Operational readiness | 3/10 | No metrics, no structured logs, DB-only alerts (R9) |
| Security & isolation | 7/10 | Strong controls; scale-sensitive gaps (per-request auth DB hit cost, no ApiClient lockout, CORS) |

---

## 3. The throughput arithmetic (why specific components break at 100K/day)

These are fixed ceilings **in code**, independent of database size or hardware:

| Component | Code (file) | Per-instance ceiling as written | Demand at 100K/day | Verdict |
|---|---|---|---|---|
| **Webhook delivery** | claim `batch-size: 20` every `fixed-delay-ms: 60000` (`application.yml:69-73`, `WebhookOutboxDispatchWorker.java:29`) | **~20/min ≈ 28.8K/day** (claim is the bottleneck, not the 10-thread executor) | 500K–1M events/day | **~17–35× under** |
| **Disbursement** | unbounded `findByStatus`, sequential, **single transaction** (`LoanDisbursementWorkerService.java:69-87`); 30s delay | mock: bounded by single tx + 1 thread; **real ICICI @ ~1.5s/call sequential ≈ <60K/day** and cannot commit incrementally | 100K/day | **Cannot meet with a real bank; daily stall risk even with mock** |
| **Reports** | `batch-size: 10` every 15s (`application.yml:76-77`) | ~40/min ≈ 57.6K/day | nightly MIS × 10 LSPs + ad-hoc | OK for report volume; **but holds pool across whole batch (R7)** |
| **Scheduler thread** | default pool size **1** (`@EnableScheduling`, no `TaskScheduler`) | all 4 workers serialize on one thread | continuous | **Head-of-line blocking: a slow disbursement batch starves webhooks + reports + alerts** |
| **Tenant DB pool** | default **10** connections, shared by all LSPs, `autoCommit=false` (`TenantIsolationDataSourceConfig.java:24-58`) | ≤10 concurrent tenant queries total | Tomcat default **200** request threads | **200 threads contend for 10 connections → connection-timeout storms** |

Even the *happy path* (mock adapter) cannot drain a 100K/day webhook stream from one worker pod with a 20/min claim on a single scheduler thread. This is the single most important new finding.

---

## 4. NEW findings (not in the previous audit)

### N1 — Single-threaded scheduler: all background work serializes on one thread — **CRITICAL**
- **Where:** `LmsApplication.java:12` (`@EnableScheduling`); no `TaskScheduler`/`SchedulingConfigurer` bean exists; no `spring.task.scheduling.pool.size` in any yml. Spring Boot's default scheduler pool size is **1**.
- **Mechanics:** `LoanDisbursementWorker.run()` (30s), `WebhookOutboxDispatchWorker.dispatchPendingEvents()` (60s), `ReportRequestProcessingWorker.processPendingRequests()` (15s), `AlertRuleSchedulerWorker` (5min) are all `@Scheduled` on that one thread. `fixedDelay` means each waits for the previous task on the thread to *finish*. The disbursement and report workers do their batch work **inline** on the scheduler thread (only webhook *deliveries* fan out to a separate executor; the claim/poll does not).
- **Impact at scale:** A disbursement batch that takes minutes (it will — see N2) blocks webhook claiming and report processing entirely. Webhook SLA, report SLA, and alert evaluation all degrade together whenever any one job is slow. This is invisible today (everything finishes in milliseconds at low volume) and guaranteed at 100K/day.
- **Business impact:** Partner webhooks (status, disbursal, repayment confirmations) silently fall hours behind; alert evaluation (stuck-disbursement, brute-force) stops firing while a batch runs.
- **Fix:** Configure a multi-threaded `ThreadPoolTaskScheduler` (e.g. `spring.task.scheduling.pool.size: 5+`) **and** give each heavy worker its own executor so polling never does batch work inline. Pairs with N2.

### N2 — Disbursement worker runs the whole batch in one transaction over an unbounded query — **CRITICAL**
- **Where:** `LoanDisbursementWorkerService.java:69-95`. `processPendingDisbursements()` is `@Transactional`; it calls `processStatus()` which loops over `findByStatus(...)` (`LoanApplicationRepository.java:97` — **no `Pageable`, no `LIMIT`**) and calls `this.processApplication(...)`. Because that call is **self-invocation**, the Spring proxy is bypassed and the `@Transactional` on `processApplication` is dead — the entire loop, including the external disbursement-adapter call, runs in the **outer single transaction on one connection**.
- **Impact at scale:** At 100K/day, if the worker ever falls behind, `findByStatus` loads the **entire growing backlog** into the JVM each 30s poll (OOM), and processes it in one ever-longer transaction holding one admin-pool connection. With **no `idle_in_transaction_session_timeout`**, that transaction can pin a connection and hold row locks for the whole batch. With a real ICICI adapter, every provider call happens inside this transaction — the prior audit's R1 double-fire risk **plus** a throughput wall.
- **Business impact:** Disbursements stall daily; money-movement latency becomes unbounded; a crash mid-batch rolls back work already acknowledged by the provider.
- **Fix:** This is the prior audit's F1 (#203/#204) — but it must be **launch-blocking at this volume, not "before ICICI."** Claim N rows via `FOR UPDATE SKIP LOCKED`, process each in its own short transaction via a *separate bean* (real proxy), call the provider outside the tx, fan out across a pool. Bound the claim query.

### N3 — No connection-level tenant isolation: one tenant drains the shared pool for all — **CRITICAL (noisy-neighbor)**
- **Where:** `TenantIsolationDataSourceConfig.java:24-86`. There is exactly **one** `tenantPhysicalDataSource` Hikari pool; `TenantAwareDataSource` switches the `app.current_lsp_id` GUC per checkout but every LSP shares the **same physical connections**. No per-tenant pool, no per-tenant connection cap, no fair queueing.
- **Mechanics:** RLS isolates *rows*, but **compute and connections are fully shared**. One tenant issuing expensive queries (a big unfiltered list, a slow report-ish read, a lock wait) occupies connections from the shared pool of (default) 10. Other tenants then block on `connection-timeout` and 5xx. Per-LSP rate limiting does **not** help: it caps request *count*, not connection *hold time* — a single 8-second query consumes a connection for 8 seconds regardless of how few requests sent it.
- **Impact at the four spike scenarios you named:**
  - *One LSP spikes:* its queries monopolize shared connections → all other tenants degrade. **Isolation failure.**
  - *Several/all spike:* pool saturates instantly; platform-wide 5xx.
- **Business impact:** The core multi-tenant promise ("one LSP cannot degrade the platform") is **not met at the DB layer**. A single partner's bad integration becomes an all-partner outage.
- **Fix:** (1) Size both pools explicitly (N4); (2) add **per-tenant in-flight concurrency caps** (bulkhead — a semaphore per `lspId` bounding simultaneous DB-bound requests) so one tenant cannot hold more than its share; (3) `statement_timeout` so no single query holds a connection indefinitely; (4) consider a dedicated read pool / read replica for ops + reporting so background and read load never competes with LSP write connections.

### N4 — No production datasource profile: pools unsized, no statement timeout, autoCommit hold — **HIGH (deepens R6)**
- **Where:** `application.yml` has **no `spring.datasource.hikari` block** → both `adminDataSource` and `tenantPhysicalDataSource` (both annotated `@ConfigurationProperties("spring.datasource.hikari")`) fall back to Hikari's default **10**. Only `application-local.yml:10-13` sets a size (5). **There is no `application-prod.yml`** — production runs the default profile with framework defaults.
- **Compounding factors:** `dataSource.setAutoCommit(false)` (`TenantIsolationDataSourceConfig.java:56`) means *every* read — even non-transactional — opens a transaction and holds the connection until Spring returns it; combined with the per-checkout `SET ROLE` + `set_config` round-trips (`TenantAwareDataSource.java:51-73`), effective per-connection throughput drops and idle-in-transaction risk rises. No `statement_timeout` and no `idle_in_transaction_session_timeout` anywhere.
- **Tomcat/DB mismatch:** server default ~**200** worker threads vs **10** DB connections → under load, 190 threads queue on connection acquisition.
- **Deployment ceiling (Supabase):** memory of prior debugging notes the Supabase **pooler caps ~15 sessions** (use port 6543). With 2 API pods + 1 worker × (admin 10 + tenant 10) = **60 desired connections** against a 15-session cap → connection failures before any load. Must use the transaction-mode pooler and size deliberately.
- **Fix:** Add a prod profile; size both pools per role (API ~20–30, worker ~10) within Postgres `max_connections`/pooler limits; set `statement_timeout` (10–30s app roles, longer report role) and `idle_in_transaction_session_timeout`; align Tomcat `server.tomcat.threads.max` to a sane multiple of pool size; enable leak detection.

### N5 — Failure isolation is shared across every axis — **HIGH**
Beyond the DB pool (N3), the platform shares single failure domains that turn one fault into a platform incident:
- **Redis:** single shared `StatefulRedisConnection` (`RateLimitConfig.java:32-36`); `RateLimitFilter.evaluate()` has **no try/catch** (`RateLimitFilter.java:106-117`). Redis latency or outage → 500 on every rate-limited write for every tenant (prior R12, confirmed and deepened — it's not just "undefined," there is no guard at all).
- **Scheduler thread (N1):** one stuck job → all background jobs stop, for all tenants.
- **Webhook executor:** 10 shared threads, 10s read timeout, no per-LSP cap (prior #230) → one tenant's dead endpoint stalls everyone's deliveries.
- **Fix:** Redis fail-open for business traffic + fail-closed for auth, with alerting (prior #223 — promote to launch); per-tenant bulkheads on DB and webhook executors; multi-thread scheduler.

### N6 — Synchronous per-request overhead stack taxes every LSP call — **MEDIUM (latency at sustained load)**
Every authenticated LSP request, before any business logic, performs in series: (1) Redis rate-limit round trip (`RateLimitFilter`), (2) per-request **DB validation of the ApiClient JWT** (the prior audit confirms `ApiClientJwtSessionValidator` does `findByClientId` + token-version + status on *every* call — deliberately, for instant revocation), (3) tenant `SET ROLE` + `set_config` round-trips on connection checkout (`TenantAwareDataSource.java:51-73`). At 100K/day plus polling traffic across 10+ tenants this is meaningful added latency and load on the very Redis/DB that are the bottlenecks. The prior audit defended (2) at ~50 rps; at this volume it deserves a version-aware cache (bust on token-version bump, not TTL) — revisit once N1–N4 land and metrics (N7) exist.

### N7 — Cannot observe any of the above — **HIGH (operations gate)**
Confirmed: `management.endpoints.web.exposure.include: health,info` only (`application.yml:30-34`); no Micrometer/Prometheus, no Hikari pool metrics, no queue-depth/oldest-pending metrics, no statement-duration logging. Every finding in this report would, in production, first surface as a partner complaint. This is the prior audit's F7/R9 — re-emphasized because at 100K/day the *time-to-detect* of N1–N5 is the difference between a 5-minute and a 5-hour incident.

---

## 5. Previously-identified findings, re-graded at 100K/day

The following remain valid as written in `scalability-assessment-2026-06-10.md`; the change is **severity/urgency** at the new volume.

| Prior ID | Finding | Prior grade | Re-grade @ 100K/day | Why |
|---|---|---|---|---|
| R1/F1 | Disbursement double-fire + single-tx batch | Critical / "before ICICI" | **Critical / launch-blocking** | See N2; throughput wall hits with the mock too |
| R2/F2 | Idempotency claim-after-execute | Critical | **Critical** | At ~10M+ writes/yr, same-50ms duplicate pairs are continuous |
| R3/R4/F3 | Payment orphan + installment race | High | **High–Critical** | 300–500K repayments/day hits the window many times daily |
| R5/F5 | Dashboard O(N) + N+1 | High | **Critical** | Millions of active accounts → OOM/timeout per ops page view; competes with workers on admin pool |
| R6/F4 | Pool sizing + statement timeout + tx-around-I/O | High | **Critical** | See N3/N4 |
| R8/F8 | Audit explorer 8-way UNION ALL, no date bound | High | **Critical** | 0.5–2B audit rows/yr; one unbounded search = outage |
| R11/F6 | In-request MIS, `byte[]` through heap | Med–High | **High** | Year-range export over 1M+ loans = OOM; holds a connection across generation |
| R12/F9 | Redis failure undefined | Med | **High** | See N5 — every write depends on it, no guard |
| F15 | Partitioning big six + idempotency purge | High | **Critical / pre-launch** | At 2B rows/yr, unpartitioned tables are un-vacuumable; must partition empty |
| R13/F11 | No ApiClient lockout | Med | **Med–High** | 10+ partners holding credentials to the book |
| F10 | Per-LSP rate overrides + read lane | Med | **High** | Static 60/min write cap saturates for a single whale; reads uncapped |
| R14/F12 | EAGER fetch on hot entities | Med | **High** | Query amplification on every read at 100K/day is no longer "just performance" |
| F18/F19 | Bounce/reversal + bulk repayment | High | **High** | Unchanged — settlement-morning batch shape is worse at this volume |

---

## 6. Per-dimension review (mapped to your focus list)

**API throughput & latency under sustained load.** API tier is stateless (JWT) and horizontally scalable in principle, but per-request latency is gated by N6 (Redis + auth DB + SET ROLE round trips) and the **200-thread-vs-10-connection** mismatch (N4). Sustained 100K/day with polling will exhaust the tenant pool long before CPU. *Fix: N4 sizing + N3 bulkheads + N6 auth cache.*

**Database scalability, indexes, queries, locks, pooling.** Index coverage is genuinely good (prior §8). The problems are structural: unsized shared pools (N3/N4), no statement timeout, autoCommit hold, unbounded `findByStatus` (N2), dashboard O(N) (R5), audit UNION (R8), and no partitioning (F15). *These are the bulk of the launch work.*

**Multi-tenant isolation & noisy-neighbor.** Rows: strong (RLS + dedicated role). Compute/connections/queues: **no isolation** (N3, N5). This is the largest gap relative to your "one LSP cannot degrade the platform" requirement.

**Failure isolation.** Shared scheduler, shared pool, shared Redis connection, shared webhook executor — every one is a single failure domain spanning all tenants (N1, N3, N5). *Fix: bulkheads + multi-thread scheduler + Redis guard.*

**Disbursement & repayment data consistency.** Disbursement: unsafe under >1 worker and retry (R1/N2). Repayment: orphan-row and same-installment races (R3/R4); no bounce/reversal (F18). Foundations (uniques, optimistic locks, outbox) are strong; the gaps are concentrated and known.

**Accurate reports under high volume.** In-request MIS with full-entity hydration (R11) OOMs on year-ranges; reports run under READ COMMITTED across a long generation window (point-in-time skew possible but acceptable if documented). Nightly precompute (prior #211/#232) is the right answer; it's currently P2 — at this volume it's launch-grade.

**Reliable audit log generation.** Audit rows are written **synchronously in the request path** to the **same OLTP database** (no async sink, no separate store). At 100K/day with multiple audit rows per operation, this is significant write amplification on the busiest tables, and they are **unpartitioned** (F15). Audit reliability is fine for correctness (same-tx) but a throughput drag and a retention liability. *Consider an async audit sink + partitioning before launch.*

**Queue/background job scalability.** Covered by N1/N2 and the throughput table (§3). DB-as-queue + SKIP LOCKED is the right pattern; the implementation is single-threaded and under-batched for 100K/day.

**Rate limiting, backpressure, retries, idempotency, timeouts.** Rate limiting is well-designed but: hard Redis dependency with no guard (N5/R12), no read lane, static per-LSP caps (F10), and it does not provide *backpressure on DB connections* (N3). Idempotency is strong at the DB-unique layer but the wrapper ordering is unsafe (R2). No circuit breaker/bulkhead anywhere (prior §10.4). Timeouts: HTTP client timeouts exist for webhooks; **no DB statement timeout** (N4).

**Security at scale.** Strong baseline (RLS, token versioning, lockout for AppUser, IP allowlists, PII audit). Scale-sensitive gaps: per-request auth DB cost (N6), no ApiClient lockout (R13), CORS hardcoded to localhost (F21 — deploy blocker), idempotency table stores PII response bodies with no purge (F15), no upload malware scanning (prior #221).

**Uptime, resilience, monitoring, recovery.** DB-queue workers self-heal on restart; but there is **no detection** (N7) and **no failure isolation** (N5), so MTTR is bounded by "when a partner calls." No leader election — at >1 worker pod the disbursement/alert workers are unsafe (prior R15); the disbursement claim rework (N2) is the prerequisite for multi-worker.

**Missing concerns not on your list:**
- **Flyway on startup** runs migrations on the boot path; at 2B-row partitioned tables a forgotten non-`CONCURRENTLY` index migration locks a table on deploy. Add a migration-safety checklist (prior runbook exists; verify it covers partitioned DDL).
- **No graceful shutdown drain** verified for in-flight worker batches — a deploy mid-disbursement-batch relies on tx rollback + sweeper (which N2 must add).
- **Cost/connection ceiling on managed Postgres/pooler** (N4 Supabase note) — a capacity question to answer before launch sizing.

---

## 7. Noisy-neighbor scenario matrix (your five scenarios)

| Scenario | Behavior as coded today | Root cause | Required behavior |
|---|---|---|---|
| One LSP calls APIs repeatedly | Per-LSP write rate limit (60/min) caps *count*, but a whale at 5K loans/day needs ~64/min → throttled by the static cap; reads uncapped | F10 (static caps), no read lane | Per-LSP DB-backed limits + read lane (#229) |
| One LSP spikes (others normal) | Its DB-bound requests occupy the shared 10-connection tenant pool → **all tenants slow/5xx** | **N3 (no connection isolation)** | Per-tenant connection bulkhead + statement timeout |
| Multiple LSPs spike together | Pool saturates faster; webhook executor saturates if any has a slow endpoint | N3, N5 | Bulkheads + per-LSP webhook cap (#230) |
| All LSPs spike at once | Tenant pool exhausted, scheduler thread backed up, Redis hot → **platform-wide 5xx** | N1, N3, N4, N5 | Sizing + bulkheads + multi-thread scheduler + Redis guard + autoscale API tier |
| One LSP's webhook endpoint dies | 10s timeout × shared 10 threads → everyone's webhooks slow; single scheduler thread compounds | N1, N5 | Per-LSP in-flight cap + isolated executor + multi-thread scheduler |

---

## 8. Prioritized findings (severity · affected module · business impact · fix)

| ID | Finding | Sev | Affected files / APIs | Business impact | Recommended fix |
|----|---------|-----|----------------------|-----------------|-----------------|
| **N1** | Single-threaded scheduler; workers serialize | **Critical** | `LmsApplication.java:12`; all `@Scheduled` workers | Webhooks/reports/alerts stall whenever any job is slow | Multi-thread `TaskScheduler` (`pool.size≥5`) + per-worker executors |
| **N2** | Disbursement batch in one tx over unbounded query (self-invocation) | **Critical** | `LoanDisbursementWorkerService.java:69-95`; `LoanApplicationRepository.java:97` | Daily disbursement stall; OOM; double-fire with retries/ICICI | Claim+SKIP LOCKED, per-loan tx via separate bean, provider call outside tx, bounded claim, parallel pool (prior #203/#204 — make launch-blocking) |
| **N3** | No connection-level tenant isolation (shared pool) | **Critical** | `TenantIsolationDataSourceConfig.java:24-86`; `TenantAwareDataSource.java` | One LSP's queries 5xx all tenants — breaks isolation promise | Per-tenant in-flight bulkhead + `statement_timeout` + sized pools + read pool/replica |
| **N4** | No prod datasource profile: unsized pools, no timeouts, autoCommit hold, Tomcat/DB mismatch | **Critical** | `application.yml` (no hikari block); no `application-prod.yml`; `TenantIsolationDataSourceConfig.java:56` | Connection-timeout storms at modest load; Supabase pooler cap breach | Add prod profile; size both pools per role; `statement_timeout`+`idle_in_transaction_session_timeout`; align Tomcat threads (prior #201 — expand scope) |
| **R5/F5** | Dashboard O(N) in JVM + N+1 TAT | **Critical** | `HomeDashboardService.java:71-118,145-183` | Ops dashboard OOM/timeout; taxes admin pool | Nightly SQL snapshot + window-function TAT (prior #211/#212 — make launch) |
| **R8/F8** | Audit explorer 8-way UNION, no date bound | **Critical** | `AuditExplorerRepository` (prior §4) | Unbounded search = outage at 0.5–2B rows/yr | Mandatory date window + indexed `lsp_id` + keyset (prior #214) |
| **F15** | No partitioning on big-six; no idempotency purge | **Critical/pre-launch** | migrations; idempotency/audit tables | Un-vacuumable tables, PII retention liability | Partition empty tables now + purge sweeps (prior #208/#209) |
| **N5/R12** | Shared failure domains (Redis, scheduler, executor) | **High** | `RateLimitFilter.java:106-117`; `RateLimitConfig.java:32-36` | One Redis blip / dead endpoint = platform incident | Redis guard (fail-open business/closed auth) + bulkheads (prior #223/#230) |
| **R1–R4** | Money-movement concurrency (idempotency, payment orphan/race) | **Critical/High** | `LspApiIdempotencyService`, `LoanRepaymentCommandService`, installment entity | Double-execution, orphan payments, double-pay | Claim-before-execute + same-tx payment + installment `FOR UPDATE` (prior #202/#205/#206) |
| **R11/F6** | In-request MIS, `byte[]` heap, full hydration | **High** | `ReportAdminController`, `PortfolioMisReadRepository` | Year-range export OOM; pool hold | Async streaming + presigned URLs (prior #213/#227/#228) |
| **N7/R9** | No metrics/structured logs/queue depth | **High** | `application.yml:30-34`; `pom.xml` | All above undetectable until partner complaint | Prometheus + Hikari/queue metrics + JSON logs (prior #217/#218/#234) |
| **N6** | Per-request Redis + auth-DB + SET ROLE overhead | **Medium** | `RateLimitFilter`, `ApiClientJwtSessionValidator`, `TenantAwareDataSource` | Added latency/load on the bottleneck resources | Version-aware auth cache after metrics exist |
| **R14/F12** | EAGER fetch on hot entities | **High** | domain package (~20 sites) | Query amplification every read | LAZY + explicit `join fetch` (prior #--, F12) |
| **F10** | Static per-LSP caps; no read lane | **High** | `application.yml:104-161`; `RateLimitRule` | Whale partner throttled; pollers hammer reads | DB-backed per-LSP limits + read lane (prior #229) |
| **F18/F19** | No bounce/reversal; no bulk repayment | **High** | repayment module | Book overstates collections; settlement-morning saturation | prior #222/#231 |
| **F21/F11/#221** | CORS localhost; no ApiClient lockout; no upload AV | **Med** | `SecurityConfig.java:236-240`; `ApiClientAuthenticationService` | Deploy blocker; credential stuffing; malware to ops browsers | prior #237/#224/#221 |

---

## 9. Recommended remediation sequence (delta vs the existing plan)

The existing 41-issue plan (#197–#237) is sound *engineering*; the **phasing is mis-ordered for 100K/day**. Concretely:

**Promote into a new "Phase 1A — Capacity gate" (launch-blocking, before any volume ramp):**
1. **N1** multi-thread scheduler + per-worker executors (*new — not in plan*).
2. **N4** prod profile + pool sizing + statement/idle timeouts + Tomcat alignment (expand #201).
3. **N3** per-tenant connection bulkhead + read pool (*new — the plan has rate limits but no connection-fairness*).
4. **N2 / F1** disbursement claim rework (#203/#204) — pull from "before ICICI" to launch.
5. **Webhook throughput**: raise claim batch + interval tuning + (later) multi-worker, once N2 makes workers multi-instance-safe (*new throughput sizing — the plan fixes correctness, not the 20/min ceiling*).
6. **R5/F5** dashboard snapshot (#211/#212) and **R8/F8** audit guardrails (#214) — pull from Phase 2 to launch.
7. **F15** partitioning (#208) + purge (#209) — pull to pre-launch.
8. **N5/R12** Redis guard (#223) — pull to launch.
9. **N7** metrics (#217/#234) — pull forward; you cannot run 100K/day blind.

**Keep in Phase 1 (already correctly placed):** R2 idempotency (#202), R3/R4 payments (#205/#206), F18 bounce (#222), F21 CORS (#237), F11 lockout (#224).

**Phase 2+ unchanged:** self-serve reports, regulatory extracts, JSON logging, runbooks, AV scanning, EAGER pass.

**New test-bed requirement:** the prior #197 seeder targets ~200K applications / ~1M payments. For this operating point the load suite (#200) must drive **≥100K creates/day and ≥400K repayments/day across ≥10 simulated tenants with one-tenant-spike and all-tenant-spike profiles**, and the concurrency harness (#199) must assert the per-tenant bulkhead (N3) holds — i.e., a spiking tenant cannot raise another tenant's p99.

---

## 10. What is genuinely strong (do not regress)

Verified and worth protecting: DB uniqueness on every record that matters; transactional webhook outbox with `FOR UPDATE SKIP LOCKED` + lease + reclaim (the reference pattern the disbursement worker should copy); partner-scoped idempotency with fingerprinting; optimistic locking on aggregate roots; RLS with a dedicated tenant DB role; layered Redis rate limiting; deep synchronous audit trail; token versioning with instant revocation; startup secret validation. The foundations are better than most pre-launch lending platforms — the gap is entirely in **capacity, isolation, and observability at 100K/day**, not correctness primitives.

---

*Prepared as a review artifact only — no code changed. Recommend we agree the Phase 1A scope in §9 before implementation.*
