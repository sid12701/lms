#!/usr/bin/env python3
"""Try tenant UPDATE on document checklist via Supabase pooler."""
from pathlib import Path
import json
import time
import psycopg2

env = {}
for line in Path(__file__).resolve().parent.parent.parent.joinpath(".env").read_text().splitlines():
    if "=" in line and not line.strip().startswith("#"):
        k, v = line.split("=", 1)
        env[k.strip()] = v.strip()

fx = json.loads(
    Path(__file__).resolve().parent.parent.parent.joinpath(".e2e-runs/edge-fixtures-light.json").read_text()
)["fixtures"]["F01_happy_lsp"]
lsp_id = fx["lspId"]

ref = "avvzobksoybnmwjkeelk"
for attempt in range(5):
    try:
        conn = psycopg2.connect(
            host="aws-1-ap-northeast-2.pooler.supabase.com",
            port=5432,
            dbname="postgres",
            user=f"postgres.{ref}",
            password=env["LMS_DB_PASSWORD"],
            sslmode="require",
            connect_timeout=10,
        )
        conn.autocommit = False
        cur = conn.cursor()
        cur.execute("SET ROLE lms_tenant_app")
        cur.execute("SELECT set_config('app.current_lsp_id', %s, true)", (lsp_id,))
        cur.execute(
            """
            SELECT id, document_type, status
            FROM loan_application_document_checklist
            WHERE loan_application_id = (
              SELECT id FROM loan_application ORDER BY created_at DESC LIMIT 1
            )
            AND document_type = 'PAN_CARD'
            """
        )
        row = cur.fetchone()
        print("select", row)
        if row:
            cid = row[0]
            cur.execute(
                """
                UPDATE loan_application_document_checklist
                SET status = 'SUBMITTED', note = 'python probe', updated_at = now()
                WHERE id = %s
                """,
                (cid,),
            )
            print("update rowcount", cur.rowcount)
        conn.rollback()
        conn.close()
        break
    except Exception as e:
        print(f"attempt {attempt+1}", e)
        time.sleep(3)
