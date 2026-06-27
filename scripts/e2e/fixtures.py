#!/usr/bin/env python3
"""Create isolated fixtures for edge-case E2E phases. Export JSON for runners."""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
import time
from pathlib import Path

import requests

from _common import admin_token, load_config, suffix, uuid_v4  # noqa: F401

ROOT_DIR = Path(__file__).resolve().parent.parent.parent
PDF = ROOT_DIR / "postman" / "assets" / "sample-pan.pdf"


def create_lsp(ah: dict, base: str, code: str, name: str, status: str = "ACTIVE") -> dict:
    r = requests.post(
        f"{base}/api/v1/internal/admin/lsps",
        headers=ah,
        json={"code": code, "name": name, "status": status},
        timeout=30,
    )
    r.raise_for_status()
    return r.json()


def create_product(ah: dict, base: str, code: str, status: str = "ACTIVE") -> dict:
    r = requests.post(
        f"{base}/api/v1/internal/admin/products",
        headers=ah,
        json={
            "code": code,
            "name": f"Edge Product {code}",
            "minPrincipal": 10000,
            "maxPrincipal": 500000,
            "interestRate": 14.5,
            "processingFeeRate": 1.5,
            "minTenureMonths": 6,
            "maxTenureMonths": 36,
            "status": status,
        },
        timeout=30,
    )
    r.raise_for_status()
    return r.json()


def map_product(ah: dict, base: str, product_id: str, lsp_id: str) -> None:
    requests.put(
        f"{base}/api/v1/internal/admin/products/{product_id}/mappings",
        headers=ah,
        json={"lspIds": [lsp_id]},
        timeout=30,
    ).raise_for_status()


def create_api_client(ah: dict, base: str, lsp_id: str, label: str) -> dict:
    r = requests.post(
        f"{base}/api/v1/internal/admin/api-clients",
        headers=ah,
        json={"lspId": lsp_id, "name": label},
        timeout=30,
    )
    r.raise_for_status()
    return r.json()


def lsp_token(base: str, client_id: str, secret: str) -> str:
    r = requests.post(
        f"{base}/api/v1/auth/token",
        json={"clientId": client_id, "clientSecret": secret},
        timeout=30,
    )
    r.raise_for_status()
    return r.json()["accessToken"]


def create_loan(lh: dict, base: str, body: dict) -> dict:
    r = requests.post(
        f"{base}/api/v1/lsp/loan-applications",
        headers={**lh, "Idempotency-Key": uuid_v4()},
        json=body,
        timeout=90,
    )
    r.raise_for_status()
    return r.json()


def _borrower_ids(sfx: str) -> tuple[str, str, str]:
    digest = int(hashlib.sha256(sfx.encode()).hexdigest(), 16)
    aadhar = str(digest % 10**12).zfill(12)
    pan = f"ABCDE{str((digest // 10**12) % 10000).zfill(4)}F"
    mobile = "9" + str(digest % 10**9).zfill(9)
    return aadhar, pan, mobile


def loan_body(lsp_id: str, product_id: str, sfx: str, **overrides) -> dict:
    aadhar, pan, mobile = _borrower_ids(sfx)
    full_name = f"Edge Borrower {sfx}"
    body = {
        "lspId": lsp_id,
        "productId": product_id,
        "lspLoanId": f"EXT-{sfx}",
        "fullName": full_name,
        "emailAddress": f"edge{sfx}@example.com",
        "mobileNumber": mobile,
        "dob": "1990-05-15",
        "gender": "MALE",
        "maritalStatus": "SINGLE",
        "fatherName": "Parent",
        "aadharNumber": aadhar,
        "panNumber": pan,
        "loanAmount": 150000,
        "interestRate": 14.5,
        "loanTenure": 12,
        "addressLine1": "42 Demo Street",
        "addressCity": "Mumbai",
        "addressState": "MH",
        "addressZipcode": "400001",
        "employmentStatus": "SALARIED",
        "organizationName": "Demo Corp",
        "monthlyIncome": 60000,
        "annualIncome": 720000,
        "bankAccountNumber": "1234567890",
        "bankName": "HDFC Bank",
        "ifscCode": "HDFC0001234",
        "accountHolderName": full_name,
        "referencePersonName": "Ref",
        "referencePersonNumber": "9123456780",
    }
    body.update(overrides)
    return body


