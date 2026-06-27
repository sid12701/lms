#!/usr/bin/env python3
"""Phase 8: UI edge cases via Playwright (see scripts/e2e/ui-checklist.json)."""
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
from pathlib import Path

import requests

E2E_DIR = Path(__file__).resolve().parent.parent
ROOT = E2E_DIR.parent.parent
FRONTEND = ROOT / "frontend"
RUNS = ROOT / ".e2e-runs"
CHECKLIST = E2E_DIR / "ui-checklist.json"

sys.path.insert(0, str(E2E_DIR))

from _common import admin_token, build_playwright_storage_state, load_config, resolve_npx, result, update_matrix, write_results  # noqa: E402

EC_IDS = ("EC-083", "EC-085", "EC-086", "EC-087", "EC-088", "EC-089", "EC-090", "EC-096", "EC-097", "EC-111")

_PREFERRED_STATUSES = ("UNDER_REPAYMENT", "DISBURSED", "APPROVED_PENDING_DISBURSAL", "INITIALIZED")


def _schedule_count(ah: dict, base: str, app_id: str) -> int:
    try:
        r = requests.get(
            f"{base}/api/v1/internal/ops/loan-applications/{app_id}/repayment-schedule",
            headers=ah,
            timeout=30,
        )
        if not r.ok:
            return 0
        body = r.json()
        rows = body if isinstance(body, list) else body.get("installments") or []
        return len(rows)
    except requests.RequestException:
        return 0


def _fixture_candidates(fixtures: dict) -> list[str]:
    ids: list[str] = []
    for key in ("F14_disbursed_ec111", "F10_disbursed_loan", "F11_under_repayment", "F01_happy_lsp"):
        block = fixtures.get(key, {})
        for field in ("applicationId", "applicationId_initialized"):
            app_id = block.get(field)
            if app_id and app_id not in ids:
                ids.append(app_id)
    return ids


def resolve_live_application_id(cfg: dict, fixtures: dict) -> tuple[str | None, str]:
    """Return a loan application id that exists in the current DB."""
    base = cfg["BASE_URL"]
    try:
        token = admin_token(cfg)
    except requests.RequestException as exc:
        return None, f"admin login failed: {exc}"
    ah = {"Authorization": f"Bearer {token}"}

    fallback: tuple[str, str] | None = None
    for app_id in _fixture_candidates(fixtures):
        try:
            r = requests.get(
                f"{base}/api/v1/internal/ops/loan-applications/{app_id}",
                headers=ah,
                timeout=30,
            )
            if r.ok:
                status = r.json().get("status") or "?"
                note = f"fixture ok ({status})"
                if status == "DISBURSED" and _schedule_count(ah, base, app_id) > 0:
                    return app_id, note
                if fallback is None:
                    fallback = (app_id, note)
        except requests.RequestException:
            continue
    if fallback is not None:
        return fallback

    try:
        listed = requests.get(
            f"{base}/api/v1/internal/ops/loan-applications?page=0&size=50",
            headers=ah,
            timeout=30,
        )
        listed.raise_for_status()
        body = listed.json()
        rows = body if isinstance(body, list) else body.get("content") or body.get("items") or []
    except requests.RequestException as exc:
        return None, f"list applications failed: {exc}"

    if not rows:
        return None, "no loan applications in database"

    for row in rows:
        app_id = row.get("id")
        if row.get("status") == "DISBURSED" and app_id and _schedule_count(ah, base, app_id) > 0:
            return app_id, "live DISBURSED (API list)"

    def sort_key(row: dict) -> tuple[int, str]:
        status = row.get("status") or ""
        rank = _PREFERRED_STATUSES.index(status) if status in _PREFERRED_STATUSES else 99
        return (rank, row.get("id") or "")

    best = sorted(rows, key=sort_key)[0]
    app_id = best.get("id")
    if not app_id:
        return None, "list response missing id"
    return app_id, f"live fallback ({best.get('status')})"


def resolve_ec111_application_id(cfg: dict, fixtures: dict) -> tuple[str | None, str]:
    """Pristine DISBURSED loan for EC-111 (must not be the general phase-8 app id)."""
    base = cfg["BASE_URL"]
    try:
        token = admin_token(cfg)
    except requests.RequestException as exc:
        return None, f"admin login failed: {exc}"
    ah = {"Authorization": f"Bearer {token}"}
    f14 = fixtures.get("F14_disbursed_ec111", {})
    app_id = f14.get("applicationId")
    if not app_id:
        return None, "F14_disbursed_ec111 missing — rebuild fixtures"
    try:
        r = requests.get(f"{base}/api/v1/internal/ops/loan-applications/{app_id}", headers=ah, timeout=30)
        if not r.ok:
            return None, f"F14 not found ({r.status_code})"
        status = r.json().get("status")
        if status != "DISBURSED":
            return None, f"F14 status is {status} (need DISBURSED) — rebuild fixtures"
        if _schedule_count(ah, base, app_id) <= 0:
            return None, "F14 has empty schedule — rebuild fixtures"
    except requests.RequestException as exc:
        return None, f"F14 probe failed: {exc}"
    return app_id, "F14_disbursed_ec111"


def frontend_up(url: str) -> bool:
    candidates = [url]
    if "127.0.0.1" in url:
        candidates.append(url.replace("127.0.0.1", "localhost"))
    for candidate in candidates:
        try:
            r = requests.get(candidate, timeout=5)
            if r.status_code < 500:
                return True
        except requests.RequestException:
            continue
    return False


