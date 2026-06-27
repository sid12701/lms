"""Negative-path E2E: the failure branches of the LSP loan lifecycle.

A negative check PASSES when the platform correctly REJECTS the bad request
(4xx/401/403/409) and FAILS (= a finding) when it wrongly accepts (2xx) or
errors with 500. Covers: auth, RBAC, tenant isolation, origination validation,
idempotency conflict, document MIME rejection, illegal/premature lifecycle
actions, and the FAILED / PENDING_RECONCILIATION disbursement outcomes.

Run from scripts/indep-e2e/:  python negative_paths.py
"""
from __future__ import annotations

import io
import time
import uuid

from client import (req, check, info, summary, rid, jwt_claims, FAIL)
from happy_path_one_repayment import admin_token, loan_body, PDF_BYTES, DOC_TYPES

SERVER_ERRORS: list[tuple[str, int]] = []


def neg(cid: str, resp, allowed: tuple[int, ...], detail: str) -> bool:
    got = resp.status_code
    ok = got in allowed
    flag = ""
    if got >= 500:
        flag = "  <<500 SERVER ERROR — expected 4xx>>"
        SERVER_ERRORS.append((cid, got))
    check(cid, ok, f"{detail} -> {got} (want {allowed}){flag}")
    return ok


def stage(title):
    print(f"\n========== {title} ==========")


def make_tenant(t, tag):
    sfx = rid(tag).split("-")[1].lower()
    lsp = req("POST", "/api/v1/internal/admin/lsps", token=t,
              json={"code": f"NEG-{tag}-{sfx}", "name": f"Neg {tag} {sfx}", "status": "ACTIVE"}, expect=(200, 201)).json()
    prod = req("POST", "/api/v1/internal/admin/products", token=t, json={
        "code": f"NEG-P-{tag}-{sfx}", "name": f"Neg Product {tag} {sfx}",
        "minPrincipal": 10000, "maxPrincipal": 500000, "interestRate": 14.5,
        "processingFeeRate": 1.5, "minTenureMonths": 6, "maxTenureMonths": 36,
        "status": "ACTIVE"}, expect=(200, 201)).json()
    req("PUT", f"/api/v1/internal/admin/products/{prod['id']}/mappings", token=t,
        json={"lspIds": [lsp["id"]]}, expect=200)
    cli = req("POST", "/api/v1/internal/admin/api-clients", token=t,
              json={"lspId": lsp["id"], "name": f"NEG-{tag}-client"}, expect=(200, 201)).json()
    tok = req("POST", "/api/v1/auth/token",
              json={"clientId": cli["clientId"], "clientSecret": cli["clientSecret"]}, expect=200).json()["accessToken"]
    return {"lsp_id": lsp["id"], "product_id": prod["id"],
            "client_id": cli["clientId"], "secret": cli["clientSecret"], "token": tok}


def approved_app(t, A):
    """Create + doc + auto-approve an app under tenant A; return (app_id, loan_acct, status).

    Doc uploads are retried (a transient upload failure leaves the checklist
    incomplete, which silently blocks auto-approval), and approval is polled for
    up to 60s. Raises if the checklist can't be completed so callers never run a
    disbursement assertion against an un-approved loan.
    """
    body = loan_body(A["lsp_id"], A["product_id"], rid("AP").split("-")[1].lower())
    app_id = req("POST", "/api/v1/lsp/loan-applications", token=A["token"], idem=str(uuid.uuid4()),
                 json=body, expect=(200, 201)).json()["id"]
    for dt in DOC_TYPES:
        ok = False
        for attempt in range(3):
            r = req("POST", f"/api/v1/lsp/loan-applications/{app_id}/documents", token=A["token"],
                    files={"file": ("blank.pdf", io.BytesIO(PDF_BYTES), "application/pdf")}, data={"documentType": dt})
            if r.status_code in (200, 201):
                ok = True
                break
            time.sleep(0.5)
        if not ok:
            raise RuntimeError(f"approved_app: doc {dt} upload failed for {app_id} -> {r.status_code}: {r.text[:160]}")
    status = None
    for _ in range(60):
        status = req("GET", f"/api/v1/internal/ops/loan-applications/{app_id}", token=t).json().get("status")
        if status in ("APPROVED_PENDING_DISBURSAL", "REJECTED"):
            break
        time.sleep(1)
    d = req("GET", f"/api/v1/internal/ops/loan-applications/{app_id}", token=t).json()
    return app_id, d.get("loanAccountId"), d.get("status")


