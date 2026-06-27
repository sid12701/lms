"""Happy-path E2E as an LSP: fresh tenant -> LSP API token -> originate loan ->
upload KYC docs (the repo's blank.pdf) -> auto-approval -> disbursement ->
ONE repayment (loan reaches UNDER_REPAYMENT).

Drives the real backend on localhost:8080. The LSP-facing steps (token exchange,
create application, upload documents, read repayment schedule) use the LSP API
client token; approval is automatic (rule engine); disbursement and recording a
repayment are platform/ops actions, matching how the system actually works.

Run from scripts/indep-e2e/:  python happy_path_one_repayment.py
"""
from __future__ import annotations

import io
import json
import os
import time
import uuid
from decimal import Decimal

from client import (req, check, info, summary, rpan, raadhaar,
                    rmobile, rifsc, racct, rid, jwt_claims, FAIL)

# Operators now sign in by email (LoginRequest = {email, password}); the shared
# client.py still sends the old {username} field, so do admin login here. The
# bootstrap admin email maps to the bootstrap username server-side.
ADMIN_EMAIL = "siddhant@bhawanafinance.com"
ADMIN_PASS = "ChangeMe123!"


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

# the blank PDF shipped in the repo root, used for every document upload
PDF_PATH = os.path.join(os.path.dirname(__file__), "..", "..", "blank.pdf")
with open(PDF_PATH, "rb") as fh:
    PDF_BYTES = fh.read()

DOC_TYPES = ["PAN_CARD", "AADHAAR_FILE", "BANK_STATEMENT", "ADDRESS_PROOF",
             "INCOME_PROOF", "SELFIE_PHOTOGRAPH", "KFS", "LOAN_AGREEMENT"]

PRINCIPAL = 150000
TENURE = 12


def loan_body(lsp_id, product_id, sfx):
    name = f"HappyPath Borrower {sfx}"
    return {
        "lspId": lsp_id, "productId": product_id, "lspLoanId": rid("EXT"),
        "fullName": name, "emailAddress": f"happy{sfx}@example.com",
        "mobileNumber": rmobile(), "dob": "1990-05-15", "gender": "MALE",
        "maritalStatus": "SINGLE", "fatherName": "Parent",
        "aadharNumber": raadhaar(), "panNumber": rpan(),
        "loanAmount": PRINCIPAL, "interestRate": 14.5, "loanTenure": TENURE,
        "addressLine1": "42 Happy Street", "addressCity": "Mumbai",
        "addressState": "MH", "addressZipcode": "400001",
        "employmentStatus": "SALARIED", "organizationName": "Happy Corp",
        "monthlyIncome": 60000, "annualIncome": 720000,
        "bankAccountNumber": racct(), "bankName": "HDFC Bank",
        "ifscCode": rifsc(), "accountHolderName": name,
        "referencePersonName": "Ref", "referencePersonNumber": rmobile(),
    }


def stage(n, title):
    print(f"\n========== STAGE {n}: {title} ==========")


