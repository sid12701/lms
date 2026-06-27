#!/usr/bin/env python3
from pathlib import Path
import psycopg2

env = {}
for line in Path(__file__).resolve().parent.parent.parent.joinpath(".env").read_text().splitlines():
    if "=" in line and not line.strip().startswith("#"):
        k, v = line.split("=", 1)
        env[k.strip()] = v.strip()

ref = "avvzobksoybnmwjkeelk"
conn = psycopg2.connect(
    host="aws-1-ap-northeast-2.pooler.supabase.com",
    port=5432,
    dbname="postgres",
    user=f"postgres.{ref}",
    password=env["LMS_DB_PASSWORD"],
    sslmode="require",
)
conn.autocommit = False
cur = conn.cursor()
try:
    cur.execute("SET ROLE lms_tenant_app")
    cur.execute("SELECT current_user, session_user")
    print("SET ROLE ok:", cur.fetchone())
    cur.execute("SELECT set_config('app.current_lsp_id', '', true)")
    conn.rollback()
except Exception as e:
    print("SET ROLE failed:", e)
finally:
    conn.close()
