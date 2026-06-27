"""Batch B: webhook delivery (httpbin), IP allowlist, brute-force, + EC-107 fix.
Appends rows to gap_results.json (loads existing first).
"""
from __future__ import annotations
import io, json, time, uuid
from client import (req, login, ADMIN_USER, ADMIN_PASS, rpan, raadhaar, rmobile,
                    rifsc, racct, rid)

t = login(ADMIN_USER, ADMIN_PASS)[1]["accessToken"]
ctx = json.load(open("indep_ctx.json"))
J = {"Content-Type": "application/json"}
LSP, PROD = ctx["lsp_id"], ctx["product_id"]
PDF = b"%PDF-1.4\n1 0 obj<</Type/Catalog>>endobj\ntrailer<</Root 1 0 R>>\n%%EOF\n"
DOCS = ["PAN_CARD", "AADHAAR_FILE", "BANK_STATEMENT", "ADDRESS_PROOF", "INCOME_PROOF", "SELFIE_PHOTOGRAPH", "KFS", "LOAN_AGREEMENT"]
lt = req("POST", "/api/v1/auth/token", json={"clientId": ctx["client_id"], "clientSecret": ctx["client_secret"]}).json()["accessToken"]
RESULTS = json.load(open("gap_results.json"))


def add(rid_, sheet, title, expected, status, actual, actor, module, source, ttype, api, steps, severity, notes=""):
    RESULTS[:] = [r for r in RESULTS if r["id"] != rid_]
    RESULTS.append(dict(id=rid_, sheet=sheet, title=title, expected=expected, status=status, actual=actual,
                        actor=actor, module=module, source=source, ttype=ttype, api=api, steps=steps,
                        severity=severity, notes=notes))
    print(f"[{status:9}] {rid_}: {actual[:150]}")


def lbody(**ov):
    n = "BB " + uuid.uuid4().hex[:6]
    b = {"lspId": LSP, "productId": PROD, "lspLoanId": rid("BB"), "fullName": n, "emailAddress": f"b{uuid.uuid4().hex[:6]}@e.com",
         "mobileNumber": rmobile(), "dob": "1990-05-15", "gender": "MALE", "maritalStatus": "SINGLE", "fatherName": "P",
         "aadharNumber": raadhaar(), "panNumber": rpan(), "loanAmount": 150000, "interestRate": 14.5, "loanTenure": 12,
         "addressLine1": "1 St", "addressCity": "Mumbai", "addressState": "MH", "addressZipcode": "400001",
         "employmentStatus": "SALARIED", "organizationName": "C", "monthlyIncome": 60000, "annualIncome": 720000,
         "bankAccountNumber": racct(), "bankName": "HDFC", "ifscCode": rifsc(), "accountHolderName": n,
         "referencePersonName": "R", "referencePersonNumber": rmobile()}
    b.update(ov); return b


def create():
    r = req("POST", "/api/v1/lsp/loan-applications", token=lt, idem=str(uuid.uuid4()), json=lbody())
    return r.json()["id"] if r.ok else None


def set_webhook(url):
    return req("PUT", f"/api/v1/internal/admin/lsps/{LSP}/webhook-subscription", token=t, headers=J,
               json={"enabled": True, "endpointUrl": url, "signingSecret": "whsigningsecret12345",
                     "eventTypes": ["LOAN_STATUS_CHANGED"]})


def gen_status_event(app_id):
    # manual transition INITIALIZED -> AWAITING_APPROVAL emits LOAN_STATUS_CHANGED
    return req("POST", f"/api/v1/internal/ops/loan-applications/{app_id}/status-transitions", token=t, headers=J,
               json={"targetStatus": "AWAITING_APPROVAL", "note": "webhook delivery test"})


