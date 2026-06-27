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
fx = json.loads((Path(__file__).resolve().parent.parent.parent / ".e2e-runs" / "edge-fixtures-light.json").read_text())["fixtures"]["F01_happy_lsp"]
tok = lsp_token(base, fx["clientId"], fx["clientSecret"])
lh = {"Authorization": f"Bearer {tok}", "Content-Type": "application/json"}
loan = create_loan(lh, base, loan_body(fx["lspId"], fx["productId"], "json" + suffix()))
app_id = loan["id"]
body = {
    "documentType": "PAN_CARD",
    "note": "json metadata only",
    "fileName": "pan.pdf",
    "fileReference": "https://example.com/pan.pdf",
    "contentType": "application/pdf",
}
import time
t0 = time.time()
r = requests.post(f"{base}/api/v1/lsp/loan-applications/{app_id}/documents", headers=lh, json=body, timeout=60)
print(f"json upload {r.status_code} in {time.time() - t0:.1f}s", r.text[:800])
