#!/usr/bin/env python3
"""Diagnose SET ROLE lms_tenant_app + checklist UPDATE via Supabase pooler.

Single session, everything rolled back. Prints each step's outcome.
"""
from pathlib import Path
import sys

import psycopg2

ROOT = Path(__file__).resolve().parent.parent.parent


def load_env() -> dict:
    env = {}
    for line in (ROOT / ".env").read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        env[key.strip()] = value.strip()
    return env


def main() -> int:
    env = load_env()
    url = env["LMS_DB_URL"]
    # jdbc:postgresql://host:port/db?sslmode=require
    hostport_db = url.split("://", 1)[1]
    hostport, _, rest = hostport_db.partition("/")
    host, _, port = hostport.partition(":")
    db = rest.split("?", 1)[0]

    conn = None
    last_error = None
    for try_port in (6543, int(port or 5432)):
        try:
            conn = psycopg2.connect(
                host=host,
                port=try_port,
                dbname=db,
                user=env["LMS_DB_USERNAME"],
                password=env["LMS_DB_PASSWORD"],
                sslmode="require",
                connect_timeout=15,
            )
            print(f"connected on port {try_port}")
            break
        except Exception as exc:
            last_error = exc
            print(f"port {try_port} failed: {exc}")
    if conn is None:
        raise last_error
    conn.autocommit = False
    cur = conn.cursor()

    cur.execute("select current_user, version()")
    print("connected as:", cur.fetchone())

    cur.execute("""
        select rolname, rolcanlogin, rolbypassrls
        from pg_roles where rolname in ('lms_tenant_app', current_user)
    """)
    print("roles:", cur.fetchall())

    cur.execute("""
        select pg_has_role(current_user, 'lms_tenant_app', 'MEMBER') as member,
               pg_has_role(current_user, 'lms_tenant_app', 'USAGE') as usage
    """)
    print("pg_has_role(member, usage):", cur.fetchone())

    cur.execute("""
        select roleid::regrole::text as granted_role,
               member::regrole::text as member_role,
               admin_option, set_option, inherit_option
        from pg_auth_members
        where roleid = 'lms_tenant_app'::regrole
    """)
    print("pg_auth_members for lms_tenant_app:", cur.fetchall())

    try:
        cur.execute("SET ROLE lms_tenant_app")
        cur.execute("select current_user")
        print("SET ROLE OK ->", cur.fetchone()[0])
        set_role_ok = True
    except Exception as exc:
        print("SET ROLE FAILED:", exc)
        conn.rollback()
        set_role_ok = False

    if set_role_ok:
        # pick a checklist row + its lsp as admin, then attempt the app's UPDATE as tenant
        cur.execute("RESET ROLE")
        cur.execute("""
            select c.id, c.loan_application_id, a.lsp_id, c.document_type, c.status
            from loan_application_document_checklist c
            join loan_application a on a.id = c.loan_application_id
            order by c.created_at desc
            limit 1
        """)
        row = cur.fetchone()
        print("sample checklist row (as admin):", row)
        cur.execute("SET ROLE lms_tenant_app")
        if row:
            checklist_id, app_id, lsp_id, doc_type, status = row
            cur.execute("select set_config('app.current_lsp_id', %s, true)", (str(lsp_id),))
            try:
                cur.execute(
                    """
                    update loan_application_document_checklist
                    set status = 'SUBMITTED', file_name = 'debug.pdf',
                        updated_by_username = 'debug', updated_at = now()
                    where id = %s
                    returning id, status
                    """,
                    (checklist_id,),
                )
                print("UPDATE OK:", cur.fetchall())
            except Exception as exc:
                print("UPDATE FAILED:", type(exc).__name__, exc)
                conn.rollback()

    conn.rollback()

    # policies + grants (as login user)
    cur.execute("RESET ROLE")
    cur.execute("""
        select polname, polcmd, polroles::regrole[]::text
        from pg_policy
        where polrelid = 'loan_application_document_checklist'::regclass
    """)
    print("policies:", cur.fetchall())
    cur.execute("""
        select privilege_type from information_schema.role_table_grants
        where grantee = 'lms_tenant_app'
          and table_name = 'loan_application_document_checklist'
    """)
    print("grants to lms_tenant_app on checklist:", [r[0] for r in cur.fetchall()])

    conn.rollback()
    conn.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
