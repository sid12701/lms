## What to build

A profile-gated synthetic data seeder that fills a staging database with a **month-9 portfolio of V2-D1** (~1M loans/month operating point):

- **10 synthetic LSPs** (not 5)
- ~500K active loan accounts with repayment schedules
- ~3M payment transactions
- ~30M rows across audit streams
- Realistic status mix across the 10 tenants

Use bulk JDBC batch inserts, not per-entity JPA persistence. Follow the existing demo-portfolio seeder pattern, scaled up.

**Operating point reference:** `scalability-execution-tracker.md` V2-D1 — ~1M loans/month sustained, **150K/day peak** for load tests (#200).

Source: scalability-assessment WI-0.1; expanded 2026-06-15 per v2 decision register.

## Acceptance criteria

- [ ] Seeder runs only under an explicit profile/flag, never in default configuration
- [ ] Full seed completes in under 60 minutes on a developer-grade machine (larger N than original 30 min / 200K spec)
- [ ] **10 LSPs** seeded with realistic per-tenant volume skew (one whale optional)
- [ ] Row counts match documented spec (~500K accounts, ~3M payments, ~30M audit)
- [ ] Re-running is idempotent or performs a clean reset
- [ ] Seeded data satisfies all schema constraints (FKs, checks, uniques)

## Blocked by

None — can start immediately
