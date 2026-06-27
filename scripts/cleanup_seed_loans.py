#!/usr/bin/env python3
"""
Remove loan applications created by scripts/seed_loans.py.

Identifies rows by external loan id prefix `SEED-` and invalidates each via the
LSP API. Loans that have already entered servicing (disbursed / under repayment)
cannot be invalidated — those are reported as SKIPPED.

Auth: admin login + API-client secret rotation (same pattern as seed_loans.py).
"""
from __future__ import annotations

import json
import sys
import uuid

try:
    import requests
except ImportError:
    sys.exit("Missing dependency: pip install requests")

BASE = "http://localhost:8080"
ADMIN_USER = "ops.admin"
ADMIN_PASS = "ChangeMe123!"
SEED_PREFIX = "SEED-"
SERVICING_STATUSES = frozenset(
    {
        "DISBURSED",
        "UNDER_REPAYMENT",
        "CLOSED",
        "FORECLOSED",
    }
)


class Api:
    def __init__(self):
        self.s = requests.Session()
        self.admin_token = None
        self.lsp_token = None

    def call(self, method, path, *, admin=False, idem=None, **kw):
        headers = kw.pop("headers", {})
        tok = self.admin_token if admin else self.lsp_token
        if tok:
            headers["Authorization"] = f"Bearer {tok}"
        if idem:
            headers["Idempotency-Key"] = idem
        return self.s.request(method, f"{BASE}{path}", headers=headers, timeout=120, **kw)


def must(resp, label):
    if not resp.ok:
        raise RuntimeError(f"{label} -> {resp.status_code}: {resp.text[:400]}")
    return resp.json() if resp.text else {}


def authenticate(api: Api) -> None:
    body = must(
        api.call(
            "POST",
            "/api/v1/auth/login",
            json={"username": ADMIN_USER, "password": ADMIN_PASS},
            headers={"Content-Type": "application/json"},
        ),
        "admin login",
    )
    api.admin_token = body["accessToken"]

    listing = must(api.call("GET", "/api/v1/internal/admin/api-clients", admin=True), "list api-clients")
    items = listing.get("items", listing) if isinstance(listing, dict) else listing
    if not items:
        sys.exit("No API clients exist — create one in the admin UI first.")
    client = next((c for c in items if (c.get("status") or "").upper() == "ACTIVE"), items[0])
    rot = must(
        api.call("POST", f"/api/v1/internal/admin/api-clients/{client['id']}/rotate-secret", admin=True),
        "rotate secret",
    )
    tok = must(
        api.call(
            "POST",
            "/api/v1/auth/token",
            json={"clientId": rot["clientId"], "clientSecret": rot["clientSecret"]},
            headers={"Content-Type": "application/json"},
        ),
        "token exchange",
    )
    api.lsp_token = tok["accessToken"]


def list_seed_applications(api: Api) -> list[dict]:
    resp = api.call(
        "GET",
        "/api/v1/internal/ops/loan-applications",
        admin=True,
        params={"lspLoanId": SEED_PREFIX, "limit": 1000},
    )
    rows = must(resp, "list seed applications")
    if not isinstance(rows, list):
        return []
    return [row for row in rows if str(row.get("lspLoanId", "")).startswith(SEED_PREFIX)]


def invalidate_application(api: Api, application_id: str) -> dict:
    return must(
        api.call(
            "POST",
            f"/api/v1/lsp/loan-applications/{application_id}/invalid",
            json={"reasonCode": "OTHERS", "reasonText": "cleanup_seed_loans.py"},
            headers={"Content-Type": "application/json"},
            idem=str(uuid.uuid4()),
        ),
        f"invalidate {application_id}",
    )


def main() -> None:
    api = Api()
    authenticate(api)
    print("authenticated")

    rows = list_seed_applications(api)
    if not rows:
        print("No SEED- loan applications found.")
        return

    summary = {"invalidated": 0, "skipped": 0, "failed": 0}
    for row in rows:
        app_id = row["id"]
        lsp_loan_id = row.get("lspLoanId", "?")
        status = (row.get("status") or "UNKNOWN").upper()
        label = f"{lsp_loan_id} ({status})"

        if status in SERVICING_STATUSES:
            summary["skipped"] += 1
            print(f"SKIP  {label} — entered servicing; cannot invalidate via API")
            continue

        try:
            invalidate_application(api, app_id)
            summary["invalidated"] += 1
            print(f"OK    {label} -> INVALID")
        except Exception as exc:  # noqa: BLE001 — script should continue
            summary["failed"] += 1
            print(f"FAIL  {label}: {exc}")

    print("\nCleanup summary:", json.dumps(summary, indent=2))
    if summary["skipped"]:
        print(
            "\nNote: disbursed SEED- loans remain in the database. "
            "Drop them manually in Supabase if you need a fully clean slate."
        )


if __name__ == "__main__":
    main()