DOC_TYPES = [
    "PAN_CARD",
    "AADHAAR_FILE",
    "BANK_STATEMENT",
    "ADDRESS_PROOF",
    "INCOME_PROOF",
    "SELFIE_PHOTOGRAPH",
    "KFS",
    "LOAN_AGREEMENT",
]


def upload_all_docs(lh: dict, base: str, app_id: str) -> None:
    # Multipart uploads must not send Content-Type: application/json from shared LSP headers.
    upload_headers = {k: v for k, v in lh.items() if k.lower() != "content-type"}
    for dt in DOC_TYPES:
        with PDF.open("rb") as f:
            res = requests.post(
                f"{base}/api/v1/lsp/loan-applications/{app_id}/documents",
                headers=upload_headers,
                files={"file": (f"{dt.lower()}.pdf", f, "application/pdf")},
                data={"documentType": dt},
                timeout=60,
            )
            if not res.ok:
                raise requests.HTTPError(
                    f"{dt} upload → {res.status_code}: {res.text[:300]}",
                    response=res,
                )


def wait_for_approved(ah: dict, base: str, app_id: str, *, timeout_sec: float = 60) -> str:
    """Poll until auto-approval reaches APPROVED_PENDING_DISBURSAL (or DISBURSED)."""
    deadline = time.time() + timeout_sec
    while time.time() < deadline:
        detail = requests.get(
            f"{base}/api/v1/internal/ops/loan-applications/{app_id}",
            headers=ah,
            timeout=30,
        ).json()
        status = detail.get("status")
        if status in ("APPROVED_PENDING_DISBURSAL", "DISBURSED"):
            return status
        if status == "REJECTED":
            raise RuntimeError(
                f"Loan {app_id} rejected before disbursement: {detail.get('invalidReasonText') or detail}"
            )
        time.sleep(1)
    raise TimeoutError(f"Loan {app_id} not approved within {timeout_sec}s")


def pay_installment(ah: dict, base: str, app_id: str, inst: dict, idem: str | None = None) -> None:
    requests.post(
        f"{base}/api/v1/internal/ops/loan-applications/{app_id}/payments",
        headers={**ah, "Content-Type": "application/json", "Idempotency-Key": idem or uuid_v4()},
        json={
            "targetInstallmentId": inst["id"],
            "amount": inst["amount"],
            "postedAt": "2026-06-11",
            "reference": f"EMI-{inst['n']}",
            "channel": "NEFT",
        },
        timeout=60,
    ).raise_for_status()


def _loan_status(ah: dict, base: str, app_id: str) -> str:
    return requests.get(
        f"{base}/api/v1/internal/ops/loan-applications/{app_id}",
        headers=ah,
        timeout=30,
    ).json().get("status", "")


def disburse(ah: dict, base: str, app_id: str) -> None:
    if _loan_status(ah, base, app_id) == "DISBURSED":
        return

    init = requests.post(
        f"{base}/api/v1/internal/ops/loan-applications/{app_id}/disbursement-requests",
        headers=ah,
        timeout=60,
    )
    if init.status_code not in (200, 201) and _loan_status(ah, base, app_id) != "DISBURSED":
        init.raise_for_status()

    if _loan_status(ah, base, app_id) != "DISBURSED":
        mock = requests.post(
            f"{base}/api/v1/internal/ops/loan-applications/{app_id}/disbursement-requests/mock-outcome",
            headers={**ah, "Content-Type": "application/json"},
            json={"outcome": "DISBURSED"},
            timeout=60,
        )
        if mock.status_code not in (200, 201) and _loan_status(ah, base, app_id) != "DISBURSED":
            mock.raise_for_status()

    terminal = {"DISBURSED", "UNDER_REPAYMENT", "CLOSED"}
    deadline = time.time() + 240
    while time.time() < deadline:
        status = _loan_status(ah, base, app_id)
        if status in terminal:
            return
        if status == "REJECTED":
            raise RuntimeError(f"Disbursement rejected for {app_id}")
        time.sleep(2)
    raise TimeoutError(f"Loan {app_id} not disbursed within 240s (last status={status})")


