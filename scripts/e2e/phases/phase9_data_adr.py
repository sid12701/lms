#!/usr/bin/env python3
"""Phase 9: Data volume, ADR/regression checks (EC-067, EC-068, EC-102, EC-049)."""
from __future__ import annotations

import argparse
import json
import re
import sys
import time
from pathlib import Path

import requests

E2E_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(E2E_DIR))

from _common import RUNS, admin_token, load_config, lsp_token_for_fixture, result, suffix, update_matrix, uuid_v4, write_results  # noqa: E402
from fixtures import ensure_ec068_portfolio, loan_body, upload_all_docs, wait_for_approved  # noqa: E402

MAX_DISBURSE_ATTEMPTS = 5


def has_unmasked_aadhaar(payload: object) -> list[str]:
    hits: list[str] = []

    def walk(node: object, path: str) -> None:
        if isinstance(node, dict):
            for key, value in node.items():
                child_path = f"{path}.{key}" if path else key
                if isinstance(value, str) and re.search(r"aadhaar|aadhar", key, re.I):
                    digits = re.sub(r"\s", "", value)
                    if re.fullmatch(r"\d{12}", digits):
                        hits.append(child_path)
                walk(value, child_path)
        elif isinstance(node, list):
            for i, item in enumerate(node):
                walk(item, f"{path}[{i}]")

    walk(payload, "")
    return hits


def run_ec067(cfg: dict, ah: dict) -> dict:
    base = cfg["BASE_URL"]
    r = requests.get(
        f"{base}/api/v1/internal/reports/portfolio-mis/preview",
        headers=ah,
        params={"page": 0, "size": 50},
        timeout=60,
    )
    if r.status_code != 200:
        return result("EC-067", "Fail", f"MIS preview → {r.status_code}", "GET portfolio-mis/preview")
    body = r.json()
    hits = has_unmasked_aadhaar(body)
    return result(
        "EC-067",
        "Pass" if not hits else "Fail",
        f"Unmasked aadhaar fields: {hits or 'none'}",
        "GET portfolio-mis/preview",
    )


def run_ec068(cfg: dict, ah: dict, fx: dict) -> dict:
    base = cfg["BASE_URL"]
    min_loans = int(cfg.get("E2E_EC068_MIN_LOANS", "100"))
    total = ensure_ec068_portfolio(cfg, ah, fx)
    if total < min_loans:
        return result(
            "EC-068",
            "Blocked",
            f"Portfolio has {total} loans (<{min_loans} required for perf gate)",
            "GET portfolio-mis CSV",
            "Set E2E_EC068_AUTO_SEED=true or seed synthetic portfolio",
        )
    started = time.perf_counter()
    csv_res = requests.get(
        f"{base}/api/v1/internal/reports/portfolio-mis",
        headers=ah,
        timeout=120,
    )
    elapsed = time.perf_counter() - started
    if csv_res.status_code != 200:
        return result("EC-068", "Fail", f"CSV download → {csv_res.status_code}", "GET portfolio-mis")
    return result(
        "EC-068",
        "Pass" if elapsed < 30 else "Fail",
        f"CSV {len(csv_res.content)} bytes in {elapsed:.1f}s",
        "GET portfolio-mis",
    )


def run_ec102(cfg: dict, ah: dict) -> dict:
    base = cfg["BASE_URL"]
    endpoints = (
        "/api/v1/internal/admin/lsps",
        "/api/v1/internal/admin/products",
        "/api/v1/internal/admin/users?page=0&size=5",
    )
    codes = []
    for ep in endpoints:
        r = requests.get(f"{base}{ep}", headers=ah, timeout=30)
        codes.append(r.status_code)
        if r.status_code == 401:
            return result(
                "EC-102",
                "Fail",
                f"401 on {ep} — admin tenant regression",
                f"GET {ep}",
            )
    return result(
        "EC-102",
        "Pass",
        f"Admin endpoints OK → {codes}",
        "GET internal/admin/* after login",
        "ADR 0005 tenant scope — no 401 loop",
    )


def run_ec049(cfg: dict, fx: dict, ah: dict) -> dict:
    base = cfg["BASE_URL"]
    f01 = fx.get("F01_happy_lsp", {})
    if not f01.get("clientId"):
        return result("EC-049", "Blocked", "F01_happy_lsp missing", "Disbursement retry exhaustion")

    try:
        tok = lsp_token_for_fixture(cfg, f01)
        lh = {"Authorization": f"Bearer {tok}", "Content-Type": "application/json", "Idempotency-Key": uuid_v4()}
        app = requests.post(
            f"{base}/api/v1/lsp/loan-applications",
            headers={**lh, "Idempotency-Key": uuid_v4()},
            json=loan_body(f01["lspId"], f01["productId"], f"p9{suffix()[-8:]}"),
            timeout=90,
        )
        if app.status_code not in (200, 201):
            return result(
                "EC-049",
                "Blocked",
                f"Intake failed → {app.status_code} {app.text[:200]}",
                "Create loan for retry test",
            )
        body = app.json()
        app_id = body.get("applicationId") or body.get("id")
        if not app_id:
            return result("EC-049", "Blocked", f"No application id in intake body keys={list(body)}", "Create loan")
        upload_all_docs(lh, base, app_id)
        wait_for_approved(ah, base, app_id, timeout_sec=180)

        failures = 0
        for _ in range(MAX_DISBURSE_ATTEMPTS):
            init = requests.post(
                f"{base}/api/v1/internal/ops/loan-applications/{app_id}/disbursement-requests",
                headers=ah,
                timeout=60,
            )
            if init.status_code not in (200, 201, 409):
                break
            mock = requests.post(
                f"{base}/api/v1/internal/ops/loan-applications/{app_id}/disbursement-requests/mock-outcome",
                headers={**ah, "Content-Type": "application/json"},
                json={"outcome": "FAILED"},
                timeout=60,
            )
            if mock.status_code in (200, 201):
                failures += 1

        detail = requests.get(
            f"{base}/api/v1/internal/ops/loan-applications/{app_id}",
            headers=ah,
            timeout=30,
        ).json()
        status = detail.get("status", "")
        ok = failures >= MAX_DISBURSE_ATTEMPTS and status == "DISBURSEMENT_RETRY"
        return result(
            "EC-049",
            "Pass" if ok else "Fail",
            f"mock FAILED x{failures}, status={status}",
            "POST disbursement + mock-outcome FAILED loop",
        )
    except requests.HTTPError as exc:
        return result("EC-049", "Blocked", f"HTTP error: {exc}", "Disbursement retry exhaustion")
    except (TimeoutError, RuntimeError) as exc:
        return result("EC-049", "Blocked", str(exc), "Wait for approval / disbursement setup")


def run(fixtures_path: Path) -> list[dict]:
    cfg = load_config()
    fx = json.loads(fixtures_path.read_text(encoding="utf-8")).get("fixtures", {})
    ah = {"Authorization": f"Bearer {admin_token(cfg)}"}
    return [
        run_ec067(cfg, ah),
        run_ec068(cfg, ah, fx),
        run_ec102(cfg, ah),
        run_ec049(cfg, fx, ah),
    ]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--fixtures", default=str(RUNS / "edge-fixtures.json"))
    ap.add_argument("--update-matrix", action="store_true")
    args = ap.parse_args()
    res = run(Path(args.fixtures))
    write_results(RUNS / "phase9-results.json", res)
    passed = sum(1 for x in res if x["status"] == "Pass")
    print(f"Phase 9: {len(res)} cases, {passed} pass, {len(res) - passed} other")
    if args.update_matrix:
        print(f"Matrix rows updated: {update_matrix(res)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
