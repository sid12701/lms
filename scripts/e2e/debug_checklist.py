#!/usr/bin/env python3
import json
import sys
from pathlib import Path

import requests

sys.path.insert(0, str(Path(__file__).resolve().parent))
from _common import admin_token, load_config, suffix
from fixtures import create_loan, loan_body, lsp_token

cfg = load_config()
base = cfg["BASE_URL"]
ah = {"Authorization": f"Bearer {admin_token(cfg)}", "Content-Type": "application/json"}
fx = json.loads((Path(__file__).resolve().parent.parent.parent / ".e2e-runs" / "edge-fixtures-light.json").read_text())["fixtures"]["F01_happy_lsp"]
tok = lsp_token(base, fx["clientId"], fx["clientSecret"])
lh = {"Authorization": f"Bearer {tok}"}
loan = create_loan(lh, base, loan_body(fx["lspId"], fx["productId"], "chk" + suffix()))
app_id = loan["id"]
print("app", app_id)

r = requests.get(f"{base}/api/v1/internal/ops/loan-applications/{app_id}", headers=ah, timeout=30)
print("ops", r.status_code)
body = r.json()
print("top keys", sorted(body.keys()))
for k in body:
    if "doc" in k.lower() or "check" in k.lower():
        print(k, body[k])

r2 = requests.get(f"{base}/api/v1/internal/ops/loan-applications/{app_id}/kyc-documents", headers=ah, timeout=30)
print("kyc-documents", r2.status_code, r2.text[:500])
