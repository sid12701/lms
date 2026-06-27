# Scalability Execution — Issue Tracker

**Source:** `scalability-assessment-2026-06-10.md` (v1.4) §17 execution plan → 47 GitHub issues on `sid12701/lms` (**#197–#248**).
**Last tracker sync:** 2026-06-15 — **v2 decision register closed**; **GitHub issues #197–#248 updated** to V2-D1/V2-D2 volumes (see comments on #247). Original register D1–D10 closed (assessment v1.2). **v1.4 cross-review (2026-06-11):** #236, #237; #207 unique-violation → 409.
**v2 360° re-audit (2026-06-14):** `scalability-audit-360-2026-06-14.md` — structural findings N1–N7 and Phase 1A issues **#243–#248**. Operating point **reconciled 2026-06-15** to owner decision **V2-D1** (not the audit's 100K/day sustained assumption).
**Deployment strategy:** `docs/architecture/deployment-strategy.md` — provider-agnostic K8s + Terraform + Helm; sizing tuned to **V2-D1 + V2-D2** below. #248 feeds topology ADR #226.

### Authoritative operating point (V2-D1, closed 2026-06-15)

| Dimension | Launch commitment |
|-----------|-------------------|
| Origination | **~1M loans/month** (~33K/day sustained, ~12M/year) |
| Peak sizing | **~150K loans/day** (4.5× sustained — V2-D2) |
| Tenants | **10 LSPs** (any may spike alone; a couple together; all together) |
| Repayments (steady-state book) | ~100–150K/day sustained; ~450K/day at peak (3× EMI traffic on peak origination day) |
| Webhook events | ~165–330K/day sustained; **~750K–1.5M/day at peak** (5–10 events/loan) |
| Audit rows | ~200–400M/year at sustained; scales with peak |
| Data consistency | **First-class** — Phase 1 money-safety + concurrency harness under spike profiles |

**Why Phase 1A stays launch-blocking at this point:** not because sustained average is 100K/day, but because (a) the **webhook claim ceiling (~28.8K/day/instance)** is below even sustained event rate, (b) **spike + isolation** requirements need bulkheads/scheduler/multi-worker, and (c) **money-path correctness** (#202–#207, #205–#206) is non-negotiable.

---

## v2 Decision register (closed 2026-06-15)

Owner decisions for AFK agents. **This section supersedes** conflicting text in `scalability-audit-360-2026-06-14.md` (100K/day sustained) and open items in `deployment-strategy.md` §12.

| ID | Decision | Resolution |
|----|----------|------------|
| **V2-D1** | Operating point | **~1M loans/month**, 10 LSPs, data consistency first-class |
| **V2-D2** | Spike / sizing target | **~150K loans/day peak** (4.5×). Load tests, bulkheads, HPA max, and #247 exit criterion use this peak — not 100K/day sustained |
| **V2-D3** | Phase 1A scope | **Launch-blocking** at V2-D1 + V2-D2 (scheduler, bulkhead, webhook throughput, pools, snapshots, partitions, metrics, Redis guard) |
| **V2-D4** | Production Postgres | **Migrate off Supabase** for prod. Managed Postgres behind **transaction-mode pooler** (PgBouncer or equivalent). Pool sizing in #201/#244 uses **pooler backend limits**, not Supabase's ~15-session cap |
| **V2-D5** | Cloud provider | **Provider-agnostic now** (K8s + Terraform + Helm modules for AWS/GCP/Azure). **Likely Azure** for first prod — use Azure as the reference module set, but **no provider SDK in app code**. #248: **implement IaC in-repo only**; **do not `terraform apply` to a real account AFK** without credentials |
| **V2-D6** | Region & retention | **India region** for prod (data residency). **8-year** archive retention confirmed; **24 months hot** partitioned data (#208), older via object-storage lifecycle (D6) |
| **V2-D7** | DR targets | **RPO ≤ 5 min**, **RTO ≤ 30 min** (Multi-AZ + PITR). Restore drill required before prod sign-off (#248) |
| **V2-D8** | Whale-tenant dedicated tier | **No** on day one (infra escape hatch documented only) |
| **V2-D9** | #222 bounce/reversal | **DEFERRED** to ICICI bank integration — bank APIs will drive bounce/reversal at integration time. Do not implement #222 in this execution pass |
| **V2-D10** | Partner contract changes | **#222** deferred (V2-D9). **#224, #229, #236:** agent **implements and merges**; add `docs/partner-api-changelog.md` entry per issue; human notifies partners before prod traffic. **#212, #228:** ops heads-up only |
| **V2-D11** | #229 per-LSP limits | **Promoted to launch-blocking** (Phase 1A). Static 60 writes/min blocks origination at volume |
| **V2-D12** | #230 webhook per-LSP cap | **Promoted to launch-blocking** — ship with **#245** |
| **V2-D13** | Async audit sink | **Out of scope** — synchronous audit + partitioning (#208) only |
| **V2-D14** | Prod config placeholders | **#237** CORS origins, **#235** SMTP + alert webhook: implement with **required env vars**; fail fast if missing in prod profile. No hardcoded secrets |
| **V2-D15** | #231 bulk repayment | **Proceed after #206** only (#222 dependency removed per V2-D9). Drain worker does not need bounce handling until ICICI |

---

## Agent implementation plan (AFK handoff)

**Read this section first.** Issues are the unit of work; ordering below is the dependency-safe sequence. Label `ready-for-agent` = fully specified. Respect **merge fences** at the end.

### Wave 0 — Test bed (start immediately, parallel)

| Order | Issue | Agent instructions |
|------:|-------|-------------------|
| 0a | **#197** | Seeder: **10 fake LSPs**, portfolio sized for **month-9 of V2-D1** (~500K active accounts, ~3M payments, ~30M audit rows). Bulk JDBC. Idempotent reset |
| 0b | **#198** | Docker-compose / k8s-local: **2 API + 1 worker**, workers flag-gated per D1 topology. Postgres + Redis + MinIO — **not Supabase** for staging (mirrors V2-D4) |
| 0c | **#199** | Concurrency harness: assert disbursement once-only, payment idempotency, **and post-#244 bulkhead** (spiking tenant must not raise another tenant's p99) |
| 0d | **#200** | k6/load profiles: **sustained 33K/day**, **peak 150K/day**, scenarios: single-LSP spike, couple spike, all-LSP spike. Baseline before fixes; regression gate after each wave |

**Wave 0 exit:** #200 runs on #198 + seeded data; documents baseline p95/error rate.

### Wave 1 — Foundations (parallel where unblocked)

| Order | Issue | Agent instructions |
|------:|-------|-------------------|
| 1 | **#201** | Add `application-prod.yml`: Hikari per role (API: tenant 20–25 + admin 5–10; worker: tenant 10 + admin 10), `statement_timeout` 30s app / 120s report role, `idle_in_transaction_session_timeout` 60s, Tomcat `max` ≈ 3× tenant pool. Document pooler arithmetic for **~8 API + 4 worker pods at peak** (V2-D2), **~100–150 PG backends** behind txn-mode pooler |
| 1 | **#223** | Redis fail-open business / fail-closed auth + metric + deduped alert |
| 1 | **#207** | Global 409 mapping + override constraint + unique-violation → 409 |
| 1 | **#208** | Partition big-six **while empty**; monthly `created_at`; 24mo hot per V2-D6 |
| 1 | **#209** | 90d idempotency purge; webhook outbox retention; metrics |
| 1 | **#225** | Upload bytes outside tx |
| 1 | **#226** | Drop RabbitMQ; ADRs INR-only + topology |
| 1 | **#210** | ICICI design ADR only — note bounce/reversal via bank inbox (V2-D9) |
| 1 | **#237** | CORS from env; prod validator |
| 1 | **#243** | `spring.task.scheduling.pool.size` ≥ 5; per-worker executors; no batch work on scheduler thread |

### Wave 2 — Money safety (strict order)

| Order | Issue | Agent instructions |
|------:|-------|-------------------|
| 2a | **#202** | Claim-before-execute; delete REQUIRES_NEW poison path. **Gate:** #199 green |
| 2b | **#203** | SKIP LOCKED claim N=25; separate bean per-loan tx; atomic attempt counter |
| 2c | **#204** | Intent row; provider outside tx; sweeper; parallel pool default 10 |
| 2d | **#205** | Payment + allocation same tx |
| 2e | **#206** | `SELECT FOR UPDATE` on installment |
| — | **#222** | **SKIP — DEFERRED (V2-D9)** |
| 2f | **#236** | Natural-key `(lsp_id, external_loan_id)` replay; changelog entry (V2-D10) |
| 2g | **#224** | ApiClient lockout; changelog entry (V2-D10) |

**Wave 2 exit:** #199 race suite green; no double-disbursement; no orphan payments; installment race → 409.

### Wave 3 — Capacity & isolation (Phase 1A)

| Order | Issue | Agent instructions |
|------:|-------|-------------------|
| 3a | **#244** | Per-LSP semaphore bulkhead; default cap = **floor(tenant_pool_size / 10)** per LSP (10 tenants); 429/503 + `Retry-After`; metrics |
| 3b | **#229** | DB-backed per-LSP limits; read lane 300/min default; whale overrides; changelog (V2-D10) |
| 3c | **#211** | Nightly KPI snapshot; window-function TAT |
| 3d | **#212** | Dashboard reads snapshot + 3 live counts; "Data as of" UI |
| 3e | **#214** | Mandatory date window 7d default / 90d max; keyset pagination |
| 3f | **#217** | Prometheus scrape; Hikari + `pg_stat_statements` |
| 3g | **#234** | Queue depth + oldest-pending-age metrics |
| 3h | **#245** + **#230** | Raise webhook claim (target: drain **≥2M events/day** at peak across workers); multi-pod; per-LSP in-flight cap |
| 3i | **#246** | Version-aware auth cache; feature-flag; after #217 only |

**Wave 3 exit (#247):** #200 **peak 150K/day all-LSP-spike** passes: error < 0.5%, no cross-tenant p99 regression, no connection-timeout 5xx, webhook backlog drains < **15 min** at peak, metrics visible.

### Wave 4 — Deployment substrate (code only)

| Order | Issue | Agent instructions |
|------:|-------|-------------------|
| 4 | **#248** | Helm chart + Terraform modules (AWS/GCP/**Azure reference**). **India region** default in example values. Transaction-mode pooler sidecar. Pre-deploy Flyway Job. HPA: API 3→**10**, worker 2→**6** at V2-D2 peak. **Do not apply to cloud AFK.** Document connection math in README |

**Blocked by:** #243, #203, #204, #201, #244, #217, #234.

### Wave 5 — Phase 2+ (after #247 gate)

Proceed in tracker phase order: **#213** → #227/#228 → #231 (blocked #206 only) → #232–#233, #215–#216 → Phase 3 **#218, #235, #219, #220, #221**.

### Merge fences (agent MUST NOT)

1. **Do not implement #222** (V2-D9).
2. **Do not `terraform apply` / provision paid cloud** without human credentials (V2-D5).
3. **Do not enable prod traffic** on #224/#229/#236 without `docs/partner-api-changelog.md` updated (V2-D10) — code merge is OK.
4. **Phase 4** (ICICI build, read replica, archival automation, Azure storage adapter): **skip** until triggers fire.

### Agent success criteria (launch gate)

All must pass on **#198 staging** with **#197** seed:

1. #200 peak profile (150K loans/day, all-LSP spike) × **2 hours**: error < 0.5%, loan-create p95 < 2s, dashboard p95 < 3s (post-#212).
2. #199: money-path races + bulkhead isolation.
3. Actuator UP; Hikari pending < 10% pool.
4. No duplicate disbursements under 2 API + 2 worker.
5. Webhook oldest-pending-age < 15 min at peak.
6. `#248` Helm renders; `terraform validate` passes (no apply required for gate).

---

**How to read each entry:**

- **Solution** — what gets built, condensed from the issue + plan.
- **Effect on app** — observable behavioral/feature/contract change (or "invisible").
- **Scaling benefit** — why this matters at the stated volume and beyond.
- **Status** — OPEN / IN PROGRESS / PR / CLOSED, with PR links as they land.

---

## At a glance

| # | WI | Title | Phase | Blocked by | Status |
|---|----|-------|-------|------------|--------|
| [#197](https://github.com/sid12701/lms/issues/197) | 0.1 | Synthetic portfolio seeder | P0 | — | **PARTIAL** — code merged; full-volume seed + verification deferred |
| [#198](https://github.com/sid12701/lms/issues/198) | 0.2 | Two-instance staging stack | P0 | — | OPEN |
| [#199](https://github.com/sid12701/lms/issues/199) | 0.3 | Concurrency race harness | P0 | — | OPEN |
| [#200](https://github.com/sid12701/lms/issues/200) | 0.4 | Load suite + baseline | P0 | 197, 198 | OPEN |
| [#201](https://github.com/sid12701/lms/issues/201) | 1.1 | Pool sizing + statement timeouts | P1 | — | OPEN |
| [#202](https://github.com/sid12701/lms/issues/202) | 1.2 | Claim-before-execute idempotency | P1 | 199 | OPEN |
| [#203](https://github.com/sid12701/lms/issues/203) | 1.3a | Disbursement claim + per-loan tx | P1 | 199 | OPEN |
| [#204](https://github.com/sid12701/lms/issues/204) | 1.3b | Intent rows + provider call outside tx | P1 | 203 | OPEN |
| [#205](https://github.com/sid12701/lms/issues/205) | 1.4 | Payment claim atomicity | P1 | 199 | OPEN |
| [#206](https://github.com/sid12701/lms/issues/206) | 1.5 | Installment row locking | P1 | 205 | OPEN |
| [#222](https://github.com/sid12701/lms/issues/222) | 1.6 | Payment bounce/reversal | P1 | 205, 206 | **DEFERRED** (V2-D9) |
| [#207](https://github.com/sid12701/lms/issues/207) | 1.7 | Conflicts → 409 + override constraint | P1 | — | OPEN |
| [#223](https://github.com/sid12701/lms/issues/223) | 1.8 | Redis failure policy | P1 | — | OPEN |
| [#224](https://github.com/sid12701/lms/issues/224) | 1.9 | ApiClient lockout | P1 | — | OPEN |
| [#208](https://github.com/sid12701/lms/issues/208) | 1.10 | Partition big six tables | P1 | — | OPEN |
| [#209](https://github.com/sid12701/lms/issues/209) | 1.11 | Retention sweeps | P1 | — | OPEN |
| [#225](https://github.com/sid12701/lms/issues/225) | 1.12 | Upload outside tx + multipart limits | P1 | — | OPEN |
| [#226](https://github.com/sid12701/lms/issues/226) | 1.13a | Remove RabbitMQ + ADRs | P1 | — | OPEN |
| [#210](https://github.com/sid12701/lms/issues/210) | 1.13b | ICICI design ADR | P1 | — | OPEN |
| [#236](https://github.com/sid12701/lms/issues/236) | 1.14 | Loan-create natural-key replay | P1 | — | OPEN |
| [#237](https://github.com/sid12701/lms/issues/237) | 1.15 | Externalize CORS origins | P1 | — | OPEN |
| [#211](https://github.com/sid12701/lms/issues/211) | 2.1a | KPI snapshot job | P2 | — | OPEN |
| [#212](https://github.com/sid12701/lms/issues/212) | 2.1b | Dashboard serves snapshots | P2 | 211 | OPEN |
| [#213](https://github.com/sid12701/lms/issues/213) | 2.2a | Report worker tx split + retry | P2 | — | OPEN |
| [#227](https://github.com/sid12701/lms/issues/227) | 2.2b | Streaming report generation | P2 | 213 | OPEN |
| [#228](https://github.com/sid12701/lms/issues/228) | 2.2c | Time-limited download URLs | P2 | — | OPEN |
| [#214](https://github.com/sid12701/lms/issues/214) | 2.3 | Audit explorer guardrails | P2 | — | OPEN |
| [#229](https://github.com/sid12701/lms/issues/229) | 2.4a / **1A** | DB-backed per-LSP limits + read lane | **1A** | — | OPEN |
| [#230](https://github.com/sid12701/lms/issues/230) | 2.4b / **1A** | Webhook per-LSP concurrency cap | **1A** | — | OPEN |
| [#231](https://github.com/sid12701/lms/issues/231) | 2.5 | Bulk repayment ingestion | P2 | 206 | OPEN |
| [#232](https://github.com/sid12701/lms/issues/232) | 2.6a | Nightly partner MIS enqueuer | P2 | 213 | OPEN |
| [#215](https://github.com/sid12701/lms/issues/215) | 2.6b | LSP self-serve report API | P2 | 228 | OPEN |
| [#216](https://github.com/sid12701/lms/issues/216) | 2.6c | Collections/DPD + disbursement register | P2 | 213 | OPEN |
| [#233](https://github.com/sid12701/lms/issues/233) | 2.6d | Generic regulatory extract | P2 | 213 | OPEN |
| [#217](https://github.com/sid12701/lms/issues/217) | 3.1 | Prometheus + Postgres telemetry | P3 | — | OPEN |
| [#218](https://github.com/sid12701/lms/issues/218) | 3.2 | JSON logging + MDC enrichment | P3 | — | OPEN |
| [#234](https://github.com/sid12701/lms/issues/234) | 3.3 | Domain metrics | P3 | 217 | OPEN |
| [#235](https://github.com/sid12701/lms/issues/235) | 3.4 | AlertNotifier fan-out | P3 | — | OPEN |
| [#219](https://github.com/sid12701/lms/issues/219) | 3.6 | Grafana dashboards + alert rules | P3 | 217, 234 | OPEN |
| [#220](https://github.com/sid12701/lms/issues/220) | 3.7 | Runbooks + alert-path verification | P3 | 235, 219 | OPEN |
| [#221](https://github.com/sid12701/lms/issues/221) | 3.8 | Upload malware scanning | P3 | 225 | OPEN |

### Phase 1A — Capacity gate (v2 audit, reconciled 2026-06-15)

Operating point **V2-D1** (~1M loans/month, peak **150K/day** per V2-D2). **#247** is the tracking gate. Launch-blocking: structural fixes (#243–#245) plus promoted issues (#201, #203, #204, #208, #209, #211, #212, #214, #217, #223, #234, **#229, #230** per V2-D11/V2-D12).

| # | WI | Title | Phase | Blocked by | Status |
|---|----|-------|-------|------------|--------|
| [#247](https://github.com/sid12701/lms/issues/247) | 1A | Capacity gate (tracking issue) | 1A | — | OPEN |
| [#243](https://github.com/sid12701/lms/issues/243) | 1A.1 | Multi-threaded scheduler (N1) | 1A | — | OPEN |
| [#244](https://github.com/sid12701/lms/issues/244) | 1A.2 | Per-tenant DB connection bulkhead (N3) | 1A | 201 | OPEN |
| [#245](https://github.com/sid12701/lms/issues/245) | 1A.3 | Webhook delivery throughput ceiling | 1A | 243, 203, 230 | OPEN |
| [#246](https://github.com/sid12701/lms/issues/246) | 1A.4 | Version-aware session cache (N6) | 1A | 217 | OPEN |
| [#248](https://github.com/sid12701/lms/issues/248) | 1A.5 | Cloud-agnostic deployment substrate | 1A | 243, 203, 204, 201, 244, 217, 234 | OPEN (IaC only — V2-D5) |
| [#229](https://github.com/sid12701/lms/issues/229) | 1A.6 | Per-LSP rate limits + read lane | 1A | — | OPEN |
| [#230](https://github.com/sid12701/lms/issues/230) | 1A.7 | Webhook per-LSP concurrency cap | 1A | — | OPEN |

Phase 4 items (archival automation, EAGER pass, read replica, quotas, JWT rotation, ICICI build, direct-to-storage uploads, Azure adapter) are trigger-driven and get issues when their triggers fire — see assessment §17.5.

---

## Phase 0 — Test bed

### #197 — WI-0.1: Synthetic portfolio seeder
**Link:** https://github.com/sid12701/lms/issues/197 · **Status:** **PARTIAL** — seeder implemented (`SyntheticPortfolioSeedService`, CLI `--seed-synthetic-portfolio`); smoke test + unit spec pass; **full month-9 volume seed not run** (deferred to staging). · **Blocked by:** none

**Solution:** Profile-gated seeder producing **10 fake LSPs**, portfolio at **month-9 of V2-D1** (~500K active accounts, ~3M payments, ~30M audit rows), via bulk JDBC batches (not JPA). Idempotent re-run or clean reset.
**Effect on app:** None in production — test tooling only.
**Scaling benefit:** Every current problem (dashboard O(N), audit UNION, worker batches) is invisible at small N. The seeder makes staging behave like month 9 of real volume, so fixes are proven against the load they exist for.

### #198 — WI-0.2: Two-instance staging stack
**Link:** https://github.com/sid12701/lms/issues/198 · **Status:** OPEN · **Blocked by:** none

**Solution:** One-command stack: 2 API pods (workers disabled via existing config flags) + 1 worker pod + Postgres/Redis/object storage. **Use local Postgres or containerized PG — not Supabase** (V2-D4) so staging matches prod pooler semantics.
**Effect on app:** None — environment only.
**Scaling benefit:** Multi-instance races (the double-disbursement class) only reproduce with ≥2 real instances against one DB. This is the rig every Phase 1 exit criterion runs on.

### #199 — WI-0.3: Concurrency race harness
**Link:** https://github.com/sid12701/lms/issues/199 · **Status:** OPEN · **Blocked by:** none

**Solution:** Latch-synchronized N-thread JUnit harness against Postgres testcontainers + instrumented mock disbursement adapter exposing an invocation counter.
**Effect on app:** None — test infrastructure; the adapter instrumentation is test-scoped.
**Scaling benefit:** Turns "provider called exactly once" from a hope into a CI assertion, permanently. Concurrency regressions get caught at commit time instead of at ₹-volume.

### #200 — WI-0.4: Load suite + baseline
**Link:** https://github.com/sid12701/lms/issues/200 · **Status:** OPEN · **Blocked by:** #197, #198

**Solution:** k6/Gatling profiles for sustained **33K/day**, peak **150K/day** (V2-D2), single-LSP spike, couple spike, all-LSP spike; baseline recorded against unfixed main.
**Effect on app:** None.
**Scaling benefit:** The before/after numbers that prove each phase's exit criteria; thresholds become regression gates for every later release.

---

## Phase 1 — Money safety

### #201 — WI-1.1: Hikari sizing + statement timeouts
**Link:** https://github.com/sid12701/lms/issues/201 · **Status:** OPEN · **Blocked by:** none

**Solution:** Explicit Hikari config for BOTH pools (admin + tenant routing) per deployment role; Postgres `statement_timeout` per app role + `idle_in_transaction_session_timeout`; separate longer-limit role for report/KPI workers.
**Effect on app:** Invisible until a pathological query — which now errors cleanly instead of freezing the whole app. Until #214 lands, a huge unfiltered audit search may visibly time out (the protection working).
**Scaling benefit:** Converts the system's worst failure mode (one bad query → total pool exhaustion → full outage) into a single-request error. The Spring default of 10 connections would collapse under launch traffic; this is the cheapest de-risking in the plan.

### #202 — WI-1.2: Claim-before-execute LSP idempotency
**Link:** https://github.com/sid12701/lms/issues/202 · **Status:** OPEN · **Blocked by:** #199

**Solution:** Idempotency record inserted FIRST, in the same transaction as the business action (status PENDING→COMPLETED before commit). Unique-index blocking serializes duplicates; the REQUIRES_NEW claim path is deleted so rollback removes the claim atomically — a recorded success for rolled-back work becomes impossible.
**Effect on app:** Happy path identical. Under concurrent duplicates: exactly-once execution instead of possible double-execution; the loser may block milliseconds longer, then receives the replayed response. The poisoned-record failure mode is structurally eliminated.
**Scaling benefit:** At ~10M LSP write calls/yr, duplicate-in-the-same-50ms pairs are a daily statistical certainty. This makes the idempotency guarantee airtight for every write endpoint at once, including all future ones using the wrapper.

### #203 — WI-1.3a: Disbursement claim + per-loan transactions
**Link:** https://github.com/sid12701/lms/issues/203 · **Status:** OPEN · **Blocked by:** #199

**Solution:** Replace unbounded `findByStatus` with atomic claim of N=25 via `FOR UPDATE SKIP LOCKED` + lease columns; per-loan processing in its own short transaction via a separate executor bean (kills the self-invocation that merges the batch into one transaction); atomic attempt counter replaces non-atomic `countBy` retry budget.
**Effect on app:** No API change; loan-status progression as seen by LSPs identical. Internally: a failing loan no longer shares fate with its batch siblings.
**Scaling benefit:** Two halves of the single most important fix. Removes the double-disbursement race that makes >1 worker pod unsafe, and removes the hours-long single transaction a 2K backlog would create. Unlocks horizontal worker scaling — worker count becomes a throughput dial.

### #204 — WI-1.3b: Intent rows + provider call outside transaction
**Link:** https://github.com/sid12701/lms/issues/204 · **Status:** OPEN · **Blocked by:** #203

**Solution:** Tx1 writes an intent row with a unique provider reference and commits → adapter call outside any DB transaction → tx2 records outcome. Sweeper resolves expired claims and stuck DISBURSEMENT_REQUESTED via provider status query — never blind resend. Parallel delivery pool (default 10) mirroring the webhook dispatcher.
**Effect on app:** Invisible to partners. Disbursement backlogs drain in minutes (10 parallel × ~1.5s/call ≈ 24K/hour vs. today's sequential ceiling); a crashed worker self-heals instead of leaving loans in limbo.
**Scaling benefit:** The provider-call-inside-transaction pattern is the defect that turns a retry into a double payment with a real bank. This is the architectural precondition for ICICI (D2) and for 8K-disbursement peak days; at 10× it scales by raising threads/pods.

### #205 — WI-1.4: Payment claim atomicity
**Link:** https://github.com/sid12701/lms/issues/205 · **Status:** OPEN · **Blocked by:** #199

**Solution:** Payment row inserted in the SAME transaction as its allocation (drop REQUIRES_NEW + the per-JVM `synchronized(intern)`); on unique violation, read the committed winner, fingerprint-check, return.
**Effect on app:** Happy path identical. The orphan failure mode — a committed RECEIVED payment with no installment allocation, then reported to the LSP as success — becomes impossible.
**Scaling benefit:** At 6–12M repayments/yr, the mid-transaction failure window gets hit weekly. Orphan payments are book-integrity corruption that recon would catch late and expensively; this removes the class.

### #206 — WI-1.5: Installment row locking
**Link:** https://github.com/sid12701/lms/issues/206 · **Status:** OPEN · **Blocked by:** #205

**Solution:** `SELECT … FOR UPDATE` on the target installment during payment resolution; concurrent different-key payments serialize.
**Effect on app:** Under a same-installment race, the loser now gets a clean `INSTALLMENT_ALREADY_PAID` 409 instead of a possible silent double-payment record.
**Scaling benefit:** Settlement-batch and retry traffic make same-installment collisions routine at volume. Single-row lock held for a short transaction — negligible contention cost at exact-EMI semantics.

### #222 — WI-1.6: Payment bounce/reversal
**Link:** https://github.com/sid12701/lms/issues/222 · **Status:** **DEFERRED (V2-D9)** · **Blocked by:** 205, 206 (when resumed)

**Decision:** Deferred to **ICICI bank integration** — bounce/reversal will be driven by bank API responses at integration time, not built in this pass.

**Original solution (do not implement now):** BOUNCED/REVERSED payment statuses + idempotent reversal command (ops + LSP); allocation rollback; `LOAN_PAYMENT_BOUNCED` webhook; D3b exact-full-EMI unchanged.

**When to resume:** ICICI build (Phase 4 trigger) or explicit new issue after bank contract is signed.

### #207 — WI-1.7: Conflicts → 409 + override constraint
**Link:** https://github.com/sid12701/lms/issues/207 · **Status:** OPEN · **Blocked by:** none

**Solution:** Global mapping of optimistic-lock/lock-acquisition failures to 409 with a stable error code; MANUAL_OVERRIDE restricted to an allowed transition set, with the unbounded form requiring a confirmation flag + CRITICAL audit alert. **v1.4 scope extension:** unique-constraint `DataIntegrityViolationException` → 409 as well (cause-inspected; FK/other integrity failures stay 500 — bugs, not races). Verified: no handler exists today, so unique-violation races (e.g. concurrent same-external-id creates) hit the generic 500. Loan-create gets dedicated replay handling in #236; this is the global net.
**Effect on app:** Partners/ops see clean 409s where races today produce 500s. Admins lose unrestricted status transitions — deliberate friction on the most dangerous button in the system.
**Scaling benefit:** At volume, concurrent transitions happen daily; 500s trigger partner retries and support tickets, 409s self-explain. The override constraint caps human blast radius as the team grows.

### #223 — WI-1.8: Redis failure policy
**Link:** https://github.com/sid12701/lms/issues/223 · **Status:** OPEN · **Blocked by:** none

**Solution:** Rate-limit filter catches Redis connectivity failures: fail-open for LSP business traffic (+ deduped ops alert + metric), fail-closed for auth endpoints; automatic recovery.
**Effect on app:** During a Redis outage, partner traffic keeps flowing (today: undefined, likely 500s on every limited path); login/token endpoints lock down.
**Scaling benefit:** Removes Redis as a single point of failure for ₹-moving traffic while keeping the brute-force surface protected. Defined failure behavior is what lets you run Redis without HA at this stage.

### #224 — WI-1.9: ApiClient credential lockout
**Link:** https://github.com/sid12701/lms/issues/224 · **Status:** OPEN · **Blocked by:** none

**Solution:** Failed-attempt counter + lockout window on API clients (mirror of the AppUser V94 design); audit parity; brute-force alert extension; admin unlock.
**Effect on app:** NEW partner-facing failure mode — a partner's bad-secret retry loop locks their client. **Partner comms + support path required.**
**Scaling benefit:** With 3–10 partners holding credentials to a ₹1,000-crore book, credential-stuffing protection on the machine surface stops being optional; per-IP rate limits alone don't stop distributed attempts.

### #208 — WI-1.10: Partition the big six tables
**Link:** https://github.com/sid12701/lms/issues/208 · **Status:** OPEN · **Blocked by:** none

**Solution:** Monthly range partitions on `created_at` for intake audit, status transitions, application audit events, document access audit, auth event audit, webhook delivery attempts (pre-launch rebuild while small); partition-creation worker 3 months ahead + missing-partition alert; payment table joins before repayment ramp.
**Effect on app:** Invisible — same data, same queries, pruned plans.
**Scaling benefit:** 50–150M audit rows/yr makes unpartitioned tables a slow-motion failure (vacuum, index bloat, un-droppable history). Partitioning empty tables is a cheap migration; re-partitioning live 100M-row tables is a project. Retention (D6: 24mo hot) becomes instant `DROP PARTITION`.

### #209 — WI-1.11: Retention sweeps
**Link:** https://github.com/sid12701/lms/issues/209 · **Status:** OPEN · **Blocked by:** none

**Solution:** Scheduled batched purges: LSP idempotency records >90 days; delivered/exhausted webhook outbox rows per policy; purge metrics.
**Effect on app:** Contract nuance — idempotency keys gain an effective 90-day memory (replay after purge creates a new operation, documented); ancient webhook deliveries become non-redrivable.
**Scaling benefit:** The idempotency table stores full response payloads and grows 10–20M PII-bearing rows/yr (tens of GB). Unbounded, it becomes both a performance and a data-protection liability.

### #225 — WI-1.12: Upload outside transaction + multipart limits
**Link:** https://github.com/sid12701/lms/issues/225 · **Status:** OPEN · **Blocked by:** none

**Solution:** Object-storage `store()` moved out of the DB transaction (bytes first, short tx for checklist/audit after; orphan-object sweep noted); explicit servlet multipart caps aligned with per-type document policy.
**Effect on app:** Upload contract unchanged. New failure nuance: a transient orphaned object in storage if the DB write fails (swept); client still gets a clean, retryable error.
**Scaling benefit:** 3–6M uploads/yr ≈ 40K/day peaks. Today every in-flight upload holds a pooled DB connection for the full storage round trip — a few dozen concurrent uploads could starve the entire app. This decouples upload bursts (cheap threads/bandwidth) from scarce connections.

### #226 — WI-1.13a: Remove RabbitMQ + ADRs
**Link:** https://github.com/sid12701/lms/issues/226 · **Status:** OPEN · **Blocked by:** none

**Solution:** Drop RabbitMQ from infra/config (D10 — nothing consumes it); ADR for INR-only currency (D9); ADR for the 2 API + 1 worker topology with flag-disabled workers (D1).
**Effect on app:** None at runtime; one less service to operate/secure; assumptions become explicit documents.
**Scaling benefit:** The INR-only ADR prevents the most painful retrofit in finance software from happening by accident; the topology ADR documents the worker-flag foot-gun before it bites a deploy.

### #210 — WI-1.13b: ICICI design ADR
**Link:** https://github.com/sid12701/lms/issues/210 · **Status:** OPEN · **Blocked by:** none

**Solution:** Design-only ADR (D2): outbox intent rows + provider idempotency references; bank event inbox (unique provider event id, ack-fast/process-async); nightly 3-way recon with mismatch alerts; breaker/timeout/bulkhead; repayment feed via the same inbox (post-launch); embedded go-live checklist + open questions for ICICI.
**Effect on app:** None — documentation; reviewed via normal PR.
**Scaling benefit:** Locks the money-safe integration shape before bank docs arrive, so the ICICI build is implementation, not invention — and the go-live checklist makes the safety ordering (Phase 1 first) enforceable.

### #236 — WI-1.14: Loan-create natural-key replay
**Link:** https://github.com/sid12701/lms/issues/236 · **Status:** OPEN · **Blocked by:** none · *(v1.4 cross-review, finding F20)*

**Solution:** `POST /lsp/loan-applications` becomes idempotent on its natural key `(lsp_id, external_loan_id)` — no new header. Duplicate external id: payload matches existing application → 200 replay (current detail); mismatch → 409 `EXTERNAL_LOAN_ID_CONFLICT` (replaces today's 400); race-window `DataIntegrityViolationException` caught in the create path and routed through the same resolve logic. Header-based alternative deliberately rejected — the external loan id *is* the key, and a header would add ~1M PII-bearing rows/yr to `lsp_api_idempotency_record` with a 90-day purge cliff (#209) the natural key doesn't have. Retry contract documented in API standards + Postman.
**Effect on app:** PARTNER-FRIENDLY CONTRACT CHANGE: retrying a successful create returns 200 with the existing application instead of 400; ID-reuse gets a clean 409 instead of 400; concurrent duplicates can no longer produce 500s. Strictly better for integrators, but the duplicate-create response shape changes — note in partner docs.
**Scaling benefit:** At ~1M creates/yr, retry-after-timeout is a daily event, and today every one lands in a partner-side error branch (or a book mismatch if they treat 400 as failure). Duplicates were never possible; this fixes what the retrying partner *experiences*, at zero storage cost.

### #237 — WI-1.15: Externalize CORS origins
**Link:** https://github.com/sid12701/lms/issues/237 · **Status:** OPEN · **Blocked by:** none · *(v1.4 cross-review, finding F21)*

**Solution:** `app.security.cors.allowed-origins` config property (env-overridable) feeds the `CorsConfigurationSource` bean, replacing the hardcoded `localhost:5173/4200` list in `SecurityConfig`; localhost defaults live only in the local profile; startup validator refuses empty list or wildcard-with-credentials outside local/test.
**Effect on app:** None at runtime when configured correctly; prod deploys gain a mandatory CORS env parameter and fail fast without it. Local dev unchanged.
**Scaling benefit:** Not a load issue — a deploy gate: with origins hardcoded to localhost, no non-local ops-frontend deployment can talk to the API at all. Trivial now, an emergency redeploy if discovered on launch day.

---

## Phase 1A — Capacity gate (v2 audit, reconciled)

Added by the 360° re-audit for structural throughput/isolation gaps. **Operating point reconciled to V2-D1** (~1M loans/month, **150K/day peak** per V2-D2) — not the audit's 100K/day sustained assumption. Run alongside Phase 1 money-safety; all listed here are launch-blocking before volume ramp.

### #247 — Phase 1A capacity gate (tracking issue)
**Link:** https://github.com/sid12701/lms/issues/247 · **Status:** OPEN · **Blocked by:** none

**Solution:** Umbrella tracking issue: V2-D1 operating point, Phase 1A list, exit criterion — **peak 150K/day all-LSP-spike** (#200) sustains target with no cross-tenant p99 regression, no connection-timeout 5xx, webhook backlog < 15 min, metrics/alerts visible.
**Effect on app:** None — coordination artifact.
**Scaling benefit:** Makes the re-phasing explicit: items the original plan placed in P2/P3 are launch-blocking at 12× volume, and three structural findings were not in the plan at all.

### #243 — WI-1A.1: Multi-threaded scheduler (N1)
**Link:** https://github.com/sid12701/lms/issues/243 · **Status:** OPEN · **Blocked by:** none

**Solution:** Configure a multi-threaded `TaskScheduler` (`spring.task.scheduling.pool.size` ≥ job count) and move each heavy worker onto its own executor so polls never run batch bodies inline. Today `@EnableScheduling` runs all four `@Scheduled` workers (disbursement/report/webhook/alerts) on Spring's default single thread.
**Effect on app:** Invisible to APIs. Internally, a slow disbursement tick no longer stalls webhook/report/alert ticks.
**Scaling benefit:** Background throughput becomes threads × pods instead of a single serial queue; prerequisite for running >1 worker pod. Security-relevant: the alert worker (brute-force, stuck-disbursement) stops being starved behind a slow batch.

### #244 — WI-1A.2: Per-tenant DB connection bulkhead (N3)
**Link:** https://github.com/sid12701/lms/issues/244 · **Status:** OPEN · **Blocked by:** none

**Solution:** Per-LSP in-flight concurrency bulkhead (semaphore keyed on `lspId`) bounding simultaneous DB-bound requests, plus (linked) a dedicated read pool/replica for ops/reporting. All tenants currently share one Hikari pool with no per-tenant cap.
**Effect on app:** A tenant exceeding its in-flight cap gets a clean 429/503 with `Retry-After` instead of starving others. Distinct from #229 (which caps request count, not connection hold time).
**Scaling benefit:** Closes the missing half of multi-tenancy — RLS isolates rows, this isolates compute/connections. Without it one LSP's slow queries 5xx every tenant; also a DoS-blast-radius control.

### #245 — WI-1A.3: Webhook delivery throughput ceiling
**Link:** https://github.com/sid12701/lms/issues/245 · **Status:** OPEN · **Blocked by:** #243, #203

**Solution:** Tune/make-configurable the claim batch + poll interval and enable multi-pod dispatch so the outbox drains **≥2M events/day at peak** (V2-D2: ~750K–1.5M events/day). Today's claim of 20 events per 60s caps a single instance at ~28.8K deliveries/day — below even **sustained** event rate at V2-D1.
**Effect on app:** Invisible to partners except that webhooks stop falling behind. **Ship with #230** (V2-D12) — per-LSP cap.
**Scaling benefit:** Turns delivery into a dial (batch × frequency × pods × threads) vs a fixed ~28.8K/day wall, against **~165K–1.5M events/day** at V2-D1 sustained/peak.

### #246 — WI-1A.4: Version-aware session cache (N6)
**Link:** https://github.com/sid12701/lms/issues/246 · **Status:** OPEN · **Blocked by:** #217 · *(P1 — after metrics; do not start before N1–N4)*

**Solution:** Version-aware cache of ApiClient session validation, invalidated on token-version bump and status→DISABLED (never TTL-only), so not every request pays a DB round trip for auth.
**Effect on app:** Lower per-request latency; revocation stays instant (ADR-0002 kill chain preserved). Feature-flagged.
**Scaling benefit:** Removes most per-request auth DB lookups at 100K/day + polling — load that otherwise competes with the connection pool. Security-sensitive: cache must be version-aware and tenant-scoped (collision test required).

### #248 — WI-1A.5: Cloud-agnostic deployment substrate
**Link:** https://github.com/sid12701/lms/issues/248 · **Status:** OPEN · **Blocked by:** #243, #203, #204, #201, #244, #217, #234 · *(V2-D5: IaC in-repo only; no `terraform apply` AFK)*

**Solution:** Provider-agnostic topology per `docs/architecture/deployment-strategy.md`: one OCI image / two roles (API + worker by flag) on Kubernetes, Terraform + Helm (AWS/GCP/**Azure reference**), **India region** default (V2-D6), transaction-mode pooler, managed Postgres (**not Supabase** — V2-D4), HPA API 3→**10** / worker 2→**6** at V2-D2 peak, 8-year archive lifecycle. **Implement and validate locally; human applies to cloud.**
**Effect on app:** None in code — depends only on portable interfaces (PG wire, Redis protocol, S3 API, SMTP, Prometheus). Switching cloud changes Terraform/Helm values, not the app.
**Scaling benefit:** Makes the platform elastic and portable: API HPA 3→12, worker tier 2→6 on queue-depth, ~480 client connections → pooler → ~100–150 backends. Topology scales the platform only **after** the component fixes above land — hence the blockers.

---

## Phase 2 — Read side & partner scale

### #211 — WI-2.1a: KPI snapshot job
**Link:** https://github.com/sid12701/lms/issues/211 · **Status:** OPEN · **Blocked by:** none

**Solution:** `dashboard_kpi_snapshot` table (unique per date/scope); nightly 00:15 IST job, SKIP-LOCKED claimed, computing all portfolio KPIs in SQL — TAT as one window-function query (deletes the per-approval N+1); idempotent admin recompute; FAILED row + alert + serve-last-good on failure.
**Effect on app:** Backend only until #212; new admin recompute endpoint.
**Scaling benefit:** Replaces an O(portfolio)-per-page-view computation with one nightly pass. At ~500K active accounts the old path dies in month 3–4; the snapshot read is O(1) forever, at 10× and beyond.

### #212 — WI-2.1b: Dashboard serves snapshots
**Link:** https://github.com/sid12701/lms/issues/212 · **Status:** OPEN · **Blocked by:** #211

**Solution:** Overview endpoint = 1 snapshot read + 3 live indexed counts (awaiting approval, in disbursement, open alerts — D5 split); permanent "Data as of <date>" in UI; old in-memory aggregation deleted.
**Effect on app:** MOST VISIBLE CHANGE for ops: portfolio KPIs become as-of-midnight with a date label; page becomes instant. Snapshot vs. old-live numbers may differ trivially at first comparison (timing cutoff, cleaner TAT method) — expect week-one questions; recompute settles them.
**Scaling benefit:** The dashboard stops taxing the write-path database entirely; cost is independent of portfolio size and viewer count.

### #213 — WI-2.2a: Report worker tx split + retry
**Link:** https://github.com/sid12701/lms/issues/213 · **Status:** OPEN · **Blocked by:** none

**Solution:** Claim+markProcessing commits with a processing lease → generate/upload outside any tx → complete/fail+notify in a new tx (email after commit); retry budget (attempt_count, next_attempt_at); terminal FAILED → ops alert.
**Effect on app:** Reports stop failing silently and permanently — they retry, then alert. No user-visible contract change.
**Scaling benefit:** Today the worker holds row locks + a DB connection + possibly SMTP across an entire batch — a long report starves the pool. With D7's report catalog multiplying job volume (nightly MIS × every LSP), correct boundaries are a prerequisite, not polish.

### #227 — WI-2.2b: Streaming report generation
**Link:** https://github.com/sid12701/lms/issues/227 · **Status:** OPEN · **Blocked by:** #213

**Solution:** Keyset-paged projection loop streaming CSV to storage (multipart) replacing full-entity hydration; guardrails: max 366-day range, per-user concurrent-pending cap, file-size cap.
**Effect on app:** Identical output; over-range/over-quota requests now get clean 400s.
**Scaling benefit:** A year-long MIS at 1M loans/yr is hundreds of thousands of rows — full hydration with 4-way join fetch is an OOM. Streaming makes report memory flat regardless of range, at any future volume.

### #228 — WI-2.2c: Time-limited download URLs
**Link:** https://github.com/sid12701/lms/issues/228 · **Status:** OPEN · **Blocked by:** none

**Solution:** NEW capability (nothing issues URLs today): "issue time-limited download URL" on the report storage abstraction, specified provider-agnostically (15-min validity) — R2 presigned GET now, Azure Blob SAS later as one class; access-audit row before issuance.
**Effect on app:** Report downloads become links instead of proxied bytes; links expire (no bookmarking) — re-fetch from the reports page. Frontend updated in-issue; Postman collection needs a touch.
**Scaling benefit:** Removes file bytes from JVM heap on every download and removes the double-hop; also the keystone for the LSP self-serve API (#215) and the future Azure migration staying a one-class job.

### #214 — WI-2.3: Audit explorer guardrails
**Link:** https://github.com/sid12701/lms/issues/214 · **Status:** OPEN · **Blocked by:** none

**Solution:** Mandatory date window (default 7d, max 90d); LSP predicate via indexed `lsp_id` column instead of regex-over-jsonb; count capped/estimated; keyset pagination past deep offsets.
**Effect on app:** OPS WORKFLOW CHANGE: a date range becomes mandatory; "search everything ever" is gone by design; deep counts may be estimates. Older research routes through the report pipeline.
**Scaling benefit:** An 8-way UNION over 50–150M rows/yr with no date bound is an outage button. The window + partition pruning (#208) fixes the worst case at design time — it cannot degrade with growth.

### #229 — WI-2.4a / 1A.6: DB-backed per-LSP limits + read lane
**Link:** https://github.com/sid12701/lms/issues/229 · **Status:** OPEN · **Phase:** **1A (launch-blocking, V2-D11)** · **Blocked by:** none

**Solution:** Per-LSP override table (writes/min, reads/min, optional daily quota) + admin UI; resolution DB-override → static default; NEW read lane (default 300/min); `Retry-After` on every 429; changes audited.
**Effect on app:** PARTNER-FACING: read limits where none existed — aggressive pollers see 429s on day one (**changelog + notify before prod** per V2-D10); commercial limit changes become a config edit, not a redeploy. New admin screen.
**Scaling benefit:** D4 math: a whale partner at 5K loans/day generates ~64 writes/min sustained — above the static 60/min default. Per-partner lanes are what make "any partner may be high-volume" operable across 3–10 partners.

### #230 — WI-2.4b / 1A.7: Webhook per-LSP concurrency cap
**Link:** https://github.com/sid12701/lms/issues/230 · **Status:** OPEN · **Phase:** **1A (launch-blocking, V2-D12)** · **Blocked by:** none

**Solution:** Per-LSP in-flight delivery cap in the dispatch executor + per-LSP in-flight metric.
**Effect on app:** Healthy partners unaffected; a partner with a dead endpoint sees its own deliveries pace-limited instead of consuming the shared pool.
**Scaling benefit:** With 10s read timeouts × 10 threads × batch 20, one blackholed partner endpoint can stall everyone's webhooks. Partner isolation is the property that keeps one integration's bug from becoming an all-partner incident.

### #231 — WI-2.5: Bulk repayment ingestion
**Link:** https://github.com/sid12701/lms/issues/231 · **Status:** OPEN · **Blocked by:** #206 only (#222 removed per V2-D15)

**Solution:** Batch endpoint (≤~10K rows) persisting payment-inbox rows (unique per lsp+batch+row), immediate ack with batch id; drain worker applies rows through existing payment machinery (locking; bounce-aware when ICICI lands). Per-row status endpoint; idempotent resubmit. (D3a resolved.)
**Effect on app:** NEW FEATURE for partners; per-EMI endpoint unchanged. Settlement mornings become one call + async drain.
**Scaling benefit:** NACH/UPI settlement results arrive as morning batches of thousands; 5K confirmations through 60/min per-call limits is ~83 minutes of saturated calls. The inbox absorbs the real arrival shape at 1× and 10×.

### #232 — WI-2.6a: Nightly partner MIS enqueuer
**Link:** https://github.com/sid12701/lms/issues/232 · **Status:** OPEN · **Blocked by:** #213

**Solution:** Cron job dropping one standing PORTFOLIO_MIS request per active LSP (previous day's close), deduped per (LSP, date), through the hardened pipeline.
**Effect on app:** NEW: every partner finds yesterday's MIS waiting each morning — removes ops as a manual report desk.
**Scaling benefit:** Partner reporting cost becomes O(partners) at a few jobs/night, instead of O(requests × ops time). Scales to 10 partners as a config row each.

### #215 — WI-2.6b: LSP self-serve report API
**Link:** https://github.com/sid12701/lms/issues/215 · **Status:** OPEN · **Blocked by:** #228

**Solution:** LSP-scoped endpoints: create report request, list own, download via time-limited URL; RLS scoping verified end-to-end; rate-limited.
**Effect on app:** NEW partner capability — previously reports were internal-only.
**Scaling benefit:** Ten partners asking "where's my file?" is a full-time ops job; self-serve deletes it. Isolation rests on RLS already in place (V71) — verified, not invented.

### #216 — WI-2.6c: Collections/DPD + disbursement register
**Link:** https://github.com/sid12701/lms/issues/216 · **Status:** OPEN · **Blocked by:** #213

**Solution:** Two new generators on the pipeline: collections/DPD (bucket-wise outstanding, slippage) and disbursement register (every transfer with provider references — the recon-facing report).
**Effect on app:** NEW report types in the catalog; no contract changes.
**Scaling benefit:** The register doubles as the human-readable face of ICICI reconciliation; collections/DPD is the daily ops lens on a 400–700K-account book that the dashboard summarizes but can't itemize.

### #233 — WI-2.6d: Generic regulatory extract
**Link:** https://github.com/sid12701/lms/issues/233 · **Status:** OPEN · **Blocked by:** #213

**Solution:** Parameterized portfolio extract (configurable columns, filters, date range, CSV) with stored presets; PII columns permission-gated. (v1.2 decision: generic now, compliance shapes become presets.)
**Effect on app:** NEW report type; ops/compliance gain ad-hoc extract capability without engineering involvement.
**Scaling benefit:** Decouples regulatory deadlines from engineering sprints — when a filing shape arrives, it's a preset, not a project.

---

## Phase 3 — Operations

### #217 — WI-3.1: Prometheus + Postgres telemetry
**Link:** https://github.com/sid12701/lms/issues/217 · **Status:** OPEN · **Blocked by:** none

**Solution:** Micrometer Prometheus registry, internal-only scrape endpoint; HTTP latency/error per endpoint, both Hikari pools, JVM, scheduler timings; `pg_stat_statements` + 500ms slow-query logging.
**Effect on app:** Invisible to users; the app becomes measurable.
**Scaling benefit:** Every capacity decision after launch (pool sizes, worker counts, read-replica trigger) is supposed to be made from these numbers instead of guesses.

### #218 — WI-3.2: JSON logging + MDC enrichment
**Link:** https://github.com/sid12701/lms/issues/218 · **Status:** OPEN · **Blocked by:** none

**Solution:** JSON log encoding in non-local profiles; MDC adds lspId, applicationId/loanAccountId, actor alongside correlationId; PII-leak guard test; local stays human-readable.
**Effect on app:** OPERATIONAL: anything tailing/grepping plain-text logs needs updating; in exchange, "show me everything for loan X / partner Y" becomes one query.
**Scaling benefit:** At 5–15M requests/yr across 10 partners, grep-able prose is unusable; structured context is how a 2am incident gets scoped to a partner and a loan in minutes.

### #234 — WI-3.3: Domain metrics
**Link:** https://github.com/sid12701/lms/issues/234 · **Status:** OPEN · **Blocked by:** #217

**Solution:** Queue depths + oldest-pending ages (webhook outbox, reports, payment inbox, disbursement claims); idempotency replays per LSP; 429s per rule per LSP; payments posted/bounced; stuck-disbursement counts.
**Effect on app:** Invisible; the workers become observable.
**Scaling benefit:** DB-table queues self-heal but don't self-report — oldest-pending-age is the single metric that catches a stalled worker in minutes instead of via partner complaint.

### #235 — WI-3.4: AlertNotifier fan-out
**Link:** https://github.com/sid12701/lms/issues/235 · **Status:** OPEN · **Blocked by:** none

**Solution:** AlertNotifier on ops-alert creation: email (daily digest ≤MEDIUM) + generic webhook (configurable URL/template — Slack/Teams/Chat by config) instant for HIGH/CRITICAL; storm grouping via existing dedupe; notifier failure never blocks alert creation. (D8 resolved.)
**Effect on app:** STAFF-VISIBLE: alerts start arriving instead of sitting unread in a table; severity split prevents fatigue.
**Scaling benefit:** The detection logic already exists — this is the last mile that turns "stuck disbursement >2h" from a database row into a 10-minute response. Phone-grade paging remains the ICICI go-live upgrade.

### #219 — WI-3.6: Grafana dashboards + alert rules
**Link:** https://github.com/sid12701/lms/issues/219 · **Status:** OPEN · **Blocked by:** #217, #234

**Solution:** Dashboards as code (API, workers, DB, per-LSP) + the full §12 alert rule set (latency, errors, pool usage, queue ages, stuck disbursements, KPI-snapshot-missing, partition-missing, Redis down, per-LSP spikes), thresholds parameterized.
**Effect on app:** None at runtime; ops gain the boards and pages.
**Scaling benefit:** The per-LSP board answers "which partner is degrading us / are we degrading partner X" — unanswerable today, mandatory at 3–10 partners.

### #220 — WI-3.7: Runbooks + alert-path verification
**Link:** https://github.com/sid12701/lms/issues/220 · **Status:** OPEN · **Blocked by:** #235, #219

**Solution:** One runbook per §13 failure mode (symptom/diagnosis/action/rollback) referencing real metric/alert names; automated end-to-end test raising a synthetic alert and asserting delivery at the webhook sink. Human fire drill + rota → launch checklist.
**Effect on app:** None at runtime; incident response becomes documented procedure.
**Scaling benefit:** At volume, incidents are when-not-if; runbooks convert 2am improvisation into execution, and the automated path-test keeps the alerting wire provably live forever.

### #221 — WI-3.8: Upload malware scanning
**Link:** https://github.com/sid12701/lms/issues/221 · **Status:** OPEN · **Blocked by:** #225

**Solution:** Scan-on-upload (ClamAV or equivalent); PENDING_SCAN/INFECTED quarantine states on the checklist; downloads blocked until clean; alert on detection; scanner outage queues (never bypasses).
**Effect on app:** OPS-VISIBLE: brief delay between upload and downloadability; infected files quarantined. LSP upload API unchanged (async scan).
**Scaling benefit:** 3–6M partner-supplied files/yr served to ops staff browsers is a malware delivery channel at scale; scanning closes it before download volume grows.

---

## Status legend & maintenance

- **OPEN** — issue created, not started · **IN PROGRESS** — branch open · **PR** — link the PR · **CLOSED** — link the closing PR · **DEFERRED** — owner decision; do not implement until resumed.
- When an issue closes, update its row in the at-a-glance table **and** its entry status line, and bump the "Last tracker sync" date in the header.
- **Authoritative decisions:** v2 register above (V2-D1–V2-D15). Original D1–D10 in assessment v1.2; D6 retention/region confirmed in V2-D6.
- **Agent entry point:** read **v2 Decision register** + **Agent implementation plan** before any issue.
- Phase gates: **#247 exit criterion** in Agent implementation plan; assessment §17.2–17.4 for historical phase definitions.
