#!/usr/bin/env python3
"""
Drive a loan application to APPROVED_PENDING_DISBURSAL through the genuine
LSP-origination business flow, then hold it there so a human/agent can
actually look at the disbursement UI in a browser.

WHY THIS EXISTS
----------------
Two full audit passes found that the money-critical disbursement
UI (`DisbursementInitiateDialog`, `DisbursementGateBanner`, the disbursement
preview) has *never* been exercised live in two full audit passes, because:

  1. APPROVED_PENDING_DISBURSAL requires all 8 KYC document types uploaded
     (see LoanApplicationDocumentRequirements / LoanAutoApprovalGateService),
     and `manual-status` explicitly refuses to fast-track into it
     (LoanApplicationStatus.MANUAL_OVERRIDE_TARGET_BLOCKED includes
     APPROVED_PENDING_DISBURSAL — see LoanApplicationLifecycleService
     .manuallyOverrideStatus -> "Use the standard approval flow instead of a
     manual status update.").
  2. Even when reached honestly, the automated disbursement worker
     (LoanDisbursementWorker, fixed-delay 30s, ENABLED BY DEFAULT via
     app.disbursement.worker.enabled) picks up every application sitting in
     APPROVED_PENDING_DISBURSAL / DISBURSEMENT_RETRY on its very next tick
     and moves it on (to DISBURSED, REJECTED, or DISBURSEMENT_RETRY) — so the
     state is normally gone within ~30-60 seconds of being created.

This script does two things no previous script did:
  a. Reaches APPROVED_PENDING_DISBURSAL for real: creates an application via
     POST /api/v1/lsp/loan-applications, uploads all 8 required document
     types as real multipart files (so they count as LMS-managed content —
     required for `hasAllRequiredLmsManagedDocuments`), which synchronously
     triggers LoanAutoApprovalGateService -> auto-approval inside the same
     HTTP request that uploads the 8th document.
  b. HOLDS it there: immediately deactivates a dedicated, isolated LSP
     (created by this script, used by nothing else) via
     PUT /api/v1/internal/admin/lsps/{id}/status. The automated worker's very
     first check in LoanDisbursementWorkerProcessor.processApplicationAsAdmin
     is `application.getLsp().getStatus() != LspStatus.ACTIVE -> skip`, so an
     inactive LSP freezes every loan under it indefinitely, with zero effect
     on manual ops actions (initiate / mock-outcome / GET), which do not
     check LSP status at all. Re-activate with --release when done.

Optionally (--also-seed-retry) also creates a second application and drives
it straight to DISBURSEMENT_RETRY (the other state never seen live) using a
deterministic mock-provider decline: MockIciciDisbursementScenario resolves
scenarios off the beneficiary IFSC, and DisbursementOutcomeApplier.resolve()
sends any FAILED+TECHNICAL outcome straight to DISBURSEMENT_RETRY (not a
5-attempt exhaustion loop — that cap only gates the *automated* worker's own
retries). The manual mock-outcome endpoint always applies FAILED as
TECHNICAL (LoanDisbursementCommandService.toProviderOutcome), so:
    POST .../disbursement-requests            (initiate)
    POST .../disbursement-requests/mock-outcome {"outcome":"FAILED"}
is sufficient — two admin calls, no waiting on the worker.

USAGE
-----
    python3 scripts/seed_disbursement_pending.py                # seed + hold
    python3 scripts/seed_disbursement_pending.py --also-seed-retry
    python3 scripts/seed_disbursement_pending.py --release <lspId>   # unfreeze
    python3 scripts/seed_disbursement_pending.py --status <lspId>    # re-check

Re-running with no arguments is safe: the dedicated LSP, its product mapping
and its API client are looked up by name and reused rather than duplicated;
only a fresh borrower + application are created each time (a loan applicant
can only carry one open loan at a time, so applications are never reused).
"""
from __future__ import annotations

import argparse
import json
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
FRONTEND_BASE = "http://localhost:5173"

