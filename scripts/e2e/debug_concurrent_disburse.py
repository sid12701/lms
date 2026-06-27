#!/usr/bin/env python3
"""Reproduce EC-109: two parallel disbursement-request POSTs, print full bodies."""
from concurrent.futures import ThreadPoolExecutor
import json
import sys
import time
from pathlib import Path

import requests

sys.path.insert(0, str(Path(__file__).resolve().parent))
from _common import admin_token, load_config, suffix, uuid_v4
from fixtures import create_loan, loan_body, lsp_token, upload_all_docs

cfg = load_config()
base = cfg["BASE_URL"]
fx = json.loads(
    (Path(__file__).resolve().parent.parent.parent / ".e2e-runs" / "edge-fixtures-light.json").read_text()
)["fixtures"]["F01_happy_lsp"]
ah = {"Authorization": f"Bearer {admin_token(cfg)}"}
tok = lsp_token(base, fx["clientId"], fx["clientSecret"])
lh = {"Authorization": f"Bearer {tok}", "Content-Type": "application/json"}

loan = create_loan(lh, base, loan_body(fx["lspId"], fx["productId"], "conc" + uuid_v4()[:6]))
app_id = loan["id"]
upload_all_docs({"Authorization": f"Bearer {tok}"}, base, app_id)
time.sleep(1)

status = requests.get(
    f"{base}/api/v1/internal/ops/loan-applications/{app_id}", headers=ah, timeout=30
).json().get("status")
print("pre-disburse status:", status)


def post_disb():
    t0 = time.time()
    r = requests.post(
        f"{base}/api/v1/internal/ops/loan-applications/{app_id}/disbursement-requests",
        headers=ah,
        timeout=60,
    )
    return r.status_code, round(time.time() - t0, 1), r.text[:400]


with ThreadPoolExecutor(max_workers=2) as ex:
    results = [f.result() for f in [ex.submit(post_disb), ex.submit(post_disb)]]

for code, secs, body in results:
    print(f"\n--- {code} in {secs}s ---\n{body}")
