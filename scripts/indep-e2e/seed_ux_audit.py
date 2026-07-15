"""Seed a UX-audit tenant with loans in every lifecycle state.

Creates one LSP + product + API client, then:
  - 22 bare applications (pagination fodder, INITIALIZED/AWAITING_*)
  - W1 docs-complete -> approved -> auto/mock DISBURSED (active loan)
  - W2 disbursed -> 3 payments (UNDER_REPAYMENT)
  - W3 disbursed -> 12 payments (CLOSED)
  - W4 approved -> mock-outcome FAILED (disbursement failure surface)
  - W5 rejected via ops status-transition (REJECTED)

Tolerant: logs failures, keeps going. Prints a context summary at the end.
"""
from __future__ import annotations

import io
import json
import time
import uuid

from client import req, info, check, rpan, raadhaar, rmobile, rifsc, racct, rid, jwt_claims

ADMIN_EMAIL = "siddhant@bhawanafinance.com"
ADMIN_PASS = "ChangeMe123!"

PDF_BYTES = (
    b"%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n"
    b"2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n"
    b"3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 200 200]>>endobj\n"
    b"xref\n0 4\n0000000000 65535 f \n0000000009 00000 n \n"
    b"0000000052 00000 n \n0000000101 00000 n \ntrailer<</Size 4/Root 1 0 R>>\n"
    b"startxref\n164\n%%EOF\n"
)
DOC_TYPES = ["PAN_CARD", "AADHAAR_FILE", "BANK_STATEMENT", "ADDRESS_PROOF",
             "INCOME_PROOF", "SELFIE_PHOTOGRAPH", "KFS", "LOAN_AGREEMENT"]


def admin_token() -> str:
    cache = ".admin_token_cache.json"
    try:
        c = json.load(open(cache))
        if c.get("exp", 0) - 120 > time.time():
            return c["token"]
    except Exception:
        pass
    r = req("POST", "/api/v1/auth/login", json={"email": ADMIN_EMAIL, "password": ADMIN_PASS}, expect=200)
    if r.status_code != 200:
        raise SystemExit(f"admin login failed {r.status_code}: {r.text[:300]}")
    tok = r.json()["accessToken"]
    exp = jwt_claims(tok).get("exp", time.time() + 3000)
    json.dump({"token": tok, "exp": exp}, open(cache, "w"))
    return tok


def loan_body(lsp_id, product_id, name, **ov):
    b = {
        "lspId": lsp_id, "productId": product_id, "lspLoanId": rid("UXA"),
        "fullName": name, "emailAddress": f"ux{uuid.uuid4().hex[:8]}@example.com",
        "mobileNumber": rmobile(), "dob": "1991-03-21", "gender": "MALE",
        "maritalStatus": "SINGLE", "fatherName": "Parent",
        "aadharNumber": raadhaar(), "panNumber": rpan(),
        "loanAmount": 150000, "interestRate": 14.5, "loanTenure": 12,
        "addressLine1": "7 Audit Lane", "addressCity": "Mumbai",
        "addressState": "MH", "addressZipcode": "400001",
        "employmentStatus": "SALARIED", "organizationName": "Audit Corp",
        "monthlyIncome": 60000, "annualIncome": 720000,
        "bankAccountNumber": racct(), "bankName": "HDFC Bank",
        "ifscCode": rifsc(), "accountHolderName": name,
        "referencePersonName": "Ref", "referencePersonNumber": rmobile(),
    }
    b.update(ov)
    return b


def upload_docs(lt, app_id):
    ok = 0
    for dt in DOC_TYPES:
        r = req("POST", f"/api/v1/lsp/loan-applications/{app_id}/documents", token=lt,
                files={"file": (f"{dt.lower()}.pdf", io.BytesIO(PDF_BYTES), "application/pdf")},
                data={"documentType": dt})
        if r.status_code in (200, 201):
            ok += 1
        else:
            info("docs", f"{app_id} {dt} -> {r.status_code} {r.text[:120]}")
    return ok


def get_app(t, app_id):
    return req("GET", f"/api/v1/internal/ops/loan-applications/{app_id}", token=t).json()


def poll_status(t, app_id, targets, tries=45, sleep=2):
    st = None
    for _ in range(tries):
        st = get_app(t, app_id).get("status")
        if st in targets:
            return st
        time.sleep(sleep)
    return st


