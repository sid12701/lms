# Schema diff (B12 / F-S15)

Structural reconciliation between **repo Flyway migrations** and **deployed databases** (Supabase prod).

## Artifacts

| File | Purpose |
|---|---|
| `artifacts/reference-schema.normalized.sql` | Normalized `pg_dump --schema-only` from a fresh `flyway migrate` on Postgres 17 |
| `artifacts/known-environment-drift.md` | Documented historical gaps and prod-only repairs |
| `artifacts/prod-schema.normalized.sql` | Generated locally when diffing prod (not committed) |
| `artifacts/prod-vs-reference.diff` | Structural diff output (not committed) |

## Regenerate reference (after migration changes)

Requires Docker.

```bash
./scripts/schema-diff/generate-reference.sh
git add scripts/schema-diff/artifacts/reference-schema.normalized.sql
```

CI runs `check-reference.sh` on migration changes and fails if the committed artifact is stale.

## Apply pending migrations to Supabase

```bash
./scripts/schema-diff/migrate-supabase.sh
```

Reads `LMS_DB_*` and `APP_TENANT_DATASOURCE_PASSWORD` from repo-root `.env`. Uses the Session pooler URL.

## Diff against production

Requires network access to the target database. Use the **Session pooler** connection from Supabase Dashboard on Windows (direct `db.<ref>.supabase.co` is often IPv6-only).

```bash
export DATABASE_URL='postgresql://postgres.<project-ref>:<password>@aws-1-<region>.pooler.supabase.com:5432/postgres?sslmode=require'
./scripts/schema-diff/diff-against-prod.sh
```

If `pg_dump` is not installed locally, use Docker:

```bash
docker run --rm postgres:17-alpine pg_dump "$DATABASE_URL" \
  --schema-only --no-owner --no-privileges --schema=public \
  | python3 scripts/schema-diff/normalize_schema.py \
  > scripts/schema-diff/artifacts/prod-schema.normalized.sql
git diff --no-index scripts/schema-diff/artifacts/reference-schema.normalized.sql \
  scripts/schema-diff/artifacts/prod-schema.normalized.sql
```

Only the **`public`** schema is compared (Supabase `auth`/`storage`/`realtime` schemas are excluded).

Review `artifacts/prod-vs-reference.diff`. Expected drift is recorded in `known-environment-drift.md` until prod is reconciled.

## Normalization rules

`normalize_schema.py` strips environment-specific noise before diff:

- SQL comments and `COMMENT ON` lines
- `SET` / `pg_catalog.set_config` session pragmas
- `OWNER TO` and `TABLESPACE` clauses
- `CREATE EXTENSION` and bare `CREATE SCHEMA public` (Supabase hosts extensions separately)

## Operational rules (see ADR 0006)

1. **No ad-hoc SQL on prod** without a matching versioned migration in `backend/src/main/resources/db/migration/` committed first.
2. **No reusing version numbers** — gaps V63, V69, V91 are historical; never backfill those slots.
3. **Partition / RLS DDL** (B4, B6) is blocked until prod-vs-reference diff is clean or drift is explicitly accepted in the ADR.
