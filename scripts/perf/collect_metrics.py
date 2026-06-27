"""Collect infrastructure metrics during perf runs (actuator + optional Postgres)."""
from __future__ import annotations

import json
import sys
import time
from pathlib import Path

import requests

PERF_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(PERF_DIR))

from _common import RUNS, load_config  # noqa: E402


def collect_actuator(cfg: dict) -> dict:
    base = cfg["BASE_URL"]
    out: dict = {"timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())}
    try:
        h = requests.get(f"{base}/actuator/health", timeout=30)
        out["health"] = {"status_code": h.status_code, "body": h.json() if h.ok else h.text[:500]}
    except Exception as exc:
        out["health"] = {"error": str(exc)}
    return out


def collect_postgres_stats(db_url: str) -> dict:
    try:
        import psycopg2  # type: ignore
    except ImportError:
        return {"error": "psycopg2 not installed — pip install psycopg2-binary for DB metrics"}

    out: dict = {}
    try:
        conn = psycopg2.connect(db_url)
        cur = conn.cursor()
        cur.execute("""
            SELECT count(*) FILTER (WHERE state = 'active') AS active,
                   count(*) AS total
            FROM pg_stat_activity WHERE datname = current_database()
        """)
        row = cur.fetchone()
        out["connections"] = {"active": row[0], "total": row[1]}

        cur.execute("""
            SELECT query, calls, mean_exec_time, max_exec_time
            FROM pg_stat_statements
            ORDER BY mean_exec_time DESC LIMIT 10
        """)
        out["slow_queries"] = [
            {"query": q[:200], "calls": c, "mean_ms": round(m, 2), "max_ms": round(x, 2)}
            for q, c, m, x in cur.fetchall()
        ]
        cur.close()
        conn.close()
    except Exception as exc:
        out["error"] = str(exc)
    return out


def main() -> int:
    cfg = load_config()
    payload = {"actuator": collect_actuator(cfg)}
    db_url = cfg.get("LMS_DB_URL")
    if db_url:
        payload["postgres"] = collect_postgres_stats(db_url)

    RUNS.mkdir(parents=True, exist_ok=True)
    out = RUNS / "infra-snapshot.json"
    out.write_text(json.dumps(payload, indent=2), encoding="utf-8")
    print(out.read_text(encoding="utf-8"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