def main():
    t = admin_token()
    print("Provisioning two isolated tenants (A, B)...")
    A = make_tenant(t, "A")
    B = make_tenant(t, "B")
    info("setup", f"A.lsp={A['lsp_id']} B.lsp={B['lsp_id']}")

    # ---- AUTH ----
    stage("AUTH negatives")
    neg("AUTH.badpass", req("POST", "/api/v1/auth/login",
        json={"email": "siddhant@bhawanafinance.com", "password": "wrong-password-x"}), (401, 400), "login wrong password")
    neg("AUTH.badsecret", req("POST", "/api/v1/auth/token",
        json={"clientId": A["client_id"], "clientSecret": "not-the-secret"}), (401, 400), "token exchange bad secret")
    neg("AUTH.notoken", req("GET", "/api/v1/lsp/products"), (401, 403), "LSP endpoint with no token")
    neg("AUTH.garbagetoken", req("GET", "/api/v1/lsp/products", token="garbage.jwt.value"), (401, 403), "LSP endpoint bad bearer")

    # ---- RBAC / tenant isolation ----
    stage("RBAC + tenant isolation")
    neg("RBAC.lsp_admin", req("POST", "/api/v1/internal/admin/lsps", token=A["token"],
        json={"code": "X", "name": "X", "status": "ACTIVE"}), (403,), "LSP token -> admin create-LSP")
    neg("RBAC.lsp_ops", req("GET", f"/api/v1/internal/ops/loan-applications/{uuid.uuid4()}", token=A["token"]),
        (403,), "LSP token -> internal ops endpoint")

    # build an approved loan under A for isolation + disbursement-failure tests
    app_a, acct_a, st_a = approved_app(t, A)
    info("setup.A_loan", f"A approved app={app_a} acct={acct_a} status={st_a}")
    neg("ISO.cross_loan", req("GET", f"/api/v1/lsp/loans/{acct_a}", token=B["token"]),
        (403, 404), "LSP B reads LSP A's loan account")
    neg("ISO.cross_originate", req("POST", "/api/v1/lsp/loan-applications", token=B["token"], idem=str(uuid.uuid4()),
        json=loan_body(A["lsp_id"], A["product_id"], "x")), (403, 400, 404, 422),
        "LSP B originates with body lspId=A")

    # ---- ORIGINATION validation ----
    stage("ORIGINATION validation")
    base = loan_body(A["lsp_id"], A["product_id"], "v")
    over = dict(base); over["loanAmount"] = 999999999
    neg("VAL.amount_over_max", req("POST", "/api/v1/lsp/loan-applications", token=A["token"], idem=str(uuid.uuid4()),
        json=over), (400, 409, 422), "loanAmount above product max")
    badten = dict(base); badten["loanTenure"] = 600
    neg("VAL.tenure_oob", req("POST", "/api/v1/lsp/loan-applications", token=A["token"], idem=str(uuid.uuid4()),
        json=badten), (400, 409, 422), "loanTenure outside product range")
    badpan = dict(base); badpan["panNumber"] = "not-a-pan"
    neg("VAL.bad_pan", req("POST", "/api/v1/lsp/loan-applications", token=A["token"], idem=str(uuid.uuid4()),
        json=badpan), (400, 422), "invalid PAN format")
    missing = dict(base); missing.pop("fullName")
    neg("VAL.missing_field", req("POST", "/api/v1/lsp/loan-applications", token=A["token"], idem=str(uuid.uuid4()),
        json=missing), (400, 422), "missing required field (fullName)")
    unmapped = dict(base); unmapped["productId"] = B["product_id"]
    neg("VAL.unmapped_product", req("POST", "/api/v1/lsp/loan-applications", token=A["token"], idem=str(uuid.uuid4()),
        json=unmapped), (400, 403, 404, 422), "productId not mapped to this LSP")

    # ---- IDEMPOTENCY conflict ----
    stage("IDEMPOTENCY conflict")
    key = str(uuid.uuid4())
    b1 = loan_body(A["lsp_id"], A["product_id"], "i")
    r1 = req("POST", "/api/v1/lsp/loan-applications", token=A["token"], idem=key, json=b1, expect=(200, 201))
    b2 = dict(b1); b2["loanAmount"] = 175000
    neg("IDEM.key_reuse_diff_body", req("POST", "/api/v1/lsp/loan-applications", token=A["token"], idem=key, json=b2),
        (409,), f"same Idempotency-Key + different body (first={r1.status_code})")

    # ---- DOCUMENT MIME rejection ----
    stage("DOCUMENT upload rejection")
    doc_app = req("POST", "/api/v1/lsp/loan-applications", token=A["token"], idem=str(uuid.uuid4()),
                  json=loan_body(A["lsp_id"], A["product_id"], "d"), expect=(200, 201)).json()["id"]
    neg("DOC.fake_pdf", req("POST", f"/api/v1/lsp/loan-applications/{doc_app}/documents", token=A["token"],
        files={"file": ("fake.pdf", io.BytesIO(b"this is plain text, not a real PDF at all\n"), "application/pdf")},
        data={"documentType": "PAN_CARD"}), (400, 415, 422), "text bytes masquerading as application/pdf")
    neg("DOC.bad_type", req("POST", f"/api/v1/lsp/loan-applications/{doc_app}/documents", token=A["token"],
        files={"file": ("blank.pdf", io.BytesIO(PDF_BYTES), "application/pdf")},
        data={"documentType": "NONSENSE_TYPE"}), (400, 422), "invalid documentType enum")

    # ---- LIFECYCLE / state machine (doc_app is unapproved INITIALIZED) ----
    stage("LIFECYCLE illegal/premature actions")
    neg("LC.disburse_unapproved", req("POST", f"/api/v1/internal/ops/loan-applications/{doc_app}/disbursement-requests",
        token=t), (400, 409, 422), "initiate disbursement before approval")
    neg("LC.pay_undisbursed", req("POST", f"/api/v1/internal/ops/loan-applications/{doc_app}/payments", token=t,
        idem=str(uuid.uuid4()), json={"targetInstallmentId": str(uuid.uuid4()), "amount": 1000,
        "postedAt": "2026-06-25", "reference": "X", "channel": "NEFT"},
        headers={"Content-Type": "application/json"}), (400, 404, 409, 422), "record payment before disbursement")
    neg("LC.illegal_transition", req("POST", f"/api/v1/internal/ops/loan-applications/{doc_app}/status-transitions",
        token=t, json={"targetStatus": "CLOSED", "note": "illegal"},
        headers={"Content-Type": "application/json"}), (400, 409, 422), "INITIALIZED -> CLOSED illegal transition")

    # ---- DISBURSEMENT failure outcomes (domain: point of no return) ----
    # NOTE: LoanDisbursementWorker auto-disburses APPROVED_PENDING_DISBURSAL loans
    # on a ~30s tick, so each outcome test must run on a FRESH loan and force the
    # outcome immediately (do NOT reuse a loan that has been sitting approved).
    stage("DISBURSEMENT failure outcomes")
    # FAILED: money returned to LSP disbursal account; loan must NOT be DISBURSED
    app_f, acct_f, _ = approved_app(t, A)
    req("POST", f"/api/v1/internal/ops/loan-applications/{app_f}/disbursement-requests", token=t)
    fr = req("POST", f"/api/v1/internal/ops/loan-applications/{app_f}/disbursement-requests/mock-outcome",
             token=t, json={"outcome": "FAILED"}, headers={"Content-Type": "application/json"})
    st_failed = req("GET", f"/api/v1/internal/ops/loan-applications/{app_f}", token=t).json().get("status")
    if fr.status_code >= 500:
        SERVER_ERRORS.append(("DISB.failed", fr.status_code))
    check("DISB.failed_not_disbursed", fr.status_code in (200, 201) and st_failed != "DISBURSED",
          f"FAILED disbursement -> status={st_failed} (must NOT be DISBURSED), call={fr.status_code}")

    # already-DISBURSED loan must REJECT a retroactive FAILED (point of no return).
    # app_a was approved early and auto-disbursed by the worker; confirm it is now
    # DISBURSED and that forcing FAILED on it is refused.
    st_a_now = req("GET", f"/api/v1/internal/ops/loan-applications/{app_a}", token=t).json().get("status")
    if st_a_now == "DISBURSED":
        fr2 = req("POST", f"/api/v1/internal/ops/loan-applications/{app_a}/disbursement-requests/mock-outcome",
                  token=t, json={"outcome": "FAILED"}, headers={"Content-Type": "application/json"})
        neg("DISB.no_retro_fail", fr2, (400, 409, 422), "FAILED on already-DISBURSED loan (point of no return)")
    else:
        info("DISB.no_retro_fail", f"app_a status={st_a_now} (not yet auto-disbursed; skipped retro-fail check)")

    # PENDING_RECONCILIATION on a second fresh approved loan: in-flight, not DISBURSED
    app_p, acct_p, st_p = approved_app(t, A)
    req("POST", f"/api/v1/internal/ops/loan-applications/{app_p}/disbursement-requests", token=t)
    pr = req("POST", f"/api/v1/internal/ops/loan-applications/{app_p}/disbursement-requests/mock-outcome",
             token=t, json={"outcome": "PENDING_RECONCILIATION"}, headers={"Content-Type": "application/json"})
    st_pending = req("GET", f"/api/v1/internal/ops/loan-applications/{app_p}", token=t).json().get("status")
    if pr.status_code >= 500:
        SERVER_ERRORS.append(("DISB.pending", pr.status_code))
    check("DISB.pending_not_disbursed", st_pending != "DISBURSED",
          f"PENDING_RECONCILIATION -> status={st_pending} (in-flight, not DISBURSED), call={pr.status_code}")
    info("DISB.pending.detail", f"app={app_p} acct={acct_p} status={st_pending}")

    # ---- summary ----
    if SERVER_ERRORS:
        print("\n!!! 500-LEVEL RESPONSES (should be 4xx) !!!")
        for cid, code in SERVER_ERRORS:
            print(f"  - {cid}: HTTP {code}")
    summary()
    return 1 if FAIL else 0


if __name__ == "__main__":
    raise SystemExit(main())
