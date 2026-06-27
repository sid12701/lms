"""Additional business-logic & edge tests beyond the happy path.
Builds the minimal extra state it needs from the indep tenant.
"""
from __future__ import annotations
import io, json, time, uuid
from client import (req, admin_token, check, info, summary, rpan, raadhaar,
                    rmobile, rifsc, racct, rid, jwt_claims, FAIL)

ctx = json.load(open("indep_ctx.json"))
t = admin_token()
J = {"Content-Type": "application/json"}
lsp_id, prod_id = ctx["lsp_id"], ctx["product_id"]
PDF = (b"%PDF-1.4\n1 0 obj<</Type/Catalog>>endobj\ntrailer<</Root 1 0 R>>\n%%EOF\n")


def lsp_token():
    r = req("POST", "/api/v1/auth/token", json={"clientId": ctx["client_id"], "clientSecret": ctx["client_secret"]})
    if r.ok:
        return r.json()["accessToken"]
    # rotate if stale
    cl = req("GET", "/api/v1/internal/admin/api-clients", token=t).json()
    row = [c for c in cl if c["clientId"] == ctx["client_id"]][0]
    ns = req("POST", f"/api/v1/internal/admin/api-clients/{row['id']}/rotate-secret", token=t).json()["clientSecret"]
    ctx["client_secret"] = ns
    return req("POST", "/api/v1/auth/token", json={"clientId": ctx["client_id"], "clientSecret": ns}).json()["accessToken"]


lt = lsp_token()


def lbody(**ov):
    name = "BL Borrower " + uuid.uuid4().hex[:6]
    b = {"lspId": lsp_id, "productId": prod_id, "lspLoanId": rid("BL"), "fullName": name,
         "emailAddress": f"bl{uuid.uuid4().hex[:6]}@e.com", "mobileNumber": rmobile(), "dob": "1990-05-15",
         "gender": "MALE", "maritalStatus": "SINGLE", "fatherName": "P", "aadharNumber": raadhaar(),
         "panNumber": rpan(), "loanAmount": 150000, "interestRate": 14.5, "loanTenure": 12,
         "addressLine1": "1 St", "addressCity": "Mumbai", "addressState": "MH", "addressZipcode": "400001",
         "employmentStatus": "SALARIED", "organizationName": "C", "monthlyIncome": 60000, "annualIncome": 720000,
         "bankAccountNumber": racct(), "bankName": "HDFC", "ifscCode": rifsc(), "accountHolderName": name,
         "referencePersonName": "R", "referencePersonNumber": rmobile()}
    b.update(ov)
    return b


def create_app(**ov):
    return req("POST", "/api/v1/lsp/loan-applications", token=lt, idem=str(uuid.uuid4()), json=lbody(**ov))


# ===== EC-026 / EC-101 one-open-loan rule (matrix saw 500; must be structured 4xx) =====
pan = rpan()
a1 = create_app(panNumber=pan)
check("EC-101.first", a1.status_code in (200, 201), f"first loan for PAN -> {a1.status_code}")
if a1.ok:
    app1 = a1.json()["id"]
    # drive app1 to an OPEN state (upload docs -> approved) so PAN has an open loan
    for dt in ["PAN_CARD", "AADHAAR_FILE", "BANK_STATEMENT", "ADDRESS_PROOF", "INCOME_PROOF", "SELFIE_PHOTOGRAPH", "KFS", "LOAN_AGREEMENT"]:
        req("POST", f"/api/v1/lsp/loan-applications/{app1}/documents", token=lt,
            files={"file": (f"{dt.lower()}.pdf", io.BytesIO(PDF), "application/pdf")}, data={"documentType": dt})
    for _ in range(30):
        s = req("GET", f"/api/v1/internal/ops/loan-applications/{app1}", token=t).json().get("status")
        if s in ("APPROVED_PENDING_DISBURSAL", "DISBURSED", "REJECTED"):
            break
        time.sleep(1)
    info("EC-026.app1", f"app1 status before duplicate attempt = {s}")
    # second application, same PAN -> expect structured 409/422, NOT 500
    a2 = create_app(panNumber=pan)
    check("EC-026", a2.status_code in (409, 422) or (a2.ok and a2.json().get("status") == "REJECTED"),
          f"duplicate open-loan PAN -> {a2.status_code} (want structured 4xx, NOT 500)")
    check("EC-026.not500", a2.status_code != 500, f"one-open-loan does NOT 500 (got {a2.status_code})")
    info("EC-026.body", f"{a2.text[:200]}")

