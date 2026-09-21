# ADR 0014 — Credential retention and the cross-instance revocation SLA are explicit, bounded, and TTL-convergent

- **Status:** Accepted (2026-09-21)
- **Drives:** audit finding L04 — the expired-refresh-token purge method had no production caller, per-process cache TTLs were implicit, and derived snapshot/idempotency retention was not uniformly explicit
- **Related:** ADR 0005 (fail-closed tenant scope), ADR 0007 (retention-by-partition precedent for the event feed)

## Context

Three lifecycle gaps shared one root cause — policy existed in code but was nowhere stated, bounded, or scheduled:

1. `RefreshTokenRepository.deleteByExpiresAtBefore` existed but nothing called it, so `refresh_token` grew without bound. Deleting carelessly is not safe either: the family reuse detector needs the revoked-but-unexpired parent rows to recognise a replayed rotated credential.
2. `AuthPrincipalCache` (30 s) and `LspSurfaceIpAllowlistFilter` (60 s) expiry were per-process constants. With multiple API instances a revocation on one instance silently stayed live on the others for up to the TTL — an accepted behaviour, but an undocumented SLA.
3. `portfolio_kpi_snapshot` (one row per LSP plus one global per run) had no retention at all; idempotency records had one but it was not documented alongside the others.

## Decision

1. **Refresh tokens purge on an expiry-plus-margin predicate, in bounded indexed batches.** `RefreshTokenRetentionWorker` deletes only rows with `expires_at < now - expired-retention-days` (default 30 days), at most `batch-size` (500) rows per transaction and `max-batches-per-run` (40) batches per hourly run, using `idx_refresh_token_expires`. The predicate is expiry-based, so it can never delete a live token or a revoked-but-unexpired row — a replayed token that is already expired fails as `TOKEN_EXPIRED` before the reuse branch runs, so purging after the margin changes a failure code, never a security decision. `auth_session` family rows and auth audit events are never purged.
2. **The cross-instance revocation SLA is documented as 60 s and stays TTL-convergent.** Principal snapshots converge within `app.security.principal-cache-ttl` (30 s) and allowlist snapshots within `app.security.lsp-ip-allowlist-cache-ttl` (60 s); the mutating instance still evicts explicitly, so the SLA is the worst case, not the norm. No invalidation messaging or Redis-backed authorization was added — the audit requires it only if the SLA is insufficient, and a 60 s bound is the accepted trade for this system.
3. **Stale cache entries expire structurally.** Both caches sweep expired entries when their distinct-key count crosses a threshold (4096 principals, 1024 allowlist pairs), so dead principals or churned keys cannot accumulate in a long-lived process. Cache keys are globally unique canonical identities (username / client id / `(lspId, surface)`); there is no cross-tenant collision surface.
4. **Derived data gets its own bounded retention; immutable records get none.** `portfolio_kpi_snapshot` rows older than `retention-days` (default 400 days) purge in batches, always preserving the newest row per scope so a silent scope keeps its last reading. Idempotency replay records keep their existing 90-day hourly purge. Financial and audit tables are never pruned; no universal TTL was applied.

## Consequences

- `refresh_token`, `portfolio_kpi_snapshot`, and the idempotency tables are all bounded; none of the purges can remove evidence a security control still reads.
- An operator can state the revocation SLA — "a principal/allowlist change is effective everywhere within 60 s" — and `AuthPrincipalCacheBoundedStaleTest` exercises the two-instance convergence bound.
- Retention values are deployment-tunable via `APP_SECURITY_REFRESH_TOKEN_RETENTION_*` and `APP_PORTFOLIO_KPI_*` env vars; shortening the refresh margin is a forensic-capacity decision, not a code change.
