#!/usr/bin/env python3
"""Normalize pg_dump --schema-only output for environment-agnostic structural diff."""

from __future__ import annotations

import re
import sys

_SKIP_PREFIXES = (
    "--",
    "SET ",
    "SELECT pg_catalog.set_config",
    "COMMENT ON",
    "\\restrict",
    "\\unrestrict",
)


def normalize(text: str) -> str:
    text = text.lstrip("\ufeff")
    lines: list[str] = []
    for raw in text.splitlines():
        line = raw.rstrip()
        stripped = line.strip()
        if not stripped:
            continue
        if any(stripped.startswith(prefix) for prefix in _SKIP_PREFIXES):
            continue
        if stripped.startswith("CREATE EXTENSION IF NOT EXISTS"):
            continue
        if stripped == "CREATE SCHEMA public;":
            continue
        line = re.sub(r"\s+OWNER TO\s+[^;]+", "", line)
        line = re.sub(r"\s+TABLESPACE\s+\w+", "", line)
        line = re.sub(r"\s+WITH\s+\([^)]*\)\s*$", "", line)
        lines.append(line)
    return "\n".join(lines) + "\n"


def main() -> int:
    sys.stdout.write(normalize(sys.stdin.read()))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