def create_user(ah: dict, base: str, username: str, roles: list[str], lsp_id: str | None = None) -> dict:
    payload = {
        "username": username,
        "email": f"{username}@edge.demo.local",
        "password": "EdgeUser#2026!",
        "status": "ACTIVE",
        "roles": roles,
    }
    if lsp_id:
        payload["lspId"] = lsp_id
    r = requests.post(f"{base}/api/v1/internal/admin/users", headers=ah, json=payload, timeout=120)
    r.raise_for_status()
    return r.json()


def build_fixtures(cfg: dict, *, light: bool = False) -> dict:
    base = cfg["BASE_URL"]
    prefix = cfg.get("E2E_FIXTURE_PREFIX", "E2E-EDGE")
    sfx = suffix()
    admin = admin_token(cfg)
    ah = {"Authorization": f"Bearer {admin}", "Content-Type": "application/json"}

    out: dict = {"createdAt": time.strftime("%Y-%m-%dT%H:%M:%SZ"), "fixtures": {}}

    # Keep codes within typical DB limits (prefix + short unique tag).
    tag = sfx[-12:]
    # F01 — primary LSP
    lsp_a = create_lsp(ah, base, f"{prefix}-A-{tag}", f"Edge LSP A {tag}")
    prod_a = create_product(ah, base, f"{prefix}-P-{tag}")
    map_product(ah, base, prod_a["id"], lsp_a["id"])
    client_a = create_api_client(ah, base, lsp_a["id"], f"{prefix}-client-A")
    tok_a = lsp_token(base, client_a["clientId"], client_a["clientSecret"])
    lh_a = {"Authorization": f"Bearer {tok_a}"}
    loan_init = create_loan(lh_a, base, loan_body(lsp_a["id"], prod_a["id"], sfx + "1"))
    out["fixtures"]["F01_happy_lsp"] = {
        "lspId": lsp_a["id"],
        "productId": prod_a["id"],
        "clientId": client_a["clientId"],
        "clientSecret": client_a["clientSecret"],
        "applicationId_initialized": loan_init["id"],
        "borrowerId": loan_init.get("borrowerId"),
    }

    # F02 — second LSP
    lsp_b = create_lsp(ah, base, f"{prefix}-B-{tag}", f"Edge LSP B {tag}")
    prod_b = create_product(ah, base, f"{prefix}-PB-{tag}")
    map_product(ah, base, prod_b["id"], lsp_b["id"])
    client_b = create_api_client(ah, base, lsp_b["id"], f"{prefix}-client-B")
    out["fixtures"]["F02_second_lsp"] = {
        "lspId": lsp_b["id"],
        "productId": prod_b["id"],
        "clientId": client_b["clientId"],
        "clientSecret": client_b["clientSecret"],
    }

    # F03 / F04 — role users
    out["fixtures"]["F03_product_admin"] = create_user(
        ah, base, f"product.admin.{sfx}", ["PRODUCT_ADMIN"]
    )
    out["fixtures"]["F04_ops_user"] = create_user(ah, base, f"ops.user.{sfx}", ["OPS_USER"])

    # F05 inactive LSP
    lsp_inact = create_lsp(ah, base, f"{prefix}-IN-{tag}", f"Inactive LSP {tag}", "INACTIVE")
    client_inact = create_api_client(ah, base, lsp_inact["id"], f"{prefix}-client-INACT")
    out["fixtures"]["F05_inactive_lsp"] = {
        "lspId": lsp_inact["id"],
        "clientId": client_inact["clientId"],
        "clientSecret": client_inact["clientSecret"],
    }

    # F06 inactive product
    lsp_c = create_lsp(ah, base, f"{prefix}-C-{tag}", f"Edge LSP C {tag}")
    prod_inact = create_product(ah, base, f"{prefix}-PI-{tag}", "INACTIVE")
    map_product(ah, base, prod_inact["id"], lsp_c["id"])
    client_c = create_api_client(ah, base, lsp_c["id"], f"{prefix}-client-C")
    out["fixtures"]["F06_inactive_product"] = {
        "lspId": lsp_c["id"],
        "productId": prod_inact["id"],
        "clientId": client_c["clientId"],
        "clientSecret": client_c["clientSecret"],
    }

    # F13 — rejected via ops transition (INITIALIZED → AWAITING_APPROVAL → REJECTED)
    loan_rej = create_loan(lh_a, base, loan_body(lsp_a["id"], prod_a["id"], sfx + "5"))
    requests.post(
        f"{base}/api/v1/internal/ops/loan-applications/{loan_rej['id']}/status-transitions",
        headers=ah,
        json={"targetStatus": "AWAITING_APPROVAL", "note": "Edge fixture queue for reject"},
        timeout=30,
    ).raise_for_status()
    requests.post(
        f"{base}/api/v1/internal/ops/loan-applications/{loan_rej['id']}/status-transitions",
        headers=ah,
        json={
            "targetStatus": "REJECTED",
            "note": "Edge fixture reject",
            "reasonCode": "FAILED_VERIFICATION",
        },
        timeout=30,
    ).raise_for_status()
    out["fixtures"]["F13_rejected_loan"] = {"applicationId": loan_rej["id"]}

    if light:
        out["adminToken"] = admin
        return out

    # F10 disbursed — full docs + disburse
    loan_full = create_loan(lh_a, base, loan_body(lsp_a["id"], prod_a["id"], sfx + "2"))
    upload_all_docs(lh_a, base, loan_full["id"])
    wait_for_approved(ah, base, loan_full["id"])
    disburse(ah, base, loan_full["id"])
    detail2 = requests.get(
        f"{base}/api/v1/internal/ops/loan-applications/{loan_full['id']}",
        headers=ah,
        timeout=30,
    ).json()
    sched = requests.get(
        f"{base}/api/v1/lsp/loans/{detail2.get('loanAccountId')}/repayment-schedule",
        headers=lh_a,
        timeout=30,
    ).json()
    inst_slim = [{"id": i["id"], "n": i["installmentNumber"], "amount": float(i["installmentAmount"])} for i in sched]
    out["fixtures"]["F10_disbursed_loan"] = {
        "applicationId": loan_full["id"],
        "loanAccountId": detail2.get("loanAccountId"),
        "installments": inst_slim,
    }

    def disbursed_loan(sfx_part: str) -> tuple[str, str, list]:
        loan = create_loan(lh_a, base, loan_body(lsp_a["id"], prod_a["id"], sfx_part))
        upload_all_docs(lh_a, base, loan["id"])
        wait_for_approved(ah, base, loan["id"])
        disburse(ah, base, loan["id"])
        det = requests.get(
            f"{base}/api/v1/internal/ops/loan-applications/{loan['id']}",
            headers=ah,
            timeout=30,
        ).json()
        sched_r = requests.get(
            f"{base}/api/v1/lsp/loans/{det.get('loanAccountId')}/repayment-schedule",
            headers=lh_a,
            timeout=30,
        ).json()
        slim = [{"id": i["id"], "n": i["installmentNumber"], "amount": float(i["installmentAmount"])} for i in sched_r]
        return loan["id"], det.get("loanAccountId"), slim

    # F11 — one payment
    app_ur, acct_ur, inst_ur = disbursed_loan(sfx + "3")
    pay_installment(ah, base, app_ur, inst_ur[0])
    out["fixtures"]["F11_under_repayment"] = {
        "applicationId": app_ur,
        "loanAccountId": acct_ur,
        "installments": inst_ur,
    }

    # F12 — closed (12 payments)
    app_cl, acct_cl, inst_cl = disbursed_loan(sfx + "4")
    for inst in inst_cl:
        pay_installment(ah, base, app_cl, inst)
    out["fixtures"]["F12_closed_loan"] = {"applicationId": app_cl, "loanAccountId": acct_cl}

    # F14 — pristine DISBURSED loan reserved for EC-111 (UI stale-status gap; never pay in fixtures)
    app_ec111, acct_ec111, inst_ec111 = disbursed_loan(sfx + "ec111")
    out["fixtures"]["F14_disbursed_ec111"] = {
        "applicationId": app_ec111,
        "loanAccountId": acct_ec111,
        "installments": inst_ec111,
    }

    out["adminToken"] = admin
    return out


