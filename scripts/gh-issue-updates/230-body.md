## What to build

Per-LSP **in-flight delivery cap** in the webhook dispatch executor so one partner's dead/slow endpoint cannot consume the shared pool and delay everyone else's webhooks.

**Phase:** **1A launch-blocking** (V2-D12) — **ship with #245**.

**Operating point:** **10 LSPs**; at **~1M loans/month** one blackholed endpoint × 10s timeout × shared threads can stall all partners. Tracker: `scalability-execution-tracker.md`.

Source: scalability-assessment WI-2.4b (F10/N5).

## Acceptance criteria

- [ ] Configurable per-LSP in-flight cap on concurrent webhook deliveries
- [ ] Per-LSP in-flight metric exposed (#217/#234)
- [ ] Healthy partners unaffected when one endpoint is down
- [ ] Shipped in same release as #245 throughput tuning

## Blocked by

None — can start immediately; coordinate release with #245
