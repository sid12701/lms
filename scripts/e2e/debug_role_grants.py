#!/usr/bin/env python3
from pathlib import Path

import psycopg2

env: dict[str, str] = {}
for line in Path(__file__).resolve().parent.parent.parent.joinpath(".env").read_text().splitlines():
    if "=" in line and not line.strip().startswith("#"):
        k, v = line.split("=", 1)
        env[k.strip()] = v.strip()

ref = "avvzobksoybnmwjkeelk"
conn = psycopg2.connect(
    host="aws-1-ap-northeast-2.pooler.supabase.com",
    port=6543,
    dbname="postgres",
    user=f"postgres.{ref}",
    password=env["LMS_DB_PASSWORD"],
    sslmode="require",
)
conn.autocommit = True
cur = conn.cursor()
cur.execute("SELECT current_user, session_user")
print("users:", cur.fetchone())
cur.execute(
    """
    SELECT r.rolname AS role, m.rolname AS member
    FROM pg_auth_members am
    JOIN pg_roles r ON r.oid = am.roleid
    JOIN pg_roles m ON m.oid = am.member
    WHERE r.rolname = 'lms_tenant_app'
    """
)
print("members of lms_tenant_app:", cur.fetchall())
cur.execute(
    "SELECT rolname, rolcanlogin FROM pg_roles WHERE rolname IN ('postgres', 'lms_tenant_app')"
)
print("roles:", cur.fetchall())
conn.close()
