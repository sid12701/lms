#!/usr/bin/env python3
"""
lsp-api-client — interactive LSP machine client for Bhawana LMS.

Walks one loan lifecycle step-by-step via the public LSP APIs. After each call you
confirm [y/n] before the next step so you can verify state in the admin UI.

Setup:
  1. Admin UI → API Clients → create machine user for your LSP (save clientId + clientSecret).
  2. Map at least one ACTIVE product to the LSP.
  3. Fill CLIENT_ID and CLIENT_SECRET below (and optional ADMIN_* for disbursement).
  4. Optional: set LSP webhook URL to http://<your-host>:8765/webhooks with WEBHOOK_SIGNING_SECRET.
  5. pip install -r lsp-api-client-requirements.txt
  6. python lsp-api-client.py
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import random
import string
import sys
import threading
import uuid
from dataclasses import dataclass, field
from datetime import date, timedelta
from decimal import Decimal
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any, Callable

try:
    import requests
except ImportError:
    print("Missing dependency: pip install -r lsp-api-client-requirements.txt", file=sys.stderr)
    raise

# =============================================================================
# Configuration — paste credentials from the admin UI after creating the API client
# =============================================================================

BASE_URL = "http://localhost:8080"

CLIENT_ID = ""
CLIENT_SECRET = ""

# Optional overrides (left blank = auto-detect from token / product list)
LSP_ID = ""
PRODUCT_ID = ""

# Admin login used only for disbursement initiation + mock outcome (SYSTEM_ADMIN)
ADMIN_USERNAME = ""
ADMIN_PASSWORD = ""

# Local webhook receiver — configure the same signing secret on the LSP subscription
WEBHOOK_ENABLED = True
WEBHOOK_HOST = "0.0.0.0"
WEBHOOK_PORT = 8765
WEBHOOK_SIGNING_SECRET = ""

# Skip optional APIs (invalidate loan, foreclosure quote) when False
INCLUDE_OPTIONAL_STEPS = True

# PDF sent for every document upload (same file, renamed per document type)
DOCUMENT_SOURCE_FILE = Path(__file__).resolve().parent / "blank.pdf"

# =============================================================================

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


def document_upload_filename(document_type: str) -> str:
    return f"{document_type.lower()}.pdf"


def load_document_pdf_bytes() -> bytes:
    path = Path(DOCUMENT_SOURCE_FILE)
    if not path.is_file():
        raise FileNotFoundError(
            f"Document source PDF not found: {path}\n"
            "Place blank.pdf in the project root or set DOCUMENT_SOURCE_FILE."
        )
    return path.read_bytes()


@dataclass
class RunContext:
    base_url: str
    access_token: str = ""
    admin_token: str = ""
    lsp_id: str = ""
    product_id: str = ""
    product: dict[str, Any] = field(default_factory=dict)
    external_loan_id: str = ""
    application_id: str = ""
    borrower_id: str = ""
    loan_account_id: str = ""
    loan_amount: Decimal = Decimal("0")
    bank_account_number: str = ""
    ifsc_code: str = ""
    account_holder_name: str = ""
    installments: list[dict[str, Any]] = field(default_factory=list)
    webhook_events: list[dict[str, Any]] = field(default_factory=list)
    onboarding_payload: dict[str, Any] = field(default_factory=dict)


class WebhookReceiver(BaseHTTPRequestHandler):
    signing_secret: str = ""
    events: list[dict[str, Any]] | None = None

    def log_message(self, fmt: str, *args: Any) -> None:
        return

    def do_POST(self) -> None:  # noqa: N802
        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length).decode("utf-8")
        timestamp = self.headers.get("X-Webhook-Timestamp", "")
        signature = self.headers.get("X-Webhook-Signature", "")
        event_type = self.headers.get("X-Webhook-Event", "")
        delivery_id = self.headers.get("X-Webhook-Delivery-Id", "")
        correlation_id = self.headers.get("X-Correlation-Id", "")

        verified = False
        if WebhookReceiver.signing_secret:
            expected = sign_webhook(WebhookReceiver.signing_secret, timestamp, body)
            verified = hmac.compare_digest(signature, f"v1={expected}")
        else:
            verified = True

        record = {
            "eventType": event_type,
            "deliveryId": delivery_id,
            "correlationId": correlation_id,
            "timestamp": timestamp,
            "signatureVerified": verified,
            "payload": json.loads(body) if body else {},
        }
        if WebhookReceiver.events is not None:
            WebhookReceiver.events.append(record)

        print("\n--- Webhook received ---")
        print(json.dumps(record, indent=2, default=str))
        print("------------------------\n")

        status = 200 if verified else 401
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(json.dumps({"ok": verified}).encode())

    def do_GET(self) -> None:  # noqa: N802
        if self.path == "/health":
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b"ok")
            return
        self.send_response(404)
        self.end_headers()


def sign_webhook(secret: str, timestamp: str, payload_json: str) -> str:
    message = f"{timestamp}.{payload_json}".encode()
    digest = hmac.new(secret.encode(), message, hashlib.sha256).hexdigest()
    return digest


def start_webhook_server(ctx: RunContext) -> ThreadingHTTPServer | None:
    if not WEBHOOK_ENABLED:
        return None
    WebhookReceiver.signing_secret = WEBHOOK_SIGNING_SECRET
    WebhookReceiver.events = ctx.webhook_events
    server = ThreadingHTTPServer((WEBHOOK_HOST, WEBHOOK_PORT), WebhookReceiver)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    print(
        f"Webhook listener: http://{WEBHOOK_HOST}:{WEBHOOK_PORT}/webhooks "
        f"(health: /health, events captured: {len(ctx.webhook_events)})"
    )
    if not WEBHOOK_SIGNING_SECRET:
        print("  Warning: WEBHOOK_SIGNING_SECRET is empty — signatures will not be verified.")
    return server


def random_pan() -> str:
    letters = string.ascii_uppercase
    return (
        "".join(random.choices(letters, k=5))
        + "".join(random.choices(string.digits, k=4))
        + random.choice(letters)
    )


def random_aadhaar() -> str:
    return "".join(random.choices(string.digits, k=12))


def random_mobile() -> str:
    return "9" + "".join(random.choices(string.digits, k=9))


def random_ifsc() -> str:
    bank = "".join(random.choices(string.ascii_uppercase, k=4))
    branch = "".join(random.choices(string.ascii_uppercase + string.digits, k=6))
    return f"{bank}0{branch}"


def random_account_number() -> str:
    return "".join(random.choices(string.digits, k=12))


def random_external_loan_id() -> str:
    return f"LSP-{uuid.uuid4().hex[:12].upper()}"


def decode_jwt_claims(token: str) -> dict[str, Any]:
    try:
        payload_segment = token.split(".")[1]
        padding = "=" * (-len(payload_segment) % 4)
        raw = base64.urlsafe_b64decode(payload_segment + padding)
        return json.loads(raw.decode())
    except (IndexError, json.JSONDecodeError, ValueError):
        return {}


def prompt_continue(step_label: str) -> bool:
    while True:
        answer = input(f"\n[{step_label}] Continue to next API call? [y/n]: ").strip().lower()
        if answer in ("y", "yes"):
            return True
        if answer in ("n", "no"):
            return False
        print("  Please enter y or n.")


def print_response(label: str, response: requests.Response) -> None:
    print(f"\n=== {label} ===")
    print(f"HTTP {response.status_code} {response.reason}")
    try:
        body = response.json()
        print(json.dumps(body, indent=2, default=str))
    except ValueError:
        text = response.text
        print(text[:2000] + ("..." if len(text) > 2000 else ""))
    if not response.ok:
        print("  ^ request failed — fix the issue before continuing.")


def api(
    ctx: RunContext,
    method: str,
    path: str,
    *,
    token: str | None = None,
    admin: bool = False,
    idempotency_key: str | None = None,
    **kwargs: Any,
) -> requests.Response:
    url = f"{ctx.base_url.rstrip('/')}{path}"
    headers = kwargs.pop("headers", {})
    bearer = ctx.admin_token if admin else (token or ctx.access_token)
    if bearer:
        headers["Authorization"] = f"Bearer {bearer}"
    if idempotency_key:
        headers["Idempotency-Key"] = idempotency_key
    return requests.request(method, url, headers=headers, timeout=120, **kwargs)


def require_config() -> None:
    if not CLIENT_ID or not CLIENT_SECRET:
        print(
            "Set CLIENT_ID and CLIENT_SECRET at the top of this file "
            "(from Admin → API Clients after creating the machine user).",
            file=sys.stderr,
        )
        sys.exit(1)


def build_onboarding_payload(ctx: RunContext) -> dict[str, Any]:
    product = ctx.product
    min_p = Decimal(str(product.get("minPrincipal", "10000")))
    max_p = Decimal(str(product.get("maxPrincipal", "500000")))
    loan_amount = (min_p + max_p) / 2
    loan_amount = loan_amount.quantize(Decimal("0.01"))

    min_tenure = int(product.get("minTenureMonths", 6))
    max_tenure = int(product.get("maxTenureMonths", 24))
    tenure = max(min_tenure, min(max_tenure, 12))

    interest = product.get("interestRate")
    if interest is None:
        interest = Decimal("18.50")
    else:
        interest = Decimal(str(interest))

    dob = date.today() - timedelta(days=random.randint(25 * 365, 45 * 365))
    first = random.choice(["Aarav", "Anika", "Rohan", "Priya", "Vikram", "Neha"])
    last = random.choice(["Sharma", "Patel", "Iyer", "Singh", "Das", "Mehta"])
    full_name = f"{first} {last}"

    ctx.external_loan_id = random_external_loan_id()
    ctx.loan_amount = loan_amount
    ctx.bank_account_number = random_account_number()
    ctx.ifsc_code = random_ifsc()
    ctx.account_holder_name = full_name

    monthly_income = (loan_amount * Decimal("2")).quantize(Decimal("0.01"))
    annual_income = (monthly_income * 12).quantize(Decimal("0.01"))

    payload = {
        "lspId": ctx.lsp_id,
        "productId": ctx.product_id,
        "lspLoanId": ctx.external_loan_id,
        "fullName": full_name,
        "emailAddress": f"{first.lower()}.{last.lower()}.{uuid.uuid4().hex[:6]}@lsp-sim.example.com",
        "mobileNumber": random_mobile(),
        "dob": dob.isoformat(),
        "gender": random.choice(["MALE", "FEMALE"]),
        "maritalStatus": random.choice(["SINGLE", "MARRIED"]),
        "fatherName": f"Father {last}",
        "aadharNumber": random_aadhaar(),
        "panNumber": random_pan(),
        "loanAmount": float(loan_amount),
        "interestRate": float(interest),
        "loanTenure": tenure,
        "addressLine1": f"{random.randint(1, 199)} Simulator Lane",
        "addressLine2": "Block B",
        "addressCity": "Mumbai",
        "addressState": "Maharashtra",
        "addressZipcode": "400001",
        "employmentStatus": "SALARIED",
        "organizationName": "Sim Corp Pvt Ltd",
        "empId": f"EMP-{uuid.uuid4().hex[:6].upper()}",
        "employmentCity": "Mumbai",
        "employmentState": "Maharashtra",
        "employmentZip": "400001",
        "monthlyIncome": float(monthly_income),
        "annualIncome": float(annual_income),
        "bankAccountNumber": ctx.bank_account_number,
        "bankName": "HDFC Bank",
        "ifscCode": ctx.ifsc_code,
        "accountHolderName": ctx.account_holder_name,
        "referencePersonName": "Ref Contact",
        "referencePersonNumber": random_mobile(),
    }
    ctx.onboarding_payload = payload
    return payload


def step_token_exchange(ctx: RunContext) -> None:
    response = api(
        ctx,
        "POST",
        "/api/v1/auth/token",
        token=None,
        json={"clientId": CLIENT_ID, "clientSecret": CLIENT_SECRET},
        headers={"Content-Type": "application/json"},
    )
    print_response("POST /api/v1/auth/token", response)
    response.raise_for_status()
    ctx.access_token = response.json()["accessToken"]
    claims = decode_jwt_claims(ctx.access_token)
    ctx.lsp_id = LSP_ID or claims.get("lspId", "")
    if not ctx.lsp_id:
        raise RuntimeError("Could not resolve lspId from token; set LSP_ID in config.")


def step_admin_login(ctx: RunContext) -> None:
    if not ADMIN_USERNAME or not ADMIN_PASSWORD:
        print("\n(Skipping admin login — set ADMIN_USERNAME / ADMIN_PASSWORD to automate disbursement.)")
        return
    response = api(
        ctx,
        "POST",
        "/api/v1/auth/login",
        token=None,
        json={"username": ADMIN_USERNAME, "password": ADMIN_PASSWORD},
        headers={"Content-Type": "application/json"},
    )
    print_response("POST /api/v1/auth/login (admin)", response)
    response.raise_for_status()
    body = response.json()
    ctx.admin_token = body["accessToken"]
    if body.get("passwordChangeRequired"):
        print("  Admin must change password before disbursement APIs will work.")


def step_list_products(ctx: RunContext) -> None:
    response = api(ctx, "GET", "/api/v1/lsp/products")
    print_response("GET /api/v1/lsp/products", response)
    response.raise_for_status()
    products = response.json()
    if not products:
        raise RuntimeError("No products provisioned for this LSP.")
    if PRODUCT_ID:
        match = next((p for p in products if p["id"] == PRODUCT_ID), None)
        if not match:
            raise RuntimeError(f"PRODUCT_ID {PRODUCT_ID} not in provisioned list.")
        ctx.product = match
    else:
        ctx.product = products[0]
    ctx.product_id = ctx.product["id"]
    print(f"\nUsing product: {ctx.product.get('code')} ({ctx.product_id})")


def step_create_application(ctx: RunContext) -> None:
    payload = build_onboarding_payload(ctx)
    print("\nGenerated onboarding payload (random borrower each run):")
    print(json.dumps(payload, indent=2, default=str))
    response = api(
        ctx,
        "POST",
        "/api/v1/lsp/loan-applications",
        json=payload,
        headers={"Content-Type": "application/json"},
    )
    print_response("POST /api/v1/lsp/loan-applications", response)
    response.raise_for_status()
    body = response.json()
    ctx.application_id = body["id"]
    ctx.borrower_id = body.get("borrowerId", "")
    print(f"\nTrack in admin UI — applicationId={ctx.application_id}, lspLoanId={ctx.external_loan_id}")


def step_get_application(ctx: RunContext) -> None:
    response = api(ctx, "GET", f"/api/v1/lsp/loan-applications/{ctx.application_id}")
    print_response("GET /api/v1/lsp/loan-applications/{id}", response)
    response.raise_for_status()
    _sync_ids_from_detail(ctx, response.json())


def step_get_by_external_id(ctx: RunContext) -> None:
    response = api(
        ctx,
        "GET",
        f"/api/v1/lsp/loan-applications/external/{ctx.external_loan_id}",
    )
    print_response("GET /api/v1/lsp/loan-applications/external/{lspLoanId}", response)
    response.raise_for_status()
    _sync_ids_from_detail(ctx, response.json())


def step_list_applications(ctx: RunContext) -> None:
    response = api(
        ctx,
        "GET",
        "/api/v1/lsp/loan-applications",
        params={"q": ctx.external_loan_id, "limit": 5, "paginationDetails": "ON"},
    )
    print_response("GET /api/v1/lsp/loan-applications?q=...", response)
    print(f"  X-Total-Count: {response.headers.get('X-Total-Count')}")


def step_invalid_reasons(ctx: RunContext) -> None:
    response = api(ctx, "GET", "/api/v1/lsp/loan-applications/invalid-reasons")
    print_response("GET /api/v1/lsp/loan-applications/invalid-reasons", response)


def step_upload_documents_batch(ctx: RunContext) -> None:
    pdf_bytes = load_document_pdf_bytes()
    print(f"Using document file: {Path(DOCUMENT_SOURCE_FILE).resolve()} ({len(pdf_bytes)} bytes)")

    metadata = [
        {
            "documentType": doc_type,
            "note": f"LSP simulator upload {doc_type}",
            "sourceReference": f"sim-{doc_type.lower()}",
        }
        for doc_type in DOCUMENT_TYPES
    ]
    files: list[tuple[str, tuple[str, bytes, str]]] = []
    for doc_type in DOCUMENT_TYPES:
        upload_name = document_upload_filename(doc_type)
        files.append(
            (
                "files",
                (upload_name, pdf_bytes, "application/pdf"),
            )
        )
        print(f"  {doc_type} -> {upload_name}")
    files.append(
        (
            "documents",
            (None, json.dumps(metadata), "application/json"),
        )
    )
    response = api(
        ctx,
        "POST",
        f"/api/v1/lsp/loan-applications/{ctx.application_id}/documents/batch",
        files=files,
    )
    print_response("POST /api/v1/lsp/loan-applications/{id}/documents/batch", response)
    response.raise_for_status()
    print("\nAfter all KYC docs, STP may auto-move the loan to APPROVED_PENDING_DISBURSAL.")


def step_list_documents(ctx: RunContext) -> None:
    response = api(
        ctx,
        "GET",
        f"/api/v1/lsp/loan-applications/{ctx.application_id}/documents",
    )
    print_response("GET /api/v1/lsp/loan-applications/{id}/documents", response)


def step_upsert_repayment_schedule(ctx: RunContext) -> None:
    response = api(
        ctx,
        "PUT",
        f"/api/v1/lsp/loan-applications/{ctx.application_id}/repayment-schedule",
        json={"mode": "GENERATED"},
        headers={"Content-Type": "application/json"},
    )
    print_response("PUT /api/v1/lsp/loan-applications/{id}/repayment-schedule (GENERATED)", response)
    response.raise_for_status()


def step_get_borrower_bank_details(ctx: RunContext) -> None:
    if not ctx.borrower_id:
        print("  No borrowerId on application — skipping.")
        return
    response = api(ctx, "GET", f"/api/v1/lsp/borrowers/{ctx.borrower_id}/bank-details")
    print_response("GET /api/v1/lsp/borrowers/{borrowerId}/bank-details", response)


def step_patch_borrower_bank_details(ctx: RunContext) -> None:
    if not ctx.borrower_id:
        print("  No borrowerId — skipping.")
        return
    body = {
        "bankAccountNumber": ctx.bank_account_number,
        "bankName": "HDFC Bank",
        "ifscCode": ctx.ifsc_code,
        "accountHolderName": ctx.account_holder_name,
    }
    response = api(
        ctx,
        "PATCH",
        f"/api/v1/lsp/borrowers/{ctx.borrower_id}/bank-details",
        json=body,
        headers={"Content-Type": "application/json"},
    )
    print_response("PATCH /api/v1/lsp/borrowers/{borrowerId}/bank-details", response)


def step_disbursement_bank_check(ctx: RunContext) -> None:
    body = {
        "disbursalAmount": float(ctx.loan_amount),
        "bankAccountNumber": ctx.bank_account_number,
        "ifscCode": ctx.ifsc_code,
        "accountHolderName": ctx.account_holder_name,
    }
    response = api(
        ctx,
        "POST",
        f"/api/v1/lsp/loan-applications/{ctx.application_id}/disbursement-bank-check",
        json=body,
        headers={"Content-Type": "application/json"},
    )
    print_response("POST /api/v1/lsp/loan-applications/{id}/disbursement-bank-check", response)


def step_admin_initiate_disbursement(ctx: RunContext) -> None:
    if not ctx.admin_token:
        print(
            "\n  MANUAL: In admin UI (loan application detail), initiate disbursement,\n"
            "  then apply mock outcome DISBURSED (or set ADMIN_USERNAME/PASSWORD)."
        )
        return
    response = api(
        ctx,
        "POST",
        f"/api/v1/internal/ops/loan-applications/{ctx.application_id}/disbursement-requests",
        admin=True,
    )
    print_response("POST /internal/ops/.../disbursement-requests", response)
    response.raise_for_status()


def step_admin_mock_disbursement(ctx: RunContext) -> None:
    if not ctx.admin_token:
        print(
            "\n  MANUAL: Resolve mock disbursement with outcome DISBURSED in admin UI.\n"
            "  Expect loan status DISBURSED and repayment schedule present."
        )
        return
    response = api(
        ctx,
        "POST",
        f"/api/v1/internal/ops/loan-applications/{ctx.application_id}/disbursement-requests/mock-outcome",
        admin=True,
        json={"outcome": "DISBURSED"},
        headers={"Content-Type": "application/json"},
    )
    print_response("POST /internal/ops/.../mock-outcome", response)
    response.raise_for_status()
    _sync_ids_from_detail(ctx, response.json())


def step_get_loan(ctx: RunContext) -> None:
    if not ctx.loan_account_id:
        step_get_application(ctx)
    if not ctx.loan_account_id:
        raise RuntimeError("loanAccountId not available — complete disbursement first.")
    response = api(ctx, "GET", f"/api/v1/lsp/loans/{ctx.loan_account_id}")
    print_response("GET /api/v1/lsp/loans/{loanId}", response)
    response.raise_for_status()


def step_get_repayment_schedule(ctx: RunContext) -> None:
    response = api(
        ctx,
        "GET",
        f"/api/v1/lsp/loans/{ctx.loan_account_id}/repayment-schedule",
    )
    print_response("GET /api/v1/lsp/loans/{loanId}/repayment-schedule", response)
    response.raise_for_status()
    ctx.installments = response.json()


def step_record_payments_loop(ctx: RunContext) -> None:
    if not ctx.installments:
        step_get_repayment_schedule(ctx)
    for inst in ctx.installments:
        inst_id = inst["id"]
        amount = inst.get("outstandingAmount") or inst.get("installmentAmount")
        label = f"installment #{inst.get('installmentNumber')} id={inst_id} amount={amount}"
        if not prompt_continue(f"LSP payment {label}"):
            print("  Stopped repayment loop.")
            break
        body = {
            "targetInstallmentId": inst_id,
            "amount": float(Decimal(str(amount))),
            "postedAt": date.today().isoformat(),
            "reference": f"SIM-{inst.get('installmentNumber')}-{uuid.uuid4().hex[:8]}",
            "channel": "UPI",
        }
        response = api(
            ctx,
            "POST",
            f"/api/v1/lsp/loans/{ctx.loan_account_id}/payments",
            json=body,
            headers={"Content-Type": "application/json"},
            idempotency_key=str(uuid.uuid4()),
        )
        print_response(f"POST /api/v1/lsp/loans/{{loanId}}/payments ({label})", response)
        if not response.ok:
            break


def step_list_payments(ctx: RunContext) -> None:
    response = api(ctx, "GET", f"/api/v1/lsp/loans/{ctx.loan_account_id}/payments")
    print_response("GET /api/v1/lsp/loans/{loanId}/payments", response)


def step_foreclosure_quote(ctx: RunContext) -> None:
    body = {"effectiveDate": date.today().isoformat()}
    response = api(
        ctx,
        "POST",
        f"/api/v1/lsp/loans/{ctx.loan_account_id}/foreclosure-quote",
        json=body,
        headers={"Content-Type": "application/json"},
    )
    print_response("POST /api/v1/lsp/loans/{loanId}/foreclosure-quote", response)


def step_invalidate_application(ctx: RunContext) -> None:
    """Optional — only valid before servicing; shown for API coverage."""
    body = {"reasonCode": "REASON_A"}
    response = api(
        ctx,
        "POST",
        f"/api/v1/lsp/loan-applications/{ctx.application_id}/invalid",
        json=body,
        headers={"Content-Type": "application/json"},
        idempotency_key=str(uuid.uuid4()),
    )
    print_response("POST /api/v1/lsp/loan-applications/{id}/invalid (expect failure if disbursed)", response)


def step_webhook_summary(ctx: RunContext) -> None:
    print(f"\nWebhooks captured this run: {len(ctx.webhook_events)}")
    for event in ctx.webhook_events:
        print(f"  - {event.get('eventType')} verified={event.get('signatureVerified')}")


def _sync_ids_from_detail(ctx: RunContext, detail: dict[str, Any]) -> None:
    ctx.application_id = detail.get("id", ctx.application_id)
    ctx.borrower_id = detail.get("borrowerId", ctx.borrower_id)
    loan_account = detail.get("loanAccount") or {}
    if loan_account.get("id"):
        ctx.loan_account_id = loan_account["id"]
    print(f"  status={detail.get('status')} loanAccount.status={loan_account.get('status')}")


def run_step(ctx: RunContext, label: str, fn: Callable[[RunContext], None]) -> None:
    print(f"\n{'=' * 72}\nNext: {label}\n{'=' * 72}")
    if not prompt_continue(label):
        print("Stopped by user.")
        sys.exit(0)
    fn(ctx)


def main() -> None:
    require_config()
    ctx = RunContext(base_url=BASE_URL.rstrip("/"))
    webhook_server = start_webhook_server(ctx)

    steps: list[tuple[str, Callable[[RunContext], None]]] = [
        ("Exchange API client credentials for JWT", step_token_exchange),
        ("Admin login (optional, for disbursement)", step_admin_login),
        ("List provisioned loan products", step_list_products),
        ("Create loan application (random valid borrower)", step_create_application),
        ("Get loan application by id", step_get_application),
        ("Get loan application by external lspLoanId", step_get_by_external_id),
        ("List loan applications (search)", step_list_applications),
        ("List invalid-loan reason codes", step_invalid_reasons),
        ("Batch upload all required KYC documents", step_upload_documents_batch),
        ("List submitted documents checklist", step_list_documents),
        ("Refresh application (check STP status)", step_get_application),
        ("Generate repayment schedule (GENERATED)", step_upsert_repayment_schedule),
        ("Get borrower bank details", step_get_borrower_bank_details),
        ("Update borrower bank details", step_patch_borrower_bank_details),
        ("Disbursement bank check (pre-disbursement validation)", step_disbursement_bank_check),
        ("Initiate disbursement (admin API or manual UI)", step_admin_initiate_disbursement),
        ("Apply mock disbursement outcome DISBURSED", step_admin_mock_disbursement),
        ("Get loan application after disbursement", step_get_application),
        ("Get loan account detail", step_get_loan),
        ("Get repayment schedule", step_get_repayment_schedule),
        ("Record payments (one installment per confirm)", step_record_payments_loop),
        ("List payment transactions", step_list_payments),
        ("Final loan / application status", step_get_application),
    ]

    if INCLUDE_OPTIONAL_STEPS:
        steps.append(("Request foreclosure quote", step_foreclosure_quote))

    steps.append(("Webhook delivery summary", step_webhook_summary))

    print(
        "\nlsp-api-client\n"
        f"  API base: {ctx.base_url}\n"
        f"  Client:   {CLIENT_ID[:8]}...\n"
        "  Each step waits for [y/n] so you can verify the admin UI.\n"
    )

    try:
        for label, fn in steps:
            if fn is step_record_payments_loop:
                print(f"\n{'=' * 72}\nNext: {label}\n{'=' * 72}")
                fn(ctx)
                continue
            run_step(ctx, label, fn)
    except requests.HTTPError as exc:
        print(f"\nHTTP error: {exc}", file=sys.stderr)
        sys.exit(1)
    except KeyboardInterrupt:
        print("\nInterrupted.")
        sys.exit(130)
    finally:
        if webhook_server:
            webhook_server.shutdown()

    print("\nDone. Loan lifecycle walkthrough complete.")
    print(f"  applicationId: {ctx.application_id}")
    print(f"  loanAccountId: {ctx.loan_account_id}")
    print(f"  lspLoanId:     {ctx.external_loan_id}")


if __name__ == "__main__":
    main()
