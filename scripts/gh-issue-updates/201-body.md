## What to build

Production-grade connection-pool and query-timeout configuration. Explicit Hikari sizing for BOTH pools (admin + tenant routing) with per-deployment-role values (API vs worker), connection timeout, and leak detection. Postgres `statement_timeout` per app role + `idle_in_transaction_session_timeout`; separate longer-limit role for report/KPI workers.

**Operating point:** `scalability-execution-tracker.md` V2-D1/V2-D2 — **~1M loans/month** sustained, **~150K/day peak**, **10 LSPs**. Size for **~8 API + ~4 worker pods at peak** → **~100–150 PG backends** behind transaction-mode pooler. **Prod: managed Postgres, not Supabase** (V2-D4).

Source: scalability-assessment WI-1.1 (R6); expanded by 360° audit finding **N4**.

## Acceptance criteria

- [ ] Pool sizes configurable per deployment role via environment
- [ ] Injected slow query killed by `statement_timeout` without exhausting pool
- [ ] Tenant pool explicitly sized and named, not Spring defaults
- [ ] Defaults for API vs worker documented

## v2 audit scope expansion — finding N4 · P0 / launch-blocking

1. **No production profile.** Add `application-prod.yml` (or env defaults); refuse unsized framework defaults in non-local profiles. Both pools currently default to **10** connections.
2. **Tomcat / DB mismatch.** Align `server.tomcat.threads.max` to ~3× tenant pool size.
3. **`autoCommit=false` hold.** Mandatory `idle_in_transaction_session_timeout` on tenant pool.
4. **Pooler ceiling.** Size total connections across pods against **managed Postgres + transaction-mode pooler** limits (not Supabase ~15-session cap). Document topology math for V2-D2 peak (~320 client → ~100–150 backend).

### Added acceptance criteria

- [ ] Prod profile exists; app refuses unsized defaults outside local/test
- [ ] `idle_in_transaction_session_timeout` verified
- [ ] Tomcat max threads aligned; documented
- [ ] Cross-pod connection math fits pooler/`max_connections`; documented

## Blocked by

None — start immediately
