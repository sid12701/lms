## Why this exists

The 360° re-audit (`scalability-audit-360-2026-06-14.md`) found structural throughput/isolation gaps. The **owner operating point** was reconciled **2026-06-15** in `scalability-execution-tracker.md` (V2-D1–V2-D15) — this supersedes the audit's **100K/day sustained** assumption.

### Authoritative operating point (V2-D1 / V2-D2)

| Dimension | Launch commitment |
|-----------|-------------------|
| Origination (sustained) | **~1M loans/month** (~33K/day, ~12M/year) |
| Origination (peak — sizing & load tests) | **~150K loans/day** (4.5× sustained) |
| Tenants | **10 LSPs** (any may spike alone; a couple together; all together) |
| Repayments (steady-state book) | ~100–150K/day sustained; **~450K/day at peak** |
| Webhook events | ~165–330K/day sustained; **~750K–1.5M/day at peak** |
| Audit rows | ~200–400M/year at sustained volume |
| Data consistency | **First-class** — Phase 1 money-safety + concurrency harness under spike profiles |

**Why Phase 1A is launch-blocking at this point:** not because sustained average is 100K/day, but because (a) the **webhook claim ceiling (~28.8K/day/instance)** is below even **sustained** event rate, (b) **spike + isolation** requirements need bulkheads/scheduler/multi-worker, and (c) **money-path correctness** is non-negotiable.

This issue tracks the **Phase 1A capacity gate**: the set that must land before any volume ramp. It runs **alongside** Phase 1 money-safety (#202/#205/#206) — not instead of it.

## New issues (structural findings)

- [ ] #243 — **N1 Multi-threaded scheduler** (single scheduler thread serializes all 4 workers)
- [ ] #244 — **N3 Per-tenant DB connection bulkhead** (one LSP can drain the shared pool → all tenants 5xx)
- [ ] #245 — **Webhook delivery throughput** (claim batch 20/60s ≈ 28.8K/day ceiling vs **~165K–1.5M events/day** at V2-D1 sustained/peak)
- [ ] #246 — **N6 Version-aware session cache** (per-request auth DB+Redis+SET ROLE overhead) — after metrics

## Promoted to launch-blocking (Phase 1A)

- [ ] **#201** — pool sizing + statement/idle timeouts (N4: prod profile, Tomcat alignment, pooler — **migrate off Supabase** for prod)
- [ ] **#203 / #204** — disbursement claim + per-loan tx + provider-call-outside-tx (daily stall at volume, not just a race)
- [ ] **#211 / #212** — dashboard KPI snapshot (O(N)-in-JVM dies at multi-million active book)
- [ ] **#214** — audit explorer guardrails (unbounded UNION over hundreds of millions of rows/yr)
- [ ] **#208 / #209** — partition big-six + retention/purge (partition while empty)
- [ ] **#223** — Redis failure policy (fail-open business / fail-closed auth)
- [ ] **#217 / #234** — Prometheus + domain metrics (cannot run blind at volume)
- [ ] **#229** — per-LSP rate limits + read lane (V2-D11 — static 60 writes/min blocks origination)
- [ ] **#230** — webhook per-LSP concurrency cap (V2-D12 — ship with #245)

## Phase 1 money-safety (parallel, not gated here)

#202 (idempotency), #205/#206 (payment atomicity + installment lock), #236 (loan-create replay), #237 (CORS), #224 (ApiClient lockout).

**#222 bounce/reversal — DEFERRED (V2-D9)** to ICICI bank integration. Do not implement in this pass.

## Test-bed implications

- **#197** seeder: **10 synthetic LSPs**, month-9 portfolio of V2-D1 (~500K active accounts, ~3M payments, ~30M audit rows).
- **#199** concurrency harness: assert **N3 bulkhead** — spiking tenant must not raise another tenant's p99.
- **#200** load suite: sustained **33K/day**, peak **150K/day**; single-LSP spike, couple spike, all-LSP spike profiles.

## Exit criterion (#247 gate)

The **peak 150K/day all-LSP-spike** profile (#200) sustains for **2+ hours** with:

- Error rate < 0.5%
- No cross-tenant p99 regression
- No connection-timeout 5xx
- Webhook backlog drains within **15 minutes** at peak
- Queue depths / oldest-pending-age visible on metrics/alerts (#217/#234)

**Tracker:** `scalability-execution-tracker.md` — v2 decision register + agent implementation plan.
