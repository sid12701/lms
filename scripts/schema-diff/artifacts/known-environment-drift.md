# Known environment schema drift (B12)

**As of:** 2026-07-06  
**Reference:** Fresh `flyway migrate` on Postgres 17 (`scripts/schema-diff/artifacts/reference-schema.normalized.sql`)

This file records drift between **repo migration history** and **Supabase production** identified in the 2026-07-03 database audit. Update it after each prod reconciliation run (`diff-against-prod.sh`).

## Migration history gaps (never existed in repo)

| Missing version | Notes |
|---|---|
| V63 | Gap in `flyway_schema_history` on prod; no in-repo script |
| V69 | Gap in `flyway_schema_history` on prod; no in-repo script |
| V91 | Gap in `flyway_schema_history` on prod; no in-repo script |

These gaps mean prod's applied-version sequence is not contiguous. Flyway validate on prod may report "missing" versions if repair rows were inserted manually. **Do not invent backfill migrations for these numbers.**

## Documented script divergence

| Versions | Issue |
|---|---|
| V80 vs V85 | `V80__document_access_audit_actor_ip_byte_count.sql` adds `actor_ip` + `byte_count` to `loan_application_document_access_audit`. `V85` repeats the same `ADD COLUMN IF NOT EXISTS` because Supabase may have received an earlier duplicate at V80. |
| V80 vs V86 | `V86__webhook_event_outbox_claim_expires_at.sql` adds `claim_expires_at` + partial index. Comment states Supabase applied a different script at V80; V86 is the canonical in-repo definition. |

Repo fresh-migrate produces the intended end state. Prod should match structurally after both scripts apply; use `diff-against-prod.sh` to verify.

## Reconciliation checklist (prod)

Run before B6 (RLS) or B4 (partitioning) DDL:

1. `SELECT tablename, tableowner FROM pg_tables WHERE schemaname = 'public' ORDER BY 1` — admin/migration role must own application tables; `lms_tenant_app` must not own tables.
2. `./scripts/schema-diff/diff-against-prod.sh` with prod `DATABASE_URL`.
3. Record any corrective SQL applied to prod in ADR 0006 (amendment section) **before** execution.
4. Re-run diff until clean or remaining drift is explicitly accepted.

## Manual prod repairs

_None recorded in-repo yet._ When prod corrective SQL is applied, append:

```
### YYYY-MM-DD — <summary>
- Migration added: Vnnn__...
- Manual prod statement (if any): ...
- diff-against-prod.sh result: clean | accepted drift: ...
```

## Prod diff snapshot (2026-07-06)

Compared `public` schema only (Supabase pooler) against reference at **V109**.

### Before migrate

**Flyway on prod:** latest applied **V104**. Missing V105–V107, V109.

### After migrate (2026-07-06)

Ran `scripts/schema-diff/migrate-supabase.sh` — applied **V105, V106, V107, V109**. Prod Flyway now at **V109**.

Structural diff (`public` schema, extensions/platform noise stripped): **clean match** with `reference-schema.normalized.sql`.