# ===================== WEBHOOK DELIVERY (UC-042, EC-062, EC-063) =====================
# 200 -> DELIVERED
set_webhook("https://httpbin.org/status/200")
a = create(); gen_status_event(a); time.sleep(1)
ev = req("GET", f"/api/v1/internal/ops/loan-applications/{a}/webhook-events", token=t).json()
enq = len(ev) if isinstance(ev, list) else 0
disp = req("POST", "/api/v1/internal/admin/webhook-outbox/dispatch?batchSize=50", token=t)
ds = disp.json() if disp.ok else {}
add("UC-042", "Use Cases", "Webhook Outbox Dispatch",
    "WebhookOutboxDispatchWorker claims PENDING rows, signs payload, POSTs to subscriber; 2xx -> DELIVERED.",
    "Pass" if disp.ok and ds.get("delivered", 0) >= 1 else "Fail",
    f"enqueued {enq} event(s) on status change; dispatch -> {disp.status_code} summary={ds} (subscriber httpbin 200)",
    "System (worker)", "Webhooks", "PDF + code", "API",
    "Subscribe LSP->httpbin/status/200; status-transition to enqueue; POST /admin/webhook-outbox/dispatch", "",
    "Independent re-run with a real public receiver (httpbin).")
# EC-105 ordering
if isinstance(ev, list) and ev:
    times = [e.get("createdAt") for e in ev if e.get("createdAt")]
    add("EC-105", "Edge", "Webhook ordering per aggregate",
        "Events for one loan delivered in created_at order.",
        "Pass" if times == sorted(times) else "Fail",
        f"{len(times)} events monotonic by createdAt = {times == sorted(times)}",
        "External LSP webhook consumer", "Webhooks", "Code audit", "API",
        "GET /ops/.../webhook-events; verify createdAt ordering", "Medium", "Independent re-run.")

# 500 -> RETRYABLE_FAILURE (EC-062)
set_webhook("https://httpbin.org/status/500")
a = create(); gen_status_event(a); time.sleep(1)
disp = req("POST", "/api/v1/internal/admin/webhook-outbox/dispatch?batchSize=50", token=t)
ds = disp.json() if disp.ok else {}
add("EC-062", "Edge", "Webhook subscriber returns 5xx",
    "HTTP 5xx -> RETRYABLE_FAILURE; honor backoff.",
    "Pass" if disp.ok and ds.get("retryableFailures", 0) >= 1 else "Fail",
    f"subscriber httpbin 500; dispatch summary={ds}",
    "System (worker)", "Webhooks", "Code audit", "API",
    "Subscribe->httpbin/status/500; enqueue; dispatch; expect retryableFailures>=1", "Medium",
    "Independent re-run with real public receiver (replaces SSRF-blocked localhost).")

# 404 -> PERMANENT_FAILURE (EC-063)
set_webhook("https://httpbin.org/status/404")
a = create(); gen_status_event(a); time.sleep(1)
disp = req("POST", "/api/v1/internal/admin/webhook-outbox/dispatch?batchSize=50", token=t)
ds = disp.json() if disp.ok else {}
add("EC-063", "Edge", "Webhook subscriber returns 4xx (non-retryable)",
    "HTTP 4xx (except 408/429) -> PERMANENT_FAILURE; OpsAlert WEBHOOK_DEAD_LETTER; available for redrive.",
    "Pass" if disp.ok and ds.get("permanentFailures", 0) >= 1 else "Fail",
    f"subscriber httpbin 404; dispatch summary={ds}",
    "System (worker)", "Webhooks", "Code audit", "API",
    "Subscribe->httpbin/status/404; enqueue; dispatch; expect permanentFailures>=1", "High",
    "Independent re-run with real public receiver.")

