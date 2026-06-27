"""Independent auth, RBAC, tenancy and validation edge cases."""
from __future__ import annotations
import json, time, uuid
from client import (req, admin_token, login, check, info, summary, jwt_claims,
                    rpan, raadhaar, rmobile, rifsc, racct, rid, FAIL, ADMIN_USER)

ctx = json.load(open("indep_ctx.json"))
t = admin_token()


def lsp_token(cid, sec):
    r = req("POST", "/api/v1/auth/token", json={"clientId": cid, "clientSecret": sec})
    return r.json().get("accessToken") if r.ok else None


def make_lsp(code_sfx, status="ACTIVE"):
    l = req("POST", "/api/v1/internal/admin/lsps", token=t,
            json={"code": f"AUTH-{code_sfx}", "name": f"Auth LSP {code_sfx}", "status": status}).json()
    return l["id"]


def make_client(lsp_id):
    c = req("POST", "/api/v1/internal/admin/api-clients", token=t,
            json={"lspId": lsp_id, "name": "AUTH-client"}).json()
    return c["clientId"], c["clientSecret"], c["id"]


def make_product_mapped(code_sfx, lsp_id, status="ACTIVE"):
    p = req("POST", "/api/v1/internal/admin/products", token=t, json={
        "code": f"AUTH-P-{code_sfx}", "name": f"Auth P {code_sfx}", "minPrincipal": 10000,
        "maxPrincipal": 500000, "interestRate": 14.5, "processingFeeRate": 1.5,
        "minTenureMonths": 6, "maxTenureMonths": 36, "status": status}).json()
    req("PUT", f"/api/v1/internal/admin/products/{p['id']}/mappings", token=t, json={"lspIds": [lsp_id]})
    return p["id"]


# ===== AUTH NEGATIVES =====
sfx = rid("A").split("-")[1].lower()

# EC-001 wrong password
c1, b1 = login(ADMIN_USER, "WrongPass#999")
check("EC-001", c1 == 401, f"wrong password -> {c1}")

# EC-002 unknown user (same shape/timing as wrong password)
c2, b2 = login(f"nouser{sfx}", "WrongPass#999")
check("EC-002", c2 == 401, f"unknown user -> {c2}")
check("EC-002.enum", b1.get("code") == b2.get("code") and b1.get("message") == b2.get("message"),
      f"no user enumeration (wrongpw code={b1.get('code')} msg={b1.get('message')!r}; unknown code={b2.get('code')})")

# EC-005 no token on internal endpoint
r = req("GET", "/api/v1/internal/system/context")
check("EC-005", r.status_code == 401, f"no token internal -> {r.status_code}")

# EC-006 tampered JWT
good = admin_token()
parts = good.split(".")
tampered = parts[0] + "." + parts[1][:-4] + "AAAA" + "." + parts[2]
r = req("GET", "/api/v1/internal/system/context", token=tampered)
check("EC-006", r.status_code == 401, f"tampered JWT -> {r.status_code}")

# EC-031 malformed JSON body
r = req("POST", "/api/v1/auth/login", data="{not json", headers={"Content-Type": "application/json"})
check("EC-031", r.status_code == 400, f"malformed JSON -> {r.status_code}")
check("EC-031.noleak", "Exception" not in r.text and "stacktrace" not in r.text.lower(),
      "no stack trace leaked in malformed JSON error")

# ===== RBAC =====
# LSP token on admin endpoint -> 403  (EC-013)
lt = lsp_token(ctx["client_id"], ctx["client_secret"])
if not lt:
    info("EC-013", "primary LSP secret stale; creating fresh client")
    nc, ns, _ = make_client(ctx["lsp_id"]); lt = lsp_token(nc, ns)
r = req("GET", "/api/v1/internal/admin/lsps", token=lt)
check("EC-013", r.status_code == 403, f"LSP_API_CLIENT on /admin/lsps -> {r.status_code} (want 403)")