ADMIN_EMAIL = "siddhant@bhawanafinance.com"
ADMIN_PASSWORD = "ChangeMe123!"
FALLBACK_ADMIN_CREDENTIALS = [
    (ADMIN_EMAIL, ADMIN_PASSWORD),
    ("ops.admin@demo.local", "DemoAdmin#2026!"),
    ("ops.admin@demo.local", "ChangeMe123!"),
]

DEDICATED_LSP_NAME = "WS5 Disbursement Audit LSP"
DEDICATED_LSP_CODE_PREFIX = "WS5-DISB-"
DEDICATED_CLIENT_NAME = "ws5-disbursement-audit-client"

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

# Minimal-but-genuinely-valid single-page PDF. Starts with "%PDF" (satisfies
# DocumentUploadPolicy.validateContentMatchesMime) and is well-formed enough
# to open in a real PDF viewer (satisfies DocumentUploadPolicy's per-type
# MIME allowlist for every one of the 8 required types, since PDF is
# accepted everywhere -- see DocumentUploadPolicy.constraintsFor).
PDF_BYTES = (
    b"%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n"
    b"2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n"
    b"3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 200 200]>>endobj\n"
    b"xref\n0 4\n0000000000 65535 f \n0000000009 00000 n \n"
    b"0000000052 00000 n \n0000000101 00000 n \ntrailer<</Size 4/Root 1 0 R>>\n"
    b"startxref\n164\n%%EOF\n"
)

# Reserved mock-provider IFSC markers (MockIciciDisbursementScenario). Any
# non-reserved IFSC resolves to SUCCESS. MOCK0NPCIDN is a TECHNICAL decline,
# which DisbursementOutcomeApplier.resolve() routes to DISBURSEMENT_RETRY
# (not REJECTED -- that's reserved for BUSINESS declines).
IFSC_TECHNICAL_DECLINE = "MOCK0NPCIDN"


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
        raise RuntimeError(f"{label} -> {resp.status_code}: {resp.text[:600]}")
    return resp.json() if resp.text else {}


def log(msg):
    print(msg, flush=True)


# ─── Auth ──────────────────────────────────────────────────────────────────


def authenticate_admin(api: Api) -> None:
    failures = []
    for email, password in FALLBACK_ADMIN_CREDENTIALS:
        response = api.call(
            "POST",
            "/api/v1/auth/login",
            json={"email": email, "password": password},
            headers={"Content-Type": "application/json"},
        )
        if response.ok:
            api.admin_token = response.json()["accessToken"]
            log(f"[auth] admin login OK ({email})")
            return
        failures.append(f"{email} -> {response.status_code}")
    raise RuntimeError("admin login failed: " + "; ".join(failures))


# ─── Dedicated, isolated LSP / product mapping / API client ────────────────


def ensure_dedicated_lsp(api: Api, suffix: str = "") -> dict:
    # Each independently-held loan gets its OWN dedicated LSP. Sharing one LSP
    # across two loans means reactivating it to onboard the second loan also
    # un-holds the first (proven live: reactivating this LSP to create loan B
    # exposed loan A back to the worker, which disbursed it ~24s later). One
    # LSP per hold avoids any cross-contamination between holds.
    name = f"{DEDICATED_LSP_NAME} {suffix}".strip()
    lsps = must(api.call("GET", "/api/v1/internal/admin/lsps", admin=True), "list lsps")
    existing = next((l for l in lsps if l.get("name") == name), None)
    if existing:
        log(f"[lsp:{suffix or '-'}] reusing dedicated LSP {existing['id']} status={existing['status']}")
        return existing
    created = must(
        api.call(
            "POST",
            "/api/v1/internal/admin/lsps",
            admin=True,
            json={"code": DEDICATED_LSP_CODE_PREFIX + (suffix or "") + "-" + rid(6), "name": name, "status": "ACTIVE"},
            headers={"Content-Type": "application/json"},
            idem=str(uuid.uuid4()),
        ),
        "create dedicated lsp",
    )
    log(f"[lsp:{suffix or '-'}] created dedicated LSP {created['id']}")
    return created


