"""Finish the UX-audit seed: W4 -> disbursement FAILED, W5 -> REJECTED."""
import io
import json
import time

from client import req
from seed_ux_audit import admin_token, get_app, poll_status, PDF_BYTES

ctx = json.load(open("ux_audit_ctx.json"))
t = admin_token()
tok = req("POST", "/api/v1/auth/token",
          json={"clientId": ctx["client_id"], "clientSecret": ctx["client_secret"]}, expect=200)
lt = tok.json()["accessToken"]

w4 = ctx["W4"]
print("W4 status:", get_app(t, w4).get("status"))
for dt in ("INCOME_PROOF", "SELFIE_PHOTOGRAPH", "LOAN_AGREEMENT"):
    r = req("POST", f"/api/v1/lsp/loan-applications/{w4}/documents", token=lt,
            files={"file": (f"{dt.lower()}.pdf", io.BytesIO(PDF_BYTES), "application/pdf")},
            data={"documentType": dt})
    print("W4 doc", dt, "->", r.status_code, "" if r.ok else r.text[:120])
    time.sleep(2)
st = poll_status(t, w4, ("APPROVED_PENDING_DISBURSAL", "DISBURSED"), tries=40, sleep=1)
print("W4 post-docs:", st)
if st == "APPROVED_PENDING_DISBURSAL":
    init = req("POST", f"/api/v1/internal/ops/loan-applications/{w4}/disbursement-requests", token=t)
    print("W4 initiate ->", init.status_code, init.text[:150])
    mo = req("POST", f"/api/v1/internal/ops/loan-applications/{w4}/disbursement-requests/mock-outcome",
             token=t, json={"outcome": "FAILED"}, headers={"Content-Type": "application/json"})
    print("W4 mock FAILED ->", mo.status_code, mo.text[:200])
    time.sleep(3)
print("W4 final:", get_app(t, w4).get("status"))

w5 = ctx["W5"]
d5 = get_app(t, w5)
print("W5 status:", d5.get("status"), "allowed:", d5.get("allowedTransitions") or d5.get("availableTransitions"))
for target in ("AWAITING_APPROVAL", "REJECTED"):
    r = req("POST", f"/api/v1/internal/ops/loan-applications/{w5}/status-transitions", token=t,
            json={"targetStatus": target, "note": "UX audit seed"},
            headers={"Content-Type": "application/json"})
    print(f"W5 -> {target}: {r.status_code} {r.text[:150]}")
print("W5 final:", get_app(t, w5).get("status"))