def main():
    print(f"Using upload file: blank.pdf ({len(PDF_BYTES)} bytes, header={PDF_BYTES[:8]!r})")
    t = admin_token()
    sfx = rid("HP").split("-")[1].lower()

    # ---- STAGE 0: admin bootstrap (LSP, product, mapping, api client) ----
    stage(0, "Admin bootstrap — provision LSP, product, API client")
    lsp = req("POST", "/api/v1/internal/admin/lsps", token=t,
              json={"code": f"HP-{sfx}", "name": f"HappyPath LSP {sfx}", "status": "ACTIVE"}, expect=(200, 201))
    check("bootstrap.lsp", lsp.status_code in (200, 201), f"create LSP -> {lsp.status_code}")
    lsp_id = lsp.json()["id"]

    prod = req("POST", "/api/v1/internal/admin/products", token=t, json={
        "code": f"HP-P-{sfx}", "name": f"HappyPath Product {sfx}",
        "minPrincipal": 10000, "maxPrincipal": 500000, "interestRate": 14.5,
        "processingFeeRate": 1.5, "minTenureMonths": 6, "maxTenureMonths": 36,
        "status": "ACTIVE"}, expect=(200, 201))
    check("bootstrap.product", prod.status_code in (200, 201), f"create product -> {prod.status_code}")
    prod_id = prod.json()["id"]
    fee_rate = Decimal(str(prod.json().get("processingFeeRate", "1.5")))

    mp = req("PUT", f"/api/v1/internal/admin/products/{prod_id}/mappings", token=t,
             json={"lspIds": [lsp_id]}, expect=200)
    check("bootstrap.mapping", mp.status_code == 200, f"map product->lsp -> {mp.status_code}")

    cli = req("POST", "/api/v1/internal/admin/api-clients", token=t,
              json={"lspId": lsp_id, "name": "HP-client"}, expect=(200, 201))
    check("bootstrap.client", cli.status_code in (200, 201), f"create api client -> {cli.status_code}")
    client_id, secret = cli.json()["clientId"], cli.json()["clientSecret"]
    info("bootstrap", f"lsp={lsp_id} product={prod_id} clientId={client_id}")

    # ---- STAGE 1: LSP authenticates (client-credentials token exchange) ----
    stage(1, "LSP authenticates via API client credentials")
    tok = req("POST", "/api/v1/auth/token", json={"clientId": client_id, "clientSecret": secret}, expect=200)
    check("lsp.token", tok.status_code == 200 and "accessToken" in tok.json(), f"token exchange -> {tok.status_code}")
    lt = tok.json()["accessToken"]
    claims = jwt_claims(lt)
    check("lsp.role", "LSP_API_CLIENT" in claims.get("roles", []), f"token roles={claims.get('roles')}")

    cat = req("GET", "/api/v1/lsp/products", token=lt, expect=200)
    check("lsp.catalog", cat.status_code == 200 and any(p["id"] == prod_id for p in cat.json()),
          f"LSP sees mapped product in catalog (count={len(cat.json())})")

    # ---- STAGE 2: LSP originates the loan application ----
    stage(2, "LSP originates loan application")
    body = loan_body(lsp_id, prod_id, sfx)
    idem = str(uuid.uuid4())
    ca = req("POST", "/api/v1/lsp/loan-applications", token=lt, idem=idem, json=body, expect=(200, 201))
    check("lsp.create", ca.status_code in (200, 201), f"create application -> {ca.status_code}")
    app = ca.json()
    app_id = app["id"]
    info("lsp.create", f"app_id={app_id} initialStatus={app.get('status')} borrowerId={app.get('borrowerId')} "
                       f"lspLoanId={body['lspLoanId']}")

    # ---- STAGE 3: LSP uploads KYC documents (blank.pdf for each) ----
    stage(3, "LSP uploads KYC documents (blank.pdf)")
    upok = 0
    for dt in DOC_TYPES:
        r = req("POST", f"/api/v1/lsp/loan-applications/{app_id}/documents", token=lt,
                files={"file": ("blank.pdf", io.BytesIO(PDF_BYTES), "application/pdf")},
                data={"documentType": dt})
        if r.status_code in (200, 201):
            upok += 1
            print(f"   [ok] {dt}")
        else:
            info("lsp.doc.fail", f"{dt} -> {r.status_code}: {r.text[:160]}")
    check("lsp.docs", upok == len(DOC_TYPES), f"uploaded {upok}/{len(DOC_TYPES)} docs from blank.pdf")

    # ---- STAGE 4: auto-approval ----
    stage(4, "Wait for rule-engine auto-approval")
    status = None
    for _ in range(40):
        d = req("GET", f"/api/v1/internal/ops/loan-applications/{app_id}", token=t, expect=200).json()
        status = d.get("status")
        if status in ("APPROVED_PENDING_DISBURSAL", "DISBURSED", "REJECTED"):
            break
        time.sleep(1)
    check("approval", status == "APPROVED_PENDING_DISBURSAL", f"auto-approved -> status={status}")
    d_appr = req("GET", f"/api/v1/internal/ops/loan-applications/{app_id}", token=t, expect=200).json()
    loan_acct = d_appr.get("loanAccountId")
    check("approval.account", bool(loan_acct), f"loan account created on approval: {loan_acct}")

    # ---- STAGE 5: disbursement ----
    stage(5, "Disburse funds (mock bank composite-pay outcome)")
    init = req("POST", f"/api/v1/internal/ops/loan-applications/{app_id}/disbursement-requests", token=t)
    info("disburse.init", f"initiate -> {init.status_code} {init.text[:140]}")
    st = req("GET", f"/api/v1/internal/ops/loan-applications/{app_id}", token=t).json().get("status")
    if st != "DISBURSED":
        mo = req("POST", f"/api/v1/internal/ops/loan-applications/{app_id}/disbursement-requests/mock-outcome",
                 token=t, json={"outcome": "DISBURSED"}, headers={"Content-Type": "application/json"})
        info("disburse.mock", f"mock-outcome DISBURSED -> {mo.status_code} {mo.text[:140]}")
    for _ in range(60):
        d = req("GET", f"/api/v1/internal/ops/loan-applications/{app_id}", token=t).json()
        st = d.get("status")
        if st in ("DISBURSED", "UNDER_REPAYMENT", "CLOSED"):
            break
        time.sleep(2)
    check("disburse", st in ("DISBURSED", "UNDER_REPAYMENT"), f"loan reached DISBURSED (status={st})")

    # processing-fee net proceeds (ADR 0004): net = principal - fee
    expected_fee = (Decimal(PRINCIPAL) * fee_rate / Decimal("100")).quantize(Decimal("0.01"))
    la = req("GET", f"/api/v1/lsp/loans/{loan_acct}", token=lt)
    la_json = la.json() if la.ok else {}
    disbursed_amount = la_json.get("disbursedAmount") or d.get("disbursedAmount")
    info("disburse.fee", f"principal={PRINCIPAL} feeRate={fee_rate}% expectedFee={expected_fee} "
                         f"netDisbursed={disbursed_amount}")

    # ---- STAGE 6: repayment schedule ----
    stage(6, "LSP reads repayment schedule")
    sch = req("GET", f"/api/v1/lsp/loans/{loan_acct}/repayment-schedule", token=lt, expect=200)
    insts = sch.json() if sch.ok else []
    check("schedule", len(insts) == TENURE, f"schedule has {TENURE} installments (got {len(insts)})")
    insts = sorted(insts, key=lambda x: x["installmentNumber"])
    first = insts[0] if insts else None

    # ---- STAGE 7: record ONE repayment ----
    stage(7, "Record the first repayment")
    if not first:
        check("repayment", False, "no installments to pay")
        summary()
        return 1
    emi = float(first["installmentAmount"])
    pk = str(uuid.uuid4())
    pr = req("POST", f"/api/v1/internal/ops/loan-applications/{app_id}/payments", token=t, idem=pk,
             json={"targetInstallmentId": first["id"], "amount": emi,
                   "postedAt": "2026-06-25", "reference": f"EMI-{first['installmentNumber']}", "channel": "NEFT"},
             headers={"Content-Type": "application/json"})
    check("repayment", pr.status_code in (200, 201), f"record installment #1 (amount={emi}) -> {pr.status_code}")
    final = req("GET", f"/api/v1/internal/ops/loan-applications/{app_id}", token=t).json().get("status")
    check("repayment.status", final == "UNDER_REPAYMENT", f"loan now UNDER_REPAYMENT after 1 repayment (got {final})")

    # ---- final report ----
    print("\n" + "#" * 70)
    print("HAPPY PATH RESULT")
    print(f"  LSP id           : {lsp_id}")
    print(f"  Loan application : {app_id}")
    print(f"  Loan account     : {loan_acct}")
    print(f"  LSP loan id      : {body['lspLoanId']}")
    print(f"  Gross principal  : {PRINCIPAL}")
    print(f"  Processing fee   : {expected_fee} ({fee_rate}%)")
    print(f"  Net disbursed    : {disbursed_amount}")
    print(f"  Installments     : {len(insts)} x EMI {emi}")
    print(f"  First repayment  : installment #{first['installmentNumber']} amount {emi} -> {pr.status_code}")
    print(f"  Final status     : {final}")
    print("#" * 70)

    json.dump({"lsp_id": lsp_id, "product_id": prod_id, "client_id": client_id,
               "app_id": app_id, "loan_acct": loan_acct, "lsp_loan_id": body["lspLoanId"],
               "final_status": final, "emi": emi, "net_disbursed": str(disbursed_amount)},
              open("happy_path_ctx.json", "w"))
    summary()
    return 1 if FAIL else 0


if __name__ == "__main__":
    raise SystemExit(main())