def ensure_active(api: Api, lsp: dict) -> dict:
    """The dedicated LSP may have been left INACTIVE by a prior --hold run."""
    if lsp["status"] == "ACTIVE":
        return lsp
    log(f"[lsp] {lsp['id']} was {lsp['status']} from a prior run; reactivating so seeding can proceed")
    return set_lsp_status(api, lsp["id"], "ACTIVE", "Reactivating for a fresh seeding run.")


def ensure_product_mapping(api: Api, lsp_id: str) -> dict:
    products = must(api.call("GET", "/api/v1/internal/admin/products", admin=True), "list products")
    active = [p for p in products if p.get("status") == "ACTIVE"]
    if not active:
        sys.exit("No ACTIVE loan products exist. Create one in the admin UI first.")
    preferred = next((p for p in active if p.get("code", "").startswith("DOC-P-")), None)
    product = preferred or active[0]

    already_mapped = any(l.get("id") == lsp_id for l in product.get("mappedLsps", []))
    if already_mapped:
        log(f"[product] {product['code']} already mapped to dedicated LSP")
        return product

    must(
        api.call(
            "POST",
            "/api/v1/internal/admin/product-lsp-mappings/entries",
            admin=True,
            json={"lspId": lsp_id, "productId": product["id"], "enabled": True},
            headers={"Content-Type": "application/json"},
        ),
        "map product to dedicated lsp",
    )
    log(f"[product] mapped {product['code']} -> dedicated LSP")
    return product


def ensure_api_client(api: Api, lsp_id: str, suffix: str = "") -> None:
    client_name = f"{DEDICATED_CLIENT_NAME}-{suffix}".strip("-") if suffix else DEDICATED_CLIENT_NAME
    clients = must(api.call("GET", "/api/v1/internal/admin/api-clients", admin=True), "list api-clients")
    existing = next((c for c in clients if c.get("name") == client_name), None)
    if existing is None:
        existing = must(
            api.call(
                "POST",
                "/api/v1/internal/admin/api-clients",
                admin=True,
                json={
                    "name": client_name,
                    "description": "WS-5 disbursement UI audit — origination + document upload only.",
                    "lspId": lsp_id,
                    "status": "ACTIVE",
                },
                headers={"Content-Type": "application/json"},
                idem=str(uuid.uuid4()),
            ),
            "create dedicated api client",
        )
        log(f"[client] created {client_name} ({existing['clientId']})")
        client_id = existing["id"]
        client_id_str = existing["clientId"]
        client_secret = existing["clientSecret"]
    else:
        client_id = existing["id"]
        rotated = must(
            api.call("POST", f"/api/v1/internal/admin/api-clients/{client_id}/rotate-secret", admin=True),
            "rotate dedicated client secret",
        )
        client_id_str = rotated["clientId"]
        client_secret = rotated["clientSecret"]
        log(f"[client] reused {client_name}, rotated secret")

    token = must(
        api.call(
            "POST",
            "/api/v1/auth/token",
            json={"clientId": client_id_str, "clientSecret": client_secret},
            headers={"Content-Type": "application/json"},
        ),
        "token exchange",
    )
    api.lsp_token = token["accessToken"]
    log("[client] LSP token OK")


# ─── Application creation + document upload ─────────────────────────────────


