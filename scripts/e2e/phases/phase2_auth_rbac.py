#!/usr/bin/env python3
"""Phase 2: Auth, session, and RBAC edge cases."""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import requests

E2E_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(E2E_DIR))

from _common import (  # noqa: E402
    RUNS,
    admin_token,
    load_config,
    login_password,
    lsp_token_for_fixture,
    result,
    update_matrix,
    uuid_v4,
    write_results,
)
from fixtures import loan_body  # noqa: E402


OPS_PASSWORD = "EdgeUser#2026!"


def _complete_password_change(cfg: dict, access_token: str, new_password: str) -> None:
    requests.post(
        f"{cfg['BASE_URL']}/api/v1/auth/password",
        headers={"Authorization": f"Bearer {access_token}", "Content-Type": "application/json"},
        json={"newPassword": new_password},
        timeout=30,
    ).raise_for_status()


def ensure_ops_token(cfg: dict, ah: dict, f04: dict) -> str | None:
    """OPS user token; reset and clear password-change gate if the fixture password is stale."""
    code, body = login_password(cfg, f04["username"], OPS_PASSWORD)
    if code == 200 and body.get("accessToken"):
        if body.get("passwordChangeRequired"):
            _complete_password_change(cfg, body["accessToken"], OPS_PASSWORD)
            code, body = login_password(cfg, f04["username"], OPS_PASSWORD)
        return body.get("accessToken") if code == 200 else None
    user_id = f04.get("id")
    if not user_id:
        return None
    rp = requests.post(
        f"{cfg['BASE_URL']}/api/v1/internal/admin/users/{user_id}/reset-password",
        headers=ah,
        timeout=30,
    )
    if not rp.ok:
        return None
    temp = rp.json().get("temporaryPassword", "")
    code2, body2 = login_password(cfg, f04["username"], temp)
    if code2 != 200 or not body2.get("accessToken"):
        return None
    if body2.get("passwordChangeRequired"):
        _complete_password_change(cfg, body2["accessToken"], OPS_PASSWORD)
        code2, body2 = login_password(cfg, f04["username"], OPS_PASSWORD)
    return body2.get("accessToken") if code2 == 200 else None


