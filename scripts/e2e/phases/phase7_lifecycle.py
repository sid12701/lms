#!/usr/bin/env python3
"""Phase 7: Auto-rejection and lifecycle assertion edge cases."""
from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

import requests

E2E_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(E2E_DIR))

from _common import RUNS, admin_token, load_config, lsp_token_for_fixture, result, update_matrix, suffix, uuid_v4, write_results  # noqa: E402
from fixtures import loan_body, lsp_token, upload_all_docs, wait_for_approved  # noqa: E402


def run(fixtures_path: Path) -> list[dict]:
    cfg = load_config()
    base = cfg["BASE_URL"]
    fx = json.loads(fixtures_path.read_text(encoding="utf-8"))["fixtures"]
    f01 = fx.get("F01_happy_lsp", {})
    f05 = fx.get("F05_inactive_lsp", {})
    f06 = fx.get("F06_inactive_product", {})
    f13 = fx.get("F13_rejected_loan", {})

    admin = admin_token(cfg)
    ah = {"Authorization": f"Bearer {admin}", "Content-Type": "application/json"}
    results: list[dict] = []

    if f06.get("clientId") and f06.get("productId"):
        tok = lsp_token_for_fixture(cfg, f06)
        r = requests.post(
            f"{base}/api/v1/lsp/loan-applications",
            headers={"Authorization": f"Bearer {tok}", "Content-Type": "application/json", "Idempotency-Key": uuid_v4()},
            json=loan_body(f06["lspId"], f06["productId"], "p7inact" + suffix()[-6:]),
            timeout=90,
        )
        results.append(
            result(
                "EC-018",
                "Pass" if r.status_code in (400, 422) else "Fail",
                f"Inactive product intake → {r.status_code}",
                "POST loan-applications",
            )
        )
    else:
        results.append(result("EC-018", "Blocked", "F06_inactive_product missing.", "POST loan-applications"))

    if f05.get("clientId"):
        token_body = {"clientId": f05["clientId"], "clientSecret": f05["clientSecret"]}
        r: requests.Response | None = None
        for _ in range(8):
            r = requests.post(f"{base}/api/v1/auth/token", json=token_body, timeout=30)
            if r.status_code == 429:
                retry_after = int(r.headers.get("Retry-After", "15") or 15)
                time.sleep(min(max(retry_after, 5), 60))
                continue
            break
        assert r is not None
        results.append(
            result(
                "EC-019",
                "Pass" if r.status_code in (401, 403) else "Fail",
                f"Inactive LSP token → {r.status_code}",
                "POST /auth/token",
            )
        )
    else:
        results.append(result("EC-019", "Blocked", "F05_inactive_lsp missing.", "POST /auth/token"))

    if f01.get("clientId") and f01.get("productId") and f01.get("lspId"):
        requests.post(
            f"{base}/api/v1/internal/admin/product-lsp-mappings/entries",
            headers=ah,
            json={"lspId": f01["lspId"], "productId": f01["productId"], "enabled": False},
            timeout=30,
        )
        tok = lsp_token_for_fixture(cfg, f01)
        r = requests.post(
            f"{base}/api/v1/lsp/loan-applications",
            headers={"Authorization": f"Bearer {tok}", "Content-Type": "application/json", "Idempotency-Key": uuid_v4()},
            json=loan_body(f01["lspId"], f01["productId"], "p7map" + suffix()[-6:]),
            timeout=90,
        )
        requests.post(
            f"{base}/api/v1/internal/admin/product-lsp-mappings/entries",
            headers=ah,
            json={"lspId": f01["lspId"], "productId": f01["productId"], "enabled": True},
            timeout=30,
        )
        results.append(
            result(
                "EC-020",
                "Pass" if r.status_code in (400, 422) else "Fail",
                f"Disabled mapping intake → {r.status_code}",
                "POST loan-applications",
            )
        )
    else:
        results.append(result("EC-020", "Blocked", "F01 mapping prerequisites missing.", "POST loan-applications"))

    if f13.get("applicationId"):
        transitions = requests.get(
            f"{base}/api/v1/internal/ops/loan-applications/{f13['applicationId']}/status-transitions",
            headers=ah,
            timeout=30,
        ).json()
        rejected = [t for t in transitions if t.get("toStatus") == "REJECTED"]
        has_reason = any(t.get("rejectionReason") or t.get("reasonCode") for t in rejected)
        results.append(
            result(
                "EC-100",
                "Pass" if rejected and has_reason else "Fail",
                f"REJECTED transitions={len(rejected)} structuredReason={has_reason}",
                "GET status-transitions",
            )
        )
    else:
        results.append(result("EC-100", "Blocked", "F13_rejected_loan missing.", "GET status-transitions"))

    if f01.get("clientId") and f01.get("productId") and f01.get("lspId"):
        results.extend(run_ec048_ec107(cfg, f01))
    else:
        results.append(result("EC-048", "Blocked", "F01_happy_lsp missing.", "POST disbursement-bank-check"))
        results.append(result("EC-107", "Blocked", "F01_happy_lsp missing.", "PUT repayment-schedule LSP_PROVIDED"))

    return results


