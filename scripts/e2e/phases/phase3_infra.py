#!/usr/bin/env python3
"""Phase 3: IP allowlist and infra edge cases."""
from __future__ import annotations

import argparse
import json
import sys
import time
import uuid
from pathlib import Path

import requests

E2E_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(E2E_DIR))

from _common import RUNS, admin_token, load_config, refresh_lsp_client_secret, result, update_matrix, write_results  # noqa: E402


def _blocked_ip_headers() -> dict[str, str]:
    return {"X-Forwarded-For": "127.0.0.1"}


def run(fixtures_path: Path) -> list[dict]:
    cfg = load_config()
    base = cfg["BASE_URL"]
    cidr = cfg.get("E2E_ALLOWLIST_CIDR", "10.255.255.0/24")
    fx = json.loads(fixtures_path.read_text(encoding="utf-8"))["fixtures"]
    f01 = fx["F01_happy_lsp"]
    lsp_id = f01["lspId"]

    admin = admin_token(cfg)
    ah = {"Authorization": f"Bearer {admin}", "Content-Type": "application/json"}
    results: list[dict] = []

    api_entry_id: str | None = None
    ui_entry_id: str | None = None
    ui_user: str | None = None
    prior_enforcement: dict | None = None

    secret = f01["clientSecret"]
    probe = requests.post(
        f"{base}/api/v1/auth/token",
        json={"clientId": f01["clientId"], "clientSecret": secret},
        timeout=30,
    )
    if probe.status_code == 401:
        secret = refresh_lsp_client_secret(cfg, admin, f01["clientId"])
        f01["clientSecret"] = secret

    try:
        prior_enforcement = requests.get(
            f"{base}/api/v1/internal/admin/lsps/{lsp_id}/allowlist-enforcement",
            headers=ah,
            timeout=30,
        ).json()

        api_create = requests.post(
            f"{base}/api/v1/internal/admin/lsps/{lsp_id}/api-ip-allowlist",
            headers=ah,
            json={"cidr": cidr, "description": "E2E phase3 — excludes localhost"},
            timeout=30,
        )
        if api_create.ok:
            api_entry_id = api_create.json().get("id")
        requests.put(
            f"{base}/api/v1/internal/admin/lsps/{lsp_id}/allowlist-enforcement",
            headers=ah,
            json={"enforceApi": True},
            timeout=30,
        ).raise_for_status()
        time.sleep(1)

        r_token = requests.post(
            f"{base}/api/v1/auth/token",
            headers=_blocked_ip_headers(),
            json={"clientId": f01["clientId"], "clientSecret": secret},
            timeout=30,
        )
        results.append(
            result(
                "EC-010",
                "Pass" if r_token.status_code == 403 else "Fail",
                f"Token from blocked IP → {r_token.status_code}",
                "POST /auth/token + X-Forwarded-For",
            )
        )

        ui_create = requests.post(
            f"{base}/api/v1/internal/admin/lsps/{lsp_id}/ui-ip-allowlist",
            headers=ah,
            json={"cidr": cidr, "description": "E2E phase3 UI"},
            timeout=30,
        )
        if ui_create.ok:
            ui_entry_id = ui_create.json().get("id")
        requests.put(
            f"{base}/api/v1/internal/admin/lsps/{lsp_id}/allowlist-enforcement",
            headers=ah,
            json={"enforceUi": True},
            timeout=30,
        ).raise_for_status()
        time.sleep(1)

        ui_user = f"lsp.ui.{uuid.uuid4().hex[:8]}"
        user_resp = requests.post(
            f"{base}/api/v1/internal/admin/users",
            headers=ah,
            json={
                "username": ui_user,
                "email": f"{ui_user}@edge.demo.local",
                "password": "EdgeUser#2026!",
                "status": "ACTIVE",
                "roles": ["LSP_UI_READ"],
                "lspId": lsp_id,
            },
            timeout=60,
        )
        if user_resp.ok:
            r_login = requests.post(
                f"{base}/api/v1/auth/login",
                headers=_blocked_ip_headers(),
                json={"username": ui_user, "password": "EdgeUser#2026!"},
                timeout=30,
            )
            results.append(
                result(
                    "EC-011",
                    "Pass" if r_login.status_code == 403 else "Fail",
                    f"LSP UI login blocked IP → {r_login.status_code}",
                    "POST /auth/login + X-Forwarded-For",
                )
            )
        else:
            results.append(
                result(
                    "EC-011",
                    "Blocked",
                    f"Could not create LSP UI user: {user_resp.status_code}",
                    "POST /admin/users",
                )
            )

        app_id = f01.get("applicationId_initialized")
        if app_id:
            r_mock = requests.post(
                f"{base}/api/v1/internal/ops/loan-applications/{app_id}/disbursement-requests/mock-outcome",
                headers=ah,
                json={"outcome": "DISBURSED"},
                timeout=30,
            )
            results.append(
                result(
                    "EC-050",
                    "Blocked",
                    f"Local profile exposes mock-outcome → {r_mock.status_code}",
                    "POST mock-outcome",
                    "Requires non-local profile to assert 404/403; N/A on local dev.",
                )
            )
        else:
            results.append(
                result("EC-050", "Blocked", "No initialized application in fixtures.", "POST mock-outcome")
            )
    finally:
        if prior_enforcement is not None:
            requests.put(
                f"{base}/api/v1/internal/admin/lsps/{lsp_id}/allowlist-enforcement",
                headers=ah,
                json={
                    "enforceUi": prior_enforcement.get("enforceUi", False),
                    "enforceApi": prior_enforcement.get("enforceApi", False),
                },
                timeout=30,
            )
        if api_entry_id:
            requests.delete(
                f"{base}/api/v1/internal/admin/lsps/{lsp_id}/api-ip-allowlist/{api_entry_id}",
                headers=ah,
                timeout=30,
            )
        if ui_entry_id:
            requests.delete(
                f"{base}/api/v1/internal/admin/lsps/{lsp_id}/ui-ip-allowlist/{ui_entry_id}",
                headers=ah,
                timeout=30,
            )

    return results


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--fixtures", default=str(RUNS / "edge-fixtures.json"))
    ap.add_argument("--update-matrix", action="store_true")
    args = ap.parse_args()
    res = run(Path(args.fixtures))
    write_results(RUNS / "phase3-results.json", res)
    passed = sum(1 for x in res if x["status"] == "Pass")
    print(f"Phase 3: {len(res)} cases, {passed} pass, {len(res) - passed} other")
    if args.update_matrix:
        print(f"Matrix rows updated: {update_matrix(res)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
