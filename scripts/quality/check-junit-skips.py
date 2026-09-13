#!/usr/bin/env python3
"""Fail CI when Maven silently skips a test outside the reviewed allowlist."""

from __future__ import annotations

import sys
import xml.etree.ElementTree as ET
from pathlib import Path


# These probes are deliberately opt-in. If either runs instead of skipping, that is fine; only
# additional skipped tests are rejected. Keep this list exact—class-level wildcards would hide
# the kind of missing-infrastructure regression this gate exists to expose.
ALLOWED_SKIPS = {
    "com.bhawana.lms.openapi.OpenApiContractExportTest#exportOpenApiSnapshot",
    "com.bhawana.lms.service.R2RegionAndStoreProbeTest#putObjectAgainstConfiguredR2Endpoint",
}


def skipped_tests(report_dir: Path) -> set[str]:
    reports = sorted(report_dir.glob("TEST-*.xml"))
    if not reports:
        raise RuntimeError(f"no Surefire XML reports found under {report_dir}")

    skipped: set[str] = set()
    for report in reports:
        root = ET.parse(report).getroot()
        for testcase in root.iter("testcase"):
            if testcase.find("skipped") is None:
                continue
            classname = testcase.attrib.get("classname", "<unknown-class>")
            name = testcase.attrib.get("name", "<unknown-test>")
            skipped.add(f"{classname}#{name}")
    return skipped


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: check-junit-skips.py <surefire-report-directory>", file=sys.stderr)
        return 2

    report_dir = Path(sys.argv[1])
    try:
        skipped = skipped_tests(report_dir)
    except (OSError, ET.ParseError, RuntimeError) as error:
        print(f"JUnit skip gate could not inspect reports: {error}", file=sys.stderr)
        return 2

    unexpected = sorted(skipped - ALLOWED_SKIPS)
    if unexpected:
        print("Unexpected skipped Maven tests:", file=sys.stderr)
        for test in unexpected:
            print(f"  - {test}", file=sys.stderr)
        print(
            "Run the missing infrastructure or document a narrow, reviewed opt-in probe; "
            "do not add a broad allowlist.",
            file=sys.stderr,
        )
        return 1

    print(f"JUnit skip gate passed ({len(skipped)} reviewed opt-in skip(s)).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