def build_borrower_payload(lsp_id: str, product: dict, tag: str, ifsc: str | None) -> dict:
    min_principal = Decimal(str(product.get("minPrincipal", "10000")))
    max_principal = Decimal(str(product.get("maxPrincipal", "500000")))
    amount = ((min_principal + max_principal) / 2).quantize(Decimal("0.01"))
    tenure = max(int(product.get("minTenureMonths", 6)), min(int(product.get("maxTenureMonths", 24)), 12))
    interest = Decimal(str(product.get("interestRate", "14.5")))

    first = random.choice(["Aarav", "Anika", "Rohan", "Priya", "Vikram", "Neha", "Kabir", "Diya"])
    last = random.choice(["Sharma", "Patel", "Iyer", "Singh", "Das", "Mehta", "Nair", "Bose"])
    full_name = f"{first} {last}"
    dob = date.today() - timedelta(days=random.randint(25 * 365, 45 * 365))
    monthly_income = (amount * Decimal("2")).quantize(Decimal("0.01"))

    return {
        "lspId": lsp_id,
        "productId": product["id"],
        "lspLoanId": f"WS5-APD-{tag}-{rid()}",
        "fullName": full_name,
        "emailAddress": f"{first.lower()}.{last.lower()}.{uuid.uuid4().hex[:6]}@ws5-seed.example.com",
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
        "addressLine1": f"{random.randint(1, 199)} Audit Lane",
        "addressLine2": "Block C",
        "addressCity": "Mumbai",
        "addressState": "Maharashtra",
        "addressZipcode": "400001",
        "employmentStatus": "SALARIED",
        "organizationName": "WS5 Audit Corp Pvt Ltd",
        "empId": f"EMP-{rid()}",
        "employmentCity": "Mumbai",
        "employmentState": "Maharashtra",
        "employmentZip": "400001",
        "monthlyIncome": str(monthly_income),
        "annualIncome": str((monthly_income * 12).quantize(Decimal("0.01"))),
        "bankAccountNumber": rand_acct(),
        "bankName": "HDFC Bank",
        "ifscCode": ifsc or rand_ifsc(),
        "accountHolderName": full_name,
        "referencePersonName": "Ref Contact",
        "referencePersonNumber": rand_mobile(),
    }


def create_application(api: Api, lsp_id: str, product: dict, tag: str, ifsc: str | None = None) -> dict:
    payload = build_borrower_payload(lsp_id, product, tag, ifsc)
    detail = must(
        api.call(
            "POST",
            "/api/v1/lsp/loan-applications",
            json=payload,
            headers={"Content-Type": "application/json"},
            idem=str(uuid.uuid4()),
        ),
        "create application",
    )
    log(f"[app:{tag}] created {detail['id']} lspLoanId={payload['lspLoanId']} borrower={payload['fullName']}")
    return detail


def upload_all_documents(api: Api, app_id: str, tag: str) -> dict:
    """Single batch multipart call -- one LSP write, well inside the 10/min
    APP_RATE_LIMIT_LSP_WRITE_PER_MINUTE local dev limit. Real files (not the
    metadata-only endpoint) so each checklist item is LMS-managed content,
    required later by hasAllRequiredLmsManagedDocuments for disbursement."""
    files = [("files", (f"{doc_type.lower()}.pdf", PDF_BYTES, "application/pdf")) for doc_type in DOCUMENT_TYPES]
    metadata = [
        {"documentType": doc_type, "note": f"WS5 seed {doc_type}", "sourceReference": f"ws5-seed-{doc_type.lower()}"}
        for doc_type in DOCUMENT_TYPES
    ]
    files.append(("documents", (None, json.dumps(metadata), "application/json")))
    must(
        api.call("POST", f"/api/v1/lsp/loan-applications/{app_id}/documents/batch", files=files),
        "batch document upload",
    )
    log(f"[app:{tag}] uploaded all {len(DOCUMENT_TYPES)} required documents")
    # The 8th document's upload synchronously triggers LoanAutoApprovalGateService
    # inside this same request (LoanDocumentService.submitStoredDocumentsForLsp),
    # so the application should already have moved by the time this returns.
    return get_application_admin(api, app_id)


def get_application_admin(api: Api, app_id: str) -> dict:
    return must(api.call("GET", f"/api/v1/internal/ops/loan-applications/{app_id}", admin=True), "get application")


def get_application_lsp(api: Api, app_id: str) -> dict:
    return must(api.call("GET", f"/api/v1/lsp/loan-applications/{app_id}"), "get application (lsp)")


# ─── LSP hold / release ──────────────────────────────────────────────────────


