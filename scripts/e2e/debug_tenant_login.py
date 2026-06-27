#!/usr/bin/env python3
from pathlib import Path

import psycopg2

env: dict[str, str] = {}
for line in Path(__file__).resolve().parent.parent.parent.joinpath(".env").read_text().splitlines():
    if "=" in line and not line.strip().startswith("#"):
        k, v = line.split("=", 1)
        env[k.strip()] = v.strip()

tenant_user = env.get("APP_TENANT_DATASOURCE_USERNAME", "lms_tenant_app")
tenant_pass = env.get("APP_TENANT_DATASOURCE_PASSWORD", "lms_tenant_app_password")
ref = "avvzobksoybnmwjkeelk"

for port in (6543, 5432):
    print(f"\n=== login as {tenant_user} port {port} ===")
    try:
        conn = psycopg2.connect(
            host="aws-1-ap-northeast-2.pooler.supabase.com",
            port=port,
            dbname="postgres",
            user=tenant_user,
            password=tenant_pass,
            sslmode="require",
            connect_timeout=10,
        )
        conn.autocommit = True
        cur = conn.cursor()
        cur.execute("SELECT current_user, session_user")
        print("ok:", cur.fetchone())
        conn.close()
    except Exception as exc:
        print("fail:", exc)
