## What to build

A **version-aware** cache for ApiClient session/credential validation so that not every LSP API call pays a database round trip for auth, while preserving instant-revocation (ADR-0002 kill chain).

Source: scalability-audit-360-2026-06-14.md, finding **N6**. Schedule **after** N1–N4 and metrics (#217).

**Operating point:** `scalability-execution-tracker.md` V2-D1 — **~1M loans/month**, **10 LSPs**, **~150K/day peak**; continuous partner polling at sustained volume.

## Evidence

Every authenticated LSP request performs in series before business logic:
1. Redis round trip (`RateLimitFilter`)
2. DB ApiClient JWT validation (`findByClientId` + token-version + status) — every call
3. Tenant `SET ROLE` + `set_config` on connection checkout

## Why it matters at scale

At **~1M loans/month** plus polling across **10 tenants**, per-request auth DB load is continuous on the primary — the same pool #201/#244 protect. Version-aware cache (bust on token-version bump and status→DISABLED, **not** TTL-only) removes most lookups while keeping instant revocation.

## Security (read carefully)

- Cache MUST bust on token-version increment and status→DISABLED — no revocation delay.
- Cache keys tenant-scoped; cross-tenant collision test required.
- Feature-flagged; only ship once #217 shows auth-path cost justifies it.

## Acceptance criteria

- [ ] Measured reduction in per-request auth DB calls on hot path.
- [ ] Revoked client rejected on very next call after disable/version bump — integration test.
- [ ] Tenant-scoped keys; collision test passes.
- [ ] Disabled via feature flag without behavior change.

## Blocked by

#217. Do **not** start before N1–N4 capacity fixes.