# OPS_USER and PRODUCT_ADMIN role guards (EC-014/EC-015)
def make_user(uname, roles, lsp_id=None):
    payload = {"username": uname, "email": f"{uname}@auth.demo.local", "password": "AuthUser#2026!",
               "status": "ACTIVE", "roles": roles}
    if lsp_id:
        payload["lspId"] = lsp_id
    return req("POST", "/api/v1/internal/admin/users", token=t, json=payload)

ops_u = f"auth.ops.{sfx}"; pa_u = f"auth.pa.{sfx}"
make_user(ops_u, ["OPS_USER"]); make_user(pa_u, ["PRODUCT_ADMIN"])
oc, ob = login(ops_u, "AuthUser#2026!"); ot = ob.get("accessToken")
pc, pb = login(pa_u, "AuthUser#2026!"); pt = pb.get("accessToken")
info("login.users", f"ops login={oc} pwChange={ob.get('passwordChangeRequired')}; pa login={pc}")

# EC-014 OPS on admin-only (reports, audit-events, lsps write)
if ot:
    r = req("GET", "/api/v1/internal/reports/portfolio-mis/preview?disbursalDateFrom=2026-01-01&disbursalDateTo=2026-12-31", token=ot)
    check("EC-069", r.status_code == 403, f"OPS_USER on /reports -> {r.status_code} (want 403)")
    r2 = req("GET", "/api/v1/internal/admin/audit-events", token=ot)
    check("EC-014", r2.status_code == 403, f"OPS_USER on /admin/audit-events -> {r2.status_code} (want 403)")
# EC-015 PRODUCT_ADMIN on non-product
if pt:
    r = req("GET", "/api/v1/internal/admin/lsps", token=pt)
    check("EC-015", r.status_code == 403, f"PRODUCT_ADMIN on /admin/lsps -> {r.status_code} (want 403)")

# EC-102 admin endpoints with valid JWT succeed (no 401 loop)
codes = [req("GET", e, token=t).status_code for e in
         ("/api/v1/internal/admin/lsps", "/api/v1/internal/admin/products", "/api/v1/internal/admin/users")]
check("EC-102", all(c == 200 for c in codes), f"admin endpoints with valid JWT -> {codes} (no 401 loop)")

# ===== TENANCY =====
# second LSP/client + product + app for cross-tenant tests
lsp_b = make_lsp(sfx + "B"); cidB, secB, _ = make_client(lsp_b); prodB = make_product_mapped(sfx + "B", lsp_b)
ltB = lsp_token(cidB, secB)

# EC-030 mismatched lspId in payload vs token -> 403
body = {"lspId": lsp_b, "productId": ctx["product_id"], "lspLoanId": rid("X"),
        "fullName": "X Y", "emailAddress": f"x{sfx}@e.com", "mobileNumber": rmobile(),
        "dob": "1990-01-01", "gender": "MALE", "maritalStatus": "SINGLE", "fatherName": "P",
        "aadharNumber": raadhaar(), "panNumber": rpan(), "loanAmount": 150000, "interestRate": 14.5,
        "loanTenure": 12, "addressLine1": "1 St", "addressCity": "Mumbai", "addressState": "MH",
        "addressZipcode": "400001", "employmentStatus": "SALARIED", "organizationName": "C",
        "monthlyIncome": 60000, "annualIncome": 720000, "bankAccountNumber": racct(),
        "bankName": "HDFC", "ifscCode": rifsc(), "accountHolderName": "X Y",
        "referencePersonName": "R", "referencePersonNumber": rmobile()}
# use primary LSP token (lt) but body says lsp_b
r = req("POST", "/api/v1/lsp/loan-applications", token=lt, idem=str(uuid.uuid4()), json=body)
check("EC-030", r.status_code == 403, f"mismatched lspId in payload -> {r.status_code} (want 403)")

