#!/usr/bin/env python3
"""
Orchestrate E2E edge-case coverage phases.

  python scripts/e2e/run_coverage.py --fixtures-only
  python scripts/e2e/run_coverage.py --phase 1 --update-matrix
  python scripts/e2e/run_coverage.py --phase all --update-matrix
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
import time
from pathlib import Path

E2E_DIR = Path(__file__).resolve().parent
ROOT = E2E_DIR.parent.parent
RUNS = ROOT / ".e2e-runs"

sys.path.insert(0, str(E2E_DIR))
from _common import resolve_npx  # noqa: E402

NEWMAN_PHASE0_CMD = [
    resolve_npx(),
    "newman",
    "run",
    "postman/LMS-E2E-Testing-Admin-and-LSP.postman_collection.json",
    "-e",
    "postman/LMS-E2E-Local.postman_environment.json",
]

PHASES = {
    "0": ("Newman happy path", NEWMAN_PHASE0_CMD),
    "1": ("API negatives", [sys.executable, str(E2E_DIR / "phases" / "phase1_api_negatives.py")]),
    "2": ("Auth / RBAC", [sys.executable, str(E2E_DIR / "phases" / "phase2_auth_rbac.py")]),
    "3": ("IP allowlist / infra", [sys.executable, str(E2E_DIR / "phases" / "phase3_infra.py")]),
    "4": ("Multi-tenant", [sys.executable, str(E2E_DIR / "phases" / "phase4_multitenant.py")]),
    "5": ("Webhooks", [sys.executable, str(E2E_DIR / "phases" / "phase5_webhooks.py")]),
    "6": ("Rate limits", [sys.executable, str(E2E_DIR / "phases" / "phase6_rate_limits.py")]),
    "7": ("Lifecycle assertions", [sys.executable, str(E2E_DIR / "phases" / "phase7_lifecycle.py")]),
    "8": ("UI Chrome DevTools", [sys.executable, str(E2E_DIR / "phases" / "phase8_ui.py")]),
    "9": ("Data / ADR / regression", [sys.executable, str(E2E_DIR / "phases" / "phase9_data_adr.py")]),
}

# Lifecycle (7) before rate limits (6) so auth/token cases are not starved by burst probes.
ALL_PHASES_ORDER = ("1", "2", "3", "4", "5", "7", "6", "8", "9")
RATE_LIMIT_COOLDOWN_SEC = 65


def run_fixtures(export: Path) -> int:
    cmd = [sys.executable, str(E2E_DIR / "fixtures.py"), "--export", str(export)]
    return subprocess.call(cmd, cwd=str(ROOT))


def run_phase(phase: str, fixtures: Path, update_matrix: bool) -> int:
    if phase == "0":
        cmd = PHASES["0"][1]
        return subprocess.call(cmd, cwd=str(ROOT))
    if phase in ("1", "2", "3", "4", "5", "6", "7", "8", "9"):
        cmd = list(PHASES[phase][1]) + ["--fixtures", str(fixtures)]
        if update_matrix:
            cmd.append("--update-matrix")
        return subprocess.call(cmd, cwd=str(ROOT))
    name, cmd = PHASES.get(phase, ("", []))
    if not cmd:
        print(f"Phase {phase} ({name}) not implemented yet — see docs/e2e-edge-cases-full-coverage-requirements.md")
        return 0
    return subprocess.call(cmd, cwd=str(ROOT))


def main() -> int:
    ap = argparse.ArgumentParser(description="E2E edge case coverage orchestrator")
    ap.add_argument("--phase", default="all", help="Phase number 0-9 or 'all'")
    ap.add_argument("--fixtures-only", action="store_true")
    ap.add_argument("--fixtures", default=str(RUNS / "edge-fixtures.json"))
    ap.add_argument("--update-matrix", action="store_true")
    ap.add_argument("--skip-newman", action="store_true")
    args = ap.parse_args()

    fixtures = Path(args.fixtures)
    if not fixtures.is_absolute():
        fixtures = ROOT / fixtures

    if args.fixtures_only:
        return run_fixtures(fixtures)

    if not fixtures.exists():
        print("Building fixtures first...")
        if run_fixtures(fixtures) != 0:
            return 1

    if args.phase == "all":
        phases = list(ALL_PHASES_ORDER)
        if not args.skip_newman:
            phases = ["0", *phases]
    else:
        phases = [args.phase]

    summary = []
    for ph in phases:
        print(f"\n=== Phase {ph}: {PHASES.get(ph, ('?',))[0]} ===")
        rc = run_phase(ph, fixtures, args.update_matrix)
        summary.append({"phase": ph, "exitCode": rc})
        if rc != 0 and ph == "0":
            print("Newman regression failed — aborting.")
            break
        if ph == "6":
            print(f"Rate-limit cooldown ({RATE_LIMIT_COOLDOWN_SEC}s) before UI phase…")
            time.sleep(RATE_LIMIT_COOLDOWN_SEC)

    RUNS.mkdir(parents=True, exist_ok=True)
    (RUNS / "coverage-run-summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    print(f"\nSummary written to {RUNS / 'coverage-run-summary.json'}")
    return 0 if all(s["exitCode"] == 0 for s in summary) else 1


if __name__ == "__main__":
    sys.exit(main())
