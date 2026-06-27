#!/usr/bin/env python3
"""
Non-interactive loan seeder -- drives the live LSP + admin APIs.

Creates exactly 10 LSP-originated loan applications across an end-to-end
portfolio:
  - INITIALIZED / intake started, documents not uploaded
  - INVALID pre-disbursal
  - DISBURSED with no repayments
  - UNDER_REPAYMENT with 1, 3, half-tenure, and late-stage installments paid
  - CLOSED / fully repaid success, twice
  - FORECLOSED success

Origination, documents, schedules, repayments, invalidation, and foreclosure
use LSP APIs. Disbursement initiation/mock resolution still uses internal ops
APIs because the LSP surface does not expose provider settlement controls.
"""
from __future__ import annotations

import json
import os
import random
import string
import sys
import time
import uuid
from datetime import date, timedelta
from decimal import Decimal
from pathlib import Path

try:
    import requests
except ImportError:
    sys.exit("Missing dependency: pip install requests")

BASE = "http://localhost:8080"
ADMIN_USER = "ops.admin"
ADMIN_EMAIL = os.environ.get("ADMIN_EMAIL", "ops.admin@demo.local")
ADMIN_PASS = os.environ.get("ADMIN_PASSWORD", os.environ.get("ADMIN_PASS", "DemoAdmin#2026!"))
FALLBACK_ADMIN_CREDENTIALS = [
    ("siddhant@bhawanafinance.com", "DemoAdmin#2026!"),
    ("siddhant@bhawanafinance.com", "ChangeMe123!"),
    ("ops.admin@demo.local", "DemoAdmin#2026!"),
    ("ops.admin@demo.local", "ChangeMe123!"),
]
PDF = Path(__file__).resolve().parent.parent / "blank.pdf"
DOCUMENT_TYPES = [
    "PAN_CARD",
    "AADHAAR_FILE",
    "ADDRESS_PROOF",
    "INCOME_PROOF",
    "BANK_STATEMENT",
    "SELFIE_PHOTOGRAPH",
    "KFS",
    "LOAN_AGREEMENT",
]
PLAN = [
    {"name": "intake_started_docs_pending", "expected": "INITIALIZED", "docs": False},
    {"name": "invalid_pre_disbursal_random", "expected": "INVALID", "docs": False, "invalidate": True},
    {"name": "disbursed_no_repayments", "expected": "DISBURSED", "mock_outcome": "DISBURSED", "repayments": 0},
    {"name": "under_repayment_one_paid", "expected": "UNDER_REPAYMENT", "mock_outcome": "DISBURSED", "repayments": 1},
    {"name": "under_repayment_three_paid", "expected": "UNDER_REPAYMENT", "mock_outcome": "DISBURSED", "repayments": 3},
    {"name": "under_repayment_half_paid", "expected": "UNDER_REPAYMENT", "mock_outcome": "DISBURSED", "repayments": "half"},
    {"name": "closed_fully_repaid_success", "expected": "CLOSED", "mock_outcome": "DISBURSED", "repayments": "all"},
    {"name": "foreclosed_success", "expected": "FORECLOSED", "mock_outcome": "DISBURSED", "foreclose": True},
    {"name": "closed_second_success", "expected": "CLOSED", "mock_outcome": "DISBURSED", "repayments": "all"},
    {"name": "under_repayment_late_stage", "expected": "UNDER_REPAYMENT", "mock_outcome": "DISBURSED", "repayments": "late"},
]


def rid(n=6):
    return "".join(random.choices(string.ascii_uppercase + string.digits, k=n))


def rand_pan():
    return (
        "".join(random.choices(string.ascii_uppercase, k=5))
        + "".join(random.choices(string.digits, k=4))
        + random.choice(string.ascii_uppercase)
    )


def rand_aadhaar():
    return "".join(random.choices(string.digits, k=12))


def rand_mobile():
    return random.choice("6789") + "".join(random.choices(string.digits, k=9))


def rand_ifsc():
    return "HDFC0" + "".join(random.choices(string.ascii_uppercase + string.digits, k=6))


def rand_acct():
    return "".join(random.choices(string.digits, k=random.randint(11, 16)))


class Api:
    def __init__(self):
        self.s = requests.Session()
        self.admin_token = None
        self.lsp_token = None

    def call(self, method, path, *, admin=False, idem=None, **kw):
        headers = kw.pop("headers", {})
        token = self.admin_token if admin else self.lsp_token
        if token:
            headers["Authorization"] = f"Bearer {token}"
        if idem:
            headers["Idempotency-Key"] = idem
        return self.s.request(method, f"{BASE}{path}", headers=headers, timeout=120, **kw)


def must(resp, label):
    if not resp.ok:
        raise RuntimeError(f"{label} -> {resp.status_code}: {resp.text[:400]}")
    return resp.json() if resp.text else {}


