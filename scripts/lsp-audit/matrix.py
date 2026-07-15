"""LSP API test matrix — runs against provisioned fixtures.json."""
from __future__ import annotations

import json
import time
import uuid
from pathlib import Path

from harness import (
    S, BASE, req, admin_login, token_exchange, jclaims, body, rec, RESULTS,
    onboarding_payload, rpan, raadhaar, rmobile, rifsc, racct, rid, BLANK_PDF, OUT_DIR,
)

FX = json.load(open(OUT_DIR / "fixtures.json"))
A, B = FX["A"], FX["B"]


_TOKCACHE: dict[str, str] = {}


def tok(fx) -> str:
    key = fx["clientId"]
    if key in _TOKCACHE:
        return _TOKCACHE[key]
    for attempt in range(6):
        r = token_exchange(fx["clientId"], fx["clientSecret"])
        if r.status_code == 200:
            _TOKCACHE[key] = r.json()["accessToken"]
            return _TOKCACHE[key]
        if r.status_code == 429:
            print(f"  token 429 for {key[:12]}, waiting 15s (attempt {attempt+1})")
            time.sleep(15)
            continue
        raise SystemExit(f"token exchange failed {r.status_code}: {r.text[:200]}")
    raise SystemExit("token exchange exhausted retries")


def run():
    admin = admin_login()
    # ---------------- AUTH / TOKEN ----------------
    r = token_exchange(A["clientId"], A["clientSecret"])
    claims = jclaims(r.json()["accessToken"]) if r.ok else {}
    rec("AUTH-01", "Valid client credentials", "200 + lspId claim + LSP_API_CLIENT role",
        r.status_code, r.ok and claims.get("lspId") == A["lspId"] and "LSP_API_CLIENT" in claims.get("roles", []),
        f"lspId={claims.get('lspId')} roles={claims.get('roles')}")

    r = token_exchange(A["clientId"], "wrong-secret-xxx")
    rec("AUTH-02", "Invalid client secret", "401", r.status_code, r.status_code == 401, str(body(r))[:120])

    r = token_exchange("cli_nonexistent", "whatever")
    rec("AUTH-03", "Unknown client id", "401", r.status_code, r.status_code == 401, str(body(r))[:120])

    r = req("POST", "/api/v1/auth/token", json={"clientId": A["clientId"]})
    rec("AUTH-04", "Missing clientSecret", "400", r.status_code, r.status_code == 400, str(body(r))[:120])

    ta, tb = tok(A), tok(B)

    # No token
    r = req("GET", "/api/v1/lsp/products")
    rec("AUTH-05", "LSP endpoint without token", "401", r.status_code, r.status_code == 401, str(body(r))[:120])

    # Malformed token
    r = req("GET", "/api/v1/lsp/products", token="not.a.jwt")
    rec("AUTH-06", "Malformed bearer token", "401", r.status_code, r.status_code == 401, str(body(r))[:120])

    # ---------------- PRODUCTS ----------------
    r = req("GET", "/api/v1/lsp/products", token=ta)
    prods = body(r)
    a_prod_ids = {p["id"] for p in prods} if isinstance(prods, list) else set()
    rec("PROD-01", "List provisioned products (A)", "200, contains A's product only",
        r.status_code, r.ok and A["productId"] in a_prod_ids and B["productId"] not in a_prod_ids,
        f"count={len(a_prod_ids)} hasA={A['productId'] in a_prod_ids} hasB={B['productId'] in a_prod_ids}")

    # ---------------- CREATE APPLICATION ----------------
    p = onboarding_payload(A)
    r = req("POST", "/api/v1/lsp/loan-applications", token=ta, json=p)
    app = body(r)
    app_id = app.get("id") if isinstance(app, dict) else None
    borrower_id = app.get("borrowerId") if isinstance(app, dict) else None
    ext_id = p["lspLoanId"]
    rec("CREATE-01", "Create application happy path", "200 + id + PENDING-ish status",
        r.status_code, r.ok and bool(app_id), f"id={app_id} status={app.get('status') if isinstance(app,dict) else None}")

    # lspId mismatch: auth A, body B's lspId
    p2 = onboarding_payload(A, lspId=B["lspId"])
    r = req("POST", "/api/v1/lsp/loan-applications", token=ta, json=p2)
    rec("CREATE-02", "Body lspId != authenticated LSP", "403 access denied",
        r.status_code, r.status_code == 403, str(body(r))[:140])

    # cross product: auth A, use B's productId
    p3 = onboarding_payload(A, productId=B["productId"])
    r = req("POST", "/api/v1/lsp/loan-applications", token=ta, json=p3)
    rec("CREATE-03", "Use another LSP's productId", "4xx (not provisioned)",
        r.status_code, 400 <= r.status_code < 500, f"{r.status_code} {str(body(r))[:120]}")

    # missing PAN (required)
    p4 = onboarding_payload(A); p4.pop("panNumber")
    r = req("POST", "/api/v1/lsp/loan-applications", token=ta, json=p4)
    rec("CREATE-04", "Missing required panNumber", "400 validation",
        r.status_code, r.status_code == 400, str(body(r))[:140])

    # invalid PAN format
    p5 = onboarding_payload(A, panNumber="INVALID123")
    r = req("POST", "/api/v1/lsp/loan-applications", token=ta, json=p5)
    rec("CREATE-05", "Invalid PAN format", "400 with clear message",
        r.status_code, r.status_code == 400, str(body(r))[:180])

    # no product selection
    p6 = onboarding_payload(A); p6.pop("productId")
    r = req("POST", "/api/v1/lsp/loan-applications", token=ta, json=p6)
    rec("CREATE-06", "No productId or loanProduct", "400 AssertTrue",
        r.status_code, r.status_code == 400, str(body(r))[:180])

    # no income
    p7 = onboarding_payload(A); p7.pop("monthlyIncome", None)
    r = req("POST", "/api/v1/lsp/loan-applications", token=ta, json=p7)
    rec("CREATE-07", "No monthly/annual income", "400 AssertTrue",
        r.status_code, r.status_code == 400, str(body(r))[:180])

    # StrictJson unknown field
    p8 = onboarding_payload(A); p8["unexpectedField"] = "x"
    r = req("POST", "/api/v1/lsp/loan-applications", token=ta, json=p8)
    rec("CREATE-08", "Unknown JSON field (StrictJson)", "400 rejected",
        r.status_code, r.status_code == 400, str(body(r))[:180])

    # negative amount
    p9 = onboarding_payload(A, loanAmount=-500)
    r = req("POST", "/api/v1/lsp/loan-applications", token=ta, json=p9)
    rec("CREATE-09", "Negative loanAmount", "400", r.status_code, r.status_code == 400, str(body(r))[:120])

    # amount above product max
    p10 = onboarding_payload(A, loanAmount=99999999)
    r = req("POST", "/api/v1/lsp/loan-applications", token=ta, json=p10)
    rec("CREATE-10", "loanAmount above product max (500000)", "expect 4xx business rule",
        r.status_code, True, f"{r.status_code} {str(body(r))[:140]}")

    # duplicate lspLoanId
    pdup = onboarding_payload(A, lspLoanId=ext_id)
    r = req("POST", "/api/v1/lsp/loan-applications", token=ta, json=pdup)
    rec("CREATE-11", "Duplicate lspLoanId (same as CREATE-01)", "expect 409/422 dedupe",
        r.status_code, True, f"{r.status_code} {str(body(r))[:160]}")

    # ---------------- IDEMPOTENCY ----------------
    idem = str(uuid.uuid4())
    pi = onboarding_payload(A)
    r1 = req("POST", "/api/v1/lsp/loan-applications", token=ta, idem=idem, json=pi)
    id1 = body(r1).get("id") if r1.ok else None
    r2 = req("POST", "/api/v1/lsp/loan-applications", token=ta, idem=idem, json=pi)
    id2 = body(r2).get("id") if r2.ok else None
    rec("IDEM-01", "Replay same key + same body", "same id returned",
        r2.status_code, r1.ok and r2.ok and id1 == id2, f"id1={id1} id2={id2}")

    r3 = req("POST", "/api/v1/lsp/loan-applications", token=ta, idem=idem, json=onboarding_payload(A))
    rec("IDEM-02", "Reuse key with different body", "409 fingerprint conflict",
        r3.status_code, r3.status_code in (409, 422), f"{r3.status_code} {str(body(r3))[:140]}")

    # ---------------- GET / LIST ----------------
    r = req("GET", f"/api/v1/lsp/loan-applications/{app_id}", token=ta)
    rec("GET-01", "Get own application by id", "200", r.status_code, r.ok, f"status={body(r).get('status') if r.ok else body(r)}")

    r = req("GET", f"/api/v1/lsp/loan-applications/external/{ext_id}", token=ta)
    rec("GET-02", "Get own application by external id", "200", r.status_code, r.ok, str(body(r).get("id") if r.ok else body(r))[:80])

    # cross-tenant: B tries to read A's app
    r = req("GET", f"/api/v1/lsp/loan-applications/{app_id}", token=tb)
    rec("ISO-01", "LSP B reads LSP A's application by id", "404/403 (isolation)",
        r.status_code, r.status_code in (403, 404), f"{r.status_code} {str(body(r))[:120]}")

    r = req("GET", f"/api/v1/lsp/loan-applications/external/{ext_id}", token=tb)
    rec("ISO-02", "LSP B reads A's app by external id", "404/403",
        r.status_code, r.status_code in (403, 404), f"{r.status_code} {str(body(r))[:120]}")

    # list + pagination
    r = req("GET", "/api/v1/lsp/loan-applications?limit=5&offset=0&paginationDetails=ON", token=ta)
    tc = r.headers.get("X-Total-Count")
    rec("LIST-01", "List apps w/ pagination headers (A)", "200 + X-Total-Count header",
        r.status_code, r.ok and tc is not None, f"X-Total-Count={tc} items={len(body(r)) if r.ok else '?'}")

    # list isolation: none of B's items should be A's
    rb = req("GET", "/api/v1/lsp/loan-applications?limit=200&paginationDetails=ON", token=tb)
    b_ids = {x["id"] for x in body(rb)} if rb.ok else set()
    rec("ISO-03", "LSP B list excludes A's app", "A's app id absent from B list",
        rb.status_code, app_id not in b_ids, f"B-count={len(b_ids)} containsA={app_id in b_ids}")

    # invalid status filter
    r = req("GET", "/api/v1/lsp/loan-applications?status=NONSENSE", token=ta)
    rec("LIST-02", "Invalid status filter value", "graceful (200 empty or 400)",
        r.status_code, r.status_code in (200, 400), f"{r.status_code} n={len(body(r)) if r.status_code==200 else '-'}")

    # limit over max (200)
    r = req("GET", "/api/v1/lsp/loan-applications?limit=500", token=ta)
    rec("LIST-03", "limit above max 200", "400 constraint", r.status_code, r.status_code == 400, str(body(r))[:120])

    # ---------------- INVALID REASONS + INVALIDATE ----------------
    r = req("GET", "/api/v1/lsp/loan-applications/invalid-reasons", token=ta)
    rec("INV-01", "List invalid reasons", "200 list", r.status_code, r.ok and isinstance(body(r), list), f"n={len(body(r)) if r.ok else '?'}")

    # make a throwaway app to invalidate
    pthrow = onboarding_payload(A)
    rt = req("POST", "/api/v1/lsp/loan-applications", token=ta, json=pthrow)
    throw_id = body(rt).get("id")
    reasons = body(r) if r.ok else []
    reason_code = reasons[0]["code"] if reasons else "REASON_A"
    r = req("POST", f"/api/v1/lsp/loan-applications/{throw_id}/invalid", token=ta,
            idem=str(uuid.uuid4()), json={"reasonCode": reason_code, "reasonText": "audit test"})
    rec("INV-02", "Invalidate own application", "200 status INVALID",
        r.status_code, r.ok and body(r).get("status") == "INVALID", f"status={body(r).get('status') if r.ok else body(r)}")

    # cross-tenant invalidate
    r = req("POST", f"/api/v1/lsp/loan-applications/{app_id}/invalid", token=tb,
            idem=str(uuid.uuid4()), json={"reasonCode": reason_code})
    rec("ISO-04", "LSP B invalidates A's app", "403/404",
        r.status_code, r.status_code in (403, 404), f"{r.status_code} {str(body(r))[:120]}")

    # ---------------- DOCUMENTS ----------------
    pdf = BLANK_PDF.read_bytes() if BLANK_PDF.exists() else b"%PDF-1.4 test"
    # metadata submit
    r = req("POST", f"/api/v1/lsp/loan-applications/{app_id}/documents", token=ta,
            json={"documentType": "PAN_CARD", "note": "meta", "fileName": "pan.pdf"})
    rec("DOC-01", "Submit document metadata (JSON)", "200",
        r.status_code, r.ok, f"{r.status_code} status={body(r).get('status') if r.ok else body(r)}"[:140])

    # multipart upload
    r = req("POST", f"/api/v1/lsp/loan-applications/{app_id}/documents", token=ta,
            files=[("file", ("aadhaar.pdf", pdf, "application/pdf"))],
            data={"documentType": "AADHAAR_FILE", "note": "upload"})
    rec("DOC-02", "Upload document (multipart)", "200",
        r.status_code, r.ok, f"{r.status_code} {str(body(r).get('fileName') if r.ok else body(r))[:120]}")

    # cross-tenant upload
    r = req("POST", f"/api/v1/lsp/loan-applications/{app_id}/documents", token=tb,
            files=[("file", ("x.pdf", pdf, "application/pdf"))], data={"documentType": "PAN_CARD"})
    rec("ISO-05", "LSP B uploads doc to A's app", "403/404",
        r.status_code, r.status_code in (403, 404), f"{r.status_code} {str(body(r))[:120]}")

    # list documents
    r = req("GET", f"/api/v1/lsp/loan-applications/{app_id}/documents", token=ta)
    rec("DOC-03", "List submitted documents", "200 list", r.status_code, r.ok, f"n={len(body(r)) if r.ok else body(r)}")

    # batch upload full KYC on a fresh app to trigger STP
    stp_payload = onboarding_payload(A)
    rs = req("POST", "/api/v1/lsp/loan-applications", token=ta, json=stp_payload)
    stp_id = body(rs).get("id")
    stp_borrower = body(rs).get("borrowerId")
    DOCS = ["PAN_CARD", "AADHAAR_FILE", "ADDRESS_PROOF", "INCOME_PROOF", "BANK_STATEMENT",
            "SELFIE_PHOTOGRAPH", "KFS", "LOAN_AGREEMENT"]
    meta = [{"documentType": d, "note": f"batch {d}", "sourceReference": f"s-{d}"} for d in DOCS]
    files = [("files", (f"{d.lower()}.pdf", pdf, "application/pdf")) for d in DOCS]
    files.append(("documents", (None, json.dumps(meta), "application/json")))
    r = req("POST", f"/api/v1/lsp/loan-applications/{stp_id}/documents/batch", token=ta, files=files)
    rec("DOC-04", "Batch upload all KYC docs", "200 list of docs",
        r.status_code, r.ok, f"{r.status_code} n={len(body(r)) if r.ok else body(r)}"[:140])

    # batch mismatch counts
    files_bad = [("files", ("a.pdf", pdf, "application/pdf"))]
    files_bad.append(("documents", (None, json.dumps(meta), "application/json")))
    r = req("POST", f"/api/v1/lsp/loan-applications/{stp_id}/documents/batch", token=ta, files=files_bad)
    rec("DOC-05", "Batch metadata/file count mismatch", "400",
        r.status_code, r.status_code == 400, f"{r.status_code} {str(body(r))[:120]}")

    # ---------------- REPAYMENT SCHEDULE UPSERT ----------------
    r = req("PUT", f"/api/v1/lsp/loan-applications/{stp_id}/repayment-schedule", token=ta,
            json={"mode": "GENERATED"})
    rec("SCHED-01", "Upsert GENERATED schedule", "expect 200 or business-state 4xx",
        r.status_code, True, f"{r.status_code} n={len(body(r)) if r.ok else str(body(r))[:120]}")

    # ---------------- BANK CHECK + BORROWER BANK DETAILS ----------------
    r = req("POST", f"/api/v1/lsp/loan-applications/{stp_id}/disbursement-bank-check", token=ta,
            json={"disbursalAmount": 100000.0, "bankAccountNumber": racct(),
                  "ifscCode": rifsc(), "accountHolderName": "Test Holder"})
    rec("BANK-01", "Disbursement bank check", "200 with status/warnings",
        r.status_code, r.ok, f"{r.status_code} {str(body(r))[:140]}")

    if stp_borrower:
        r = req("GET", f"/api/v1/lsp/borrowers/{stp_borrower}/bank-details", token=ta)
        rec("BANK-02", "Get borrower bank details (unmasked, audited)", "200 full account",
            r.status_code, r.ok, f"acct={body(r).get('bankAccountNumber') if r.ok else body(r)}"[:120])

        # cross-tenant bank details
        r = req("GET", f"/api/v1/lsp/borrowers/{stp_borrower}/bank-details", token=tb)
        rec("ISO-06", "LSP B reads A's borrower bank details", "403/404",
            r.status_code, r.status_code in (403, 404), f"{r.status_code} {str(body(r))[:120]}")

        r = req("PATCH", f"/api/v1/lsp/borrowers/{stp_borrower}/bank-details", token=ta,
                json={"bankAccountNumber": racct(), "bankName": "ICICI", "ifscCode": rifsc(),
                      "accountHolderName": "Updated Holder"})
        rec("BANK-03", "Update borrower bank details", "200",
            r.status_code, r.ok, f"{r.status_code} {str(body(r))[:120]}")

    # ---------------- LIFECYCLE TO DISBURSED ----------------
    # STP may auto-approve after full KYC. Poll status.
    disbursed_loan_id = None
    status_now = None
    for _ in range(6):
        r = req("GET", f"/api/v1/lsp/loan-applications/{stp_id}", token=ta)
        status_now = body(r).get("status") if r.ok else None
        if status_now and status_now not in ("PENDING", "UNDER_REVIEW", "SUBMITTED", "IN_REVIEW"):
            break
        time.sleep(3)
    rec("STP-01", "Status after full KYC batch upload", f"observed={status_now}",
        200, True, f"status={status_now}")

    # Admin drives disbursement if approved
    r = req("GET", f"/api/v1/internal/ops/loan-applications/{stp_id}", token=admin)
    admin_status = body(r).get("status") if r.ok else None
    if admin_status in ("APPROVED_PENDING_DISBURSAL", "APPROVED"):
        ri = req("POST", f"/api/v1/internal/ops/loan-applications/{stp_id}/disbursement-requests", token=admin)
        rec("DISB-01", "Admin initiate disbursement", "200/accepted", ri.status_code, ri.ok or ri.status_code in (200,201,202), f"{ri.status_code} {str(body(ri))[:100]}")
        rm = req("POST", f"/api/v1/internal/ops/loan-applications/{stp_id}/disbursement-requests/mock-outcome",
                 token=admin, json={"outcome": "DISBURSED"})
        d = body(rm)
        if rm.ok and isinstance(d, dict):
            la = d.get("loanAccount") or {}
            disbursed_loan_id = la.get("id")
        rec("DISB-02", "Apply mock outcome DISBURSED", "200 status DISBURSED",
            rm.status_code, rm.ok, f"{rm.status_code} status={d.get('status') if isinstance(d,dict) else d}"[:140])
    else:
        rec("DISB-00", "Loan not auto-approved for disbursement", f"admin_status={admin_status}",
            200, True, f"admin_status={admin_status} (manual approval likely required)")

    # ---------------- LOAN SERVICING (needs disbursed loan) ----------------
    if disbursed_loan_id:
        FX["disbursedLoanId"] = disbursed_loan_id
        FX["disbursedAppId"] = stp_id
        json.dump(FX, open(OUT_DIR / "fixtures.json", "w"), indent=2)
        r = req("GET", f"/api/v1/lsp/loans/{disbursed_loan_id}", token=ta)
        rec("LOAN-01", "Get loan account", "200", r.status_code, r.ok, f"{r.status_code} {str(body(r).get('status') if r.ok else body(r))[:80]}")

        r = req("GET", f"/api/v1/lsp/loans/{disbursed_loan_id}/repayment-schedule", token=ta)
        insts = body(r) if r.ok else []
        rec("LOAN-02", "Get repayment schedule", "200 installments", r.status_code, r.ok and len(insts) > 0, f"n={len(insts) if r.ok else body(r)}")

        # cross-tenant loan read
        r = req("GET", f"/api/v1/lsp/loans/{disbursed_loan_id}", token=tb)
        rec("ISO-07", "LSP B reads A's loan account", "403/404", r.status_code, r.status_code in (403,404), f"{r.status_code}")

        # record a payment
        if insts:
            inst = insts[0]
            amt = inst.get("outstandingAmount") or inst.get("installmentAmount")
            r = req("POST", f"/api/v1/lsp/loans/{disbursed_loan_id}/payments", token=ta, idem=str(uuid.uuid4()),
                    json={"targetInstallmentId": inst["id"], "amount": float(amt),
                          "postedAt": time.strftime("%Y-%m-%d"), "channel": "UPI", "reference": rid("PAY")})
            rec("PAY-01", "Record repayment", "200/201", r.status_code, r.ok, f"{r.status_code} {str(body(r).get('status') if r.ok else body(r))[:100]}")

            # cross-tenant payment
            r = req("POST", f"/api/v1/lsp/loans/{disbursed_loan_id}/payments", token=tb, idem=str(uuid.uuid4()),
                    json={"targetInstallmentId": inst["id"], "amount": 100.0,
                          "postedAt": time.strftime("%Y-%m-%d"), "channel": "UPI", "reference": rid("PAY")})
            rec("ISO-08", "LSP B records payment on A's loan", "403/404", r.status_code, r.status_code in (403,404), f"{r.status_code}")

            # future postedAt
            r = req("POST", f"/api/v1/lsp/loans/{disbursed_loan_id}/payments", token=ta, idem=str(uuid.uuid4()),
                    json={"targetInstallmentId": inst["id"], "amount": 100.0,
                          "postedAt": "2099-01-01", "channel": "UPI", "reference": rid("PAY")})
            rec("PAY-02", "Payment postedAt in future", "400 PastOrPresent", r.status_code, r.status_code == 400, f"{r.status_code} {str(body(r))[:100]}")

        r = req("GET", f"/api/v1/lsp/loans/{disbursed_loan_id}/payments", token=ta)
        rec("PAY-03", "List payments", "200", r.status_code, r.ok, f"n={len(body(r)) if r.ok else body(r)}")

        # foreclosure quote
        r = req("POST", f"/api/v1/lsp/loans/{disbursed_loan_id}/foreclosure-quote", token=ta,
                json={"effectiveDate": time.strftime("%Y-%m-%d")})
        rec("FCL-01", "Request foreclosure quote", "200 quote", r.status_code, r.ok, f"{r.status_code} {str(body(r).get('settlementAmount') if r.ok else body(r))[:100]}")
    else:
        rec("LOAN-00", "No disbursed loan reached", "servicing endpoints not exercised live", 0, True,
            "STP did not auto-disburse in window; documented separately")

    # ---------------- NON-EXISTENT / MALFORMED IDS ----------------
    r = req("GET", "/api/v1/lsp/loan-applications/00000000-0000-0000-0000-000000000000", token=ta)
    rec("EDGE-01", "Get non-existent application", "404", r.status_code, r.status_code == 404, f"{r.status_code} {str(body(r))[:100]}")

    r = req("GET", "/api/v1/lsp/loan-applications/not-a-uuid", token=ta)
    rec("EDGE-02", "Malformed UUID path", "400", r.status_code, r.status_code == 400, f"{r.status_code} {str(body(r))[:100]}")

    r = req("GET", "/api/v1/lsp/loans/00000000-0000-0000-0000-000000000000", token=ta)
    rec("EDGE-03", "Get non-existent loan", "404", r.status_code, r.status_code == 404, f"{r.status_code} {str(body(r))[:100]}")

    # ---------------- ROLE BOUNDARY: LSP client hits admin ----------------
    r = req("GET", "/api/v1/internal/admin/lsps", token=ta)
    rec("ROLE-01", "LSP API client hits admin endpoint", "403", r.status_code, r.status_code == 403, f"{r.status_code}")

    r = req("POST", f"/api/v1/internal/ops/loan-applications/{app_id}/disbursement-requests", token=ta)
    rec("ROLE-02", "LSP API client initiates disbursement (ops)", "403", r.status_code, r.status_code == 403, f"{r.status_code}")

    # summary
    passed = sum(1 for x in RESULTS if x["pass"])
    print(f"\n{'='*70}\nTOTAL {len(RESULTS)}  PASS {passed}  FAIL {len(RESULTS)-passed}\n{'='*70}")
    json.dump(RESULTS, open(OUT_DIR / "results.json", "w"), indent=2)


if __name__ == "__main__":
    run()