def set_lsp_status(api: Api, lsp_id: str, status: str, note: str) -> dict:
    # LspStatusService.updateStatus 409s ("LSP is already X; no audit event was
    # recorded") on a same-status update -- check first so hold()/release() are
    # genuinely idempotent, per this script's re-runnability requirement.
    current = must(api.call("GET", f"/api/v1/internal/admin/lsps/{lsp_id}", admin=True), "get lsp")
    if current["status"] == status:
        return current
    return must(
        api.call(
            "PUT",
            f"/api/v1/internal/admin/lsps/{lsp_id}/status",
            admin=True,
            json={"status": status, "reason": "OPERATIONAL", "note": note},
            headers={"Content-Type": "application/json"},
            idem=str(uuid.uuid4()),
        ),
        f"set lsp status {status}",
    )


def hold(api: Api, lsp_id: str) -> None:
    set_lsp_status(
        api,
        lsp_id,
        "INACTIVE",
        "WS-5 disbursement UI audit: holding loans under this LSP so the automated "
        "disbursement worker (LoanDisbursementWorkerProcessor) skips them. Does not "
        "affect GET requests or manual ops actions. Release with --release.",
    )
    log(f"[hold] LSP {lsp_id} set INACTIVE — automated worker will now skip every loan under it")


def release(api: Api, lsp_id: str) -> None:
    set_lsp_status(api, lsp_id, "ACTIVE", "WS-5 disbursement UI audit complete; releasing hold.")
    log(f"[release] LSP {lsp_id} set ACTIVE again — automated worker will resume processing on its next tick")


# ─── DISBURSEMENT_RETRY recipe (manual, deterministic, ~immediate) ─────────


def seed_disbursement_retry(api: Api, app_id: str, tag: str) -> dict:
    must(
        api.call(
            "POST",
            f"/api/v1/internal/ops/loan-applications/{app_id}/disbursement-requests",
            admin=True,
            headers={"Content-Type": "application/json"},
            idem=str(uuid.uuid4()),
        ),
        "initiate disbursement",
    )
    must(
        api.call(
            "POST",
            f"/api/v1/internal/ops/loan-applications/{app_id}/disbursement-requests/mock-outcome",
            admin=True,
            json={"outcome": "FAILED"},
            headers={"Content-Type": "application/json"},
            idem=str(uuid.uuid4()),
        ),
        "force FAILED mock outcome",
    )
    detail = get_application_admin(api, app_id)
    log(f"[app:{tag}] forced disbursement outcome FAILED -> status is now {detail['status']}")
    return detail


# ─── Orchestration ───────────────────────────────────────────────────────────


def print_summary(app_detail: dict, tag: str, held: bool) -> None:
    app_id = app_detail["id"]
    account = app_detail.get("loanAccount")
    print("")
    print(f"=== {tag} ===")
    print(f"  application id : {app_id}")
    print(f"  status         : {app_detail['status']}")
    print(f"  externalLoanId : {app_detail.get('externalLoanId')}")
    print(f"  loan account id: {account['id'] if account else app_detail.get('loanAccountId') or '(none yet)'}")
    print(f"  held from worker: {held}")
    print(f"  ops UI (overview): {FRONTEND_BASE}/loan-applications/{app_id}")
    print(f"  ops UI (schedule/disbursement tab): {FRONTEND_BASE}/loan-applications/{app_id}?tab=schedule")
    print(f"  API detail: {BASE}/api/v1/internal/ops/loan-applications/{app_id}")