def main():
    try:
        sys.stdout.reconfigure(line_buffering=True)
    except AttributeError:
        pass

    if not PDF.exists():
        sys.exit(f"blank.pdf not found at {PDF}")

    random.seed()
    pdf_bytes = PDF.read_bytes()
    api = Api()
    authenticate(api)

    products = must(api.call("GET", "/api/v1/lsp/products"), "list products")
    if not products:
        sys.exit("No products provisioned for this LSP. Map one in the admin UI first.")
    product = products[0]
    print(f"using product {product.get('code')} ({product['id']})")

    summary = {}
    created = []
    for index, scenario in enumerate(PLAN, start=1):
        try:
            result = seed_one(api, product, pdf_bytes, scenario, index)
            label = f"{result['scenario']}:{result['status']}"
            summary[label] = summary.get(label, 0) + 1
            created.append(result)
            print(
                f"[{index:>2}/10] {result['scenario']:<32} -> "
                f"{result['status']:<28} payments={result['payments_posted']:<2} "
                f"loan={result.get('loan_account_id') or '-'}"
            )
        except Exception as exc:  # keep going so one bad scenario does not hide the rest
            summary["FAILED"] = summary.get("FAILED", 0) + 1
            print(f"[{index:>2}/10] {scenario['name']:<32} -> FAILED: {exc}")

    print("\nSeed summary:", json.dumps(summary, indent=2))
    if created:
        print("\nCreated loans:")
        print(json.dumps(created, indent=2, default=str))
    if summary.get("FAILED"):
        sys.exit(1)


def authenticate(api: Api) -> None:
    body = login_with_fallbacks(api)
    api.admin_token = body["accessToken"]
    print("admin login OK")

    listing = must(api.call("GET", "/api/v1/internal/admin/api-clients", admin=True), "list api-clients")
    items = listing.get("items", listing) if isinstance(listing, dict) else listing
    if not items:
        sys.exit("No API clients exist. Create one in the admin UI first.")
    client = next((c for c in items if (c.get("status") or "").upper() == "ACTIVE"), items[0])
    print(f"rotating secret for client {client.get('name')} ({client.get('clientId')})")
    rotated = must(
        api.call("POST", f"/api/v1/internal/admin/api-clients/{client['id']}/rotate-secret", admin=True),
        "rotate secret",
    )

    token = must(
        api.call(
            "POST",
            "/api/v1/auth/token",
            json={"clientId": rotated["clientId"], "clientSecret": rotated["clientSecret"]},
            headers={"Content-Type": "application/json"},
        ),
        "token exchange",
    )
    api.lsp_token = token["accessToken"]
    print("LSP token OK")


def login_with_fallbacks(api: Api):
    credentials = [(ADMIN_EMAIL, ADMIN_PASS)]
    for fallback in FALLBACK_ADMIN_CREDENTIALS:
        if fallback not in credentials:
            credentials.append(fallback)

    failures = []
    for email, password in credentials:
        response = api.call(
            "POST",
            "/api/v1/auth/login",
            json={"email": email, "password": password},
            headers={"Content-Type": "application/json"},
        )
        if response.ok:
            return response.json()
        failures.append(f"{email} -> {response.status_code}")

    raise RuntimeError(
        "admin login failed for configured and known local credentials: "
        + ", ".join(failures)
        + ". Set ADMIN_EMAIL and ADMIN_PASSWORD for this environment."
    )


def seed_one(api: Api, product, pdf_bytes, scenario, index):
    app = create_application(api, product, index)
    app_id = app["id"]
    payments_posted = 0
    loan_id = None

    if scenario.get("invalidate"):
        invalidate_application(api, app_id)
        return result(api, scenario, app_id, loan_id, payments_posted)

    if not scenario.get("docs", True):
        return result(api, scenario, app_id, loan_id, payments_posted)

    upload_documents(api, app_id, pdf_bytes)
    approved_or_disbursed = wait_for_status(api, app_id, {"APPROVED_PENDING_DISBURSAL", "DISBURSED", "UNDER_REPAYMENT"})

    if not scenario.get("schedule", True):
        return result(api, scenario, app_id, loan_id, payments_posted)

    outcome = scenario.get("mock_outcome", "DISBURSED")
    if approved_or_disbursed == "APPROVED_PENDING_DISBURSAL":
        must(
            api.call(
                "PUT",
                f"/api/v1/lsp/loan-applications/{app_id}/repayment-schedule",
                json={"mode": "GENERATED"},
                headers={"Content-Type": "application/json"},
            ),
            "repayment schedule",
        )
        must(
            api.call("POST", f"/api/v1/internal/ops/loan-applications/{app_id}/disbursement-requests", admin=True),
            "initiate disbursement",
        )
        mock_response = api.call(
            "POST",
            f"/api/v1/internal/ops/loan-applications/{app_id}/disbursement-requests/mock-outcome",
            admin=True,
            json={"outcome": outcome},
            headers={"Content-Type": "application/json"},
        )
        if not mock_response.ok:
            current_status = status(api, app_id)
            if not (mock_response.status_code == 422 and outcome == "DISBURSED" and current_status == "DISBURSED"):
                must(mock_response, f"mock disbursement {outcome}")

    loan_id = loan_account_id(api, app_id)
    if outcome != "DISBURSED":
        return result(api, scenario, app_id, loan_id, payments_posted)
    if not loan_id:
        raise RuntimeError("disbursed application has no loanAccount.id")

    if scenario.get("foreclose"):
        foreclose_loan(api, loan_id)
        return result(api, scenario, app_id, loan_id, payments_posted)

    repayments = scenario.get("repayments", 0)
    if repayments:
        installments = repayment_schedule(api, loan_id)
        repay_count = repayment_count(repayments, len(installments))
        for installment in installments[:repay_count]:
            post_installment_payment(api, loan_id, installment)
            payments_posted += 1

    return result(api, scenario, app_id, loan_id, payments_posted)


