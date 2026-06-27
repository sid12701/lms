"""Diagnostics to resolve ambiguous gap-test results before recording."""
from __future__ import annotations
import io, json, time, uuid, re
from client import req, login, ADMIN_USER, ADMIN_PASS, rpan, raadhaar, rmobile, rifsc, racct, rid

# fresh admin token (bypass cache)
t = login(ADMIN_USER, ADMIN_PASS)[1]["accessToken"]
ctx = json.load(open("indep_ctx.json"))
J = {"Content-Type": "application/json"}
LSP, PROD = ctx["lsp_id"], ctx["product_id"]
PDF = b"%PDF-1.4\n1 0 obj<</Type/Catalog>>endobj\ntrailer<</Root 1 0 R>>\n%%EOF\n"
DOCS = ["PAN_CARD", "AADHAAR_FILE", "BANK_STATEMENT", "ADDRESS_PROOF", "INCOME_PROOF", "SELFIE_PHOTOGRAPH", "KFS", "LOAN_AGREEMENT"]
lt = req("POST", "/api/v1/auth/token", json={"clientId": ctx["client_id"], "clientSecret": ctx["client_secret"]}).json()["accessToken"]


def lbody(**ov):
    n = "Diag " + uuid.uuid4().hex[:6]
    acct = racct(); ifsc = rifsc()
    b = {"lspId": LSP, "productId": PROD, "lspLoanId": rid("DG"), "fullName": n, "emailAddress": f"d{uuid.uuid4().hex[:6]}@e.com",
         "mobileNumber": rmobile(), "dob": "1990-05-15", "gender": "MALE", "maritalStatus": "SINGLE", "fatherName": "P",
         "aadharNumber": raadhaar(), "panNumber": rpan(), "loanAmount": 150000, "interestRate": 14.5, "loanTenure": 12,
         "addressLine1": "1 St", "addressCity": "Mumbai", "addressState": "MH", "addressZipcode": "400001",
         "employmentStatus": "SALARIED", "organizationName": "C", "monthlyIncome": 60000, "annualIncome": 720000,
         "bankAccountNumber": acct, "bankName": "HDFC", "ifscCode": ifsc, "accountHolderName": n,
         "referencePersonName": "R", "referencePersonNumber": rmobile()}
    b.update(ov); return b, n, acct, ifsc


def create():
    body, n, acct, ifsc = lbody()
    r = req("POST", "/api/v1/lsp/loan-applications", token=lt, idem=str(uuid.uuid4()), json=body)
    return (r.json()["id"], n, acct, ifsc, r.json().get("borrowerId")) if r.ok else (None, n, acct, ifsc, None)


# --- 1. EC-107 with correct mode LSP_PROVIDED + bad sum ---
app, n, acct, ifsc, bid = create()
bad = {"mode": "LSP_PROVIDED", "installments": [
    {"installmentNumber": 1, "dueDate": "2026-07-14", "openingPrincipal": 150000, "principalDue": 1000,
     "interestDue": 100, "installmentAmount": 1100, "closingPrincipal": 149000}]}
r = req("PUT", f"/api/v1/lsp/loan-applications/{app}/repayment-schedule", token=lt, headers=J, json=bad)
print(f"EC-107 LSP_PROVIDED bad sum -> {r.status_code}: {r.text[:200]}")

# --- 2. bank-check valid vs mismatch (fresh loan, known on-file details) ---
app2, n2, acct2, ifsc2, bid2 = create()
okc = req("POST", f"/api/v1/lsp/loan-applications/{app2}/disbursement-bank-check", token=lt, headers=J,
          json={"disbursalAmount": 150000, "bankAccountNumber": acct2, "ifscCode": ifsc2, "accountHolderName": n2})
print(f"EC-025 bank-check MATCH -> {okc.status_code}: {str(okc.text)[:160]}")
misc = req("POST", f"/api/v1/lsp/loan-applications/{app2}/disbursement-bank-check", token=lt, headers=J,
           json={"disbursalAmount": 150000, "bankAccountNumber": acct2, "ifscCode": ifsc2, "accountHolderName": "Zzz Wrong Name"})
print(f"EC-048 bank-check NAME-MISMATCH -> {misc.status_code}: {str(misc.text)[:220]}")
misc2 = req("POST", f"/api/v1/lsp/loan-applications/{app2}/disbursement-bank-check", token=lt, headers=J,
            json={"disbursalAmount": 150000, "bankAccountNumber": racct(), "ifscCode": rifsc(), "accountHolderName": n2})
print(f"EC-048 bank-check ACCT-MISMATCH -> {misc2.status_code}: {str(misc2.text)[:220]}")

# --- 3. doc download non-existent app + missing type (fresh token) ---
r1 = req("GET", f"/api/v1/internal/ops/loan-applications/{uuid.uuid4()}/kyc-documents/PAN_CARD/content", token=t)
print(f"EC-044 download random app -> {r1.status_code}")
app3, *_ = create()
r2 = req("GET", f"/api/v1/internal/ops/loan-applications/{app3}/kyc-documents/PAN_CARD/content", token=t)
print(f"EC-045 download missing-type (real app, no docs) -> {r2.status_code}")

# --- 4. EC-112 bank acct masking on a FRESH borrower (admin 360) ---
if bid2:
    b = req("GET", f"/api/v1/internal/admin/borrowers/{bid2}", token=t).json()
    print(f"EC-112 fresh borrower bankAccountNumberMasked={b.get('bankAccountNumberMasked')!r} (on-file acct was {acct2}) aadharNumberMasked={b.get('aadharNumberMasked')!r}")

# --- 5. EC-049 mock FAILED sequence (is first 500 a real bug?) ---
app5, *_ = create()
for dt in DOCS:
    req("POST", f"/api/v1/lsp/loan-applications/{app5}/documents", token=lt,
        files={"file": (f"{dt.lower()}.pdf", io.BytesIO(PDF), "application/pdf")}, data={"documentType": dt})
for _ in range(40):
    if req("GET", f"/api/v1/internal/ops/loan-applications/{app5}", token=t).json().get("status") == "APPROVED_PENDING_DISBURSAL":
        break
    time.sleep(1)
init = req("POST", f"/api/v1/internal/ops/loan-applications/{app5}/disbursement-requests", token=t)
print(f"EC-049 initiate -> {init.status_code}")
f1 = req("POST", f"/api/v1/internal/ops/loan-applications/{app5}/disbursement-requests/mock-outcome", token=t, headers=J, json={"outcome": "FAILED"})
print(f"EC-049 mock FAILED #1 -> {f1.status_code}: {f1.text[:200]}")
st = req("GET", f"/api/v1/internal/ops/loan-applications/{app5}", token=t).json().get("status")
print(f"EC-049 status after 1 FAILED -> {st}")
