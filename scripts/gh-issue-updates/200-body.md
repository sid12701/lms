## What to build

A k6 (or Gatling) load-test suite covering spike-tolerant scenarios for **V2-D1 / V2-D2**:

| Profile | Target |
|---------|--------|
| **Sustained** | ~33K loans/day (~1M/month), 10 LSPs, mixed traffic |
| **Peak** | **~150K loans/day** (4.5× sustained — sizing gate) |
| **Spike scenarios** | Single-LSP spike; couple-LSP spike; **all-LSP spike** |

Also: settlement-morning payment burst, document-upload spike, rate-limit breach, dashboard-under-load.

Record baseline against unfixed main; regression gate after each wave.

**Operating point:** `scalability-execution-tracker.md` V2-D1/V2-D2. Harness: `scripts/perf/`.

Source: scalability-assessment WI-0.4; expanded 2026-06-15.

## Acceptance criteria

- [ ] Suite runs against staging stack (#198) via single documented command
- [ ] Sustained (**33K/day**) and peak (**150K/day**) profiles implemented
- [ ] All three spike scenarios (one / couple / all LSPs) implemented
- [ ] Baseline results against unfixed main recorded in repo
- [ ] Pass/fail thresholds parameterized; align with `docs/perf/LOAD_TEST_PLAN.md` SLOs
- [ ] **#247 exit:** peak all-LSP-spike × 2h — error < 0.5%, no cross-tenant p99 regression, no connection-timeout 5xx, webhook backlog < 15 min

## Blocked by

- #197
- #198
