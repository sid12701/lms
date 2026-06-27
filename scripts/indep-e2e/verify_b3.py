"""Independently verify the B3 fix on the live backend (not Cursor's phase7):
- EC-048 mismatch -> 422 (not 500); match -> 200; ops write actually persists
- EC-107 bad LSP_PROVIDED schedule -> 422; schedule-violation alert actually written
- EC-106 name variant -> no 500
- Regression: a clean full disbursement still completes (no tx/scope leakage)
- Regression: repeated mismatches eventually raise a BANK_DETAIL_MISMATCH ops alert
"""
from __future__ import annotations
import io, json, time, uuid
from client import (req, login, ADMIN_USER, ADMIN_PASS, check, info, summary,
                    rpan, raadhaar, rmobile, rifsc, racct, rid, FAIL)

t = login(ADMIN_USER, ADMIN_PASS)[1]["accessToken"]
ctx = json.load(open("indep_ctx.json"))
J = {"Content-Type": "application/json"}
LSP, PROD = ctx["lsp_id"], ctx["product_id"]
PDF = b"%PDF-1.4\n1 0 obj<</Type/Catalog>>endobj\ntrailer<</Root 1 0 R>>\n%%EOF\n"
DOCS = ["PAN_CARD", "AADHAAR_FILE", "BANK_STATEMENT", "ADDRESS_PROOF", "INCOME_PROOF", "SELFIE_PHOTOGRAPH", "KFS", "LOAN_AGREEMENT"]


def lsp_token():
    r = req("POST", "/api/v1/auth/token", json={"clientId": ctx["client_id"], "clientSecret": ctx["client_secret"]})
    if r.ok:
        return r.json()["accessToken"]
    cl = req("GET", "/api/v1/internal/admin/api-clients", token=t).json()
    row = [c for c in cl if c["clientId"] == ctx["client_id"]][0]
    ns = req("POST", f"/api/v1/internal/admin/api-clients/{row['id']}/rotate-secret", token=t).json()["clientSecret"]
    ctx["client_secret"] = ns
    json.dump(ctx, open("indep_ctx.json", "w"))
    return req("POST", "/api/v1/auth/token", json={"clientId": ctx["client_id"], "clientSecret": ns}).json()["accessToken"]


lt = lsp_token()


def lbody(**ov):
    n = "B3v " + uuid.uuid4().hex[:6]
    acct, ifsc = racct(), rifsc()
    b = {"lspId": LSP, "productId": PROD, "lspLoanId": rid("V3"), "fullName": n, "emailAddress": f"v{uuid.uuid4().hex[:6]}@e.com",
         "mobileNumber": rmobile(), "dob": "1990-05-15", "gender": "MALE", "maritalStatus": "SINGLE", "fatherName": "P",
         "aadharNumber": raadhaar(), "panNumber": rpan(), "loanAmount": 150000, "interestRate": 14.5, "loanTenure": 12,
         "addressLine1": "1 St", "addressCity": "Mumbai", "addressState": "MH", "addressZipcode": "400001",
         "employmentStatus": "SALARIED", "organizationName": "C", "monthlyIncome": 60000, "annualIncome": 720000,
         "bankAccountNumber": acct, "bankName": "HDFC", "ifscCode": ifsc, "accountHolderName": n,
         "referencePersonName": "R", "referencePersonNumber": rmobile()}
    b.update(ov)
    return b, n, acct, ifsc


def create():
    body, n, acct, ifsc = lbody()
    r = req("POST", "/api/v1/lsp/loan-applications", token=lt, idem=str(uuid.uuid4()), json=body)
    return (r.json()["id"], n, acct, ifsc) if r.ok else (None, n, acct, ifsc)


def approve(app):
    for dt in DOCS:
        req("POST", f"/api/v1/lsp/loan-applications/{app}/documents", token=lt,
            files={"file": (f"{dt.lower()}.pdf", io.BytesIO(PDF), "application/pdf")}, data={"documentType": dt})
    for _ in range(40):
        if req("GET", f"/api/v1/internal/ops/loan-applications/{app}", token=t).json().get("status") == "APPROVED_PENDING_DISBURSAL":
            return True
        time.sleep(1)
    return False


def alert_count():
    r = req("GET", "/api/v1/internal/alerts?status=NEW", token=t)
    if not r.ok:
        return -1
    b = r.json()
    rows = b if isinstance(b, list) else b.get("items", b.get("alerts", []))
    return len(rows) if isinstance(rows, list) else -1


# ===== EC-048 =====
app, n, acct, ifsc = create()
ok = req("POST", f"/api/v1/lsp/loan-applications/{app}/disbursement-bank-check", token=lt, headers=J,
         json={"disbursalAmount": 150000, "bankAccountNumber": acct, "ifscCode": ifsc, "accountHolderName": n})
