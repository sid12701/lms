#!/usr/bin/env python3
"""Probe tenant SET ROLE + checklist UPDATE via Supabase transaction pooler (port 6543)."""
from __future__ import annotations

import json
import time
from pathlib import Path

import psycopg2

ROOT = Path(__file__).resolve().parent.parent.parent
env: dict[str, str] = {}
for line in ROOT.joinpath(".env").read_text().splitlines():
    if "=" in line and not line.strip().startswith("#"):
        k, v = line.split("=", 1)
        env[k.strip()] = v.strip()

fx = json.loads(ROOT.joinpath(".e2e-runs/edge-fixtures-light.json").read_text())["fixtures"]["F01_happy_lsp"]
lsp_id = fx["lspId"]
ref = "avvzobksoybnmwjkeelk"

for port in (6543, 5432):
    print(f"\n=== port {port} ===")
    for attempt in range(3):
        try:
            conn = psycopg2.connect(
                host="aws-1-ap-northeast-2.pooler.supabase.com",
                port=port,
                dbname="postgres",
                user=f"postgres.{ref}",
                password=env["LMS_DB_PASSWORD"],
                sslmode="require",
                connect_timeout=15,
            )
            conn.autocommit = False
            cur = conn.cursor()
            cur.execute("SET ROLE lms_tenant_app")
            cur.execute("SELECT current_user, session_user")
            print("role:", cur.fetchone())
            cur.execute("SELECT set_config('app.current_lsp_id', %s, true)", (lsp_id,))
            cur.execute(
                """
                SELECT c.id, c.document_type, c.status, a.lsp_id::text
                FROM loan_application_document_checklist c
                JOIN loan_application a ON a.id = c.loan_application_id
                WHERE a.lsp_id = %s::uuid
                ORDER BY c.created_at DESC
                LIMIT 1
                """,
                (lsp_id,),
            )
            row = cur.fetchone()
            print("latest checklist:", row)
            if row:
                cid = row[0]
                cur.execute(
                    """
                    UPDATE loan_application_document_checklist
                    SET status = 'SUBMITTED',
                        note = 'python tenant probe',
                        file_name = 'probe.pdf',
                        file_reference = 'https://example.com/probe.pdf',
                        content_type = 'application/pdf',
                        updated_at = now()
                    WHERE id = %s
                    """,
                    (cid,),
                )
                print("update rowcount:", cur.rowcount)
            conn.rollback()
            conn.close()
            break
        except Exception as exc:
            print(f"attempt {attempt + 1}:", exc)
            time.sleep(2)
