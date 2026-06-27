#!/usr/bin/env python3
"""Merge perf JSON reports into a markdown summary."""
from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent
REPORTS = Path(__file__).resolve().parent / ".perf-runs" / "reports"
OUT = ROOT / "docs" / "perf" / "PERFORMANCE_REPORT_AGGREGATED.md"


def main() -> int:
    REPORTS.mkdir(parents=True, exist_ok=True)
    files = sorted(REPORTS.glob("*.json"), key=lambda p: p.stat().st_mtime)
    if not files:
        print("No report JSON files in .perf-runs/reports — run scenarios first.")
        return 1

    lines = [
        "# LMS Performance Test Report (Generated)",
        "",
        f"Reports aggregated: {len(files)}",
        "",
    ]
    for f in files[-10:]:
        data = json.loads(f.read_text(encoding="utf-8"))
        s = data.get("summary", {})
        lat = s.get("latency_ms", {})
        lines.extend([
            f"## {data.get('scenario', f.stem)} — {data.get('timestamp', '')}",
            "",
            f"- Duration: {s.get('duration_sec')}s",
            f"- RPS: {s.get('rps')}",
            f"- Errors: {s.get('error_count')} ({s.get('error_rate_pct')}%)",
            f"- Latency p50/p95/p99: {lat.get('p50', 0):.0f} / {lat.get('p95', 0):.0f} / {lat.get('p99', 0):.0f} ms",
            "",
        ])
        by_wf = s.get("by_workflow", {})
        if by_wf:
            lines.append("### Workflows")
            lines.append("")
            lines.append("| Workflow | Count | Error % | p95 ms |")
            lines.append("|----------|------:|--------:|-------:|")
            for wf, row in sorted(by_wf.items(), key=lambda x: -x[1]["count"])[:12]:
                lines.append(f"| {wf} | {row['count']} | {row['error_rate_pct']} | {row['p95']:.0f} |")
            lines.append("")

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text("\n".join(lines), encoding="utf-8")
    print(f"Wrote {OUT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
