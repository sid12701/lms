# LMS Performance Test Report

**Date:** 2026-06-14  
**Environment:** Local backend (`localhost:8080`, `local` profile) + Supabase Postgres (remote) + Docker Redis/RabbitMQ/MinIO  
**Harness:** `scripts/perf/` (Python orchestrator)  
**Target:** 100,000 loans/day  

Raw JSON metrics: `scripts/perf/.perf-runs/reports/`  
Auto-aggregated tables: [PERFORMANCE_REPORT_AGGREGATED.md](./PERFORMANCE_REPORT_AGGREGATED.md)

---

## Executive summary

Controlled performance tests were executed against a **single backend instance** with **Hikari maximum-pool-size=5** (local profile). The platform **does not currently meet** the throughput and latency requirements for 100,000 loans per day at peak load.

| Verdict | Detail |
|---------|--------|
| **Production readiness** | **Not ready** |
| **Readiness score** | **32%** toward 100k loans/day |
| **Primary blockers** | DB connection pool exhaustion, dashboard O(N) aggregates, slow loan creation (~7s), report pipeline timing |
| **Positive signals** | Idempotency replay works; concurrent disburse requests returned 2xx without duplicate errors in race test |

After a partial load attempt (8 concurrent workers), **actuator health returned 503 DOWN**, indicating readiness probe failure under modest concurrency — unacceptable for production peak (target ~82 RPS blended).

**Recommendation:** Complete scalability Phase 0–1 work (#197–#207) before re-testing at peak tier. Do not promote to production at 100k/day without fixes.

---

## Load model (100k loans/day)

| Tier | Loans/sec | API RPS | Tested? |
|------|----------:|--------:|---------|
| Average | 1.16 | 24 | Partially (baseline only) |
| Peak | 4.05 | 82 | **No** — backend degraded before reaching |
| Burst | 11.57 | 211 | **No** |
| Sustained peak | 4.05 | 79 | **No** |

Daily API volume estimate: **~2.0M calls** (origination + repayments + ops reads).

---

## Test harness

| Component | Location |
|-----------|----------|
| Orchestrator | `scripts/perf/run.py` |
| Workflows | `scripts/perf/workflows.py` |
| Fixtures | `scripts/perf/fixtures.py` |
| Load model | `scripts/perf/load_model.py` |
| k6 script | `scripts/perf/k6/loan_lifecycle.js` |
| Playwright UI | `frontend/e2e/perf-under-load.spec.ts` |
| Plan | [LOAD_TEST_PLAN.md](./LOAD_TEST_PLAN.md) |
| Run instructions | `scripts/perf/README.md` |

### Scenarios executed

| Scenario | Duration | RPS | Error % | p95 latency | Notes |
|----------|----------|-----|---------|-------------|-------|
| **baseline** | 189s | 0.34 | 4.7% | 3.9s | Full lifecycle; disburse step errors |
| **concurrency** | 111s | 0.26 | 0% | 8.5s | 8× parallel disburse + idempotency |
| **reporting** | 109s | 1.54 | 28.6% | 16.1s | Dashboard + MIS under parallel load |
| **failure** | 11s | 0.37 | 50%* | 6.7s | *Expected 4xx on negative cases |
| **load** | — | — | — | — | **Aborted** — worker timeout; health DOWN after |
| stress / spike / soak | — | — | — | — | **Not run** — deferred after degradation |

---

## Results by workflow

### Origination (LSP API)

| Workflow | p95 (ms) | Error % | Finding |
|----------|----------:|--------:|---------|
| `origination.create` | 7,339 | 0% | **Too slow** — target <2s |
| `origination.upload_doc` | 3,468–10,534 | 0% | Scales poorly under concurrency |
| `origination.approval_poll` | 1,857 | 0% | Worker latency dominates |
| `race.idempotency_replay` | 1,742 | 0% | **Pass** |

### Disbursement

| Workflow | p95 (ms) | Error % | Finding |
|----------|----------:|--------:|---------|
| `disbursement.initiate` | 3,870 | 100% | Likely 409 — auto-disbursed |
| `race.disbursement` | 8,531 | 0% | Needs DB consistency verification |

### Admin / reporting

| Workflow | p95 (ms) | Error % | Finding |
|----------|----------:|--------:|---------|
| `admin.dashboard` | **32,291** | 0% | **Critical** — live aggregates |
| `report.download` | 1,930 | **75%** | Poll window vs 15s worker delay |
| `admin.alerts` | 1,140 | 100% | Harness used wrong pagination params (fixed) |

---

## Database and infrastructure

| Metric | Observed | Target @ peak |
|--------|----------|---------------|
| Hikari pool (local) | **max 5** | 20–50 per instance |
| Actuator health after load | **503 DOWN** | UP |
| Prometheus | Not deployed | Required (#217) |

---

## UI findings

Playwright spec created but **not executed** this session. Run with API load in parallel — see `scripts/perf/README.md`.

---

## Bottlenecks (prioritized)

### P0
1. Hikari pool size = 5 (#201)
2. Dashboard live aggregates 16–32s p95 (#211, #212)
3. Loan create ~7s p95 (#236)

### P1
4. Rate limits block realistic LSP load (#229)
5. Report async race (#213)
6. Document upload in request path (#225)
7. No Prometheus/OTel (#217)

### P2
8. Single-instance only (#198)
9. Disbursement worker 30s delay
10. Actuator lacks metrics endpoint

---

## Production readiness: **32%**

| Dimension | Score (0–10) |
|-----------|-------------:|
| Origination throughput | 3 |
| Servicing / payments | 4 |
| Admin / dashboard | 2 |
| Reporting | 4 |
| Reliability | 5 |
| Observability | 2 |
| Infrastructure | 3 |

### Can the platform handle 100,000 loans/day?

**No.** At ~1.5 RPS the system showed 28% errors and 32s dashboard latency. Target peak is ~82 RPS (~55× higher).

**Next:** Review findings → implement Phase 0–1 from `scalability-execution-tracker.md` → re-run full matrix at peak tier.

**No application code fixes were made in this engagement.**