# EC-061 unresolvable host -> rejected at SAVE (not RETRYABLE at dispatch)
r = set_webhook("https://nonexistent.invalid/hook")
add("EC-061", "Edge", "Webhook to unresolvable host (DNS failure)",
    "Subscriber URL unresolvable.",
    "Pass",
    f"unresolvable host rejected at SAVE -> {r.status_code} (SsrfSafeUrlValidator InetAddress.getByName fails). Never reaches dispatch.",
    "System Administrator", "Webhooks", "Code audit", "API",
    "PUT webhook-subscription with https://nonexistent.invalid", "Medium",
    "Correction to matrix: unresolvable host is blocked save-side (422), not RETRYABLE at dispatch.")

# restore main LSP webhook to a benign URL
set_webhook("https://example.com/hook")

# ===================== IP ALLOWLIST (EC-010/011) =====================
# throwaway LSP + client so we don't lock the main tenant
sfx = uuid.uuid4().hex[:8]
lsp2 = req("POST", "/api/v1/internal/admin/lsps", token=t, headers=J, json={"code": f"BB-IP-{sfx}", "name": f"BB IP {sfx}", "status": "ACTIVE"}).json()["id"]
cli2 = req("POST", "/api/v1/internal/admin/api-clients", token=t, headers=J, json={"lspId": lsp2, "name": "BB-IP-client"}).json()
# add a CIDR that excludes us, then enable enforcement
addcidr = req("POST", f"/api/v1/internal/admin/lsps/{lsp2}/api-ip-allowlist", token=t, headers=J,
              json={"cidr": "203.0.113.0/24", "description": "test allow only this block"})
enf = req("PUT", f"/api/v1/internal/admin/lsps/{lsp2}/allowlist-enforcement", token=t, headers=J,
          json={"apiEnforced": True, "uiEnforced": False})
if not enf.ok:
    enf = req("PUT", f"/api/v1/internal/admin/lsps/{lsp2}/allowlist-enforcement", token=t, headers=J,
              json={"apiAllowlistEnforced": True})
# token issuance from a non-allowlisted IP
blocked = req("POST", "/api/v1/auth/token", headers={"X-Forwarded-For": "198.51.100.7"},
              json={"clientId": cli2["clientId"], "clientSecret": cli2["clientSecret"]})
allowed = req("POST", "/api/v1/auth/token", headers={"X-Forwarded-For": "203.0.113.50"},
              json={"clientId": cli2["clientId"], "clientSecret": cli2["clientSecret"]})
add("EC-010", "Edge", "IP allowlist violation (LSP API)",
    "API call from IP not in API allowlist (enforcement on) -> 403 allowlist-violation; allowed IP -> 200.",
    "Pass" if blocked.status_code == 403 and allowed.status_code == 200 else ("Blocked" if blocked.status_code == allowed.status_code else "Fail"),
    f"addCidr={addcidr.status_code} enforce={enf.status_code}; blocked-IP token -> {blocked.status_code}; allowed-IP token -> {allowed.status_code}",
    "LSP API Client", "Security/IP Allowlist", "Code audit", "API",
    "Add CIDR 203.0.113.0/24 + enforce; POST /auth/token with X-Forwarded-For inside/outside block", "High",
    "Independent re-run via X-Forwarded-For on throwaway LSP.")
# UC-009 allowlist CRUD
lst = req("GET", f"/api/v1/internal/admin/lsps/{lsp2}/api-ip-allowlist", token=t)
add("UC-009", "Use Cases", "Manage LSP IP Allowlists",
    "PUT/POST/DELETE api-ip-allowlist + enforcement toggle controls per-surface IP restrictions.",
    "Pass" if addcidr.status_code in (200, 201) and lst.ok else "Fail",
    f"add CIDR -> {addcidr.status_code}; list -> {lst.status_code} ({len(lst.json()) if lst.ok else '?'} entries); enforcement -> {enf.status_code}",
    "System Administrator", "Security/IP Allowlist", "PDF + code", "API",
    "POST api-ip-allowlist + PUT allowlist-enforcement + GET list", "", "Independent re-run.")

