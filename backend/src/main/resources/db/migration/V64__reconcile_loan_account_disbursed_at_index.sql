-- F-14: Reconcile idx_loan_account_disbursed_at to the partial form V34 intended.
--
-- V29 created `idx_loan_account_disbursed_at` as a full index on
-- loan_account(disbursed_at). V34 later declared the same index name
-- with `IF NOT EXISTS` and a `WHERE disbursed_at IS NOT NULL` predicate,
-- but because the V29 index already existed under that name, V34's
-- statement silently no-opped. The partial variant was never created.
--
-- Every read path that touches disbursed_at filters to disbursed rows
-- (range filters `account.disbursedAt >= :from` / `< :to` in MIS and
-- portfolio reports; `case when ... is not null` aggregations on the
-- home dashboard). Nothing scans `WHERE disbursed_at IS NULL`, so the
-- index entries for undisbursed rows are pure write amplification.
--
-- Drop the full index and recreate it in the intended partial form.
-- See docs/database-sql-review.md F-14 / issue #33.
DROP INDEX IF EXISTS idx_loan_account_disbursed_at;
CREATE INDEX IF NOT EXISTS idx_loan_account_disbursed_at
    ON loan_account (disbursed_at)
    WHERE disbursed_at IS NOT NULL;
