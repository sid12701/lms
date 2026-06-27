#!/usr/bin/env python3
"""Verify EC-093 (streams param) and EC-054 (double pay → 409) against live backend."""
import json
import sys
from pathlib import Path

import requests

sys.path.insert(0, str(Path(__file__).resolve().parent))
from _common import admin_token, load_config, uuid_v4

cfg = load_config()
base = cfg["BASE_URL"]
ah = {"Authorization": "Bearer " + admin_token(cfg)}

r = requests.get(
    base + "/api/v1/internal/admin/audit-events",
    headers=ah,
    params={"streams": "INVALID"},
    timeout=15,
)
print("EC-093 streams=INVALID ->", r.status_code, "(want 400)")

fx = json.loads(
    (Path(__file__).resolve().parent.parent.parent / ".e2e-runs" / "edge-fixtures.json").read_text()
)["fixtures"]
f10 = fx.get("F10_disbursed_loan", {})
inst = f10.get("installments", [])
if f10.get("applicationId") and inst:
    body = {
        "targetInstallmentId": inst[0]["id"],
        "amount": inst[0]["amount"],
        "postedAt": "2026-06-11",
        "channel": "NEFT",
    }
    h = {**ah, "Content-Type": "application/json"}
    url = base + f"/api/v1/internal/ops/loan-applications/{f10['applicationId']}/payments"
    r1 = requests.post(url, headers={**h, "Idempotency-Key": uuid_v4()}, json=body, timeout=60)
    r2 = requests.post(url, headers={**h, "Idempotency-Key": uuid_v4()}, json=body, timeout=30)
    print("EC-054 first/second ->", r1.status_code, "/", r2.status_code)
    print("  second body:", r2.text[:200])
else:
    print("EC-054: no F10 fixture installments available")
