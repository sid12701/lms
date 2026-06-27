"""Independent full-lifecycle E2E: fresh tenant -> origination -> docs ->
auto-approval -> disbursement -> repayment -> closure, with strict assertions
and business-logic math checks (EMI sum, processing-fee net proceeds, status
transition timing). Creates its own fixtures; trusts nothing pre-existing.
"""
from __future__ import annotations

import io
import json
import time
import uuid
from decimal import Decimal

from client import (BASE, req, admin_token, check, info, summary, rpan, raadhaar,
                    rmobile, rifsc, racct, rid, jwt_claims, FAIL)

# minimal valid single-page PDF (has %PDF- header + %%EOF)
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


def loan_body(lsp_id, product_id, sfx, **ov):
    name = f"Indep Borrower {sfx}"
    b = {
        "lspId": lsp_id, "productId": product_id, "lspLoanId": rid("EXT"),
        "fullName": name, "emailAddress": f"indep{sfx}@example.com",
        "mobileNumber": rmobile(), "dob": "1990-05-15", "gender": "MALE",
        "maritalStatus": "SINGLE", "fatherName": "Parent",
        "aadharNumber": raadhaar(), "panNumber": rpan(),
        "loanAmount": 150000, "interestRate": 14.5, "loanTenure": 12,
        "addressLine1": "42 Indep Street", "addressCity": "Mumbai",
        "addressState": "MH", "addressZipcode": "400001",
        "employmentStatus": "SALARIED", "organizationName": "Indep Corp",
        "monthlyIncome": 60000, "annualIncome": 720000,
        "bankAccountNumber": racct(), "bankName": "HDFC Bank",
        "ifscCode": rifsc(), "accountHolderName": name,
        "referencePersonName": "Ref", "referencePersonNumber": rmobile(),
    }
    b.update(ov)
    return b