def create_application(api: Api, product, index):
    min_principal = Decimal(str(product.get("minPrincipal", "10000")))
    max_principal = Decimal(str(product.get("maxPrincipal", "500000")))
    amount = ((min_principal + max_principal) / 2 + Decimal(index * 1000)).quantize(Decimal("0.01"))
    if amount > max_principal:
        amount = ((min_principal + max_principal) / 2).quantize(Decimal("0.01"))

    tenure = max(int(product.get("minTenureMonths", 6)), min(int(product.get("maxTenureMonths", 24)), 12))
    interest = Decimal(str(product.get("interestRate", "18.50")))
    first = random.choice(["Aarav", "Anika", "Rohan", "Priya", "Vikram", "Neha", "Kabir", "Diya"])
    last = random.choice(["Sharma", "Patel", "Iyer", "Singh", "Das", "Mehta", "Nair", "Bose"])
    full_name = f"{first} {last}"
    dob = date.today() - timedelta(days=random.randint(25 * 365, 45 * 365))
    monthly_income = (amount * Decimal("2")).quantize(Decimal("0.01"))
    account_number = rand_acct()
    ifsc = rand_ifsc()

    payload = {
        "lspId": product.get("lspId") or lsp_from_token(api),
        "productId": product["id"],
        "lspLoanId": f"SEED-{index:02d}-{rid()}",
        "fullName": full_name,
        "emailAddress": f"{first.lower()}.{last.lower()}.{uuid.uuid4().hex[:6]}@seed.example.com",
        "mobileNumber": rand_mobile(),
        "dob": dob.isoformat(),
        "gender": random.choice(["MALE", "FEMALE"]),
        "maritalStatus": random.choice(["SINGLE", "MARRIED"]),
        "fatherName": f"Father {last}",
        "aadharNumber": rand_aadhaar(),
        "panNumber": rand_pan(),
        "loanAmount": str(amount),
        "interestRate": str(interest),
        "loanTenure": tenure,
        "addressLine1": f"{random.randint(1, 199)} Seed Lane",
        "addressLine2": "Block B",
        "addressCity": "Mumbai",
        "addressState": "Maharashtra",
        "addressZipcode": "400001",
        "employmentStatus": "SALARIED",
        "organizationName": "Seed Corp Pvt Ltd",
        "empId": f"EMP-{rid()}",
        "employmentCity": "Mumbai",
        "employmentState": "Maharashtra",
        "employmentZip": "400001",
        "monthlyIncome": str(monthly_income),
        "annualIncome": str((monthly_income * 12).quantize(Decimal("0.01"))),
        "bankAccountNumber": account_number,
        "bankName": "HDFC Bank",
        "ifscCode": ifsc,
        "accountHolderName": full_name,
        "referencePersonName": "Ref Contact",
        "referencePersonNumber": rand_mobile(),
    }
    return must(
        api.call(
            "POST",
            "/api/v1/lsp/loan-applications",
            json=payload,
            headers={"Content-Type": "application/json"},
            idem=str(uuid.uuid4()),
        ),
        "create app",
    )


def upload_documents(api: Api, app_id, pdf_bytes) -> None:
    files = [("files", (f"{document_type.lower()}.pdf", pdf_bytes, "application/pdf")) for document_type in DOCUMENT_TYPES]
    metadata = [
        {"documentType": document_type, "note": f"seed {document_type}", "sourceReference": f"seed-{document_type.lower()}"}
        for document_type in DOCUMENT_TYPES
    ]
    files.append(("documents", (None, json.dumps(metadata), "application/json")))
    must(api.call("POST", f"/api/v1/lsp/loan-applications/{app_id}/documents/batch", files=files), "docs batch")