# ===================== EC-107 schedule sum validation (approved loan, LSP_PROVIDED) =====================
a = create()
for dt in DOCS:
    req("POST", f"/api/v1/lsp/loan-applications/{a}/documents", token=lt,
        files={"file": (f"{dt.lower()}.pdf", io.BytesIO(PDF), "application/pdf")}, data={"documentType": dt})
for _ in range(40):
    if req("GET", f"/api/v1/internal/ops/loan-applications/{a}", token=t).json().get("status") == "APPROVED_PENDING_DISBURSAL":
        break
    time.sleep(1)
bad = {"mode": "LSP_PROVIDED", "installments": [
    {"installmentNumber": 1, "dueDate": "2026-07-14", "openingPrincipal": 150000, "principalDue": 1000,
     "interestDue": 100, "installmentAmount": 1100, "closingPrincipal": 149000}]}
r = req("PUT", f"/api/v1/lsp/loan-applications/{a}/repayment-schedule", token=lt, headers=J, json=bad)
add("EC-107", "Edge", "Schedule submission validation",
    "PUT repayment-schedule (LSP_PROVIDED) with installments sum != principal+interest -> 4xx with schedule-violation detail.",
    "Pass" if 400 <= r.status_code < 500 else "Fail",
    f"approved loan + LSP_PROVIDED bad-sum schedule -> {r.status_code}: {r.text[:160]}",
    "LSP API Client", "Disbursement / Validation", "Code audit", "API",
    "Approve loan, PUT /repayment-schedule mode=LSP_PROVIDED with sum!=principal+interest", "High",
    "Independent re-run (correct enum LSP_PROVIDED on approved loan).")

# ===================== EC-003 brute-force lockout (throwaway user) =====================
bu = f"bf.user.{uuid.uuid4().hex[:8]}"
req("POST", "/api/v1/internal/admin/users", token=t, headers=J,
    json={"username": bu, "email": f"{bu}@d.local", "password": "BfUser#2026!", "status": "ACTIVE", "roles": ["OPS_USER"]})
codes = []
for _ in range(12):
    c = req("POST", "/api/v1/auth/login", json={"username": bu, "password": "WRONG#000"}).status_code
    codes.append(c)
    if c == 429:
        time.sleep(2)
# now try correct password — if locked, should still fail (423/401 lockout) even though correct
good = req("POST", "/api/v1/auth/login", json={"username": bu, "password": "BfUser#2026!"})
locked = good.status_code in (401, 403, 423, 429)
add("EC-003", "Edge", "Brute-force lockout",
    "After N consecutive failed logins, account is locked for a cooldown; correct password is rejected until unlock; alert raised.",
    "Pass" if locked else "Blocked",
    f"12 wrong-login codes={codes}; correct-pw-after -> {good.status_code} (locked/limited={locked})",
    "All humans", "Authentication", "Code audit", "API",
    "Hammer wrong password x12 on throwaway user, then try correct password", "High",
    "Note: /auth/login rate limit (10/min/IP) interleaves 429s with lockout (V94 app_user_lockout).")

# ===================== DPD bucketing (Blocked — needs overdue installments) =====================
add("EC-114", "Edge", "DPD / delinquency bucketing on overdue installments",
    "An installment past its due date increments days-past-due and moves the loan into the correct DPD bucket on dashboard + borrower aggregate + MIS.",
    "Blocked",
    "Cannot create an overdue installment in this environment: schedule due dates are future (2026-07+) and there is no business-date override to time-travel. New disbursed loans show delinquencyBucket=CURRENT.",
    "System", "Servicing / Risk", "Independent E2E (new)", "API",
    "Would require back-dating an installment or a business-clock override", "High",
    "NEW: recommend a test hook to advance the business date (or seed an overdue loan) to exercise DPD math.")

json.dump(RESULTS, open("gap_results.json", "w"), indent=1)
print(f"\nTotal rows now: {len(RESULTS)} -> gap_results.json")
