#!/usr/bin/env python3
"""Reproduce LSP document upload and print full error body."""
from __future__ import annotations

import json
import sys
from pathlib import Path

import requests

E2E = Path(__file__).resolve().parent
sys.path.insert(0, str(E2E))

from _common import admin_token, load_config, suffix, uuid_v4  # noqa: E402
from fixtures import create_loan, loan_body, lsp_token  # noqa: E402

PDF = Path(__file__).resolve().parent.parent.parent / "postman" / "assets" / "sample-pan.pdf"


def main() -> int:
    cfg = load_config()
    base = cfg["BASE_URL"]
    fx_path = E2E.parent.parent / ".e2e-runs" / "edge-fixtures-light.json"
    if not fx_path.exists():
        print(f"Missing {fx_path} — run fixtures.py --light first", file=sys.stderr)
        return 1

    fx = json.loads(fx_path.read_text(encoding="utf-8"))["fixtures"]["F01_happy_lsp"]
    sfx = suffix()
    admin = admin_token(cfg)
    ah = {"Authorization": f"Bearer {admin}", "Content-Type": "application/json"}
    tok = lsp_token(base, fx["clientId"], fx["clientSecret"])
    lh = {"Authorization": f"Bearer {tok}"}
    loan = create_loan(lh, base, loan_body(fx["lspId"], fx["productId"], "docdebug" + sfx))
    app_id = loan["id"]
    print("applicationId", app_id)

    # Ops view — checklist + status
    ops = requests.get(
        f"{base}/api/v1/internal/ops/loan-applications/{app_id}",
        headers=ah,
        timeout=30,
    )
    print("ops detail", ops.status_code)
    if ops.ok:
        body = ops.json()
        print("status", body.get("status"))
        docs = body.get("documentChecklist") or body.get("documents") or []
        print("checklist items", len(docs) if isinstance(docs, list) else type(docs))
        if isinstance(docs, list):
            pan = [d for d in docs if d.get("documentType") == "PAN_CARD"]
            print("PAN_CARD item", pan[0] if pan else "MISSING")

    kyc = requests.get(
        f"{base}/api/v1/internal/ops/loan-applications/{app_id}/kyc-documents",
        headers=ah,
        timeout=30,
    )
    print("kyc-documents", kyc.status_code, "count", len(kyc.json()) if kyc.ok else kyc.text[:200])

    with PDF.open("rb") as f:
        r_ok = requests.post(
            f"{base}/api/v1/lsp/loan-applications/{app_id}/documents",
            headers=lh,
            files={"file": ("pan.pdf", f, "application/pdf")},
            data={"documentType": "PAN_CARD"},
            timeout=60,
        )
    print("PAN_CARD upload", r_ok.status_code)
    print(r_ok.text[:1200])

    # correlation id for backend log grep
    if r_ok.headers.get("X-Correlation-Id"):
        print("correlation", r_ok.headers.get("X-Correlation-Id"))
    try:
        print("correlation body", r_ok.json().get("correlationId"))
    except Exception:
        pass
    return 0 if r_ok.ok else 1


if __name__ == "__main__":
    sys.exit(main())