def portfolio_mis_loan_count(cfg: dict, ah: dict) -> int:
    base = cfg["BASE_URL"]
    preview = requests.get(
        f"{base}/api/v1/internal/reports/portfolio-mis/preview",
        headers=ah,
        params={"page": 0, "size": 1},
        timeout=60,
    )
    preview.raise_for_status()
    return int(preview.json().get("totalElements", 0))


def ensure_ec068_portfolio(cfg: dict, ah: dict, fx: dict) -> int:
    """Create disbursed loans until MIS preview meets E2E_EC068_MIN_LOANS."""
    min_loans = int(cfg.get("E2E_EC068_MIN_LOANS", "100"))
    auto_seed = cfg.get("E2E_EC068_AUTO_SEED", "true").lower() not in ("0", "false", "no")
    total = portfolio_mis_loan_count(cfg, ah)
    if total >= min_loans or not auto_seed:
        return total

    f01 = fx.get("F01_happy_lsp", {})
    if not f01.get("clientId"):
        return total

    base = cfg["BASE_URL"]
    tok = lsp_token(base, f01["clientId"], f01["clientSecret"])
    lh = {"Authorization": f"Bearer {tok}", "Content-Type": "application/json"}
    needed = min_loans - total
    tag = suffix()
    print(f"[ec068] seeding {needed} disbursed loans (portfolio has {total}, need {min_loans})", flush=True)
    for i in range(needed):
        sfx = f"ec068-{tag}-{i}"
        loan = create_loan(lh, base, loan_body(f01["lspId"], f01["productId"], sfx))
        app_id = loan.get("id") or loan.get("applicationId")
        if not app_id:
            raise RuntimeError(f"EC-068 seed intake missing application id: {loan}")
        upload_all_docs(lh, base, app_id)
        wait_for_approved(ah, base, app_id, timeout_sec=180)
        disburse(ah, base, app_id)
    return portfolio_mis_loan_count(cfg, ah)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--export", default=".e2e-runs/edge-fixtures.json")
    p.add_argument("--light", action="store_true", help="F01–F06 + F13 only (no doc upload / disburse)")
    args = p.parse_args()
    cfg = load_config()
    try:
        data = build_fixtures(cfg, light=args.light)
    except requests.HTTPError as e:
        body = e.response.text[:500] if e.response is not None else ""
        print(f"Fixture build failed: {e} — {body}", file=sys.stderr)
        return 1
    except requests.RequestException as e:
        print(f"Fixture build failed: {e}", file=sys.stderr)
        return 1
    path = Path(args.export)
    if not path.is_absolute():
        path = Path(__file__).resolve().parent.parent.parent / path
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2), encoding="utf-8")
    print(f"Exported fixtures to {path}")
    print(f"Keys: {list(data['fixtures'].keys())}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
