#!/usr/bin/env python3
"""Emit MCP init script + baselines for Phase 8 Chrome DevTools runs."""
from __future__ import annotations

import json
import sys
from pathlib import Path

E2E_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(E2E_DIR))

import requests  # noqa: E402

from _common import admin_token, build_playwright_storage_state, load_config  # noqa: E402

RUNS = E2E_DIR.parent.parent / ".e2e-runs"


def pick_application_id(fixtures: dict) -> str:
    for key in ("F10_disbursed_loan", "F11_under_repayment", "F01_happy_lsp"):
        block = fixtures.get(key, {})
        app_id = block.get("applicationId") or block.get("applicationId_initialized")
        if app_id:
            return app_id
    raise RuntimeError("No application id in edge-fixtures.json")


def main() -> None:
    cfg = load_config()
    auth_path = RUNS / "mcp-auth.json"
    build_playwright_storage_state(cfg, auth_path)
    state = json.loads(auth_path.read_text())
    session = state["origins"][0]["localStorage"][0]["value"]
    init = f'localStorage.setItem("bhawana-lms-session", {json.dumps(session)});'
    (RUNS / "mcp-init.js").write_text(init, encoding="utf-8")

    fx = json.loads((RUNS / "edge-fixtures.json").read_text(encoding="utf-8"))["fixtures"]
    app_id = pick_application_id(fx)
    token = admin_token(cfg)
    ah = {"Authorization": f"Bearer {token}"}
    base = cfg["BASE_URL"]
    detail = requests.get(f"{base}/api/v1/internal/ops/loan-applications/{app_id}", headers=ah, timeout=30)
    detail.raise_for_status()
    wh = requests.get(
        f"{base}/api/v1/internal/ops/loan-applications/{app_id}/webhook-events",
        headers=ah,
        timeout=30,
    )
    wh_body = wh.json() if wh.ok else {}
    deliveries = wh_body.get("deliveries") if isinstance(wh_body, dict) else wh_body
    webhook_count = len(deliveries or [])

    out = {
        "applicationId": app_id,
        "frontendUrl": cfg.get("FRONTEND_URL", "http://localhost:5173"),
        "apiStatus": detail.json().get("status"),
        "webhookCount": webhook_count,
        "initScriptPath": str(RUNS / "mcp-init.js"),
    }
    (RUNS / "mcp-baseline.json").write_text(json.dumps(out, indent=2), encoding="utf-8")
    print(json.dumps(out))


if __name__ == "__main__":
    main()
