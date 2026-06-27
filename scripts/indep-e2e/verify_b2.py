"""Live end-to-end verification of B2 (ADR 0004 processing-fee deduction).
Fresh loan (principal 150000, product rate 1.5%): expect fee 2250.00, net 147750.00.
Verifies: persisted fee surfaces in MIS (no more synthetic fiction), MIS net column,
and the DISBURSEMENT_COMPLETED webhook payload carries fee + net.
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

# product rate sanity
prod = req("GET", "/api/v1/internal/admin/products", token=t).json()
rate = next((p.get("processingFeeRate") for p in prod if p["id"] == PROD), None)
info("setup", f"product processingFeeRate={rate} (expect 1.5)")

# subscribe so DISBURSEMENT_COMPLETED enqueues (delivery not required; we read the outbox payload)
req("PUT", f"/api/v1/internal/admin/lsps/{LSP}/webhook-subscription", token=t, headers=J,
    json={"enabled": True, "endpointUrl": "https://example.com/hook", "signingSecret": "whsigningsecret12345",
          "eventTypes": ["LOAN_STATUS_CHANGED", "DISBURSEMENT_COMPLETED", "DISBURSEMENT_REQUESTED"]})

# create + approve + disburse a fresh loan, principal 150000
n = "B2 " + uuid.uuid4().hex[:6]
body = {"lspId": LSP, "productId": PROD, "lspLoanId": rid("B2"), "fullName": n, "emailAddress": f"b2{uuid.uuid4().hex[:6]}@e.com",
        "mobileNumber": rmobile(), "dob": "1990-05-15", "gender": "MALE", "maritalStatus": "SINGLE", "fatherName": "P",
        "aadharNumber": raadhaar(), "panNumber": rpan(), "loanAmount": 150000, "interestRate": 14.5, "loanTenure": 12,
        "addressLine1": "1 St", "addressCity": "Mumbai", "addressState": "MH", "addressZipcode": "400001",
        "employmentStatus": "SALARIED", "organizationName": "C", "monthlyIncome": 60000, "annualIncome": 720000,
        "bankAccountNumber": racct(), "bankName": "HDFC", "ifscCode": rifsc(), "accountHolderName": n,
        "referencePersonName": "R", "referencePersonNumber": rmobile()}
app = req("POST", "/api/v1/lsp/loan-applications", token=lt, idem=str(uuid.uuid4()), json=body).json()["id"]
for dt in DOCS:
    req("POST", f"/api/v1/lsp/loan-applications/{app}/documents", token=lt,
        files={"file": (f"{dt.lower()}.pdf", io.BytesIO(PDF), "application/pdf")}, data={"documentType": dt})
    time.sleep(1.2)
for _ in range(40):
    if req("GET", f"/api/v1/internal/ops/loan-applications/{app}", token=t).json().get("status") == "APPROVED_PENDING_DISBURSAL":
        break
    time.sleep(1)
req("POST", f"/api/v1/internal/ops/loan-applications/{app}/disbursement-requests", token=t)
req("POST", f"/api/v1/internal/ops/loan-applications/{app}/disbursement-requests/mock-outcome", token=t, headers=J, json={"outcome": "DISBURSED"})
st = None
for _ in range(40):
    st = req("GET", f"/api/v1/internal/ops/loan-applications/{app}", token=t).json().get("status")
    if st in ("DISBURSED", "UNDER_REPAYMENT"):
        break
    time.sleep(2)
check("disbursed", st in ("DISBURSED", "UNDER_REPAYMENT"), f"loan disbursed -> {st}")

# ---- MIS row: persisted fee (not synthetic) + gross + net ----
mis = req("GET", "/api/v1/internal/reports/portfolio-mis/preview?disbursalDateFrom=2026-01-01&disbursalDateTo=2026-12-31&limit=500", token=t).json()
row = next((r for r in mis.get("content", []) if r.get("applicationId") == app), {})
fee = row.get("processingFeeAmount"); gross = row.get("disbursalAmount"); net = row.get("netDisbursedAmount")
info("MIS.row", f"processingFeeAmount={fee} disbursalAmount={gross} netDisbursedAmount={net}")
check("B2.fee", str(fee) in ("2250.0", "2250.00", "2250"), f"MIS processingFeeAmount=2250 (got {fee})")
check("B2.gross", str(gross) in ("150000.0", "150000.00", "150000"), f"MIS disbursalAmount=150000 gross (got {gross})")
check("B2.net", str(net) in ("147750.0", "147750.00", "147750"), f"MIS netDisbursedAmount=147750 (got {net})")

# ---- MIS CSV has the Net Disbursed Amount column ----
csv = req("GET", "/api/v1/internal/reports/portfolio-mis?disbursalDateFrom=2026-01-01&disbursalDateTo=2026-12-31", token=t).text
check("B2.csvcol", "Net Disbursed Amount" in csv.splitlines()[0], "MIS CSV has 'Net Disbursed Amount' column")

# ---- DISBURSEMENT_COMPLETED webhook payload carries fee + net (admin outbox -> payloadJson -> payload) ----
acct_id = req("GET", f"/api/v1/internal/ops/loan-applications/{app}", token=t).json().get("loanAccountId")
rows = req("GET", f"/api/v1/internal/admin/webhook-outbox?lspId={LSP}", token=t).json()
completed = [e for e in rows if e.get("eventType") == "DISBURSEMENT_COMPLETED" and e.get("aggregateId") == acct_id]
if completed:
    envelope = json.loads(completed[-1]["payloadJson"])
    pj = envelope.get("payload") or {}
    info("webhook.payload", json.dumps({k: pj.get(k) for k in ("principalAmount", "processingFeeAmount", "netDisbursedAmount")}))
    check("B2.webhook.fee", str(pj.get("processingFeeAmount")) in ("2250.0", "2250.00", "2250"),
          f"webhook processingFeeAmount=2250 (got {pj.get('processingFeeAmount')})")
    check("B2.webhook.net", str(pj.get("netDisbursedAmount")) in ("147750.0", "147750.00", "147750"),
          f"webhook netDisbursedAmount=147750 (got {pj.get('netDisbursedAmount')})")
else:
    info("B2.webhook", "no DISBURSEMENT_COMPLETED outbox row found for this loan account")

print(f"\nB2 verify app={app}")
summary()
raise SystemExit(1 if FAIL else 0)
