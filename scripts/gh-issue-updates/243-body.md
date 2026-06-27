## What to build

A multi-threaded task scheduler plus dedicated executors for the heavy background workers, so that the four `@Scheduled` jobs no longer share a single thread and no poll does its batch work inline on the scheduler thread.

Source: scalability-audit-360-2026-06-14.md, finding **N1** (Phase 1A capacity gate #247).

**Operating point:** `scalability-execution-tracker.md` V2-D1 — **~1M loans/month** (~33K/day sustained), **~150K/day peak**, **10 LSPs**.

## Evidence (verified in source)

- `LmsApplication.java:12` — `@EnableScheduling`, no `TaskScheduler` / `spring.task.scheduling.pool.size` → default pool size **1**.
- All four workers `@Scheduled` on that one thread: disbursement (30s), reports (15s), webhooks (60s), alerts (5min).
- `fixedDelay` — slow disbursement/report batch **blocks** webhook claiming, report processing, and alert evaluation for all tenants.

## Why it matters at scale (V2-D1 + V2-D2)

At low volume this is invisible. At **~1M loans/month** with **150K/day spikes**, disbursement and report batches take seconds-to-minutes; while either runs, webhooks and alerts stall for **all 10 LSPs**. Partner webhooks fall hours behind; stuck-disbursement and brute-force alerts go quiet under peak load. Decoupling makes background throughput a function of executor sizing, not one serial queue.

## Cross-impacts

- **Security:** `AlertRuleSchedulerWorker` delayed behind slow batches → longer attacker dwell time.
- **Webhooks / reports:** partner state and MIS processing compete on the same thread.

## Acceptance criteria

- [ ] `ThreadPoolTaskScheduler` (or `spring.task.scheduling.pool.size` ≥ 5) configured; context test.
- [ ] Disbursement and report polls submit batch work to bounded executors; scheduler thread only triggers.
- [ ] Test: slow disbursement tick does **not** delay webhook/report/alert ticks beyond their fixed delay.
- [ ] Executor sizes configurable per deployment role (API vs worker).
- [ ] Graceful shutdown drains in-flight executor tasks.

## Blocked by

None. Pairs with #203/#204 and #230.
