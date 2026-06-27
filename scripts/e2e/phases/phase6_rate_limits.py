#!/usr/bin/env python3
"""Phase 6: Rate-limit edge cases (requires Redis + APP_RATE_LIMIT_ENABLED=true)."""
from __future__ import annotations

import argparse
import json
import sys
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import requests

E2E_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(E2E_DIR))

from _common import RUNS, load_config, lsp_token_for_fixture, result, suffix, update_matrix, uuid_v4, write_results  # noqa: E402
from fixtures import loan_body  # noqa: E402


def burst(url: str, *, count: int, json_body: dict | None = None, headers: dict | None = None) -> list[int]:
    codes: list[int] = []
    for _ in range(count):
        try:
            r = requests.post(url, headers=headers or {}, json=json_body, timeout=10)
            codes.append(r.status_code)
        except requests.RequestException:
            codes.append(0)
    return codes


def run(fixtures_path: Path) -> list[dict]:
    cfg = load_config()
    base = cfg["BASE_URL"]
    fx = json.loads(fixtures_path.read_text(encoding="utf-8"))["fixtures"]
    f01 = fx["F01_happy_lsp"]
    results: list[dict] = []

    # Authenticate before bursts so login/token buckets are not needed mid-run.
    tok = lsp_token_for_fixture(cfg, f01)

    probe = burst(
        f"{base}/api/v1/auth/login",
        count=8,
        json_body={"username": "nobody", "password": "wrong"},
    )
    if 429 not in probe:
        for tid in ("EC-074", "EC-075", "EC-076"):
            results.append(
                result(
                    tid,
                    "Blocked",
                    f"No 429 in login burst probe → {probe[:6]}",
                    "Enable APP_RATE_LIMIT_ENABLED + Redis; restart backend",
                    "Set APP_RATE_LIMIT_AUTH_PER_MINUTE=5 and LSP_WRITE=10 in config.env",
                )
            )
        return results

    login_codes = burst(
        f"{base}/api/v1/auth/login",
        count=8,
        json_body={"username": cfg["ADMIN_USERNAME"], "password": "definitely-wrong"},
    )
    results.append(
        result(
            "EC-074",
            "Pass" if 429 in login_codes else "Fail",
            f"Login burst codes → {login_codes}",
            "POST /auth/login x8",
        )
    )

    def post_loan(i: int) -> int:
        body = loan_body(f01["lspId"], f01["productId"], f"rl{i}{suffix()[-6:]}")
        headers = {
            "Authorization": f"Bearer {tok}",
            "Content-Type": "application/json",
            "Idempotency-Key": uuid_v4(),
        }
        try:
            r = requests.post(
                f"{base}/api/v1/lsp/loan-applications",
                headers=headers,
                json=body,
                timeout=120,
            )
            return r.status_code
        except requests.RequestException:
            return 0

    # Parallel burst — sequential posts refill the per-minute bucket before 429 is reachable.
    with ThreadPoolExecutor(max_workers=12) as pool:
        futures = [pool.submit(post_loan, i) for i in range(12)]
        lsp_codes = [future.result() for future in futures]
    results.append(
        result(
            "EC-076",
            "Pass" if 429 in lsp_codes else "Fail",
            f"LSP write burst → {lsp_codes}",
            "POST loan-applications x12",
        )
    )

    token_codes = burst(
        f"{base}/api/v1/auth/token",
        count=8,
        json_body={"clientId": f01["clientId"], "clientSecret": "wrong-secret"},
    )
    results.append(
        result(
            "EC-075",
            "Pass" if 429 in token_codes else "Fail",
            f"Token burst codes → {token_codes}",
            "POST /auth/token x8",
        )
    )
    return results


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--fixtures", default=str(RUNS / "edge-fixtures.json"))
    ap.add_argument("--update-matrix", action="store_true")
    args = ap.parse_args()
    res = run(Path(args.fixtures))
    write_results(RUNS / "phase6-results.json", res)
    passed = sum(1 for x in res if x["status"] == "Pass")
    print(f"Phase 6: {len(res)} cases, {passed} pass, {len(res) - passed} other")
    if args.update_matrix:
        print(f"Matrix rows updated: {update_matrix(res)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
