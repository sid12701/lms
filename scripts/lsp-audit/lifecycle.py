"""Full LSP lifecycle driver to a DISBURSED loan, with pacing to avoid rate limits."""
from __future__ import annotations

import json
import time
import uuid

from harness import (
    req, admin_login, token_exchange, body, rec, RESULTS,
    onboarding_payload, rifsc, racct, rid, BLANK_PDF, OUT_DIR,
)

FX = json.load(open(OUT_DIR / "fixtures.json"))
A = FX["A"]
DOCS = ["PAN_CARD", "AADHAAR_FILE", "ADDRESS_PROOF", "INCOME_PROOF", "BANK_STATEMENT",
        "SELFIE_PHOTOGRAPH", "KFS", "LOAN_AGREEMENT"]


def run():
    admin = admin_login()
    ta = token_exchange(A["clientId"], A["clientSecret"]).json()["accessToken"]
    pdf = BLANK_PDF.read_bytes() if BLANK_PDF.exists() else b"%PDF-1.4 test"

    # 1. Create complete application
    p = onboarding_payload(A)
    r = req("POST", "/api/v1/lsp/loan-applications", token=ta, json=p)
    app = body(r)
    app_id, borrower_id = app["id"], app["borrowerId"]
    rec("LC-01", "Create complete application", "200 INITIALIZED", r.status_code, r.ok,
        f"status={app.get('status')} id={app_id}")

    # 2. Batch upload all 8 KYC docs (should complete checklist -> STP fires)
    meta = [{"documentType": d, "note": f"lc {d}", "sourceReference": f"s-{d}"} for d in DOCS]
    files = [("files", (f"{d.lower()}.pdf", pdf, "application/pdf")) for d in DOCS]
    files.append(("documents", (None, json.dumps(meta), "application/json")))
    r = req("POST", f"/api/v1/lsp/loan-applications/{app_id}/documents/batch", token=ta, files=files)
    rec("LC-02", "Batch upload all KYC docs (STP trigger)", "200", r.status_code, r.ok,
        f"n={len(body(r)) if r.ok else body(r)}")

    # 3. Poll status - expect auto-approval
    status = None
    for _ in range(8):
        r = req("GET", f"/api/v1/lsp/loan-applications/{app_id}", token=ta)
        status = body(r).get("status")
        if status not in ("INITIALIZED", "AWAITING_APPROVAL"):
            break
        time.sleep(3)
    rec("LC-03", "Auto-approval after full KYC + complete borrower", "APPROVED_PENDING_DISBURSAL",
        200, status == "APPROVED_PENDING_DISBURSAL", f"status={status}")

    # 4. Admin view + initiate disbursement
    r = req("GET", f"/api/v1/internal/ops/loan-applications/{app_id}", token=admin)
    admin_status = body(r).get("status")
    disbursed_loan_id = None
    if admin_status == "APPROVED_PENDING_DISBURSAL":
        ri = req("POST", f"/api/v1/internal/ops/loan-applications/{app_id}/disbursement-requests", token=admin)
        rec("LC-04", "Admin initiate disbursement", "2xx", ri.status_code,
            ri.status_code in (200, 201, 202), f"{ri.status_code} {str(body(ri))[:80]}")
        time.sleep(2)
        rm = req("POST", f"/api/v1/internal/ops/loan-applications/{app_id}/disbursement-requests/mock-outcome",
                 token=admin, json={"outcome": "DISBURSED"})
        d = body(rm)
        if rm.ok:
            disbursed_loan_id = (d.get("loanAccount") or {}).get("id")
        rec("LC-05", "Apply mock outcome DISBURSED", "200 DISBURSED", rm.status_code,
            rm.ok, f"status={d.get('status') if isinstance(d, dict) else d}")
    else:
        rec("LC-04", "Admin disbursement precondition", f"admin_status={admin_status}", 200,
            admin_status == "APPROVED_PENDING_DISBURSAL", f"admin_status={admin_status}")

    if not disbursed_loan_id:
        # maybe worker auto-disburses; poll app for loanAccount + DISBURSED
        for _ in range(6):
            r = req("GET", f"/api/v1/lsp/loan-applications/{app_id}", token=ta)
            la = body(r).get("loanAccount") or {}
            if la.get("status") in ("DISBURSED", "ACTIVE") or body(r).get("status") == "DISBURSED":
                disbursed_loan_id = la.get("id")
                break
            time.sleep(5)

    # 5. Loan servicing endpoints
    if disbursed_loan_id:
        FX["disbursedLoanId"] = disbursed_loan_id
        FX["disbursedAppId"] = app_id
        FX["disbursedBorrowerId"] = borrower_id
        json.dump(FX, open(OUT_DIR / "fixtures.json", "w"), indent=2)

        r = req("GET", f"/api/v1/lsp/loans/{disbursed_loan_id}", token=ta)
        rec("LC-06", "Get loan account", "200", r.status_code, r.ok,
            f"acct-status={(body(r).get('loanAccount') or {}).get('status') if r.ok else body(r)}")

        r = req("GET", f"/api/v1/lsp/loans/{disbursed_loan_id}/repayment-schedule", token=ta)
        insts = body(r) if r.ok else []
        rec("LC-07", "Get repayment schedule", "200 non-empty", r.status_code,
            r.ok and len(insts) > 0, f"n={len(insts) if r.ok else body(r)}")

        if insts:
            inst = insts[0]
            amt = inst.get("outstandingAmount") or inst.get("installmentAmount")
            r = req("POST", f"/api/v1/lsp/loans/{disbursed_loan_id}/payments", token=ta,
                    idem=str(uuid.uuid4()), json={"targetInstallmentId": inst["id"], "amount": float(amt),
                    "postedAt": time.strftime("%Y-%m-%d"), "channel": "UPI", "reference": rid("PAY")})
            rec("LC-08", "Record full installment payment", "2xx", r.status_code, r.ok,
                f"{r.status_code} status={body(r).get('status') if r.ok else body(r)}"[:120])

            # duplicate reference/idempotency: same idem key replay
            k = str(uuid.uuid4())
            r1 = req("POST", f"/api/v1/lsp/loans/{disbursed_loan_id}/payments", token=ta, idem=k,
                     json={"targetInstallmentId": insts[1]["id"] if len(insts) > 1 else inst["id"],
                           "amount": 500.0, "postedAt": time.strftime("%Y-%m-%d"), "channel": "UPI",
                           "reference": rid("PAY")})
            r2 = req("POST", f"/api/v1/lsp/loans/{disbursed_loan_id}/payments", token=ta, idem=k,
                     json={"targetInstallmentId": insts[1]["id"] if len(insts) > 1 else inst["id"],
                           "amount": 500.0, "postedAt": time.strftime("%Y-%m-%d"), "channel": "UPI",
                           "reference": rid("PAY")})
            rec("LC-09", "Payment idempotency replay", "same txn id",
                r2.status_code, r1.ok and r2.ok and body(r1).get("id") == body(r2).get("id"),
                f"id1={body(r1).get('id') if r1.ok else r1.status_code} id2={body(r2).get('id') if r2.ok else r2.status_code}")

        r = req("GET", f"/api/v1/lsp/loans/{disbursed_loan_id}/payments", token=ta)
        rec("LC-10", "List payments", "200", r.status_code, r.ok, f"n={len(body(r)) if r.ok else body(r)}")

        # foreclosure quote + execute
        r = req("POST", f"/api/v1/lsp/loans/{disbursed_loan_id}/foreclosure-quote", token=ta,
                json={"effectiveDate": time.strftime("%Y-%m-%d")})
        q = body(r)
        rec("LC-11", "Request foreclosure quote", "200", r.status_code, r.ok,
            f"settle={q.get('settlementAmount') if r.ok else q}"[:120])
        if r.ok and q.get("id"):
            r = req("POST", f"/api/v1/lsp/loans/{disbursed_loan_id}/foreclosure-quotes/{q['id']}/execute",
                    token=ta, idem=str(uuid.uuid4()),
                    json={"settlementDate": time.strftime("%Y-%m-%d"), "reference": rid("FC"), "note": "audit"})
            rec("LC-12", "Execute foreclosure quote", "2xx -> loan closed", r.status_code, r.ok,
                f"{r.status_code} status={body(r).get('status') if r.ok else body(r)}"[:120])

        # invalidate after disbursed should fail
        r = req("POST", f"/api/v1/lsp/loan-applications/{app_id}/invalid", token=ta,
                idem=str(uuid.uuid4()), json={"reasonCode": "REASON_A"})
        rec("LC-13", "Invalidate a disbursed/serviced loan", "4xx business rule",
            r.status_code, 400 <= r.status_code < 500, f"{r.status_code} {str(body(r))[:100]}")
    else:
        rec("LC-06", "Reach disbursed loan", "could not disburse", 0, False, "no loan id")

    passed = sum(1 for x in RESULTS if x["pass"])
    print(f"\n{'='*60}\nLIFECYCLE {len(RESULTS)} PASS {passed} FAIL {len(RESULTS)-passed}\n{'='*60}")
    json.dump(RESULTS, open(OUT_DIR / "lifecycle_results.json", "w"), indent=2)


if __name__ == "__main__":
    run()
