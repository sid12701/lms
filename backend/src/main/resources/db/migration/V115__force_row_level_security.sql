-- C1: table owners bypass RLS unless FORCE ROW LEVEL SECURITY is set. Every table
-- that already enables RLS must also force it so future owner drift cannot turn
-- isolation off silently.
DO $$
DECLARE
    table_record RECORD;
BEGIN
    FOR table_record IN
        SELECT c.relname AS table_name
        FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE c.relkind = 'r'
          AND n.nspname = 'public'
          AND c.relrowsecurity
          AND NOT c.relforcerowsecurity
    LOOP
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', table_record.table_name);
    END LOOP;
END
$$;
