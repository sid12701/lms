-- Authoritative single-live-head invariant per session family. At most one
-- unrevoked refresh row may exist per non-null family; rotation revokes the consumed
-- parent before inserting its single successor, so the constraint holds at every commit.
-- Legacy human rows created before the session-family model and machine rows carry NULL
-- (NULLs never conflict in a partial unique index). Additive only: no backfill, no
-- purge, no change to existing constraints (V73 subject XOR untouched).
CREATE UNIQUE INDEX uq_refresh_token_live_family_head
    ON refresh_token (family_id)
    WHERE revoked = FALSE AND family_id IS NOT NULL;

COMMENT ON INDEX uq_refresh_token_live_family_head IS
    'At most one live refresh head per session family; rotation revokes-then-inserts.';
