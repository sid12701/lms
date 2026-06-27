"""Shared helpers for LMS performance / load test harness."""
from __future__ import annotations

import json
import statistics
import time
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import requests

ROOT = Path(__file__).resolve().parent.parent.parent
PERF_DIR = Path(__file__).resolve().parent
RUNS = PERF_DIR / ".perf-runs"
REPORTS = RUNS / "reports"


def load_config() -> dict[str, str]:
    cfg: dict[str, str] = {
        "BASE_URL": "http://localhost:8080",
        "FRONTEND_URL": "http://127.0.0.1:5173",
        "ADMIN_USERNAME": "ops.admin",
        "ADMIN_PASSWORD": "ChangeMe123!",
        "PERF_RUN_PREFIX": "PERF",
        "PERF_FIXTURES_JSON": str(RUNS / "fixtures.json"),
        "PERF_CONCURRENCY": "10",
        "PERF_RAMP_UP_SEC": "30",
        "PERF_RAMP_DOWN_SEC": "15",
        "PERF_DURATION_SEC": "300",
        "PERF_TARGET_LOANS_PER_DAY": "100000",
        "PERF_PEAK_FACTOR": "3.5",
        "PERF_BURST_FACTOR": "10",
        "PERF_REPORT_DIR": str(REPORTS),
    }
    env_file = PERF_DIR / "config.env"
    if env_file.exists():
        for line in env_file.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                cfg[k.strip()] = v.strip()
    return cfg


def run_id(cfg: dict[str, str]) -> str:
    return f"{cfg.get('PERF_RUN_PREFIX', 'PERF')}-{time.strftime('%Y%m%d-%H%M%S')}-{uuid.uuid4().hex[:6]}"


def suffix(run: str) -> str:
    return run.replace("-", "")[-12:]


def uuid_v4() -> str:
    return str(uuid.uuid4())


def wait_for_backend(cfg: dict, *, timeout_sec: float = 180, interval_sec: float = 3) -> None:
    deadline = time.time() + timeout_sec
    url = f"{cfg['BASE_URL']}/api/v1/auth/login"
    body = {"username": cfg["ADMIN_USERNAME"], "password": cfg["ADMIN_PASSWORD"]}
    last_err: Exception | None = None
    while time.time() < deadline:
        try:
            r = requests.post(url, json=body, timeout=8)
            if r.status_code == 200 and r.json().get("accessToken"):
                return
            last_err = RuntimeError(f"login returned {r.status_code}")
        except Exception as exc:
            last_err = exc
        time.sleep(interval_sec)
    raise TimeoutError(f"Backend not ready at {cfg['BASE_URL']}: {last_err}")


def login_password(cfg: dict, username: str, password: str) -> tuple[int, dict]:
    url = f"{cfg['BASE_URL']}/api/v1/auth/login"
    body = {"username": username, "password": password}
    last: requests.Response | None = None
    for _ in range(8):
        last = requests.post(url, json=body, timeout=30)
        if last.status_code == 429:
            retry_after = int(last.headers.get("Retry-After", "15") or 15)
            time.sleep(min(max(retry_after, 5), 60))
            continue
        return last.status_code, last.json() if last.content else {}
    if last is not None:
        return last.status_code, last.json() if last.content else {}
    return 0, {}


def admin_token(cfg: dict) -> str:
    code, body = login_password(cfg, cfg["ADMIN_USERNAME"], cfg["ADMIN_PASSWORD"])
    if code != 200 or not body.get("accessToken"):
        raise RuntimeError(f"admin login failed → {code}")
    return body["accessToken"]


def lsp_token(cfg: dict, client_id: str, secret: str) -> str:
    url = f"{cfg['BASE_URL']}/api/v1/auth/token"
    body = {"clientId": client_id, "clientSecret": secret}
    for _ in range(8):
        r = requests.post(url, json=body, timeout=30)
        if r.status_code == 429:
            time.sleep(int(r.headers.get("Retry-After", "15") or 15))
            continue
        r.raise_for_status()
        return r.json()["accessToken"]
    raise RuntimeError("LSP token exchange failed after retries")


@dataclass
class RequestSample:
    method: str
    path: str
    status: int
    latency_ms: float
    workflow: str
    error: str | None = None


