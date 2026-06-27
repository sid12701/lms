## What to build

Raise and make-configurable the webhook **delivery throughput** so the outbox can drain the event volume at **V2-D1 sustained** and **V2-D2 peak**, and make the dispatcher horizontally scalable across worker pods. Distinct from per-LSP isolation cap **#230** (ship both — V2-D12).

Source: scalability-audit-360-2026-06-14.md §3 + finding N5. Operating point: `scalability-execution-tracker.md` V2-D1/V2-D2.

## Evidence (verified in source)

- `application.yml:69-73`: `batch-size: 20`, `fixed-delay-ms: 60000`, `thread-pool-size: 10`.
- `WebhookOutboxDispatchWorker.java`: claims **20** events per 60s tick → **~28,800 deliveries/day per instance** (claim is the ceiling).

## The arithmetic (V2-D1 / V2-D2)

| | Events/day |
|---|------------|
| **Sustained** (~1M loans/month, ~33K/day + repayments) | **~165–330K** (5–10 lifecycle events/loan) |
| **Peak** (~150K loans/day, 4.5×) | **~750K–1.5M** |
| **Current ceiling (1 instance)** | **~28.8K** |

The outbox backs up from day one at sustained volume. Fixing claim batch/interval + multi-worker turns throughput into a dial (batch × frequency × pods × threads).

**Target:** drain **≥2M events/day** at peak across the worker tier; backlog **< 15 min** at peak (#247 exit).

## Acceptance criteria

- [ ] Claim batch size and poll interval tuned/configurable; document resulting deliveries/day per pod.
- [ ] Multiple worker pods run dispatcher without double-delivery (race-harness + existing lease/claim).
- [ ] Backlog drain test: e.g. 200K-event backlog clears within documented SLA on staging.
- [ ] Oldest-pending-age / outbox-depth metrics + alerts (#217/#234).
- [ ] Shipped with **#230** per-LSP in-flight cap.

## Blocked by

- #243, #203 for multi-pod path. Single-pod tuning can land first.