# ===== Document upload negatives =====
appd = create_app()
if appd.ok:
    adi = appd.json()["id"]
    # EC-040 disallowed MIME (plain text)
    r = req("POST", f"/api/v1/lsp/loan-applications/{adi}/documents", token=lt,
            files={"file": ("x.txt", io.BytesIO(b"hello world not a pdf"), "text/plain")}, data={"documentType": "PAN_CARD"})
    check("EC-040", 400 <= r.status_code < 500, f"text/plain upload -> {r.status_code} (want 4xx)")
    # EC-042 documentType not on checklist / invalid enum
    r = req("POST", f"/api/v1/lsp/loan-applications/{adi}/documents", token=lt,
            files={"file": ("x.pdf", io.BytesIO(PDF), "application/pdf")}, data={"documentType": "NOT_A_REAL_TYPE"})
    check("EC-042", 400 <= r.status_code < 500, f"bad documentType -> {r.status_code} (want 4xx)")
# EC-043 upload to non-existent application
r = req("POST", f"/api/v1/lsp/loan-applications/{uuid.uuid4()}/documents", token=lt,
        files={"file": ("x.pdf", io.BytesIO(PDF), "application/pdf")}, data={"documentType": "PAN_CARD"})
check("EC-043", r.status_code == 404, f"upload to random app -> {r.status_code} (want 404)")

# ===== Disbursement on non-eligible status (EC-046) =====
appi = create_app()
if appi.ok:
    aii = appi.json()["id"]  # INITIALIZED, no docs
    r = req("POST", f"/api/v1/internal/ops/loan-applications/{aii}/disbursement-requests", token=t)
    check("EC-046", 400 <= r.status_code < 500, f"disburse INITIALIZED -> {r.status_code} (want 4xx)")
    # EC-058 foreclosure quote on non-eligible
    r = req("POST", f"/api/v1/internal/ops/loan-applications/{aii}/foreclosure-quotes", token=t, headers=J, json={})
    check("EC-058", 400 <= r.status_code < 500, f"foreclosure quote on INITIALIZED -> {r.status_code} (want 4xx)")

# ===== Illegal transition + reason validation =====
appt = create_app()
if appt.ok:
    ati = appt.json()["id"]
    # move to AWAITING_APPROVAL then REJECTED without reasonCode -> EC-039 4xx
    req("POST", f"/api/v1/internal/ops/loan-applications/{ati}/status-transitions", token=t, headers=J,
        json={"targetStatus": "AWAITING_APPROVAL", "note": "queue"})
    r = req("POST", f"/api/v1/internal/ops/loan-applications/{ati}/status-transitions", token=t, headers=J,
            json={"targetStatus": "REJECTED", "note": "no reason code"})
    check("EC-039", 400 <= r.status_code < 500, f"REJECTED without reasonCode -> {r.status_code} (want 4xx)")
    # now reject properly
    req("POST", f"/api/v1/internal/ops/loan-applications/{ati}/status-transitions", token=t, headers=J,
        json={"targetStatus": "REJECTED", "note": "reject", "reasonCode": "FAILED_VERIFICATION"})
    # EC-033 REJECTED -> APPROVED illegal
    r = req("POST", f"/api/v1/internal/ops/loan-applications/{ati}/status-transitions", token=t, headers=J,
            json={"targetStatus": "APPROVED_PENDING_DISBURSAL", "note": "illegal"})
    check("EC-033", 400 <= r.status_code < 500, f"REJECTED->APPROVED illegal -> {r.status_code} (want 4xx)")
    # EC-100 rejection reason persisted
    tr = req("GET", f"/api/v1/internal/ops/loan-applications/{ati}/status-transitions", token=t)
    if tr.ok:
        rows = tr.json() if isinstance(tr.json(), list) else tr.json().get("transitions", [])
        rej = [x for x in rows if x.get("toStatus") == "REJECTED" or x.get("targetStatus") == "REJECTED"]
        info("EC-100", f"rejected transition rows: {len(rej)}; sample reason: {rej[0].get('reasonCode') if rej else None}")

# ===== Payment negatives on the CLOSED loan / a fresh UNDER_REPAYMENT loan =====
# Build a fresh disbursed loan to test payment validation cleanly
appp = create_app()
acct = None; insts = []
if appp.ok:
    api_ = appp.json()["id"]
    for dt in ["PAN_CARD", "AADHAAR_FILE", "BANK_STATEMENT", "ADDRESS_PROOF", "INCOME_PROOF", "SELFIE_PHOTOGRAPH", "KFS", "LOAN_AGREEMENT"]:
        req("POST", f"/api/v1/lsp/loan-applications/{api_}/documents", token=lt,
            files={"file": (f"{dt.lower()}.pdf", io.BytesIO(PDF), "application/pdf")}, data={"documentType": dt})
    for _ in range(30):
        d = req("GET", f"/api/v1/internal/ops/loan-applications/{api_}", token=t).json()
        if d.get("status") in ("APPROVED_PENDING_DISBURSAL", "DISBURSED"):
            break
        time.sleep(1)
    req("POST", f"/api/v1/internal/ops/loan-applications/{api_}/disbursement-requests", token=t)
    req("POST", f"/api/v1/internal/ops/loan-applications/{api_}/disbursement-requests/mock-outcome", token=t, headers=J, json={"outcome": "DISBURSED"})
    for _ in range(30):
        d = req("GET", f"/api/v1/internal/ops/loan-applications/{api_}", token=t).json()
        if d.get("status") in ("DISBURSED", "UNDER_REPAYMENT"):
            acct = d.get("loanAccountId"); break
        time.sleep(1)
    if acct:
        insts = sorted(req("GET", f"/api/v1/lsp/loans/{acct}/repayment-schedule", token=lt).json(),
                       key=lambda x: x["installmentNumber"])