def invalidate_application(api: Api, app_id) -> None:
    payload = random.choice(
        [
            {"reasonCode": "REASON_A"},
            {"reasonCode": "REASON_B"},
            {"reasonCode": "REASON_C"},
            {"reasonCode": "OTHERS", "reasonText": "Random seed invalidation before disbursal"},
        ]
    )
    must(
        api.call(
            "POST",
            f"/api/v1/lsp/loan-applications/{app_id}/invalid",
            json=payload,
            headers={"Content-Type": "application/json"},
            idem=str(uuid.uuid4()),
        ),
        "invalidate app",
    )


def repayment_count(repayments, installment_count):
    if repayments == "all":
        return installment_count
    if repayments == "half":
        return max(1, installment_count // 2)
    if repayments == "late":
        return max(1, installment_count - 1)
    return min(int(repayments), installment_count)


def repayment_schedule(api: Api, loan_id):
    rows = must(api.call("GET", f"/api/v1/lsp/loans/{loan_id}/repayment-schedule"), "repayment schedule")
    if isinstance(rows, dict):
        rows = rows.get("installments", [])
    return sorted(rows, key=lambda row: row.get("installmentNumber") or 0)


def post_installment_payment(api: Api, loan_id, installment) -> None:
    amount = Decimal(str(installment.get("outstandingAmount") or installment.get("installmentAmount"))).quantize(
        Decimal("0.01")
    )
    must(
        api.call(
            "POST",
            f"/api/v1/lsp/loans/{loan_id}/payments",
            json={
                "targetInstallmentId": installment["id"],
                "amount": str(amount),
                "postedAt": date.today().isoformat(),
                "reference": f"SEED-PAY-{rid(8)}",
                "channel": random.choice(["UPI", "NEFT", "IMPS", "BANK_TRANSFER"]),
            },
            headers={"Content-Type": "application/json"},
            idem=str(uuid.uuid4()),
        ),
        f"pay installment {installment.get('installmentNumber')}",
    )


def foreclose_loan(api: Api, loan_id) -> None:
    today = date.today().isoformat()
    quote = must(
        api.call(
            "POST",
            f"/api/v1/lsp/loans/{loan_id}/foreclosure-quote",
            json={"effectiveDate": today},
            headers={"Content-Type": "application/json"},
        ),
        "foreclosure quote",
    )
    must(
        api.call(
            "POST",
            f"/api/v1/lsp/loans/{loan_id}/foreclosure-quotes/{quote['id']}/execute",
            json={
                "settlementDate": today,
                "reference": f"SEED-FC-{rid(8)}",
                "note": "Seed foreclosure success",
            },
            headers={"Content-Type": "application/json"},
            idem=str(uuid.uuid4()),
        ),
        "foreclosure execute",
    )


_LSP_ID_CACHE = {}


def lsp_from_token(api: Api):
    if "value" in _LSP_ID_CACHE:
        return _LSP_ID_CACHE["value"]
    import base64

    parts = api.lsp_token.split(".")
    padded_claims = parts[1] + "=" * (-len(parts[1]) % 4)
    claims = json.loads(base64.urlsafe_b64decode(padded_claims))
    _LSP_ID_CACHE["value"] = claims.get("lspId", "")
    return _LSP_ID_CACHE["value"]


def status(api: Api, app_id):
    response = api.call("GET", f"/api/v1/lsp/loan-applications/{app_id}")
    if response.ok and response.text:
        return response.json().get("status", "UNKNOWN")
    return "UNKNOWN"


def detail(api: Api, app_id):
    return must(api.call("GET", f"/api/v1/lsp/loan-applications/{app_id}"), "application detail")


def loan_account_id(api: Api, app_id):
    loan_account = detail(api, app_id).get("loanAccount") or {}
    return loan_account.get("id")


def wait_for_status(api: Api, app_id, expected_statuses, timeout_seconds=20):
    deadline = time.time() + timeout_seconds
    latest = status(api, app_id)
    while time.time() < deadline:
        if latest in expected_statuses:
            return latest
        time.sleep(0.5)
        latest = status(api, app_id)
    raise RuntimeError(f"Expected status in {sorted(expected_statuses)} but found {latest}")


def result(api: Api, scenario, app_id, loan_id, payments_posted):
    expected = scenario["expected"]
    final_status = wait_for_status(api, app_id, {expected}, timeout_seconds=10)
    return {
        "scenario": scenario["name"],
        "application_id": app_id,
        "loan_account_id": loan_id,
        "status": final_status,
        "expected": expected,
        "payments_posted": payments_posted,
    }


if __name__ == "__main__":
    main()