def main():
    t = admin_token()
    sfx = uuid.uuid4().hex[:6].upper()

    lsp = req("POST", "/api/v1/internal/admin/lsps", token=t,
              json={"code": f"UXAUD-{sfx}", "name": f"UX Audit LSP {sfx}", "status": "ACTIVE"},
              expect=(200, 201))
    lsp_id = lsp.json()["id"]
    prod = req("POST", "/api/v1/internal/admin/products", token=t, json={
        "code": f"UXA-P-{sfx}", "name": f"UX Audit Product {sfx}",
        "minPrincipal": 10000, "maxPrincipal": 500000, "interestRate": 14.5,
        "processingFeeRate": 1.5, "minTenureMonths": 6, "maxTenureMonths": 36,
        "status": "ACTIVE"}, expect=(200, 201))
    prod_id = prod.json()["id"]
    req("PUT", f"/api/v1/internal/admin/products/{prod_id}/mappings", token=t,
        json={"lspIds": [lsp_id]}, expect=200)
    cli = req("POST", "/api/v1/internal/admin/api-clients", token=t,
              json={"lspId": lsp_id, "name": f"UXAUD-client-{sfx}"}, expect=(200, 201))
    client_id, secret = cli.json()["clientId"], cli.json()["clientSecret"]
    tok = req("POST", "/api/v1/auth/token", json={"clientId": client_id, "clientSecret": secret}, expect=200)
    lt = tok.json()["accessToken"]
    print(f"tenant ready: lsp={lsp_id} product={prod_id}")

    # pagination fodder
    fodder = []
    for i in range(22):
        r = req("POST", "/api/v1/lsp/loan-applications", token=lt, idem=str(uuid.uuid4()),
                json=loan_body(lsp_id, prod_id, f"Fodder Borrower {i+1:02d}",
                               loanAmount=50000 + i * 10000))
        if r.status_code in (200, 201):
            fodder.append(r.json()["id"])
    print(f"fodder created: {len(fodder)}/22")

    # workflow loans
    w = {}
    for key, name in [("W1", "Asha Active"), ("W2", "Ravi Repaying"),
                      ("W3", "Chetan Closed"), ("W4", "Farida FailedDisb"),
                      ("W5", "Rekha Rejected")]:
        r = req("POST", "/api/v1/lsp/loan-applications", token=lt, idem=str(uuid.uuid4()),
                json=loan_body(lsp_id, prod_id, name), expect=(200, 201))
        w[key] = r.json()["id"]
        print(f"{key} {name}: {w[key]} status={r.json().get('status')}")

    # W5: reject before docs
    rj = req("POST", f"/api/v1/internal/ops/loan-applications/{w['W5']}/status-transitions", token=t,
             json={"targetStatus": "REJECTED", "note": "UX audit seed rejection"},
             headers={"Content-Type": "application/json"})
    print(f"W5 reject -> {rj.status_code} {rj.text[:160]}")

    # docs for W1..W4
    for key in ("W1", "W2", "W3", "W4"):
        n = upload_docs(lt, w[key])
        print(f"{key} docs {n}/8")

    # wait approval for W1..W4
    for key in ("W1", "W2", "W3", "W4"):
        st = poll_status(t, w[key], ("APPROVED_PENDING_DISBURSAL", "DISBURSED", "REJECTED"), tries=40, sleep=1)
        print(f"{key} post-docs status={st}")

    # W4: force disbursement failure BEFORE worker picks it up if possible
    init4 = req("POST", f"/api/v1/internal/ops/loan-applications/{w['W4']}/disbursement-requests", token=t)
    print(f"W4 initiate -> {init4.status_code}")
    mo4 = req("POST", f"/api/v1/internal/ops/loan-applications/{w['W4']}/disbursement-requests/mock-outcome",
              token=t, json={"outcome": "FAILED"}, headers={"Content-Type": "application/json"})
    print(f"W4 mock FAILED -> {mo4.status_code} {mo4.text[:200]}")

    # W1..W3: disburse
    for key in ("W1", "W2", "W3"):
        st = get_app(t, w[key]).get("status")
        if st == "APPROVED_PENDING_DISBURSAL":
            req("POST", f"/api/v1/internal/ops/loan-applications/{w[key]}/disbursement-requests", token=t)
            req("POST", f"/api/v1/internal/ops/loan-applications/{w[key]}/disbursement-requests/mock-outcome",
                token=t, json={"outcome": "DISBURSED"}, headers={"Content-Type": "application/json"})
        st = poll_status(t, w[key], ("DISBURSED", "UNDER_REPAYMENT", "CLOSED"), tries=45, sleep=2)
        print(f"{key} disbursement status={st}")

    # payments helper
    def pay(key, count):
        d = get_app(t, w[key])
        acct = d.get("loanAccountId")
        if not acct:
            print(f"{key}: no loan account, skip payments")
            return
        sch = req("GET", f"/api/v1/lsp/loans/{acct}/repayment-schedule", token=lt)
        insts = sorted(sch.json(), key=lambda x: x["installmentNumber"]) if sch.ok else []
        for inst in insts[:count]:
            pr = req("POST", f"/api/v1/internal/ops/loan-applications/{w[key]}/payments", token=t,
                     idem=str(uuid.uuid4()),
                     json={"targetInstallmentId": inst["id"], "amount": float(inst["installmentAmount"]),
                           "postedAt": "2026-07-02", "reference": f"UXA-EMI-{inst['installmentNumber']}",
                           "channel": "NEFT"},
                     headers={"Content-Type": "application/json"})
            if pr.status_code not in (200, 201):
                print(f"{key} pay {inst['installmentNumber']} -> {pr.status_code} {pr.text[:160]}")
        print(f"{key}: paid {min(count, len(insts))} installments -> status={get_app(t, w[key]).get('status')}")

    pay("W2", 3)
    pay("W3", 12)

    ctx = {"lsp_id": lsp_id, "product_id": prod_id, "client_id": client_id,
           "client_secret": secret, **{k: v for k, v in w.items()}}
    json.dump(ctx, open("ux_audit_ctx.json", "w"), indent=2)
    print("\nFINAL STATES:")
    for key in w:
        print(f"  {key}: {get_app(t, w[key]).get('status')}")
    print(json.dumps(ctx, indent=2))


if __name__ == "__main__":
    main()