if acct and insts:
    i0 = insts[0]; amt = float(i0["installmentAmount"])
    pay = lambda **kw: req("POST", f"/api/v1/internal/ops/loan-applications/{api_}/payments", token=t, **kw)
    # EC-051 missing Idempotency-Key
    r = pay(headers=J, json={"targetInstallmentId": i0["id"], "amount": amt, "postedAt": "2026-06-14", "reference": "R", "channel": "NEFT"})
    check("EC-051", r.status_code == 400, f"payment no Idempotency-Key -> {r.status_code} (want 400)")
    # EC-052 missing targetInstallmentId
    r = pay(idem=str(uuid.uuid4()), headers=J, json={"amount": amt, "postedAt": "2026-06-14", "reference": "R", "channel": "NEFT"})
    check("EC-052", r.status_code == 400, f"payment no targetInstallmentId -> {r.status_code} (want 400)")
    # EC-053 partial amount
    r = pay(idem=str(uuid.uuid4()), headers=J, json={"targetInstallmentId": i0["id"], "amount": round(amt/2, 2), "postedAt": "2026-06-14", "reference": "R", "channel": "NEFT"})
    check("EC-053", 400 <= r.status_code < 500, f"partial amount -> {r.status_code} (want 4xx)")
    # EC-056 invalid channel
    r = pay(idem=str(uuid.uuid4()), headers=J, json={"targetInstallmentId": i0["id"], "amount": amt, "postedAt": "2026-06-14", "reference": "R", "channel": "BITCOIN"})
    check("EC-056", r.status_code == 400, f"invalid channel -> {r.status_code} (want 400)")
    # EC-056b UPI is VALID (pay installment 1 with UPI)
    r = pay(idem=str(uuid.uuid4()), headers=J, json={"targetInstallmentId": i0["id"], "amount": amt, "postedAt": "2026-06-14", "reference": "R", "channel": "UPI"})
    check("EC-056b", r.status_code in (200, 201), f"UPI channel payment -> {r.status_code} (UPI should be valid)")
    # EC-054 double-pay same installment
    r = pay(idem=str(uuid.uuid4()), headers=J, json={"targetInstallmentId": i0["id"], "amount": amt, "postedAt": "2026-06-14", "reference": "R2", "channel": "NEFT"})
    check("EC-054", 400 <= r.status_code < 500, f"double-pay installment 1 -> {r.status_code} (want 4xx)")

# ===== EC-109 concurrent disbursement (race) =====
appc = create_app()
if appc.ok:
    aci = appc.json()["id"]
    for dt in ["PAN_CARD", "AADHAAR_FILE", "BANK_STATEMENT", "ADDRESS_PROOF", "INCOME_PROOF", "SELFIE_PHOTOGRAPH", "KFS", "LOAN_AGREEMENT"]:
        req("POST", f"/api/v1/lsp/loan-applications/{aci}/documents", token=lt,
            files={"file": (f"{dt.lower()}.pdf", io.BytesIO(PDF), "application/pdf")}, data={"documentType": dt})
    for _ in range(30):
        if req("GET", f"/api/v1/internal/ops/loan-applications/{aci}", token=t).json().get("status") == "APPROVED_PENDING_DISBURSAL":
            break
        time.sleep(1)
    import concurrent.futures as cf
    def fire():
        return req("POST", f"/api/v1/internal/ops/loan-applications/{aci}/disbursement-requests", token=t).status_code
    with cf.ThreadPoolExecutor(max_workers=2) as ex:
        codes = list(ex.map(lambda _: fire(), range(2)))
    # at most one should "win"; neither should 500
    check("EC-109", all(c != 500 for c in codes), f"concurrent disburse codes={codes} (no 500)")
    final = req("GET", f"/api/v1/internal/ops/loan-applications/{aci}", token=t).json().get("status")
    info("EC-109.final", f"final status after concurrent disburse = {final}")

summary()
raise SystemExit(1 if FAIL else 0)