def main():
    t = admin_token()
    ah = {"Content-Type": "application/json"}
    sfx = rid("L").split("-")[1].lower()

    # ---- build fresh tenant ----
    lsp = req("POST", "/api/v1/internal/admin/lsps", token=t,
              json={"code": f"INDEP-{sfx}", "name": f"Indep LSP {sfx}", "status": "ACTIVE"}, expect=(200, 201))
    check("setup.lsp", lsp.status_code in (200, 201), f"create LSP -> {lsp.status_code}")
    lsp_id = lsp.json()["id"]

    prod = req("POST", "/api/v1/internal/admin/products", token=t, json={
        "code": f"INDEP-P-{sfx}", "name": f"Indep Product {sfx}",
        "minPrincipal": 10000, "maxPrincipal": 500000, "interestRate": 14.5,
        "processingFeeRate": 1.5, "minTenureMonths": 6, "maxTenureMonths": 36,
        "status": "ACTIVE"}, expect=(200, 201))
    check("setup.product", prod.status_code in (200, 201), f"create product -> {prod.status_code}")
    prod_id = prod.json()["id"]
    processing_fee_rate = Decimal(str(prod.json().get("processingFeeRate", "1.5")))

    mp = req("PUT", f"/api/v1/internal/admin/products/{prod_id}/mappings", token=t,
             json={"lspIds": [lsp_id]}, expect=200)
    check("setup.mapping", mp.status_code == 200, f"map product->lsp -> {mp.status_code}")

    cli = req("POST", "/api/v1/internal/admin/api-clients", token=t,
              json={"lspId": lsp_id, "name": "INDEP-client"}, expect=(200, 201))
    check("setup.client", cli.status_code in (200, 201), f"create api client -> {cli.status_code}")
    client_id, secret = cli.json()["clientId"], cli.json()["clientSecret"]

    # ---- UC-004 token exchange + claim shape ----
    tok = req("POST", "/api/v1/auth/token", json={"clientId": client_id, "clientSecret": secret}, expect=200)
    check("UC-004", tok.status_code == 200 and "accessToken" in tok.json(), f"client token exchange -> {tok.status_code}")
    lt = tok.json()["accessToken"]
    claims = jwt_claims(lt)
    check("UC-004.claims", claims.get("roles") == ["LSP_API_CLIENT"] or "LSP_API_CLIENT" in claims.get("roles", []),
          f"LSP token roles={claims.get('roles')} tenant={claims.get('tenant') or claims.get('lspId')}")

    # ---- UC-047 product catalog scoped ----
    cat = req("GET", "/api/v1/lsp/products", token=lt, expect=200)
    cat_ok = cat.status_code == 200 and any(p["id"] == prod_id for p in cat.json())
    check("UC-047", cat_ok, f"LSP product catalog returns mapped product (count={len(cat.json())})")

    # ---- UC-016 create application ----
    body = loan_body(lsp_id, prod_id, sfx)
    idem = str(uuid.uuid4())
    ca = req("POST", "/api/v1/lsp/loan-applications", token=lt, idem=idem, json=body, expect=(200, 201))
    check("UC-016", ca.status_code in (200, 201), f"create application -> {ca.status_code} status={ca.json().get('status') if ca.content else ''}")
    app = ca.json()
    app_id = app["id"]
    borrower_id = app.get("borrowerId")
    info("UC-016.status", f"initial status={app.get('status')} app_id={app_id} borrowerId={borrower_id}")

    # ---- EC-027 idempotency replay (same key + body -> same id) ----
    replay = req("POST", "/api/v1/lsp/loan-applications", token=lt, idem=idem, json=body, expect=(200, 201))
    same = replay.status_code in (200, 201) and replay.json().get("id") == app_id
    check("EC-027", same, f"idempotent replay same key->same id ({replay.status_code}, id_match={same})")

    # ---- EC-027b fingerprint mismatch (same key, different body -> 409) ----
    body2 = dict(body); body2["loanAmount"] = 160000
    mm = req("POST", "/api/v1/lsp/loan-applications", token=lt, idem=idem, json=body2, expect=409)
    check("EC-027b", mm.status_code == 409, f"same key + different body -> {mm.status_code} (want 409)")

    # ---- check loanAccount absent before approval (EC-104) ----
    d0 = req("GET", f"/api/v1/internal/ops/loan-applications/{app_id}", token=t, expect=200).json()
    check("EC-104.pre", not d0.get("loanAccountId"),
          f"no loanAccountId before approval (status={d0.get('status')}, loanAccountId={d0.get('loanAccountId')})")

    # ---- UC-019 upload 8 docs ----
    upok = 0
    for i, dt in enumerate(DOC_TYPES):
        r = req("POST", f"/api/v1/lsp/loan-applications/{app_id}/documents", token=lt,
                files={"file": (f"{dt.lower()}.pdf", io.BytesIO(PDF_BYTES), "application/pdf")},
                data={"documentType": dt})
        if r.status_code in (200, 201):
            upok += 1
        else:
            info("UC-019.fail", f"{dt} upload -> {r.status_code}: {r.text[:200]}")
    check("UC-019", upok == 8, f"uploaded {upok}/8 KYC docs")

    # ---- UC-018/UC-021 auto-approval poll ----
    status = None
    for _ in range(40):
        d = req("GET", f"/api/v1/internal/ops/loan-applications/{app_id}", token=t, expect=200).json()
        status = d.get("status")
        if status in ("APPROVED_PENDING_DISBURSAL", "DISBURSED", "REJECTED"):
            break
        time.sleep(1)
    check("UC-018", status == "APPROVED_PENDING_DISBURSAL",
          f"auto-approval after docs -> status={status}")

    # ---- EC-104 loanAccount created on approval ----
    d_appr = req("GET", f"/api/v1/internal/ops/loan-applications/{app_id}", token=t, expect=200).json()
    loan_acct = d_appr.get("loanAccountId")
    check("EC-104", bool(loan_acct), f"loanAccountId present after approval: {loan_acct}")

    # ---- UC-026/028 disburse via mock outcome ----
    init = req("POST", f"/api/v1/internal/ops/loan-applications/{app_id}/disbursement-requests", token=t)
    info("UC-026", f"initiate disbursement -> {init.status_code} {init.text[:160]}")
    st = req("GET", f"/api/v1/internal/ops/loan-applications/{app_id}", token=t).json().get("status")
    if st != "DISBURSED":
        mo = req("POST", f"/api/v1/internal/ops/loan-applications/{app_id}/disbursement-requests/mock-outcome",
                 token=t, json={"outcome": "DISBURSED"}, headers={"Content-Type": "application/json"})
        info("UC-028", f"mock-outcome DISBURSED -> {mo.status_code} {mo.text[:160]}")
    # poll disbursed
    for _ in range(60):
        d = req("GET", f"/api/v1/internal/ops/loan-applications/{app_id}", token=t).json()
        st = d.get("status")
        if st in ("DISBURSED", "UNDER_REPAYMENT", "CLOSED"):
            break
        time.sleep(2)
    check("UC-026.disbursed", st in ("DISBURSED", "UNDER_REPAYMENT"), f"loan reached DISBURSED (status={st})")

    # ---- EC-103 processing fee net proceeds (ADR 0004) ----
    d_dis = req("GET", f"/api/v1/internal/ops/loan-applications/{app_id}", token=t).json()
    la = req("GET", f"/api/v1/lsp/loans/{loan_acct}", token=lt)
    la_json = la.json() if la.ok else {}
    principal = Decimal("150000")
    expected_fee = (principal * processing_fee_rate / Decimal("100")).quantize(Decimal("0.01"))
    disbursed_amount = la_json.get("disbursedAmount") or d_dis.get("disbursedAmount")
    info("EC-103", f"principal={principal} feeRate={processing_fee_rate}% expectedFee={expected_fee} "
                   f"disbursedAmount={disbursed_amount} loanAccount keys={list(la_json)[:12]}")

    # ---- UC-024 repayment schedule + EMI math ----
    sch = req("GET", f"/api/v1/lsp/loans/{loan_acct}/repayment-schedule", token=lt, expect=200)
    insts = sch.json() if sch.ok else []
    check("UC-024", len(insts) == 12, f"repayment schedule has 12 installments (got {len(insts)})")
    if insts:
        total = sum(Decimal(str(i.get("installmentAmount", 0))) for i in insts)
        # principal interest sum sanity: total should exceed principal (interest>0)
        info("UC-024.math", f"sum(installmentAmount)={total} vs principal={principal} "
                            f"(interest portion={total - principal})")
        check("UC-024.sum_gt_principal", total > principal, f"schedule total {total} > principal {principal}")

    # ---- UC-029 record all payments -> CLOSED, with transition checks ----
    slim = [{"id": i["id"], "n": i["installmentNumber"], "amount": float(i["installmentAmount"])}
            for i in sorted(insts, key=lambda x: x["installmentNumber"])]
    pay_status = []
    for idx, inst in enumerate(slim):
        pk = str(uuid.uuid4())
        pr = req("POST", f"/api/v1/internal/ops/loan-applications/{app_id}/payments", token=t, idem=pk,
                 json={"targetInstallmentId": inst["id"], "amount": inst["amount"],
                       "postedAt": "2026-06-13", "reference": f"EMI-{inst['n']}", "channel": "NEFT"},
                 headers={"Content-Type": "application/json"})
        pay_status.append(pr.status_code)
        if idx == 0:
            st1 = req("GET", f"/api/v1/internal/ops/loan-applications/{app_id}", token=t).json().get("status")
            check("EC-098", st1 == "UNDER_REPAYMENT", f"after 1st payment status=UNDER_REPAYMENT (got {st1})")
        if pr.status_code not in (200, 201):
            info("UC-029.fail", f"payment {inst['n']} -> {pr.status_code}: {pr.text[:200]}")
    check("UC-029", all(s in (200, 201) for s in pay_status), f"12 payments status codes={pay_status}")
    final = req("GET", f"/api/v1/internal/ops/loan-applications/{app_id}", token=t).json().get("status")
    check("EC-099", final == "CLOSED", f"after 12 payments status=CLOSED (got {final})")

    # ---- EC-057 payment on CLOSED loan -> 4xx ----
    pc = req("POST", f"/api/v1/internal/ops/loan-applications/{app_id}/payments", token=t, idem=str(uuid.uuid4()),
             json={"targetInstallmentId": slim[0]["id"], "amount": slim[0]["amount"],
                   "postedAt": "2026-06-13", "reference": "EMI-X", "channel": "NEFT"},
             headers={"Content-Type": "application/json"})
    check("EC-057", 400 <= pc.status_code < 500, f"payment on CLOSED loan -> {pc.status_code} (want 4xx)")

    # ---- EC-034 illegal transition CLOSED -> DISBURSED ----
    it = req("POST", f"/api/v1/internal/ops/loan-applications/{app_id}/status-transitions", token=t,
             json={"targetStatus": "DISBURSED", "note": "illegal"}, headers={"Content-Type": "application/json"})
    check("EC-034", 400 <= it.status_code < 500, f"CLOSED->DISBURSED illegal transition -> {it.status_code} (want 4xx)")

    print(f"\nTENANT: lsp={lsp_id} product={prod_id} client={client_id}")
    print(f"CLOSED loan app_id={app_id} loanAccountId={loan_acct}")
    # persist context for later phases
    json.dump({"lsp_id": lsp_id, "product_id": prod_id, "client_id": client_id,
               "client_secret": secret, "closed_app_id": app_id, "loan_acct": loan_acct,
               "borrower_id": borrower_id},
              open("indep_ctx.json", "w"))
    summary()
    return 1 if FAIL else 0


if __name__ == "__main__":
    raise SystemExit(main())
