#!/usr/bin/env python3
"""LMS performance test orchestrator.

Usage:
  python run.py --list
  python run.py baseline
  python run.py load --concurrency 20 --duration 300
  python run.py all --dry-run
"""
from __future__ import annotations

import argparse
import json
import random
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

PERF_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(PERF_DIR))

from _common import (  # noqa: E402
    MetricsCollector,
    admin_token,
    load_config,
    print_summary,
    run_id,
    wait_for_backend,
    write_report,
)
from fixtures import provision  # noqa: E402
from load_model import build_load_model  # noqa: E402
from workflows import (  # noqa: E402
    workflow_admin_reads,
    workflow_disbursement,
    workflow_idempotency_replay,
    workflow_origination,
    workflow_race_duplicate_disburse,
    workflow_report_async,
    workflow_repayment,
)

SCENARIOS = {
    "baseline": "Single-thread full lifecycle smoke with metrics",
    "load": "Sustained mixed workload at target average RPS",
    "stress": "Ramp beyond peak until errors or timeouts",
    "spike": "Sudden burst then recovery",
    "soak": "Long-duration average load",
    "concurrency": "Race conditions and idempotency",
    "reporting": "Dashboard + async MIS under parallel load",
    "failure": "Invalid payloads, auth failures, retry behavior",
    "all": "Run baseline + load + concurrency + reporting (abbreviated)",
}


def _load_fixture(cfg: dict, rid: str) -> dict:
    fx_path = Path(cfg.get("PERF_FIXTURES_JSON", ".perf-runs/fixtures.json"))
    if fx_path.exists():
        try:
            fx = json.loads(fx_path.read_text(encoding="utf-8"))
            if fx.get("clientId"):
                return fx
        except json.JSONDecodeError:
            pass
    wait_for_backend(cfg)
    fx = provision(cfg, rid)
    fx_path.parent.mkdir(parents=True, exist_ok=True)
    fx_path.write_text(json.dumps(fx, indent=2), encoding="utf-8")
    return fx


def _full_lifecycle_worker(cfg: dict, metrics: MetricsCollector, fixture: dict, admin: str) -> bool:
    orig = workflow_origination(cfg, metrics, fixture, doc_count=3, poll_approval=True)
    if not orig.get("ok"):
        return False
    app_id = orig["applicationId"]
    workflow_disbursement(cfg, metrics, fixture, app_id, admin)
    # Resolve loan account id
    import requests
    ah = {"Authorization": f"Bearer {admin}"}
    detail = requests.get(
        f"{cfg['BASE_URL']}/api/v1/internal/ops/loan-applications/{app_id}",
        headers=ah, timeout=30,
    ).json()
    loan_id = detail.get("loanAccountId")
    if loan_id:
        workflow_repayment(cfg, metrics, fixture, loan_id, admin)
    workflow_admin_reads(cfg, metrics, admin, fixture["lspId"])
    return True


def run_baseline(cfg: dict, fixture: dict, rid: str) -> MetricsCollector:
    metrics = MetricsCollector()
    admin = admin_token(cfg)
    _full_lifecycle_worker(cfg, metrics, fixture, admin)
    workflow_idempotency_replay(cfg, metrics, fixture)
    metrics.finish()
    write_report(cfg, "baseline", metrics, {"run_id": rid})
    print_summary(metrics, "baseline")
    return metrics


def run_load(cfg: dict, fixture: dict, rid: str, *, concurrency: int, duration_sec: float) -> MetricsCollector:
    metrics = MetricsCollector()
    admin = admin_token(cfg)
    stop = threading.Event()
    model = build_load_model(int(cfg.get("PERF_TARGET_LOANS_PER_DAY", "100000")))

    def worker():
        while not stop.is_set():
            try:
                choice = random.random()
                if choice < 0.55:
                    workflow_origination(cfg, metrics, fixture, doc_count=3, poll_approval=False)
                elif choice < 0.75:
                    workflow_admin_reads(cfg, metrics, admin, fixture["lspId"])
                elif choice < 0.90:
                    workflow_idempotency_replay(cfg, metrics, fixture)
                else:
                    workflow_report_async(cfg, metrics, admin, fixture["lspId"])
            except Exception:
                pass
            time.sleep(random.uniform(0.05, 0.3))

    with ThreadPoolExecutor(max_workers=concurrency) as pool:
        futs = [pool.submit(worker) for _ in range(concurrency)]
        time.sleep(duration_sec)
        stop.set()
        for f in as_completed(futs, timeout=duration_sec + 60):
            try:
                f.result()
            except Exception:
                pass

    metrics.finish()
    extra = {"run_id": rid, "concurrency": concurrency, "duration_sec": duration_sec, "load_model": model}
    write_report(cfg, "load", metrics, extra)
    print_summary(metrics, "load")
    return metrics


def run_stress(cfg: dict, fixture: dict, rid: str) -> MetricsCollector:
    metrics = MetricsCollector()
    admin = admin_token(cfg)
    concurrency = int(cfg.get("PERF_CONCURRENCY", "10"))
    for step in [concurrency, concurrency * 2, concurrency * 4, concurrency * 8]:
        stop = threading.Event()

        def worker():
            while not stop.is_set():
                try:
                    workflow_origination(cfg, metrics, fixture, doc_count=1, poll_approval=False)
                except Exception:
                    pass

        with ThreadPoolExecutor(max_workers=step) as pool:
            futs = [pool.submit(worker) for _ in range(step)]
            time.sleep(45)
            stop.set()
        s = metrics.summary()
        if s["error_rate_pct"] > 25:
            break

    metrics.finish()
    write_report(cfg, "stress", metrics, {"run_id": rid})
    print_summary(metrics, "stress")
    return metrics


