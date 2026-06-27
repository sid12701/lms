"""Focused payment-validation negatives on a freshly disbursed loan."""
from __future__ import annotations
import io, json, time, uuid
from client import (req, admin_token, check, info, summary, rpan, raadhaar,
                    rmobile, rifsc, racct, rid, FAIL)

ctx = json.load(open("indep_ctx.json"))
t = admin_token(); J = {"Content-Type": "application/json"}
PDF = b"%PDF-1.4\n1 0 obj<</Type/Catalog>>endobj\ntrailer<</Root 1 0 R>>\n%%EOF\n"


def lsp_token():
    r = req("POST", "/api/v1/auth/token", json={"clientId": ctx["client_id"], "clientSecret": ctx["client_secret"]})
    if r.ok: return r.json()["accessToken"]
    cl = req("GET", "/api/v1/internal/admin/api-clients", token=t).json()
    row = [c for c in cl if c["clientId"] == ctx["client_id"]][0]
    ns = req("POST", f"/api/v1/internal/admin/api-clients/{row['id']}/rotate-secret", token=t).json()["clientSecret"]
    ctx["client_secret"] = ns
    return req("POST", "/api/v1/auth/token", json={"clientId": ctx["client_id"], "clientSecret": ns}).json()["accessToken"]


lt = lsp_token()
name = "Pay BL " + uuid.uuid4().hex[:6]
body = {"lspId": ctx["lsp_id"], "productId": ctx["product_id"], "lspLoanId": rid("PN"), "fullName": name,
        "emailAddress": f"pn{uuid.uuid4().hex[:6]}@e.com", "mobileNumber": rmobile(), "dob": "1990-05-15",
        "gender": "MALE", "maritalStatus": "SINGLE", "fatherName": "P", "aadharNumber": raadhaar(), "panNumber": rpan(),
        "loanAmount": 150000, "interestRate": 14.5, "loanTenure": 12, "addressLine1": "1 St", "addressCity": "Mumbai",
        "addressState": "MH", "addressZipcode": "400001", "employmentStatus": "SALARIED", "organizationName": "C",
        "monthlyIncome": 60000, "annualIncome": 720000, "bankAccountNumber": racct(), "bankName": "HDFC",
        "ifscCode": rifsc(), "accountHolderName": name, "referencePersonName": "R", "referencePersonNumber": rmobile()}
app = req("POST", "/api/v1/lsp/loan-applications", token=lt, idem=str(uuid.uuid4()), json=body).json()["id"]
for dt in ["PAN_CARD", "AADHAAR_FILE", "BANK_STATEMENT", "ADDRESS_PROOF", "INCOME_PROOF", "SELFIE_PHOTOGRAPH", "KFS", "LOAN_AGREEMENT"]:
    req("POST", f"/api/v1/lsp/loan-applications/{app}/documents", token=lt,
        files={"file": (f"{dt.lower()}.pdf", io.BytesIO(PDF), "application/pdf")}, data={"documentType": dt})
for _ in range(40):
    if req("GET", f"/api/v1/internal/ops/loan-applications/{app}", token=t).json().get("status") == "APPROVED_PENDING_DISBURSAL":
        break
    time.sleep(1)
req("POST", f"/api/v1/internal/ops/loan-applications/{app}/disbursement-requests", token=t)
req("POST", f"/api/v1/internal/ops/loan-applications/{app}/disbursement-requests/mock-outcome", token=t, headers=J, json={"outcome": "DISBURSED"})
acct = None
for _ in range(40):
    d = req("GET", f"/api/v1/internal/ops/loan-applications/{app}", token=t).json()
    if d.get("status") in ("DISBURSED", "UNDER_REPAYMENT"):
        acct = d.get("loanAccountId"); break
    time.sleep(2)
check("setup.disbursed", bool(acct), f"loan disbursed, acct={acct}")
insts = sorted(req("GET", f"/api/v1/lsp/loans/{acct}/repayment-schedule", token=lt).json(), key=lambda x: x["installmentNumber"])
i0 = insts[0]; amt = float(i0["installmentAmount"])
pay = lambda **kw: req("POST", f"/api/v1/internal/ops/loan-applications/{app}/payments", token=t, **kw)

# EC-051 missing Idempotency-Key
r = pay(headers=J, json={"targetInstallmentId": i0["id"], "amount": amt, "postedAt": "2026-06-14", "reference": "R", "channel": "NEFT"})
check("EC-051", r.status_code == 400, f"no Idempotency-Key -> {r.status_code}")
# EC-052 missing targetInstallmentId
r = pay(idem=str(uuid.uuid4()), headers=J, json={"amount": amt, "postedAt": "2026-06-14", "reference": "R", "channel": "NEFT"})
check("EC-052", r.status_code == 400, f"no targetInstallmentId -> {r.status_code}")
# EC-053 partial amount
r = pay(idem=str(uuid.uuid4()), headers=J, json={"targetInstallmentId": i0["id"], "amount": round(amt/2, 2), "postedAt": "2026-06-14", "reference": "R", "channel": "NEFT"})
check("EC-053", 400 <= r.status_code < 500, f"partial amount -> {r.status_code}")
# EC-056 invalid channel
r = pay(idem=str(uuid.uuid4()), headers=J, json={"targetInstallmentId": i0["id"], "amount": amt, "postedAt": "2026-06-14", "reference": "R", "channel": "BITCOIN"})
check("EC-056", r.status_code == 400, f"invalid channel BITCOIN -> {r.status_code}")
# EC-056b UPI valid -> pays installment 1
k = str(uuid.uuid4())
r = pay(idem=k, headers=J, json={"targetInstallmentId": i0["id"], "amount": amt, "postedAt": "2026-06-14", "reference": "R", "channel": "UPI"})
check("EC-056b", r.status_code in (200, 201), f"UPI channel valid -> {r.status_code}")
# EC-055 idempotent payment replay (same key+body -> same/200, no double count)
r2 = pay(idem=k, headers=J, json={"targetInstallmentId": i0["id"], "amount": amt, "postedAt": "2026-06-14", "reference": "R", "channel": "UPI"})
check("EC-055", r2.status_code in (200, 201), f"payment idempotent replay -> {r2.status_code}")
# EC-054 double-pay installment 1 (new key)
r = pay(idem=str(uuid.uuid4()), headers=J, json={"targetInstallmentId": i0["id"], "amount": amt, "postedAt": "2026-06-14", "reference": "R2", "channel": "NEFT"})
check("EC-054", 400 <= r.status_code < 500, f"double-pay installment 1 -> {r.status_code}")

summary()
raise SystemExit(1 if FAIL else 0)
