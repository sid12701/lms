-- Retention on the loan event log enforces itself (ADR 0007). Partitions are created ahead of the
-- first write that needs them, so a lifecycle change never fails because next month's partition is
-- missing; and a partition is dropped once every row it could hold is past the retention window, so
-- retention is a metadata operation rather than a large delete. This is why V116 created the table
-- partitioned from birth rather than converting a populated table later.
--
-- Retention is a floor, not an exact age. Partitions are whole months, so the month containing the
-- cutoff still holds rows inside the window and is kept: effective retention runs from
-- retention_days to retention_days plus one month. Trimming that month early would serve partners
-- less history than the contract promises, which is the worse failure of the two.
--
-- Month boundaries use date_trunc('month', ...) in the database's own timezone, exactly as V116
-- computed them. Deriving them any other way would leave a gap or an overlap against the partitions
-- V116 already created.
--
-- Only the loan event log has a retention window. loan_application_status_transition and
-- loan_application_audit_event are internal history, are never pruned, and are untouched here.

CREATE OR REPLACE FUNCTION maintain_loan_event_partitions(
    lead_months INTEGER,
    retention_days INTEGER
) RETURNS TABLE (action TEXT, partition_name TEXT)
    LANGUAGE plpgsql
AS $$
DECLARE
    -- Serialises concurrent maintenance so two pods cannot race on the same CREATE. Held for the
    -- caller's transaction and taken here rather than at the caller, so the guarantee survives any
    -- future caller. Blocking rather than skipping: the work is fast metadata DDL, and a caller that
    -- gets a silent "someone else has it" could go on to write into a partition that is not there yet.
    lifecycle_lock_id CONSTANT BIGINT := 4207117;
    month_offset INTEGER;
    partition_start TIMESTAMP WITH TIME ZONE;
    partition_end TIMESTAMP WITH TIME ZONE;
    candidate_name TEXT;
    retention_cutoff TIMESTAMP WITH TIME ZONE;
    expired_partition RECORD;
BEGIN
    IF lead_months < 1 THEN
        RAISE EXCEPTION 'lead_months must be at least 1, got %', lead_months;
    END IF;
    IF retention_days < 1 THEN
        RAISE EXCEPTION 'retention_days must be at least 1, got %', retention_days;
    END IF;

    PERFORM pg_advisory_xact_lock(lifecycle_lock_id);

    FOR month_offset IN 0..lead_months LOOP
        partition_start := date_trunc('month', CURRENT_TIMESTAMP) + make_interval(months => month_offset);
        partition_end := partition_start + INTERVAL '1 month';
        candidate_name := 'loan_event_' || to_char(partition_start, 'YYYY_MM');

        -- Checked against the catalog rather than written as CREATE TABLE IF NOT EXISTS, so the
        -- result set reports what was actually created, and so a same-named relation that is not a
        -- partition of loan_event fails loudly instead of being silently accepted as one.
        CONTINUE WHEN EXISTS (
            SELECT 1
            FROM pg_inherits
            JOIN pg_class child ON child.oid = pg_inherits.inhrelid
            WHERE pg_inherits.inhparent = 'loan_event'::regclass
              AND child.relname = candidate_name
        );

        EXECUTE format(
            'CREATE TABLE %I PARTITION OF loan_event FOR VALUES FROM (%L) TO (%L)',
            candidate_name,
            partition_start,
            partition_end
        );
        action := 'CREATED';
        partition_name := candidate_name;
        RETURN NEXT;
    END LOOP;

    retention_cutoff := CURRENT_TIMESTAMP - make_interval(days => retention_days);

    FOR expired_partition IN
        -- The upper bound is read from the partition's real bound rather than parsed out of its
        -- name, so a partition whose name and contents ever disagree is dropped on what it actually
        -- holds. A bound that does not parse — a DEFAULT or MAXVALUE partition — yields NULL, which
        -- fails the comparison and is therefore kept.
        SELECT child.relname AS name,
               child.oid::regclass::text AS qualified_name
        FROM pg_inherits
        JOIN pg_class child ON child.oid = pg_inherits.inhrelid
        WHERE pg_inherits.inhparent = 'loan_event'::regclass
          AND (regexp_match(
                  pg_get_expr(child.relpartbound, child.oid),
                  'TO \(''([^'']+)''\)'
              ))[1]::TIMESTAMP WITH TIME ZONE <= retention_cutoff
        ORDER BY child.relname
    LOOP
        EXECUTE format('DROP TABLE %s', expired_partition.qualified_name);
        action := 'DROPPED';
        partition_name := expired_partition.name;
        RETURN NEXT;
    END LOOP;
END
$$;

-- Not SECURITY DEFINER, so a caller without ownership of loan_event already fails on the DDL. The
-- revoke keeps the entry point off the tenant role's surface regardless.
REVOKE EXECUTE ON FUNCTION maintain_loan_event_partitions(INTEGER, INTEGER) FROM PUBLIC;