def run_spike(cfg: dict, fixture: dict, rid: str) -> MetricsCollector:
    metrics = MetricsCollector()
    admin = admin_token(cfg)
    burst = int(float(cfg.get("PERF_BURST_FACTOR", "10"))) * 2

    def burst_worker():
        for _ in range(5):
            try:
                workflow_origination(cfg, metrics, fixture, doc_count=1, poll_approval=False)
            except Exception:
                pass

    with ThreadPoolExecutor(max_workers=burst) as pool:
        futs = [pool.submit(burst_worker) for _ in range(burst)]
        for f in as_completed(futs):
            try:
                f.result()
            except Exception:
                pass

    time.sleep(10)
    for _ in range(10):
        workflow_admin_reads(cfg, metrics, admin, fixture["lspId"])

    metrics.finish()
    write_report(cfg, "spike", metrics, {"run_id": rid, "burst_workers": burst})
    print_summary(metrics, "spike")
    return metrics


def run_soak(cfg: dict, fixture: dict, rid: str, duration_sec: float) -> MetricsCollector:
    return run_load(cfg, fixture, rid, concurrency=5, duration_sec=duration_sec)


def run_concurrency(cfg: dict, fixture: dict, rid: str) -> MetricsCollector:
    metrics = MetricsCollector()
    admin = admin_token(cfg)
    orig = workflow_origination(cfg, metrics, fixture, doc_count=8, poll_approval=True)
    if orig.get("ok"):
        workflow_race_duplicate_disburse(cfg, metrics, orig["applicationId"], admin, threads=8)
    for _ in range(5):
        workflow_idempotency_replay(cfg, metrics, fixture)
    metrics.finish()
    write_report(cfg, "concurrency", metrics, {"run_id": rid})
    print_summary(metrics, "concurrency")
    return metrics


def run_reporting(cfg: dict, fixture: dict, rid: str) -> MetricsCollector:
    metrics = MetricsCollector()
    admin = admin_token(cfg)

    def worker():
        workflow_admin_reads(cfg, metrics, admin, fixture["lspId"])
        workflow_report_async(cfg, metrics, admin, fixture["lspId"])

    with ThreadPoolExecutor(max_workers=6) as pool:
        futs = [pool.submit(worker) for _ in range(12)]
        for f in as_completed(futs):
            try:
                f.result()
            except Exception:
                pass

    metrics.finish()
    write_report(cfg, "reporting", metrics, {"run_id": rid})
    print_summary(metrics, "reporting")
    return metrics


def run_failure(cfg: dict, fixture: dict, rid: str) -> MetricsCollector:
    metrics = MetricsCollector()
    base = cfg["BASE_URL"]
    from _common import timed_request

    # Invalid auth
    timed_request(metrics, method="GET", url=f"{base}/api/v1/internal/home/overview",
                  workflow="failure.no_auth", path_label="GET /api/v1/internal/home/overview", timeout=10)
    # Invalid loan create
    timed_request(metrics, method="POST", url=f"{base}/api/v1/lsp/loan-applications",
                  workflow="failure.bad_body", path_label="POST /api/v1/lsp/loan-applications",
                  headers={"Content-Type": "application/json"}, json={}, timeout=10)
    # Idempotency mismatch
    workflow_idempotency_replay(cfg, metrics, fixture)
    metrics.finish()
    write_report(cfg, "failure", metrics, {"run_id": rid})
    print_summary(metrics, "failure")
    return metrics


def main() -> int:
    parser = argparse.ArgumentParser(description="LMS performance test runner")
    parser.add_argument("scenario", nargs="?", choices=list(SCENARIOS.keys()), default="baseline")
    parser.add_argument("--list", action="store_true")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--concurrency", type=int, default=None)
    parser.add_argument("--duration", type=int, default=None)
    args = parser.parse_args()

    if args.list:
        for k, v in SCENARIOS.items():
            print(f"  {k}: {v}")
        model = build_load_model()
        print("\nLoad model (100k loans/day):")
        print(json.dumps(model, indent=2))
        return 0

    cfg = load_config()
    if args.concurrency:
        cfg["PERF_CONCURRENCY"] = str(args.concurrency)
    if args.duration:
        cfg["PERF_DURATION_SEC"] = str(args.duration)

    rid = run_id(cfg)
    print(f"Run ID: {rid}")

    if args.dry_run:
        print(json.dumps({"run_id": rid, "scenario": args.scenario, "config": cfg}, indent=2))
        return 0

    fixture = _load_fixture(cfg, rid)
    duration = int(cfg.get("PERF_DURATION_SEC", "300"))

    runners = {
        "baseline": lambda: run_baseline(cfg, fixture, rid),
        "load": lambda: run_load(cfg, fixture, rid, concurrency=int(cfg["PERF_CONCURRENCY"]), duration_sec=duration),
        "stress": lambda: run_stress(cfg, fixture, rid),
        "spike": lambda: run_spike(cfg, fixture, rid),
        "soak": lambda: run_soak(cfg, fixture, rid, duration_sec=max(duration, 600)),
        "concurrency": lambda: run_concurrency(cfg, fixture, rid),
        "reporting": lambda: run_reporting(cfg, fixture, rid),
        "failure": lambda: run_failure(cfg, fixture, rid),
    }

    if args.scenario == "all":
        for name in ("baseline", "load", "concurrency", "reporting"):
            print(f"\n>>> Running {name}")
            runners[name]()
        return 0

    runners[args.scenario]()
    return 0


if __name__ == "__main__":
    sys.exit(main())
