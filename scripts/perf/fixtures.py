"""Provision isolated perf-test tenant (LSP, product, API client)."""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
import time
from pathlib import Path

import requests

from _common import RUNS, admin_token, load_config, run_id, suffix, uuid_v4

ROOT = Path(__file__).resolve().parent.parent.parent
PDF = ROOT / "postman" / "assets" / "sample-pan.pdf"

DOC_TYPES = [
    "PAN_CARD", "AADHAAR_FILE", "BANK_STATEMENT", "ADDRESS_PROOF",
    "INCOME_PROOF", "SELFIE_PHOTOGRAPH", "KFS", "LOAN_AGREEMENT",
]


def _borrower_ids(sfx: str) -> tuple[str, str, str]:
    digest = int(hashlib.sha256(sfx.encode()).hexdigest(), 16)
    return (
        str(digest % 10**12).zfill(12),
        f"ABCDE{str((digest // 10**12) % 10000).zfill(4)}F",
        "9" + str(digest % 10**9).zfill(9),
    )


def loan_body(lsp_id: str, product_id: str, sfx: str, **overrides) -> dict:
    aadhar, pan, mobile = _borrower_ids(sfx)
    full_name = f"Perf Borrower {sfx}"
    body = {
        "lspId": lsp_id, "productId": product_id, "lspLoanId": f"PERF-EXT-{sfx}",
        "fullName": full_name, "emailAddress": f"perf{sfx}@example.com",
        "mobileNumber": mobile, "dob": "1990-05-15", "gender": "MALE",
        "maritalStatus": "SINGLE", "fatherName": "Parent",
        "aadharNumber": aadhar, "panNumber": pan,
        "loanAmount": 150000, "interestRate": 14.5, "loanTenure": 12,
        "addressLine1": "42 Perf Street", "addressCity": "Mumbai",
        "addressState": "MH", "addressZipcode": "400001",
        "employmentStatus": "SALARIED", "organizationName": "Perf Corp",
        "monthlyIncome": 60000, "annualIncome": 720000,
        "bankAccountNumber": "1234567890", "bankName": "HDFC Bank",
        "ifscCode": "HDFC0001234", "accountHolderName": full_name,
        "referencePersonName": "Ref", "referencePersonNumber": "9123456780",
    }
    body.update(overrides)
    return body


def provision(cfg: dict, rid: str) -> dict:
    base = cfg["BASE_URL"]
    token = admin_token(cfg)
    ah = {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}
    sfx = suffix(rid)

    lsp = requests.post(f"{base}/api/v1/internal/admin/lsps", headers=ah,
                        json={"code": f"PERF-{sfx}", "name": f"Perf LSP {sfx}", "status": "ACTIVE"}, timeout=30)
    lsp.raise_for_status()
    lsp_id = lsp.json()["id"]

    prod = requests.post(f"{base}/api/v1/internal/admin/products", headers=ah, json={
        "code": f"PERF-P-{sfx}", "name": f"Perf Product {sfx}",
        "minPrincipal": 10000, "maxPrincipal": 500000, "interestRate": 14.5,
        "processingFeeRate": 1.5, "minTenureMonths": 6, "maxTenureMonths": 36,
        "status": "ACTIVE"}, timeout=30)
    prod.raise_for_status()
    product_id = prod.json()["id"]

    requests.put(f"{base}/api/v1/internal/admin/products/{product_id}/mappings",
                 headers=ah, json={"lspIds": [lsp_id]}, timeout=30).raise_for_status()

    cli = requests.post(f"{base}/api/v1/internal/admin/api-clients", headers=ah,
                        json={"lspId": lsp_id, "name": f"perf-client-{sfx}"}, timeout=30)
    cli.raise_for_status()
    client_id = cli.json()["clientId"]
    client_secret = cli.json()["clientSecret"]

    return {
        "run_id": rid,
        "created_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "lspId": lsp_id,
        "productId": product_id,
        "clientId": client_id,
        "clientSecret": client_secret,
        "suffix": sfx,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Provision perf test fixtures")
    parser.add_argument("--export", action="store_true", help="Write fixtures JSON")
    args = parser.parse_args()
    cfg = load_config()
    from _common import wait_for_backend
    wait_for_backend(cfg)
    rid = run_id(cfg)
    fx = provision(cfg, rid)
    if args.export:
        out = Path(cfg.get("PERF_FIXTURES_JSON", str(RUNS / "fixtures.json")))
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(json.dumps(fx, indent=2), encoding="utf-8")
        print(f"Wrote {out}")
    print(json.dumps({k: v for k, v in fx.items() if k != "clientSecret"}, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
