-- The tenant connection pool cannot always log in directly as ${tenant_app_role}
-- (e.g. the Supabase pooler only authenticates project-scoped users). In that
-- case it connects as the admin login and runs SET ROLE ${tenant_app_role}.
--
-- On PostgreSQL 16+ a non-superuser that creates a role receives ADMIN on it
-- but not the SET option, so SET ROLE fails with "permission denied". The
-- creator can self-grant SET because it holds ADMIN. Superusers (local Docker,
-- CI containers) can always SET ROLE, so pg_has_role is true and this no-ops.
DO $$
BEGIN
    IF current_setting('server_version_num')::INT >= 160000 THEN
        IF NOT pg_has_role(CURRENT_USER, '${tenant_app_role}', 'SET') THEN
            EXECUTE format(
                'GRANT %I TO CURRENT_USER WITH SET TRUE, INHERIT FALSE',
                '${tenant_app_role}'
            );
        END IF;
    END IF;
END
$$;
