#!/usr/bin/env python3
"""Verify set_config(..., true) requires an open transaction block."""
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
conn.autocommit = False
cur = conn.cursor()
lsp = "00000000-0000-0000-0000-000000000001"

print("without BEGIN:")
try:
    cur.execute("SELECT set_config('app.current_lsp_id', %s, true)", (lsp,))
    print("  ok", cur.fetchone())
except Exception as exc:
    print("  fail:", exc)
conn.rollback()

print("with BEGIN:")
try:
    cur.execute("BEGIN")
    cur.execute("SELECT set_config('app.current_lsp_id', %s, true)", (lsp,))
    print("  ok", cur.fetchone())
except Exception as exc:
    print("  fail:", exc)
conn.rollback()
conn.close()
