## What to build

A two-instance staging stack mirroring launch topology (D1): **2 API pods** (scheduled workers **disabled** via config flags) + **1 worker pod** (workers enabled), Postgres, Redis, object storage. One command brings the stack up.

**V2-D4:** Use **local/containerized Postgres** (or managed PG with pooler) — **not Supabase** — so staging matches prod connection semantics.

**Operating point:** sized for **10 LSPs** and load suite (#200) at **33K/day sustained / 150K/day peak**. Tracker: `scalability-execution-tracker.md`.

Source: scalability-assessment WI-0.2; updated 2026-06-15.

## Acceptance criteria

- [ ] Both API pods serve traffic behind a single entry point
- [ ] Only worker pod runs scheduled workers (verifiable from logs/config)
- [ ] Single documented command to boot stack
- [ ] #200 spike scenarios runnable against this stack
- [ ] Postgres is not Supabase session-pooler (mirrors V2-D4 prod path)

## Blocked by

None — start immediately
