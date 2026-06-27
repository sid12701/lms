#!/usr/bin/env python3
"""Grant postgres the SET option on lms_tenant_app (one-time Supabase fix).

postgres already holds ADMIN OPTION on the role (it created it via Flyway),
so it can self-grant SET. Uses the transaction pooler (6543) because the
session pooler is saturated by the backend's Hikari pools.
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
    host = env["LMS_DB_URL"].split("://", 1)[1].split(":", 1)[0]
    conn = psycopg2.connect(
        host=host,
        port=6543,
        dbname="postgres",
        user=env["LMS_DB_USERNAME"],
        password=env["LMS_DB_PASSWORD"],
        sslmode="require",
        connect_timeout=15,
    )
    conn.autocommit = True
    cur = conn.cursor()
    cur.execute("GRANT lms_tenant_app TO postgres WITH SET TRUE, INHERIT FALSE")
    cur.execute(
        """
        select roleid::regrole::text, member::regrole::text,
               admin_option, set_option, inherit_option
        from pg_auth_members
        where roleid = 'lms_tenant_app'::regrole
        """
    )
    print("memberships after grant:", cur.fetchall())
    conn.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