# EC-012 cross-tenant read -> 404 (LSP B reads LSP A's closed app)
if ltB:
    r = req("GET", f"/api/v1/lsp/loan-applications/{ctx['closed_app_id']}", token=ltB)
    check("EC-012", r.status_code == 404, f"LSP B reads LSP A app -> {r.status_code} (want 404)")
    # sanity: LSP A can read its own
    r2 = req("GET", f"/api/v1/lsp/loan-applications/{ctx['closed_app_id']}", token=lt)
    check("EC-012.own", r2.status_code == 200, f"LSP A reads own app -> {r2.status_code}")

# ===== VALIDATION (auto-reject / 400) =====
def lsp_create(token, **ov):
    b = dict(body); b["lspId"] = ctx["lsp_id"]; b["productId"] = ctx["product_id"]
    b["lspLoanId"] = rid("V"); b["panNumber"] = rpan(); b["aadharNumber"] = raadhaar()
    b["mobileNumber"] = rmobile(); b.update(ov)
    return req("POST", "/api/v1/lsp/loan-applications", token=token, idem=str(uuid.uuid4()), json=b)

check("EC-021", lsp_create(lt, loanAmount=1000).status_code == 400, "loanAmount<min -> 400")
check("EC-022", lsp_create(lt, loanAmount=600000).status_code == 400, "loanAmount>max -> 400")
check("EC-023", lsp_create(lt, loanTenure=48).status_code == 400, "tenure>max -> 400")
check("EC-029", lsp_create(lt, panNumber="BADPAN").status_code == 400, "invalid PAN -> 400")
r = req("POST", "/api/v1/lsp/loan-applications", token=lt, idem=str(uuid.uuid4()), json={"lspId": ctx["lsp_id"]})
check("EC-028", r.status_code == 400, f"missing required fields -> {r.status_code}")

# EC-018 inactive product auto-reject behaviour
lsp_ip = make_lsp(sfx + "IP"); cidIP, secIP, _ = make_client(lsp_ip); prodIP = make_product_mapped(sfx + "IP", lsp_ip, "INACTIVE")
ltIP = lsp_token(cidIP, secIP)
if ltIP:
    r = lsp_create(ltIP, lspId=lsp_ip, productId=prodIP)
    info("EC-018", f"inactive product create -> {r.status_code} body={r.text[:160]}")
    check("EC-018", r.status_code in (400, 422) or (r.ok and r.json().get("status") == "REJECTED"),
          f"inactive product -> reject/4xx ({r.status_code})")

# EC-009 inactive LSP token issuance -> 401
lsp_in = make_lsp(sfx + "IN", "INACTIVE"); cidIN, secIN, _ = make_client(lsp_in)
r = req("POST", "/api/v1/auth/token", json={"clientId": cidIN, "clientSecret": secIN})
check("EC-009", r.status_code in (401, 403), f"inactive LSP token issuance -> {r.status_code} (want 401/403)")

# EC-008 rotate secret grace: old + new both valid briefly
cidR, secR, idR = make_client(ctx["lsp_id"])
old_ok = lsp_token(cidR, secR) is not None
rot = req("POST", f"/api/v1/internal/admin/api-clients/{idR}/rotate-secret", token=t)
if rot.ok:
    new_secret = rot.json()["clientSecret"]
    new_ok = lsp_token(cidR, new_secret) is not None
    old_still = lsp_token(cidR, secR) is not None
    check("EC-008", old_ok and new_ok and old_still,
          f"rotate grace: old_before={old_ok} new={new_ok} old_after={old_still}")
else:
    info("EC-008", f"rotate-secret -> {rot.status_code}")

# ===== UC-003 refresh + logout =====
lc, lb = login(ADMIN_USER, "ChangeMe123!")
# refresh uses httpOnly cookie set in session by login
rf = req("POST", "/api/v1/auth/refresh")
info("UC-003.refresh", f"refresh -> {rf.status_code}")
lo = req("POST", "/api/v1/auth/logout")
check("UC-003", lo.status_code in (200, 204), f"logout -> {lo.status_code}")

summary()
raise SystemExit(1 if FAIL else 0)