@dataclass
class MetricsCollector:
    samples: list[RequestSample] = field(default_factory=list)
    workflow_counts: dict[str, int] = field(default_factory=dict)
    workflow_errors: dict[str, int] = field(default_factory=dict)
    start_ts: float = field(default_factory=time.time)
    end_ts: float | None = None

    def record(self, sample: RequestSample) -> None:
        self.samples.append(sample)
        self.workflow_counts[sample.workflow] = self.workflow_counts.get(sample.workflow, 0) + 1
        if sample.status >= 400 or sample.error:
            self.workflow_errors[sample.workflow] = self.workflow_errors.get(sample.workflow, 0) + 1

    def finish(self) -> None:
        self.end_ts = time.time()

    @property
    def duration_sec(self) -> float:
        end = self.end_ts or time.time()
        return max(end - self.start_ts, 0.001)

    def latency_percentiles(self, latencies: list[float]) -> dict[str, float]:
        if not latencies:
            return {"p50": 0, "p90": 0, "p95": 0, "p99": 0}
        s = sorted(latencies)
        n = len(s)

        def pct(p: float) -> float:
            idx = min(int(n * p), n - 1)
            return s[idx]

        return {"p50": pct(0.50), "p90": pct(0.90), "p95": pct(0.95), "p99": pct(0.99)}

    def by_endpoint(self) -> dict[str, dict[str, Any]]:
        buckets: dict[str, list[RequestSample]] = {}
        for s in self.samples:
            key = f"{s.method} {s.path}"
            buckets.setdefault(key, []).append(s)
        out: dict[str, dict[str, Any]] = {}
        for key, items in buckets.items():
            lats = [i.latency_ms for i in items]
            errors = sum(1 for i in items if i.status >= 400 or i.error)
            out[key] = {
                "count": len(items),
                "error_count": errors,
                "error_rate_pct": round(100 * errors / len(items), 2),
                "rps": round(len(items) / self.duration_sec, 3),
                **self.latency_percentiles(lats),
            }
        return out

    def by_workflow(self) -> dict[str, dict[str, Any]]:
        buckets: dict[str, list[RequestSample]] = {}
        for s in self.samples:
            buckets.setdefault(s.workflow, []).append(s)
        out: dict[str, dict[str, Any]] = {}
        for wf, items in buckets.items():
            lats = [i.latency_ms for i in items]
            errors = sum(1 for i in items if i.status >= 400 or i.error)
            out[wf] = {
                "count": len(items),
                "error_count": errors,
                "error_rate_pct": round(100 * errors / len(items), 2) if items else 0,
                "rps": round(len(items) / self.duration_sec, 3),
                **self.latency_percentiles(lats),
            }
        return out

    def summary(self) -> dict[str, Any]:
        lats = [s.latency_ms for s in self.samples]
        errors = sum(1 for s in self.samples if s.status >= 400 or s.error)
        timeouts = sum(1 for s in self.samples if s.error and "timeout" in (s.error or "").lower())
        total = len(self.samples)
        return {
            "total_requests": total,
            "duration_sec": round(self.duration_sec, 2),
            "rps": round(total / self.duration_sec, 3) if total else 0,
            "error_count": errors,
            "error_rate_pct": round(100 * errors / total, 2) if total else 0,
            "timeout_count": timeouts,
            "latency_ms": self.latency_percentiles(lats),
            "by_endpoint": self.by_endpoint(),
            "by_workflow": self.by_workflow(),
        }


def timed_request(
    metrics: MetricsCollector,
    *,
    method: str,
    url: str,
    workflow: str,
    path_label: str | None = None,
    timeout: float = 60,
    **kwargs: Any,
) -> requests.Response:
    path = path_label or url.split("://", 1)[-1]
    if "?" in path:
        path = path.split("?", 1)[0]
    t0 = time.perf_counter()
    err: str | None = None
    status = 0
    try:
        r = requests.request(method, url, timeout=timeout, **kwargs)
        status = r.status_code
        return r
    except requests.Timeout:
        err = "timeout"
        raise
    except Exception as exc:
        err = str(exc)[:200]
        raise
    finally:
        latency_ms = (time.perf_counter() - t0) * 1000
        metrics.record(
            RequestSample(
                method=method,
                path=path,
                status=status,
                latency_ms=latency_ms,
                workflow=workflow,
                error=err,
            )
        )


def write_report(cfg: dict, scenario: str, metrics: MetricsCollector, extra: dict | None = None) -> Path:
    report_dir = Path(cfg.get("PERF_REPORT_DIR", str(REPORTS)))
    report_dir.mkdir(parents=True, exist_ok=True)
    rid = extra.get("run_id") if extra else None
    stamp = time.strftime("%Y%m%d-%H%M%S")
    fname = f"{stamp}-{scenario}-{rid or 'run'}.json"
    path = report_dir / fname
    payload = {
        "scenario": scenario,
        "timestamp": stamp,
        "config_snapshot": {k: cfg[k] for k in sorted(cfg) if not k.lower().endswith("password")},
        "summary": metrics.summary(),
        "extra": extra or {},
    }
    path.write_text(json.dumps(payload, indent=2), encoding="utf-8")
    return path


def print_summary(metrics: MetricsCollector, scenario: str) -> None:
    s = metrics.summary()
    print(f"\n=== {scenario} ===")
    print(f"Duration: {s['duration_sec']}s | RPS: {s['rps']} | Errors: {s['error_count']} ({s['error_rate_pct']}%)")
    lat = s["latency_ms"]
    print(f"Latency ms — p50={lat['p50']:.0f} p90={lat['p90']:.0f} p95={lat['p95']:.0f} p99={lat['p99']:.0f}")
    print("\nTop workflows:")
    for wf, data in sorted(s["by_workflow"].items(), key=lambda x: -x[1]["count"])[:8]:
        print(f"  {wf}: n={data['count']} err={data['error_rate_pct']}% p95={data['p95']:.0f}ms")