def seed_one_held_attempt(api: Api, suffix: str, tag: str, ifsc: str | None = None) -> tuple[dict, str]:
    """One attempt: create/reuse a dedicated LSP, originate one application under
    it, drive it to APPROVED_PENDING_DISBURSAL, and hold (deactivate its LSP)
    immediately. Returns (detail, lsp_id)."""
    t0 = time.monotonic()
    lsp = ensure_dedicated_lsp(api, suffix)
    lsp = ensure_active(api, lsp)
    lsp_id = lsp["id"]
    product = ensure_product_mapping(api, lsp_id)
    ensure_api_client(api, lsp_id, suffix)

    app = create_application(api, lsp_id, product, tag=tag, ifsc=ifsc)
    t1 = time.monotonic()
    detail = upload_all_documents(api, app["id"], tag=tag)
    t2 = time.monotonic()
    status = detail["status"]
    log(f"[app:{tag}] timing: setup+create={t1 - t0:.2f}s, doc-upload+approval-observed={t2 - t1:.2f}s")
    if status == "APPROVED_PENDING_DISBURSAL":
        hold(api, lsp_id)
        t3 = time.monotonic()
        log(f"[app:{tag}] reached APPROVED_PENDING_DISBURSAL and held; hold() took {t3 - t2:.2f}s")
    else:
        log(f"[app:{tag}] WARNING: expected APPROVED_PENDING_DISBURSAL, got {status}.")
    return detail, lsp_id


def seed_one_held_application(
    api: Api, suffix: str, tag: str, ifsc: str | None = None, max_attempts: int = 5
) -> tuple[dict, str]:
    """seed_one_held_attempt, retried until the hold is *confirmed* to have won
    the race against the automated worker.

    WHY A RETRY LOOP: the only lever that stops the automated worker
    (LoanDisbursementWorkerProcessor's LSP-ACTIVE check) only prevents a NEW
    disbursement_intent from being created; it cannot be applied before the loan
    exists, and once an intent exists (created inside the same worker tick that
    first observes the loan APPROVED_PENDING_DISBURSAL) it is executed
    synchronously in that same tick regardless of LSP status -- by design, per
    CONTEXT.md's "in flight ... hands-off" rule; there is no cancel/pause path.
    So this is an honest, unavoidable race against the worker's ~30s tick phase,
    not a bug in the hold mechanism. Losing the race just means a worker tick
    happened to be due in the few seconds between approval and the hold PUT
    landing. Retrying with a fresh loan (fresh LSP, so a previous attempt's
    already-consumed loan cannot interfere) until one is confirmed to survive
    past the danger window is the reliable way to get a loan a human can
    actually inspect.
    """
    for attempt in range(1, max_attempts + 1):
        attempt_suffix = suffix if attempt == 1 else f"{suffix}{attempt}"
        log(f"\n[app:{tag}] attempt {attempt}/{max_attempts} (LSP suffix '{attempt_suffix}')")
        detail, lsp_id = seed_one_held_attempt(api, attempt_suffix, tag, ifsc=ifsc)
        if detail["status"] != "APPROVED_PENDING_DISBURSAL":
            continue  # didn't even reach the target status; try again fresh

        # Confirm the hold actually won: wait past the danger window (a worker
        # tick that was already "due" when we approved) and re-check from a
        # clean GET. If a tick raced us, this app_id is now DISBURSED (or
        # REJECTED/DISBURSEMENT_RETRY) and the *next* attempt gets a fresh LSP.
        log(f"[app:{tag}] verifying hold survives past one worker tick (~32s)...")
        time.sleep(32)
        verify = get_application_admin(api, detail["id"])
        if verify["status"] == "APPROVED_PENDING_DISBURSAL":
            log(f"[app:{tag}] CONFIRMED held: still APPROVED_PENDING_DISBURSAL after 32s.")
            return verify, lsp_id
        log(f"[app:{tag}] LOST THE RACE: worker moved it to {verify['status']} "
            f"(lastActivity={verify.get('lastActivity')}) before the hold landed. Retrying fresh.")

    raise RuntimeError(f"[app:{tag}] failed to seed a durably-held loan after {max_attempts} attempts.")


