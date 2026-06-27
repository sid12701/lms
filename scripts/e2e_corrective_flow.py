#!/usr/bin/env python3
"""Corrective E2E flow: upload docs with correct types, attempt disbursement chain."""
import json
import sys
import uuid
from pathlib import Path

import requests

BASE = "http://localhost:8080"
PDF = Path(r"D:\Desktop-New\Folders\LMS\blank.pdf")
ENV_FILE = Path(__file__).resolve().parent.parent / ".e2e-runs" / "env-after-full.json"
OUT = Path(__file__).resolve().parent.parent / ".e2e-runs" / "corrective-flow.json"


def load_env():
    data = json.loads(ENV_FILE.read_text(encoding="utf-8"))
    return {v["key"]: v["value"] for v in data["values"] if v.get("value")}


def login(username, password):
    r = requests.post(f"{BASE}/api/v1/auth/login", json={"username": username, "password": password}, timeout=30)
    r.raise_for_status()
    return r.json()["accessToken"]


def lsp_token(client_id, secret):
    r = requests.post(f"{BASE}/api/v1/auth/token", json={"clientId": client_id, "clientSecret": secret}, timeout=30)
    r.raise_for_status()
    return r.json()["accessToken"]


def upload_doc(token, app_id, doc_type, filename):
    headers = {"Authorization": f"Bearer {token}"}
    with PDF.open("rb") as f:
        files = {"file": (filename, f, "application/pdf")}
        data = {"documentType": doc_type}
        r = requests.post(
            f"{BASE}/api/v1/lsp/loan-applications/{app_id}/documents",
            headers=headers, files=files, data=data, timeout=60,
        )
    return r.status_code, r.text[:500]


def main():
    env = load_env()
    results = {"steps": []}

    admin_token = login("ops.admin", "ChangeMe123!")
    lsp_tok = lsp_token(env["lspApiClientId"], env["lspApiClientSecret"])
    app_id = env["applicationId"]
    ah = {"Authorization": f"Bearer {admin_token}", "Content-Type": "application/json"}
    lh = {"Authorization": f"Bearer {lsp_tok}"}

    for doc_type, fname in [
        ("PAN_CARD", "pan-card.pdf"),
        ("AADHAAR_FILE", "aadhaar.pdf"),
        ("BANK_STATEMENT", "bank-statement.pdf"),
    ]:
        code, body = upload_doc(lsp_tok, app_id, doc_type, fname)
        results["steps"].append({"upload": doc_type, "status": code, "body": body})

    # List docs admin side
    r = requests.get(f"{BASE}/api/v1/internal/ops/loan-applications/{app_id}/kyc-documents", headers=ah, timeout=30)
    results["kyc_list"] = {"status": r.status_code, "body": r.json() if r.ok else r.text[:300]}

    # Try approve transition
    r = requests.post(
        f"{BASE}/api/v1/internal/ops/loan-applications/{app_id}/status-transitions",
        headers=ah,
        json={"targetStatus": "APPROVED_PENDING_DISBURSAL", "reasonCode": "MANUAL_OPS_APPROVAL"},
        timeout=30,
    )
    results["approve"] = {"status": r.status_code, "body": r.json() if r.content else ""}

    if r.ok:
        # Admin disbursement request
        r2 = requests.post(
            f"{BASE}/api/v1/internal/ops/loan-applications/{app_id}/disbursement-requests",
            headers=ah, json={}, timeout=30,
        )
        results["disbursement_request"] = {"status": r2.status_code, "body": r2.text[:400]}

        r3 = requests.post(
            f"{BASE}/api/v1/internal/ops/loan-applications/{app_id}/disbursement-requests/mock-outcome",
            headers=ah, json={"outcome": "SUCCESS"}, timeout=30,
        )
        results["mock_outcome"] = {"status": r3.status_code, "body": r3.text[:400]}

    # LSP UI login test
    r = requests.post(
        f"{BASE}/api/v1/auth/login",
        json={"username": env["lspUiUsername"], "password": "LspUi#2026!"},
        timeout=30,
    )
    results["lsp_ui_login_bootstrap"] = {"status": r.status_code, "body": r.text[:200]}
    if r.status_code != 200:
        r = requests.post(
            f"{BASE}/api/v1/auth/login",
            json={"username": env["lspUiUsername"], "password": "LspUiRotated#2026!"},
            timeout=30,
        )
        results["lsp_ui_login_rotated"] = {"status": r.status_code, "body": r.text[:200]}

    # Edge: wrong password
    r = requests.post(f"{BASE}/api/v1/auth/login", json={"username": "ops.admin", "password": "WrongPass!"}, timeout=10)
    results["ec001"] = {"status": r.status_code}

    # Edge: no auth
    r = requests.get(f"{BASE}/api/v1/internal/ops/loan-applications", timeout=10)
    results["ec_no_auth"] = {"status": r.status_code}

    # MIS with date params
    r = requests.get(
        f"{BASE}/api/v1/internal/reports/portfolio-mis/preview",
        headers=ah,
        params={"disbursalDateFrom": "2026-01-01", "disbursalDateTo": "2026-12-31", "limit": 5},
        timeout=30,
    )
    results["mis_preview"] = {"status": r.status_code, "content_type": r.headers.get("content-type"), "len": len(r.content)}

    OUT.write_text(json.dumps(results, indent=2), encoding="utf-8")
    print(json.dumps(results, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
