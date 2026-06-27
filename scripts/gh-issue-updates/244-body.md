## What to build

Per-tenant (per-LSP) **in-flight concurrency bulkheads** for DB-bound work, plus an isolated read pool, so one tenant cannot occupy the entire shared connection pool and degrade the other **nine LSPs**.

Distinct from per-LSP **request-count** rate limits in **#229** (launch-blocking, V2-D11).

Source: scalability-audit-360-2026-06-14.md, finding **N3**. Operating point: **10 LSPs**, **~150K/day peak** spike scenarios (V2-D2). Tracker: `scalability-execution-tracker.md`.

## Evidence

- One `tenantPhysicalDataSource` Hikari pool; all LSPs share physical connections.
- RLS isolates rows, not connections/compute.
- Rate limits (#229) cap requests/min, not connection hold time.

## Why it matters (noisy-neighbor @ 10 LSPs)

- *One LSP spikes:* monopolizes shared pool → all tenants 5xx.
- *Couple / all spike at **150K/day peak:*** pool saturates instantly.

Default bulkhead cap: **floor(tenant_pool_size / 10)** per LSP.

## Acceptance criteria

- [ ] Configurable per-LSP in-flight cap (semaphore on `lspId`); sane default + override hook
- [ ] Load test: spiking tenant does **not** raise another tenant's p99 (#199 / #200 spike profiles)
- [ ] At cap: 429/503 + `Retry-After`, not connection-timeout 500
- [ ] Per-tenant in-flight + rejection metrics (#217)
- [ ] (Stretch) dedicated read pool / replica for ops reporting

## Blocked by

#201 for sizing math. Metrics assertions depend on #217.
