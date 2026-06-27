## What to build

Per-LSP rate limits as data, not config (D4: **10 LSPs**, any may be high-volume — static **60 writes/min** blocks origination at **~1M loans/month**). Per-LSP override table (writes/min, reads/min, optional daily quota) + admin UI; resolution DB-override → static default; **read lane** for LSP GET traffic (default **300/min**); `Retry-After` on every 429.

**Phase:** **1A launch-blocking** (V2-D11). Operating point: `scalability-execution-tracker.md` V2-D1 — ~33K/day sustained, **150K/day peak**, 10 LSPs.

Source: scalability-assessment WI-2.4 (F10); promoted 2026-06-15 per v2 decision register.

## Acceptance criteria

- [ ] Limit changes take effect without redeploy (cache TTL documented)
- [ ] Read lane enforced per LSP (300/min default; whale overrides)
- [ ] All 429 responses carry `Retry-After`
- [ ] Limit changes audited
- [ ] Entry in `docs/partner-api-changelog.md` (V2-D10); human notifies partners before prod traffic

## Blocked by

None — start immediately (Phase 1A gate #247)