def run(fixtures_path: Path) -> list[dict]:
    cfg = load_config()
    base = cfg["BASE_URL"]
    fx = json.loads(fixtures_path.read_text(encoding="utf-8"))["fixtures"]
    results: list[dict] = []

    admin = admin_token(cfg)
    ah = {"Authorization": f"Bearer {admin}", "Content-Type": "application/json"}

    # EC-009 inactive LSP token
    f05 = fx.get("F05_inactive_lsp", {})
    if f05.get("clientId"):
        r = requests.post(
            f"{base}/api/v1/auth/token",
            json={"clientId": f05["clientId"], "clientSecret": f05["clientSecret"]},
            timeout=30,
        )
        results.append(result("EC-009", "Pass" if r.status_code in (401, 403) else "Fail", f"Inactive LSP token → {r.status_code}", "POST /auth/token"))

    # EC-015 PRODUCT_ADMIN on admin lsps
    f03 = fx.get("F03_product_admin", {})
    if f03.get("username"):
        code, body = login_password(cfg, f03["username"], OPS_PASSWORD)
        if code == 200:
            tok = body["accessToken"]
            r = requests.get(f"{base}/api/v1/internal/admin/lsps", headers={"Authorization": f"Bearer {tok}"}, timeout=15)
            results.append(result("EC-015", "Pass" if r.status_code == 403 else "Fail", f"PRODUCT_ADMIN /admin/lsps → {r.status_code}", "GET /admin/lsps"))

    # EC-064 OPS webhook redrive / EC-069 OPS MIS download (share one OPS token)
    f04 = fx.get("F04_ops_user", {})
    f01 = fx.get("F01_happy_lsp", {})
    ops_tok = ensure_ops_token(cfg, ah, f04) if f04.get("username") else None
    if ops_tok and f01.get("lspId"):
        r = requests.post(
            f"{base}/api/v1/internal/admin/webhook-outbox/dispatch",
            headers={"Authorization": f"Bearer {ops_tok}"},
            params={"batchSize": 5},
            timeout=15,
        )
        results.append(result("EC-064", "Pass" if r.status_code == 403 else "Fail", f"OPS redrive → {r.status_code}", "POST dispatch"))
    elif f04.get("username") and f01.get("lspId"):
        results.append(result("EC-064", "Fail", "Could not obtain OPS user token", "POST dispatch"))

    if ops_tok:
        r = requests.get(
            f"{base}/api/v1/internal/reports/portfolio-mis",
            headers={"Authorization": f"Bearer {ops_tok}"},
            params={"lspId": f01.get("lspId"), "disbursalDateFrom": "2026-01-01", "disbursalDateTo": "2026-12-31"},
            timeout=30,
        )
        results.append(result("EC-069", "Pass" if r.status_code == 403 else "Fail", f"OPS MIS → {r.status_code}", "GET portfolio-mis"))
    elif f04.get("username"):
        results.append(result("EC-069", "Fail", "Could not obtain OPS user token", "GET portfolio-mis"))

    # EC-008 secret rotation grace
    if f01.get("clientId"):
        probe = requests.post(
            f"{base}/api/v1/auth/token",
            json={"clientId": f01["clientId"], "clientSecret": f01["clientSecret"]},
            timeout=30,
        )
        if probe.status_code == 401:
            lsp_token_for_fixture(cfg, f01, admin_bearer=admin)
        old_secret = f01["clientSecret"]
        clients = requests.get(f"{base}/api/v1/internal/admin/api-clients", headers=ah, timeout=15).json()
        row = next((c for c in clients if c.get("clientId") == f01["clientId"]), None)
        if row and row.get("id"):
            rot = requests.post(f"{base}/api/v1/internal/admin/api-clients/{row['id']}/rotate-secret", headers=ah, timeout=30)
            if rot.ok:
                new_secret = rot.json().get("clientSecret")
                old_ok = requests.post(
                    f"{base}/api/v1/auth/token",
                    json={"clientId": f01["clientId"], "clientSecret": old_secret},
                    timeout=30,
                ).status_code
                new_ok = requests.post(
                    f"{base}/api/v1/auth/token",
                    json={"clientId": f01["clientId"], "clientSecret": new_secret},
                    timeout=30,
                ).status_code
                results.append(
                    result(
                        "EC-008",
                        "Pass" if old_ok == 200 and new_ok == 200 else "Fail",
                        f"Rotate grace old={old_ok} new={new_ok}",
                        "POST rotate-secret + token x2",
                    )
                )
                f01["clientSecret"] = new_secret

    # EC-017 reset password forces change
    if f04.get("id"):
        rp = requests.post(f"{base}/api/v1/internal/admin/users/{f04['id']}/reset-password", headers=ah, timeout=30)
        if rp.ok:
            temp = rp.json().get("temporaryPassword")
            code2, b2 = login_password(cfg, f04["username"], temp or OPS_PASSWORD)
            pcr = b2.get("passwordChangeRequired") if code2 == 200 else None
            results.append(
                result(
                    "EC-017",
                    "Pass" if code2 == 200 and pcr is True else "Fail",
                    f"Reset login passwordChangeRequired={pcr}",
                    "POST reset-password + login",
                )
            )

    # EC-018 inactive product intake
    f06 = fx.get("F06_inactive_product", {})
    if f06.get("clientId") and f06.get("productId"):
        tok = lsp_token_for_fixture(cfg, f06, admin_bearer=admin)
        lh = {"Authorization": f"Bearer {tok}"}
        r = requests.post(
            f"{base}/api/v1/lsp/loan-applications",
            headers={**lh, "Content-Type": "application/json", "Idempotency-Key": uuid_v4()},
            json=loan_body(f06["lspId"], f06["productId"], "inactprod"),
            timeout=90,
        )
        results.append(result("EC-018", "Pass" if r.status_code in (400, 422) else "Fail", f"Inactive product intake → {r.status_code}", "POST loan-applications"))

    # EC-024 missing required borrower fields
    if f01.get("clientId"):
        tok = lsp_token_for_fixture(cfg, f01, admin_bearer=admin)
        r = requests.post(
            f"{base}/api/v1/lsp/loan-applications",
            headers={"Authorization": f"Bearer {tok}", "Content-Type": "application/json", "Idempotency-Key": uuid_v4()},
            json={"lspId": f01["lspId"], "productId": f01["productId"], "lspLoanId": "EXT-SPARSE"},
            timeout=30,
        )
        results.append(result("EC-024", "Pass" if r.status_code == 400 else "Fail", f"Sparse intake → {r.status_code}", "POST loan-applications"))

    # EC-007 revoke session (last — invalidates the disposable admin session)
    code, body = login_password(cfg, cfg["ADMIN_USERNAME"], cfg["ADMIN_PASSWORD"])
    if code == 200:
        tok = body["accessToken"]
        ctx = requests.get(f"{base}/api/v1/internal/system/context", headers={"Authorization": f"Bearer {tok}"}, timeout=15)
        ctx_body = ctx.json() if ctx.ok else {}
        user_id = ctx_body.get("id") or ctx_body.get("userId")
        if user_id:
            requests.post(f"{base}/api/v1/internal/admin/users/{user_id}/revoke-sessions", headers=ah, timeout=15)
            r = requests.get(f"{base}/api/v1/internal/system/context", headers={"Authorization": f"Bearer {tok}"}, timeout=15)
            results.append(result("EC-007", "Pass" if r.status_code == 401 else "Fail", f"Revoked token reuse → {r.status_code}", "revoke-sessions + GET context"))
        else:
            results.append(
                result("EC-007", "Fail", f"No user id in context (login={code}, ctx={ctx.status_code})", "revoke-sessions + GET context")
            )
    else:
        results.append(result("EC-007", "Fail", f"Admin login failed → {code}", "revoke-sessions + GET context"))

    # EC-016 skipped — would lock out admin; document as N/A in notes
    results.append(
        result(
            "EC-016",
            "Blocked",
            "Skipped: disabling sole SYSTEM_ADMIN risks local env lockout.",
            "Manual sandbox only",
            "Run in disposable DB with backup admin",
        )
    )

    return results


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--fixtures", default=str(RUNS / "edge-fixtures.json"))
    ap.add_argument("--update-matrix", action="store_true")
    args = ap.parse_args()
    res = run(Path(args.fixtures))
    write_results(RUNS / "phase2-results.json", res)
    passed = sum(1 for x in res if x["status"] == "Pass")
    print(f"Phase 2: {len(res)} cases, {passed} pass, {len(res) - passed} other")
    if args.update_matrix:
        print(f"Matrix rows updated: {update_matrix(res)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
