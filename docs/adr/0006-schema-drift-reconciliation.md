# ADR 0006 — Schema drift reconciliation and migration discipline

- **Status:** Accepted (2026-07-06)
- **Source:** F-S15 (database audit 2026-07-03); remediation spec B12
- **Related:** B4 (partitioning), B6 (additive RLS / V110), `scripts/schema-diff/`

## Context

Flyway migration history has diverged between Supabase production and the repository:

- Version gaps **V63, V69, V91** exist in prod history but never had in-repo scripts.
- **V80 / V85 / V86** admit environment-specific application order (document-access columns and webhook `claim_expires_at`).
- Broad `IF NOT EXISTS` / idempotent repairs paper over drift and can silently no-op (see V29/V34 → V64 partial-index reconciliation).

Partition DDL, additive RLS, and large index changes are high-risk on a drifted schema (wrong owner, missing column, or silent skip → production-only failure).

## Decision

1. **Canonical schema** is defined by running all migrations in `backend/src/main/resources/db/migration/` on a clean Postgres 17 database. The normalized snapshot lives at `scripts/schema-diff/artifacts/reference-schema.normalized.sql` and is regenerated via `scripts/schema-diff/generate-reference.sh`.

2. **Prod reconciliation** uses `scripts/schema-diff/diff-against-prod.sh` (`pg_dump --schema-only`, normalize, diff). Documented historical drift is tracked in `scripts/schema-diff/artifacts/known-environment-drift.md` until prod matches reference or remaining differences are explicitly accepted here.

3. **CI enforcement:**
   - `FlywaySchemaValidationPostgresTest` — `flyway.validate()` after migrate on Testcontainers Postgres.
   - `scripts/schema-diff/check-reference.sh` on migration changes — fails if the reference artifact is stale.

4. **Migration discipline (binding):**
   - Repair / hotfix SQL is committed as a **new versioned migration** in-repo **before** it runs on any environment (including prod).
   - Never reuse or backfill removed version numbers (V63, V69, V91).
   - Idempotent `IF NOT EXISTS` is allowed only when the ADR or migration comment documents why environments may differ.
   - Partitioning (B4) and additive RLS (B6) require a clean prod-vs-reference diff or an explicit accepted-drift amendment to this ADR.

## Consequences

- Operators have a repeatable prod-vs-repo structural check instead of inferring state from `flyway_schema_history` alone.
- CI catches broken migration chains and stale reference artifacts before merge.
- Extra step when adding migrations: regenerate and commit the reference snapshot when `db/migration/**` changes.
- Historical prod gaps remain in Flyway history until manually repaired; structural diff is the source of truth for DDL readiness.

## Amendments

| Date | Change |
|---|---|
| 2026-07-06 | Initial acceptance; tooling + CI; documented V63/V69/V91 and V80/V85/V86 drift |
| 2026-07-06 | Supabase prod migrated V105–V109 via `migrate-supabase.sh`; public schema diff clean |
