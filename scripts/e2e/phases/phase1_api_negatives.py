#!/usr/bin/env python3
"""Phase 1: API-negative edge cases."""
from __future__ import annotations

import argparse
import json
import sys
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import requests

E2E_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(E2E_DIR))

from _common import (  # noqa: E402
    RUNS,
    admin_token,
    fetch_unpaid_installments,
    load_config,
    lsp_token_for_fixture,
    result,
    suffix,
    update_matrix,
    uuid_v4,
    write_results,
)
from fixtures import create_loan, loan_body, upload_all_docs  # noqa: E402


def ok_status(code: int, expected: set[int]) -> bool:
    return code in expected


def run(fixtures_path: Path) -> list[dict]:
    cfg = load_config()
    base = cfg["BASE_URL"]
    fx = json.loads(fixtures_path.read_text(encoding="utf-8"))["fixtures"]
    f01, f10 = fx["F01_happy_lsp"], fx.get("F10_disbursed_loan", {})
    f11, f12, f13 = fx.get("F11_under_repayment", {}), fx.get("F12_closed_loan", {}), fx.get("F13_rejected_loan", {})

    admin = admin_token(cfg)
    ah = {"Authorization": f"Bearer {admin}", "Content-Type": "application/json"}
    tok = lsp_token_for_fixture(cfg, f01)
    lh = {"Authorization": f"Bearer {tok}"}

    results: list[dict] = []
    app_init = f01["applicationId_initialized"]
    app_disb = f10.get("applicationId")

    def add(tid, status, actual, steps=""):
        results.append(result(tid, status, actual, steps))

    installments = fetch_unpaid_installments(ah, base, app_disb) if app_disb else []

    # EC-051 missing Idempotency-Key (post-fix)
    if app_disb and installments:
        r = requests.post(
            f"{base}/api/v1/internal/ops/loan-applications/{app_disb}/payments",
            headers={**ah, "Content-Type": "application/json"},
            json={
                "targetInstallmentId": installments[0]["id"],
                "amount": installments[0]["amount"],
                "postedAt": "2026-06-11",
                "channel": "NEFT",
            },
            timeout=30,
        )
        add("EC-051", "Pass" if r.status_code in (400, 422) else "Fail", f"No Idempotency-Key → {r.status_code}", "POST payment")

    # EC-027 idempotency replay on loan create
    idem = uuid_v4()
    body = loan_body(f01["lspId"], f01["productId"], "idem" + idem[:8], lspLoanId=f"EXT-IDEM-{idem[:8]}")
    h = {**lh, "Content-Type": "application/json", "Idempotency-Key": idem}
    r1 = requests.post(f"{base}/api/v1/lsp/loan-applications", headers=h, json=body, timeout=90)
    r2 = requests.post(f"{base}/api/v1/lsp/loan-applications", headers=h, json=body, timeout=90)
    j1, j2 = r1.json() if r1.ok else {}, r2.json() if r2.ok else {}
    app_id_1 = j1.get("applicationId") or j1.get("id")
    app_id_2 = j2.get("applicationId") or j2.get("id")
    same = r1.ok and r2.ok and app_id_1 and app_id_1 == app_id_2
    add("EC-027", "Pass" if same else "Fail", f"Replay idem → {r1.status_code}/{r2.status_code} sameId={same}", "POST loan x2")

    # EC-032 oversized JSON (not multipart) — no Idempotency-Key (payload rejected before claim)
    ec32_body = loan_body(f01["lspId"], f01["productId"], "ec32" + suffix()[-8:])
    r = requests.post(
        f"{base}/api/v1/lsp/loan-applications",
        headers={**lh, "Content-Type": "application/json"},
        json={**ec32_body, "note": "x" * (11 * 1024 * 1024)},
        timeout=120,
    )
    add("EC-032", "Pass" if r.status_code in (400, 413, 422) else "Fail", f"Oversized body → {r.status_code}", "POST huge JSON")

    # Illegal transitions
    if f13.get("applicationId"):
        r = requests.post(
            f"{base}/api/v1/internal/ops/loan-applications/{f13['applicationId']}/status-transitions",
            headers=ah,
            json={"targetStatus": "APPROVED_PENDING_DISBURSAL", "note": "bad"},
            timeout=30,
        )
        add("EC-033", "Pass" if r.status_code in (400, 422) else "Fail", f"REJECTED→APPROVED → {r.status_code}", "POST transition")

    if f12.get("applicationId"):
        r = requests.post(
            f"{base}/api/v1/internal/ops/loan-applications/{f12['applicationId']}/status-transitions",
            headers=ah,
            json={"targetStatus": "DISBURSED", "note": "bad"},
            timeout=30,
        )
        add("EC-034", "Pass" if r.status_code in (400, 422) else "Fail", f"CLOSED→DISBURSED → {r.status_code}", "POST transition")

    # EC-037 invalidate after disburse — LSP invalidate
    if app_disb:
        r = requests.post(
            f"{base}/api/v1/lsp/loan-applications/{app_disb}/invalid",
            headers={**lh, "Content-Type": "application/json", "Idempotency-Key": uuid_v4()},
            json={"reasonCode": "REASON_A", "note": "edge"},
            timeout=30,
        )
        add("EC-037", "Pass" if r.status_code in (400, 422) else "Fail", f"Invalidate DISBURSED → {r.status_code}", "POST invalid")

    # EC-039 transition without reason when required — use REJECTED without reasonCode
    r = requests.post(
        f"{base}/api/v1/internal/ops/loan-applications/{app_init}/status-transitions",
        headers=ah,
        json={"targetStatus": "REJECTED", "note": "no reason code"},
        timeout=30,
    )
    add("EC-039", "Pass" if r.status_code in (400, 422) else "Fail", f"REJECTED no reasonCode → {r.status_code}", "POST transition")

    # EC-041 large upload
    big = b"%PDF-1.4\n" + b"0" * (11 * 1024 * 1024)
    r = requests.post(
        f"{base}/api/v1/lsp/loan-applications/{app_init}/documents",
        headers=lh,
        files={"file": ("big.pdf", big, "application/pdf")},
        data={"documentType": "PAN_CARD"},
        timeout=120,
    )
    add("EC-041", "Pass" if r.status_code in (400, 413, 422) else "Fail", f"11MB upload → {r.status_code}", "POST document")

    # EC-042 invalid document type
    r = requests.post(
        f"{base}/api/v1/lsp/loan-applications/{app_init}/documents",
        headers=lh,
        files={"file": ("x.pdf", b"%PDF-1.4", "application/pdf")},
        data={"documentType": "NOT_A_REAL_TYPE"},
        timeout=30,
    )
    add("EC-042", "Pass" if r.status_code in (400, 422) else "Fail", f"Bad documentType → {r.status_code}", "POST document")

    # EC-046, EC-058
    r = requests.post(f"{base}/api/v1/internal/ops/loan-applications/{app_init}/disbursement-requests", headers=ah, timeout=30)
    add("EC-046", "Pass" if r.status_code in (400, 422) else "Fail", f"Disburse INITIALIZED → {r.status_code}", "POST disbursement")
    r = requests.post(
        f"{base}/api/v1/internal/ops/loan-applications/{app_init}/foreclosure-quotes",
        headers={**ah, "Content-Type": "application/json"},
        json={"effectiveDate": "2026-06-11"},
        timeout=30,
    )
    add("EC-058", "Pass" if r.status_code in (400, 422) else "Fail", f"Foreclosure INITIALIZED → {r.status_code}", "POST foreclosure-quotes")

    if app_disb and installments:
        inst = installments[0]
        for tid, payload, label in [
            ("EC-052", {"amount": inst["amount"], "postedAt": "2026-06-11", "channel": "NEFT"}, "no targetInstallmentId"),
            ("EC-056", {"targetInstallmentId": inst["id"], "amount": inst["amount"], "postedAt": "2026-06-11", "channel": "INVALID"}, "bad channel"),
            ("EC-053", {"targetInstallmentId": inst["id"], "amount": 1.0, "postedAt": "2026-06-11", "channel": "NEFT"}, "partial"),
        ]:
            r = requests.post(
                f"{base}/api/v1/internal/ops/loan-applications/{app_disb}/payments",
                headers={**ah, "Content-Type": "application/json", "Idempotency-Key": uuid_v4()},
                json=payload,
                timeout=30,
            )
            expected = {400, 409, 422} if tid == "EC-053" else {400, 422}
            add(tid, "Pass" if r.status_code in expected else "Fail", f"{label} → {r.status_code}", "POST payment")

        # EC-054 double pay
        idem2 = uuid_v4()
        pay_h = {**ah, "Content-Type": "application/json", "Idempotency-Key": idem2}
        pay_body = {"targetInstallmentId": inst["id"], "amount": inst["amount"], "postedAt": "2026-06-11", "channel": "NEFT"}
        r1 = requests.post(f"{base}/api/v1/internal/ops/loan-applications/{app_disb}/payments", headers=pay_h, json=pay_body, timeout=60)
        r2 = requests.post(
            f"{base}/api/v1/internal/ops/loan-applications/{app_disb}/payments",
            headers={**ah, "Content-Type": "application/json", "Idempotency-Key": uuid_v4()},
            json=pay_body,
            timeout=30,
        )
        add("EC-054", "Pass" if r1.status_code == 200 and r2.status_code in (400, 409, 422) else "Fail", f"Double pay → {r1.status_code}/{r2.status_code}", "POST payment x2")

        # EC-055 idempotency replay
        idem3 = uuid_v4()
        h3 = {**ah, "Content-Type": "application/json", "Idempotency-Key": idem3}
        inst2 = installments[1] if len(installments) > 1 else inst
        pb = {"targetInstallmentId": inst2["id"], "amount": inst2["amount"], "postedAt": "2026-06-11", "channel": "NEFT"}
        rp1 = requests.post(f"{base}/api/v1/internal/ops/loan-applications/{app_disb}/payments", headers=h3, json=pb, timeout=60)
        rp2 = requests.post(f"{base}/api/v1/internal/ops/loan-applications/{app_disb}/payments", headers=h3, json=pb, timeout=60)
        replay_ok = rp1.ok and rp2.ok and rp1.json().get("id") == rp2.json().get("id")
        add("EC-055", "Pass" if replay_ok else "Fail", f"Payment idem replay → {rp1.status_code}/{rp2.status_code}", "POST payment x2 same key")

    if f12.get("applicationId") and installments:
        r = requests.post(
            f"{base}/api/v1/internal/ops/loan-applications/{f12['applicationId']}/payments",
            headers={**ah, "Content-Type": "application/json", "Idempotency-Key": uuid_v4()},
            json={"targetInstallmentId": installments[0]["id"], "amount": installments[0]["amount"], "postedAt": "2026-06-11", "channel": "NEFT"},
            timeout=30,
        )
        add("EC-057", "Pass" if r.status_code in (400, 422) else "Fail", f"Payment on CLOSED → {r.status_code}", "POST payment")

    # EC-072 invalid IFSC
    bid = f01.get("borrowerId")
    if bid:
        r = requests.patch(
            f"{base}/api/v1/internal/admin/borrowers/{bid}/bank-details",
            headers=ah,
            json={"ifscCode": "INVALID", "bankAccountNumber": "1234567890", "accountHolderName": "Test"},
            timeout=30,
        )
        add("EC-072", "Pass" if r.status_code in (400, 422) else "Fail", f"Bad IFSC → {r.status_code}", "PATCH bank-details")

    # Alerts / audit / MIS
    r = requests.post(f"{base}/api/v1/internal/alerts/{uuid.uuid4()}/acknowledge", headers={**ah, "Content-Type": "application/json"}, json={"note": "x"}, timeout=15)
    add("EC-077", "Pass" if r.status_code == 404 else "Fail", f"Random alert → {r.status_code}", "POST acknowledge")
    r = requests.get(f"{base}/api/v1/internal/alerts?status=NEW", headers=ah, timeout=15)
    if r.ok and r.json():
        r2 = requests.post(
            f"{base}/api/v1/internal/alerts/{r.json()[0]['id']}/acknowledge",
            headers={**ah, "Content-Type": "application/json"},
            json={"note": "x" * 501},
            timeout=15,
        )
        add("EC-078", "Pass" if r2.status_code in (400, 422) else "Fail", f"Long note → {r2.status_code}", "POST acknowledge")
        aid = r.json()[0]["id"]
        requests.post(f"{base}/api/v1/internal/alerts/{aid}/acknowledge", headers={**ah, "Content-Type": "application/json"}, json={"note": "ok"}, timeout=15)
        r3 = requests.post(f"{base}/api/v1/internal/alerts/{aid}/acknowledge", headers={**ah, "Content-Type": "application/json"}, json={"note": "again"}, timeout=15)
        add("EC-079", "Pass" if r3.status_code in (400, 409, 422) else "Fail", f"Double ack → {r3.status_code}", "POST acknowledge x2")

    r = requests.get(f"{base}/api/v1/internal/admin/audit-events", headers=ah, params={"streams": "INVALID"}, timeout=15)
    add("EC-093", "Pass" if r.status_code in (400, 422) else "Fail", f"Bad streams → {r.status_code}", "GET audit-events")
    r = requests.get(f"{base}/api/v1/internal/admin/audit-events", headers=ah, params={"limit": 99999}, timeout=30)
    add("EC-092", "Pass" if r.ok else "Fail", f"limit=99999 → {r.status_code}", "GET audit-events")
    r = requests.get(
        f"{base}/api/v1/internal/reports/portfolio-mis/preview",
        headers=ah,
        params={"lspId": f01["lspId"], "disbursalDateFrom": "2099-01-01", "disbursalDateTo": "2099-12-31", "page": 0, "size": 10},
        timeout=30,
    )
    add("EC-070", "Pass" if r.ok else "Fail", f"Empty MIS range → {r.status_code}", "GET MIS preview")

    # EC-098 / EC-099 lifecycle
    if f11.get("applicationId"):
        det = requests.get(f"{base}/api/v1/internal/ops/loan-applications/{f11['applicationId']}", headers=ah, timeout=30).json()
        add("EC-098", "Pass" if det.get("status") == "UNDER_REPAYMENT" else "Fail", f"After 1 pay status={det.get('status')}", "GET ops detail")
    if f12.get("applicationId"):
        det = requests.get(f"{base}/api/v1/internal/ops/loan-applications/{f12['applicationId']}", headers=ah, timeout=30).json()
        add("EC-099", "Pass" if det.get("status") == "CLOSED" else "Fail", f"After 12 pay status={det.get('status')}", "GET ops detail")

    # EC-109 concurrent disbursement
    app_cd, _, _ = None, None, None
    try:
        loan_cd = create_loan(lh, base, loan_body(f01["lspId"], f01["productId"], "conc" + uuid_v4()[:6]))
        upload_all_docs(lh, base, loan_cd["id"])
        time.sleep(1)
        app_cd = loan_cd["id"]

        def post_disb():
            return requests.post(f"{base}/api/v1/internal/ops/loan-applications/{app_cd}/disbursement-requests", headers=ah, timeout=60)

        with ThreadPoolExecutor(max_workers=2) as ex:
            codes = [f.result().status_code for f in [ex.submit(post_disb), ex.submit(post_disb)]]
        # Repeat disbursement requests are idempotent (200) by design; the loser
        # of the race may also surface 409 CONCURRENT_MODIFICATION. Never 5xx.
        add("EC-109", "Pass" if 200 in codes and all(c in (200, 400, 409, 422) for c in codes) else "Fail", f"Concurrent disburse → {codes}", "Parallel POST")
    except Exception as e:
        add("EC-109", "Fail", str(e)[:120], "Concurrent disburse")

    # EC-040, EC-043
    r = requests.post(f"{base}/api/v1/lsp/loan-applications/{app_init}/documents", headers=lh, files={"file": ("b.txt", b"t", "text/plain")}, data={"documentType": "PAN_CARD"}, timeout=30)
    add("EC-040", "Pass" if r.status_code in (400, 422) else "Fail", f"text/plain → {r.status_code}", "POST doc")
    r = requests.post(f"{base}/api/v1/lsp/loan-applications/{uuid.uuid4()}/documents", headers=lh, files={"file": ("x.pdf", b"%PDF", "application/pdf")}, data={"documentType": "PAN_CARD"}, timeout=30)
    add("EC-043", "Pass" if r.status_code in (400, 404) else "Fail", f"Random app doc → {r.status_code}", "POST doc")

    return results


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--fixtures", default=str(RUNS / "edge-fixtures.json"))
    ap.add_argument("--update-matrix", action="store_true")
    args = ap.parse_args()
    res = run(Path(args.fixtures))
    write_results(RUNS / "phase1-results.json", res)
    passed = sum(1 for x in res if x["status"] == "Pass")
    print(f"Phase 1: {len(res)} cases, {passed} pass, {len(res) - passed} fail")
    if args.update_matrix:
        print(f"Matrix rows updated: {update_matrix(res)}")
    return 0 if passed == len(res) else 1


if __name__ == "__main__":
    sys.exit(main())