def run_ec048_ec107(cfg: dict, f01: dict) -> list[dict]:
    """B3 regression: LSP validation failure branches must return structured 4xx, not 500."""
    base = cfg["BASE_URL"]
    results: list[dict] = []
    try:
        tok = lsp_token_for_fixture(cfg, f01)
        lh = {"Authorization": f"Bearer {tok}", "Content-Type": "application/json"}
        ah = {"Authorization": f"Bearer {admin_token(cfg)}", "Content-Type": "application/json"}

        app = requests.post(
            f"{base}/api/v1/lsp/loan-applications",
            headers={**lh, "Idempotency-Key": uuid_v4()},
            json=loan_body(f01["lspId"], f01["productId"], f"p7val{suffix()[-6:]}"),
            timeout=90,
        )
        if app.status_code not in (200, 201):
            note = f"Intake failed → {app.status_code}"
            return [
                result("EC-048", "Blocked", note, "Create approved loan for bank-check"),
                result("EC-107", "Blocked", note, "Create approved loan for schedule validation"),
            ]
        body = app.json()
        app_id = body.get("applicationId") or body.get("id")
        borrower_id = body.get("borrowerId")
        if not app_id:
            return [
                result("EC-048", "Blocked", "No application id in intake response", "Create loan"),
                result("EC-107", "Blocked", "No application id in intake response", "Create loan"),
            ]
        upload_all_docs(lh, base, app_id)
        wait_for_approved(ah, base, app_id, timeout_sec=180)

        bank = requests.get(
            f"{base}/api/v1/lsp/borrowers/{borrower_id}/bank-details",
            headers=lh,
            timeout=30,
        )
        if not bank.ok:
            note = f"Borrower bank-details → {bank.status_code}"
            return [
                result("EC-048", "Blocked", note, "GET borrower bank-details"),
                result("EC-107", "Blocked", note, "GET borrower bank-details"),
            ]
        on_file = bank.json()
        mismatch = requests.post(
            f"{base}/api/v1/lsp/loan-applications/{app_id}/disbursement-bank-check",
            headers=lh,
            json={
                "disbursalAmount": 150000,
                "bankAccountNumber": "000000000001",
                "ifscCode": on_file.get("ifscCode", "HDFC0001234"),
                "accountHolderName": on_file.get("accountHolderName", "Test Borrower"),
            },
            timeout=30,
        )
        match = requests.post(
            f"{base}/api/v1/lsp/loan-applications/{app_id}/disbursement-bank-check",
            headers=lh,
            json={
                "disbursalAmount": 150000,
                "bankAccountNumber": on_file.get("bankAccountNumber"),
                "ifscCode": on_file.get("ifscCode"),
                "accountHolderName": on_file.get("accountHolderName"),
            },
            timeout=30,
        )
        ec048_ok = mismatch.status_code == 422 and mismatch.json().get("error") == "DISBURSEMENT_VALIDATION_FAILED"
        results.append(
            result(
                "EC-048",
                "Pass" if ec048_ok and match.status_code == 200 else "Fail",
                f"mismatch → {mismatch.status_code} ({mismatch.json().get('error', mismatch.text[:80])}); "
                f"match → {match.status_code}",
                "POST disbursement-bank-check mismatch vs match",
            )
        )

        bad_schedule = {
            "mode": "LSP_PROVIDED",
            "installments": [
                {
                    "installmentNumber": 1,
                    "dueDate": "2026-08-14",
                    "openingPrincipal": 150000,
                    "principalDue": 1000,
                    "interestDue": 100,
                    "installmentAmount": 1100,
                    "closingPrincipal": 149000,
                }
            ],
        }
        bad = requests.put(
            f"{base}/api/v1/lsp/loan-applications/{app_id}/repayment-schedule",
            headers=lh,
            json=bad_schedule,
            timeout=30,
        )
        ec107_ok = bad.status_code == 422 and bad.json().get("error") == "REPAYMENT_SCHEDULE_INVALID"
        results.append(
            result(
                "EC-107",
                "Pass" if ec107_ok else "Fail",
                f"LSP_PROVIDED bad-sum schedule → {bad.status_code} ({bad.json().get('error', bad.text[:120])})",
                "PUT repayment-schedule mode=LSP_PROVIDED invalid sum",
            )
        )
    except (requests.HTTPError, TimeoutError, RuntimeError) as exc:
        results.append(result("EC-048", "Blocked", str(exc), "LSP validation bank-check"))
        results.append(result("EC-107", "Blocked", str(exc), "LSP validation schedule"))
    return results


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--fixtures", default=str(RUNS / "edge-fixtures.json"))
    ap.add_argument("--update-matrix", action="store_true")
    args = ap.parse_args()
    res = run(Path(args.fixtures))
    write_results(RUNS / "phase7-results.json", res)
    passed = sum(1 for x in res if x["status"] == "Pass")
    print(f"Phase 7: {len(res)} cases, {passed} pass, {len(res) - passed} other")
    if args.update_matrix:
        print(f"Matrix rows updated: {update_matrix(res)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