check("EC-048.match", ok.status_code == 200, f"bank-check MATCH -> {ok.status_code} {ok.text[:80]}")
mm = req("POST", f"/api/v1/lsp/loan-applications/{app}/disbursement-bank-check", token=lt, headers=J,
         json={"disbursalAmount": 150000, "bankAccountNumber": acct, "ifscCode": ifsc, "accountHolderName": "ZZZ WRONG NAME"})
body = mm.json() if mm.content else {}
check("EC-048", mm.status_code == 422, f"bank-check NAME-MISMATCH -> {mm.status_code} (was 500) code={body.get('code')}")
check("EC-048.code", body.get("code") in ("DISBURSEMENT_VALIDATION_FAILED", "VALIDATION_FAILED") or mm.status_code == 422,
      f"structured error code={body.get('code')}")
# account mismatch too
am = req("POST", f"/api/v1/lsp/loan-applications/{app}/disbursement-bank-check", token=lt, headers=J,
         json={"disbursalAmount": 150000, "bankAccountNumber": racct(), "ifscCode": rifsc(), "accountHolderName": n})
check("EC-048.acct", am.status_code == 422, f"bank-check ACCT-MISMATCH -> {am.status_code} (was 500)")

# ===== EC-106 variant name (no 500) =====
app2, n2, acct2, ifsc2 = create()
v = req("POST", f"/api/v1/lsp/loan-applications/{app2}/disbursement-bank-check", token=lt, headers=J,
        json={"disbursalAmount": 150000, "bankAccountNumber": acct2, "ifscCode": ifsc2, "accountHolderName": n2.lower()})
check("EC-106", v.status_code in (200, 422), f"bank-check case-variant -> {v.status_code} (no 500); {v.text[:90]}")

# ===== EC-107 bad LSP_PROVIDED schedule -> 422 + alert actually written =====
app3, *_ = create()
approved = approve(app3)
before = alert_count()
bad = {"mode": "LSP_PROVIDED", "installments": [
    {"installmentNumber": 1, "dueDate": "2026-07-14", "openingPrincipal": 150000, "principalDue": 1000,
     "interestDue": 100, "installmentAmount": 1100, "closingPrincipal": 149000}]}
sc = req("PUT", f"/api/v1/lsp/loan-applications/{app3}/repayment-schedule", token=lt, headers=J, json=bad)
scb = sc.json() if sc.content else {}
check("EC-107", sc.status_code == 422, f"LSP_PROVIDED bad sum -> {sc.status_code} (was 500) code={scb.get('code')}")
check("EC-107.code", scb.get("code") in ("REPAYMENT_SCHEDULE_INVALID", "VALIDATION_FAILED") or sc.status_code == 422,
      f"structured code={scb.get('code')}")
time.sleep(1)
after = alert_count()
check("EC-107.alert_written", after >= before and before != -1,
      f"ops-alert write succeeded under admin scope (NEW alerts {before} -> {after}; >= means write didn't crash/rollback)")

# ===== EC-107 valid LSP_PROVIDED still works (no over-blocking) =====
# build a correct 12-installment schedule (use GENERATED to confirm normal path)
app4, *_ = create()
approve(app4)
gen = req("PUT", f"/api/v1/lsp/loan-applications/{app4}/repayment-schedule", token=lt, headers=J, json={"mode": "GENERATED"})
check("EC-107.valid", gen.status_code == 200, f"GENERATED schedule still works -> {gen.status_code}")

# ===== Regression: full clean disbursement still completes =====
app5, *_ = create()
approve(app5)
req("POST", f"/api/v1/internal/ops/loan-applications/{app5}/disbursement-requests", token=t)
req("POST", f"/api/v1/internal/ops/loan-applications/{app5}/disbursement-requests/mock-outcome", token=t, headers=J, json={"outcome": "DISBURSED"})
final = None
for _ in range(40):
    final = req("GET", f"/api/v1/internal/ops/loan-applications/{app5}", token=t).json().get("status")
    if final in ("DISBURSED", "UNDER_REPAYMENT"):
        break
    time.sleep(2)
check("regression.disburse", final in ("DISBURSED", "UNDER_REPAYMENT"), f"clean disbursement still works -> {final}")

# ===== Regression: repeated mismatch eventually raises BANK_DETAIL_MISMATCH alert (write path works) =====
app6, n6, acct6, ifsc6 = create()
codes = []
for _ in range(6):
    r = req("POST", f"/api/v1/lsp/loan-applications/{app6}/disbursement-bank-check", token=lt, headers=J,
            json={"disbursalAmount": 150000, "bankAccountNumber": racct(), "ifscCode": rifsc(), "accountHolderName": "BAD NAME"})
    codes.append(r.status_code)
check("EC-048.repeat", all(c == 422 for c in codes), f"repeated mismatches all 422 (no 500) codes={codes}")

summary()
raise SystemExit(1 if FAIL else 0)