def parse_playwright_json(report_text: str) -> dict[str, str]:
    """Map EC-xxx id → passed | failed | skipped."""
    outcomes: dict[str, str] = {}
    try:
        data = json.loads(report_text)
    except json.JSONDecodeError:
        return outcomes

    def walk_suite(suite: dict) -> None:
        for spec in suite.get("specs", []):
            title = spec.get("title", "")
            match = re.search(r"(EC-\d+)", title)
            if not match:
                continue
            ec_id = match.group(1)
            ok = all(test.get("status") == "expected" for test in spec.get("tests", []))
            skipped = any(test.get("status") == "skipped" for test in spec.get("tests", []))
            if skipped and not ok:
                outcomes[ec_id] = "skipped"
            else:
                outcomes[ec_id] = "passed" if ok else "failed"
        for child in suite.get("suites", []):
            walk_suite(child)

    for suite in data.get("suites", []):
        walk_suite(suite)
    return outcomes


def run_playwright(application_id: str, cfg: dict[str, str], *, ec111_application_id: str | None = None) -> tuple[dict[str, str], str]:
    auth_path = RUNS / "phase8-auth-storage.json"
    try:
        build_playwright_storage_state(cfg, auth_path)
    except requests.RequestException as exc:
        return {}, f"admin session bootstrap failed: {exc}"

    env = os.environ.copy()
    env["E2E_APPLICATION_ID"] = application_id
    if ec111_application_id:
        env["E2E_EC111_APPLICATION_ID"] = ec111_application_id
    env["E2E_API_BASE"] = cfg.get("BASE_URL", "http://localhost:8080")
    env["E2E_STORAGE_STATE"] = str(auth_path)
    # Playwright treats any truthy CI as "do not reuse webServer"; drop falsey values.
    if env.get("CI", "").lower() in ("", "0", "false", "no"):
        env.pop("CI", None)

    report_path = RUNS / "playwright-phase8.json"
    cmd = [
        resolve_npx(),
        "playwright",
        "test",
        "e2e/edge-coverage-phase8.spec.ts",
        "--reporter=json",
        "--workers=1",
        "--timeout=90000",
    ]
    proc = subprocess.run(
        cmd,
        cwd=str(FRONTEND),
        env=env,
        capture_output=True,
        text=True,
        timeout=600,
        shell=True,
    )
    stdout = proc.stdout or ""
    stderr = proc.stderr or ""
    if stdout.strip():
        report_path.write_text(stdout, encoding="utf-8")
    log = (stderr + "\n" + stdout)[-4000:]
    if proc.returncode not in (0, 1):
        return {}, f"playwright exit {proc.returncode}: {log}"
    return parse_playwright_json(stdout), log


def run(fixtures_path: Path) -> list[dict]:
    cfg = load_config()
    frontend_url = cfg.get("FRONTEND_URL", "http://localhost:5173")
    fx = json.loads(fixtures_path.read_text(encoding="utf-8")).get("fixtures", {})
    app_id, app_note = resolve_live_application_id(cfg, fx)
    ec111_id, ec111_note = resolve_ec111_application_id(cfg, fx)
    results: list[dict] = []

    if not app_id:
        for tid in EC_IDS:
            results.append(result(tid, "Blocked", app_note, "Rebuild fixtures: python scripts/e2e/run_coverage.py --fixtures-only"))
        return results

    if not frontend_up(frontend_url):
        for tid in EC_IDS:
            results.append(
                result(
                    tid,
                    "Blocked",
                    f"Frontend not reachable at {frontend_url}",
                    "Start frontend: cd frontend && npm run dev",
                )
            )
        return results

    outcomes, log = run_playwright(app_id, cfg, ec111_application_id=ec111_id)
    checklist = json.loads(CHECKLIST.read_text(encoding="utf-8"))
    title_by_id = {c["id"]: c.get("title", "") for c in checklist.get("cases", [])}
    app_meta = f"app={app_id} ({app_note})"

    for tid in EC_IDS:
        outcome = outcomes.get(tid)
        if outcome == "passed":
            results.append(
                result(tid, "Pass", f"{title_by_id.get(tid, 'Playwright passed')} — {app_meta}", "Playwright edge-coverage-phase8")
            )
        elif outcome == "skipped":
            skip_note = "Playwright skipped (fixture prerequisite)"
            if tid == "EC-111":
                skip_note = ec111_note or "EC-111 needs DISBURSED loan with schedule installments"
            results.append(result(tid, "Blocked", skip_note, "Playwright edge-coverage-phase8", log[-300:] if log else ""))
        elif outcome == "failed":
            results.append(result(tid, "Fail", "Playwright assertion failed", "Playwright edge-coverage-phase8", log[-500:]))
        else:
            results.append(
                result(
                    tid,
                    "Blocked",
                    "Playwright did not run this case",
                    "Playwright edge-coverage-phase8",
                    log[-300:] if log else "",
                )
            )
    return results


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--fixtures", default=str(RUNS / "edge-fixtures.json"))
    ap.add_argument("--update-matrix", action="store_true")
    args = ap.parse_args()
    res = run(Path(args.fixtures))
    write_results(RUNS / "phase8-results.json", res)
    passed = sum(1 for x in res if x["status"] == "Pass")
    print(f"Phase 8: {len(res)} cases, {passed} pass, {len(res) - passed} other")
    if args.update_matrix:
        print(f"Matrix rows updated: {update_matrix(res)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