def do_seed(also_retry: bool) -> None:
    api = Api()
    authenticate_admin(api)

    # Each held loan gets its OWN dedicated LSP (see seed_one_held_application's
    # docstring) -- proven necessary live: sharing one LSP between loan A and
    # loan B meant reactivating it to onboard B also un-held A, and the worker
    # disbursed A about 24 seconds later.
    log("\n--- seeding APPROVED_PENDING_DISBURSAL loan (A) ---")
    detail_a, lsp_id_a = seed_one_held_application(api, suffix="A", tag="A")
    app_a_id = detail_a["id"]

    detail_b = None
    lsp_id_b = None
    if also_retry:
        log("\n--- seeding DISBURSEMENT_RETRY loan (B, on its own dedicated LSP) ---")
        detail_b, lsp_id_b = seed_one_held_application(api, suffix="B", tag="B", ifsc=IFSC_TECHNICAL_DECLINE)
        app_b_id = detail_b["id"]
        if detail_b["status"] == "APPROVED_PENDING_DISBURSAL":
            # B's LSP is already held (INACTIVE) at this point, so the manual
            # initiate/mock-outcome pair below cannot race the automated worker
            # -- worker skips inactive-LSP loans; manual ops actions are not
            # gated by LSP status at all (LoanApplicationOpsController does not
            # check LSP status on any disbursement-requests endpoint).
            detail_b = seed_disbursement_retry_with_retry(api, app_b_id, tag="B")

    # Re-verify from a clean GET (not the upload-response payload) so the
    # printed summary reflects genuinely-current server state.
    verify_a = get_application_admin(api, app_a_id)
    print_summary(verify_a, "Loan A -- APPROVED_PENDING_DISBURSAL (held)", held=True)
    print(f"  held via dedicated LSP: {lsp_id_a} (INACTIVE)")
    print(f"  release with: python3 scripts/seed_disbursement_pending.py --release {lsp_id_a}")

    if detail_b is not None:
        verify_b = get_application_admin(api, app_b_id)
        print_summary(verify_b, "Loan B -- DISBURSEMENT_RETRY (held)", held=True)
        print(f"  held via dedicated LSP: {lsp_id_b} (INACTIVE)")
        print(f"  release with: python3 scripts/seed_disbursement_pending.py --release {lsp_id_b}")

    print("\nManual ops actions (viewing, initiate, mock-outcome, manual-status) are NOT")
    print("gated by LSP status and continue to work normally while held. Releasing a hold")
    print("lets the automated worker resume on its next ~30s tick and the loan WILL move")
    print("(to DISBURSED, REJECTED, or a further DISBURSEMENT_RETRY attempt) shortly after.")


def seed_disbursement_retry_with_retry(api: Api, app_id: str, tag: str, attempts: int = 4) -> dict:
    """The 409 CONCURRENT_MODIFICATION seen in an earlier run happened because the
    hold landed after the worker had already raced in. Now that hold() runs before
    this is ever called, this retry loop is just defensive belt-and-suspenders."""
    last_error = None
    for attempt in range(1, attempts + 1):
        try:
            return seed_disbursement_retry(api, app_id, tag)
        except RuntimeError as exc:
            last_error = exc
            log(f"[app:{tag}] retry-seed attempt {attempt} failed ({exc}); retrying in 1s")
            time.sleep(1)
    raise last_error


def do_release(lsp_id: str) -> None:
    api = Api()
    authenticate_admin(api)
    release(api, lsp_id)


def do_status(lsp_id: str) -> None:
    api = Api()
    authenticate_admin(api)
    detail = must(api.call("GET", f"/api/v1/internal/admin/lsps/{lsp_id}", admin=True), "get lsp")
    print(json.dumps(detail, indent=2, default=str))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--also-seed-retry", action="store_true",
                         help="Also create a second loan and drive it to DISBURSEMENT_RETRY.")
    parser.add_argument("--release", metavar="LSP_ID",
                         help="Reactivate a previously-held dedicated LSP (unfreezes its loans).")
    parser.add_argument("--status", metavar="LSP_ID",
                         help="Print current status of the dedicated LSP.")
    args = parser.parse_args()

    try:
        sys.stdout.reconfigure(line_buffering=True)
    except AttributeError:
        pass
    random.seed()

    if args.release:
        do_release(args.release)
        return
    if args.status:
        do_status(args.status)
        return
    do_seed(also_retry=args.also_seed_retry)


if __name__ == "__main__":
    main()
