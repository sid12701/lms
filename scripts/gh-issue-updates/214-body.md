## What to build

Audit explorer guardrails for **~200–400M audit rows/year** at V2-D1 sustained volume. Mandatory date window (default **7 days**, max **90**); indexed `lsp_id` predicate instead of regex-over-jsonb; capped/estimated count; keyset pagination.

**Operating point:** `scalability-execution-tracker.md` V2-D1 — **~1M loans/month**, **10 LSPs**, **150K/day peak**.

Source: scalability-assessment WI-2.3 (R8/F8); promoted Phase 1A launch-blocking.

## Acceptance criteria

- [ ] Windowless or over-window requests rejected with 400
- [ ] EXPLAIN shows partition pruning + index use at **30M-row** seeder scale (#197)
- [ ] Deep pagination latency stable
- [ ] UI filters work within window rules

## Blocked by

None — start immediately
