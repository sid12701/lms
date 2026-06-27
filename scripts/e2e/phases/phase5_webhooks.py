#!/usr/bin/env python3
"""Phase 5: Webhook subscription and delivery edge cases."""
from __future__ import annotations

import argparse
import json
import socket
import subprocess
import sys
import time
from pathlib import Path

import requests

E2E_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(E2E_DIR))

from _common import RUNS, admin_token, load_config, lsp_token_for_fixture, result, suffix, update_matrix, uuid_v4, write_results  # noqa: E402
from fixtures import loan_body  # noqa: E402


def mock_running(host: str = "127.0.0.1", port: int = 9090) -> bool:
    try:
        with socket.create_connection((host, port), timeout=1):
            return True
    except OSError:
        return False


def ensure_mock() -> subprocess.Popen | None:
    if mock_running():
        return None
    server = E2E_DIR / "webhook-mock" / "server.py"
    if not server.exists():
        return None
    return subprocess.Popen([sys.executable, str(server)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def subscribe(ah: dict, base: str, lsp_id: str, body: dict) -> requests.Response:
    return requests.put(
        f"{base}/api/v1/internal/admin/lsps/{lsp_id}/webhook-subscription",
        headers=ah,
        json=body,
        timeout=30,
    )


def run(fixtures_path: Path) -> list[dict]:
    cfg = load_config()
    base = cfg["BASE_URL"]
    fx = json.loads(fixtures_path.read_text(encoding="utf-8"))["fixtures"]
    f01 = fx["F01_happy_lsp"]
    lsp_id = f01["lspId"]
    mock_url = cfg.get("E2E_WEBHOOK_MOCK_URL", "http://127.0.0.1:9090/hook")
    mock_5xx = cfg.get("E2E_WEBHOOK_MOCK_5XX_URL", "http://127.0.0.1:9090/status/500")
    mock_4xx = cfg.get("E2E_WEBHOOK_MOCK_4XX_URL", "http://127.0.0.1:9090/status/404")
    ssrf_url = cfg.get("E2E_WEBHOOK_SSRF_URL", "http://169.254.169.254/latest/meta-data")

    admin = admin_token(cfg)
    ah = {"Authorization": f"Bearer {admin}", "Content-Type": "application/json"}
    results: list[dict] = []

    mock_proc = ensure_mock()
    if mock_proc:
        time.sleep(0.5)
    mock_ok = mock_running()

    base_sub = {
        "enabled": True,
        "signingSecret": "e2e-webhook-secret",
        "eventTypes": ["LOAN_CREATED"],
    }

    r61 = subscribe(ah, base, lsp_id, {**base_sub, "endpointUrl": "https://nonexistent.invalid/hook"})
    results.append(
        result(
            "EC-061",
            "Pass" if r61.status_code in (400, 422) else "Fail",
            f"Subscribe invalid host → {r61.status_code}",
            "PUT webhook-subscription",
        )
    )

    r66 = subscribe(ah, base, lsp_id, {**base_sub, "endpointUrl": ssrf_url})
    results.append(
        result(
            "EC-066",
            "Pass" if r66.status_code in (400, 422) else "Fail",
            f"Subscribe SSRF URL → {r66.status_code}",
            "PUT webhook-subscription",
        )
    )

    results.append(
        result(
            "EC-065",
            "Blocked",
            "Subscriber signature verification is consumer-side.",
            "N/A for LMS API runner",
            "Document in integration tests for webhook consumers.",
        )
    )

    if not mock_ok and not mock_running():
        for tid, note in (
            ("EC-062", "Webhook mock server not running on :9090"),
            ("EC-063", "Webhook mock server not running on :9090"),
            ("EC-105", "Webhook mock server not running on :9090"),
        ):
            results.append(result(tid, "Blocked", note, "Start scripts/e2e/webhook-mock/server.py"))
        if mock_proc:
            mock_proc.terminate()
        return results

    tok = lsp_token_for_fixture(cfg, f01)
    lh = {"Authorization": f"Bearer {tok}", "Content-Type": "application/json"}

    for tid, url, expect_status in (
        ("EC-062", mock_5xx, "RETRYABLE_FAILURE"),
        ("EC-063", mock_4xx, "PERMANENT_FAILURE"),
    ):
        sub = subscribe(ah, base, lsp_id, {**base_sub, "endpointUrl": url, "eventTypes": ["LOAN_CREATED"]})
        if sub.status_code in (400, 422) and "127.0.0.1" in url:
            results.append(
                result(
                    tid,
                    "Blocked",
                    f"Subscribe localhost mock blocked by SSRF guard → {sub.status_code}",
                    "PUT webhook-subscription",
                    "Use a non-private webhook URL or SSRF test bypass for local E2E.",
                )
            )
            continue
        if not sub.ok:
            results.append(
                result(tid, "Fail", f"Subscribe failed → {sub.status_code} {sub.text[:120]}", "PUT webhook-subscription")
            )
            continue
        loan_resp = requests.post(
            f"{base}/api/v1/lsp/loan-applications",
            headers={**lh, "Idempotency-Key": uuid_v4()},
            json=loan_body(lsp_id, f01["productId"], f"wh{tid[-2:]}{suffix()[-6:]}"),
            timeout=90,
        )
        if not loan_resp.ok:
            results.append(
                result(tid, "Fail", f"Loan create for webhook → {loan_resp.status_code}", "POST loan-applications")
            )
            continue
        requests.post(
            f"{base}/api/v1/internal/admin/webhook-outbox/dispatch",
            headers=ah,
            params={"batchSize": 10},
            timeout=60,
        )
        time.sleep(2)
        outbox = requests.get(
            f"{base}/api/v1/internal/admin/webhook-outbox",
            headers=ah,
            params={"lspId": lsp_id},
            timeout=30,
        ).json()
        statuses = [row.get("status") for row in outbox if row.get("eventType") == "LOAN_CREATED"][-5:]
        passed = expect_status in statuses
        results.append(
            result(
                tid,
                "Pass" if passed else "Fail",
                f"Outbox statuses (recent) → {statuses}",
                "dispatch + GET outbox",
            )
        )

    app_id = f01.get("applicationId_initialized")
    if app_id:
        events = requests.get(
            f"{base}/api/v1/internal/ops/loan-applications/{app_id}/webhook-events",
            headers=ah,
            timeout=30,
        )
        if events.ok:
            rows = events.json()
            created = [e.get("createdAt") for e in rows if e.get("createdAt")]
            monotonic = created == sorted(created)
            results.append(
                result(
                    "EC-105",
                    "Pass" if monotonic else "Fail",
                    f"Webhook events monotonic by createdAt ({len(rows)} rows)",
                    "GET webhook-events",
                )
            )
        else:
            results.append(
                result("EC-105", "Fail", f"webhook-events → {events.status_code}", "GET webhook-events")
            )
    else:
        results.append(result("EC-105", "Blocked", "No application id in fixtures.", "GET webhook-events"))

    if mock_proc:
        mock_proc.terminate()
    return results


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--fixtures", default=str(RUNS / "edge-fixtures.json"))
    ap.add_argument("--update-matrix", action="store_true")
    args = ap.parse_args()
    res = run(Path(args.fixtures))
    write_results(RUNS / "phase5-results.json", res)
    passed = sum(1 for x in res if x["status"] == "Pass")
    print(f"Phase 5: {len(res)} cases, {passed} pass, {len(res) - passed} other")
    if args.update_matrix:
        print(f"Matrix rows updated: {update_matrix(res)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
