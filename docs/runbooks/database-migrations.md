# Database Migration Runbook

This runbook records the production-safety contract for database migrations that
move existing data, not just schema. It was added for [DB F-22 / issue
#41](https://github.com/sid12701/lms/issues/41).

The immediate scope is:

- `backend/src/main/resources/db/migration/V41__tenant_isolation_rls.sql`
- `backend/src/main/resources/db/migration/V43__global_borrowers_with_lsp_access.sql`

## Plain-language summary

A database migration is normally run once by Flyway as the application version
moves forward. Some migrations are harmless to repeat because they use guarded
operations such as `ADD COLUMN IF NOT EXISTS`.

V41 and V43 are different. They reshaped borrower ownership by copying,
merging, deleting, and rewiring real borrower rows. They assume a specific
starting database shape. Running either script again against the wrong restored
database can attach loans to the wrong borrower row, create duplicate borrowers,
choose a different canonical borrower, or fail partway through after making
some changes.

Policy: V41 and V43 are one-shot migrations. Do not directly rerun them in
production. Any recovery from a backup, botched migration, or disaster-recovery
restore must use a restored copy, inspect the actual schema/data state, and
produce a reviewed manual recovery plan before touching production.

## What V41 changed

`V41__tenant_isolation_rls.sql` introduced tenant-owned borrower rows.

Before V41, borrower rows were effectively global. A loan application pointed
to a borrower, but the borrower row itself did not carry the owning LSP. V41:

- dropped the old unique PAN constraint;
- added `borrower.lsp_id`;
- cloned borrower data per loan application and LSP;
- rewrote `loan_application.borrower_id` to point at the new LSP-specific
  borrower row;
- rewrote `loan_account.borrower_id` through the loan application relationship;
- deleted original borrower rows that were no longer referenced;
- made `borrower.lsp_id` required and added the LSP foreign key;
- enabled row-level security policies that use `app.current_lsp_id`.

App effect: LSP-facing database access became tenant-scoped. A tenant user sees
only rows owned by its LSP through PostgreSQL row-level security.

Required start state for V41:

- `borrower.lsp_id` does not already represent the final tenant model.
- `loan_application.borrower_id` and `loan_account.borrower_id` still point to
  the pre-V41 borrower rows.
- borrower PAN uniqueness has not already been replaced by the V43 canonical
  borrower model.
- no concurrent application writes are changing borrower, loan application, or
  loan account rows while the migration runs.

Why direct rerun is unsafe:

- it generates fresh borrower IDs with `gen_random_uuid()`;
- it clones from whatever borrower rows loan applications point at now, even if
  those rows are already post-migration rows;
- it rewrites borrower foreign keys again;
- it can delete rows that appear unused in the current, already-mutated shape.

## What V43 changed

`V43__global_borrowers_with_lsp_access.sql` replaced the V41 borrower-per-LSP
model with one canonical borrower per PAN plus an access table.

After V41, the same person could exist as several borrower rows, one per LSP.
V43:

- created `borrower_lsp_access`;
- selected one canonical borrower for each PAN, preferring the most recently
  updated row;
- inserted LSP access rows for every borrower/LSP relationship;
- rewrote `loan_application.borrower_id` to point at the canonical borrower;
- rewrote `loan_account.borrower_id` to point at the canonical borrower;
- deleted the duplicate non-canonical borrower rows;
- dropped `borrower.lsp_id`;
- restored unique PAN enforcement with `uk_borrower_pan`;
- changed borrower RLS to use `borrower_lsp_access`.

App effect: the app has a single borrower identity per PAN, while LSP users
still only see borrowers that their LSP can access.

Required start state for V43:

- V41 has completed successfully.
- `borrower.lsp_id` exists and is populated for all borrower rows.
- duplicate borrower rows by PAN are expected and still point to the correct
  LSP ownership.
- `borrower_lsp_access` has not already become the source of borrower
  visibility.
- no concurrent application writes are changing borrower, loan application, or
  loan account rows while the migration runs.

Why direct rerun is unsafe:

- after V43, `borrower.lsp_id` is gone, so the script no longer matches the
  final schema;
- if run against a partially restored or hand-edited database, it may pick a
  different canonical borrower per PAN;
- it deletes borrower rows whose IDs are not selected as canonical;
- it rewrites loan and account borrower references based on the current data
  shape, not the original V41 output.

## What "rerun" means

In this runbook, a rerun means any attempt to apply V41 or V43 again outside the
normal original Flyway upgrade path. Examples:

- restoring a backup from before V41 or between V41 and V43;
- reapplying a Flyway migration after a failed or interrupted deployment;
- replaying migrations into a production-like database from a partial dump;
- resetting a developer or staging database from old data and trying to catch
  up with these scripts manually.

## Restore and recovery checklist

Use this checklist before any action involving V41 or V43 on restored data.

1. Restore into an isolated database first, not production.
2. Confirm the Flyway schema history and exact migration boundary.
3. Inspect whether `borrower.lsp_id` exists.
4. Inspect whether `borrower_lsp_access` exists and has rows.
5. Count borrowers by PAN and identify duplicate PAN groups.
6. Count loan applications and loan accounts whose `borrower_id` does not point
   at an existing borrower.
7. Confirm whether row-level security policies are present and enabled.
8. Stop application writes or keep the restored database fully offline during
   analysis.
9. Write a manual recovery plan for the observed state.
10. Review the plan with an engineer who understands borrower visibility and
    tenant isolation before production changes.

Do not solve restore uncertainty by running V41 or V43 directly. The first
decision must be "what state are we in?", not "which migration can we replay?"

## Validation checklist

Before a reviewed recovery plan:

- every `loan_application.borrower_id` points at an existing borrower;
- every `loan_account.borrower_id` points at an existing borrower;
- every loan application and loan account still belongs to the expected LSP;
- borrower PAN groups are understood before any merge/delete operation;
- `borrower_lsp_access` rows, if present, match expected LSP visibility;
- RLS policies match the schema state being recovered.

After a reviewed recovery plan:

- no orphaned loan application borrower references exist;
- no orphaned loan account borrower references exist;
- each PAN has the expected canonical borrower shape for the target schema;
- each LSP can see only the borrowers it should see;
- internal admin borrower lookup still finds the expected borrower history;
- application smoke tests cover LSP loan listing/detail and internal borrower
  lookup against the recovered database.

## Operator notes

- V41 and V43 are not rollback scripts.
- V41 and V43 are not safe data repair scripts.
- A successful DR rehearsal should record row counts, duplicate PAN counts,
  orphan counts, elapsed time, and any manual SQL used.
- If the restored database is before V41, the safest path may be to rerun the
  full application migration chain in a clean isolated environment, then compare
  data shape before planning production cutover.
- If the restored database is between V41 and V43, treat it as an intermediate
  state and inspect it before deciding whether the normal forward migration path
  still applies.
- If the restored database is after V43, do not reintroduce V41's
  `borrower.lsp_id` model as a shortcut.

## Related references

- [Database SQL review](../database-sql-review.md)
- [Database optimization tracker](../database-optimization-tracker.md)
- [V41 tenant isolation migration](../../backend/src/main/resources/db/migration/V41__tenant_isolation_rls.sql)
- [V43 global borrowers migration](../../backend/src/main/resources/db/migration/V43__global_borrowers_with_lsp_access.sql)
