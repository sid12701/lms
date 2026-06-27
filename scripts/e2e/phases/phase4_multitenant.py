#!/usr/bin/env python3
"""Phase 4: Multi-tenant isolation edge cases."""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import requests

E2E_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(E2E_DIR))

from _common import RUNS, load_config, lsp_token_for_fixture, result, update_matrix, write_results  # noqa: E402


def run(fixtures_path: Path) -> list[dict]:
    cfg = load_config()
    base = cfg["BASE_URL"]
    fx = json.loads(fixtures_path.read_text(encoding="utf-8"))["fixtures"]
    f01, f02 = fx["F01_happy_lsp"], fx.get("F02_second_lsp", {})
    results: list[dict] = []

    if not f02.get("clientId"):
        for tid in ("EC-012", "EC-044", "EC-071"):
            results.append(result(tid, "Blocked", "F02_second_lsp missing from fixtures.", "Multi-tenant fixture"))
        return results

    tok_b = lsp_token_for_fixture(cfg, f02)
    lh_b = {"Authorization": f"Bearer {tok_b}"}
    app_a = f01.get("applicationId_initialized")
    borrower_a = f01.get("borrowerId")

    if app_a:
        r = requests.get(f"{base}/api/v1/lsp/loan-applications/{app_a}", headers=lh_b, timeout=30)
        results.append(
            result(
                "EC-012",
                "Pass" if r.status_code in (400, 403, 404) else "Fail",
                f"LSP B → LSP A application → {r.status_code}",
                "GET loan-applications/{id}",
            )
        )
        r = requests.get(f"{base}/api/v1/lsp/loan-applications/{app_a}/documents", headers=lh_b, timeout=30)
        results.append(
            result(
                "EC-044",
                "Pass" if r.status_code in (400, 403, 404) else "Fail",
                f"LSP B list LSP A docs → {r.status_code}",
                "GET documents",
            )
        )
    else:
        results.append(result("EC-012", "Blocked", "No F01 application id.", "GET loan-applications"))
        results.append(result("EC-044", "Blocked", "No F01 application id.", "GET documents"))

    if borrower_a:
        r = requests.get(
            f"{base}/api/v1/lsp/borrowers/{borrower_a}/bank-details",
            headers=lh_b,
            timeout=30,
        )
        results.append(
            result(
                "EC-071",
                "Pass" if r.status_code in (400, 403, 404) else "Fail",
                f"LSP B → LSP A borrower → {r.status_code}",
                "GET bank-details",
            )
        )
    else:
        results.append(result("EC-071", "Blocked", "No F01 borrowerId.", "GET bank-details"))

    return results


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--fixtures", default=str(RUNS / "edge-fixtures.json"))
    ap.add_argument("--update-matrix", action="store_true")
    args = ap.parse_args()
    res = run(Path(args.fixtures))
    write_results(RUNS / "phase4-results.json", res)
    passed = sum(1 for x in res if x["status"] == "Pass")
    print(f"Phase 4: {len(res)} cases, {passed} pass, {len(res) - passed} other")
    if args.update_matrix:
        print(f"Matrix rows updated: {update_matrix(res)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
