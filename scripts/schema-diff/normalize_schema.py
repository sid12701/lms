#!/usr/bin/env python3
"""Normalize pg_dump --schema-only output for environment-agnostic structural diff.

Runtime partition inventory (M01): `loan_event` partitions are created relative
to the migration date (V116) and by `maintain_loan_event_partitions()` at runtime
(V117), so their names and FOR VALUES bounds drift with the calendar month. The
normalized schema therefore captures only the stable structure — the partitioned
parent table, its indexes, policies, triggers and the maintenance function — and
replaces each parent's child inventory with a deterministic count marker. A real
schema change (added/dropped table, index, policy, constraint, function) still
changes the output; a different generation month does not.
"""

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

_ATTACH_PARTITION_RE = re.compile(
    r"^ALTER TABLE(?: ONLY)? (\S+) ATTACH PARTITION (\S+)\b"
)
_ALTER_INDEX_ATTACH_RE = re.compile(r"^ALTER INDEX \S+ ATTACH PARTITION \S+")
_CREATE_TABLE_RE = re.compile(r"^CREATE TABLE (\S+)")
# Statements whose subject is a known partition child relation. pg_dump emits
# each partition child as an ordinary CREATE TABLE plus per-child constraints,
# indexes, triggers and policies — all of it is runtime inventory once the
# parent is partitioned.
_ALTER_TABLE_CHILD_RE = re.compile(r"^ALTER TABLE(?: ONLY)? (\S+)")
_CREATE_INDEX_CHILD_RE = re.compile(
    r"^CREATE (?:UNIQUE )?INDEX \S+ ON (?:ONLY )?(\S+)"
)
_CREATE_TRIGGER_CHILD_RE = re.compile(
    r"^CREATE (?:CONSTRAINT )?TRIGGER \S+ .* ON (\S+)"
)
_CREATE_POLICY_CHILD_RE = re.compile(r"^CREATE POLICY \S+ ON (\S+)")


def _partition_children(lines: list[str]) -> dict[str, list[str]]:
    """parent -> sorted child names, discovered from ATTACH PARTITION statements."""
    children: dict[str, set[str]] = {}
    for raw in lines:
        match = _ATTACH_PARTITION_RE.match(raw.strip())
        if match:
            children.setdefault(match.group(1), set()).add(match.group(2))
    return {parent: sorted(names) for parent, names in children.items()}


def _is_child_statement(stripped: str, children: set[str]) -> bool:
    if not children:
        return False
    for pattern in (
        _ALTER_TABLE_CHILD_RE,
        _CREATE_INDEX_CHILD_RE,
        _CREATE_TRIGGER_CHILD_RE,
        _CREATE_POLICY_CHILD_RE,
    ):
        match = pattern.match(stripped)
        if match and match.group(1) in children:
            return True
    return False


def normalize(text: str) -> str:
    text = text.lstrip("\ufeff")
    source_lines = text.splitlines()
    children_by_parent = _partition_children(source_lines)
    children: set[str] = {
        child for names in children_by_parent.values() for child in names
    }

    lines: list[str] = []
    in_child_statement_block = False
    for raw in source_lines:
        line = raw.rstrip()
        stripped = line.strip()

        if in_child_statement_block:
            # A dropped statement may span multiple lines (pg_dump wraps
            # ALTER TABLE … ADD CONSTRAINT across two); it ends with ";".
            if stripped.endswith(";"):
                in_child_statement_block = False
            continue

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
        stripped = line.strip()

        create_table_match = _CREATE_TABLE_RE.match(stripped)
        if create_table_match and create_table_match.group(1) in children:
            if not stripped.endswith(";"):
                in_child_statement_block = True
            continue
        if _ATTACH_PARTITION_RE.match(stripped):
            continue
        if _ALTER_INDEX_ATTACH_RE.match(stripped):
            continue
        if _is_child_statement(stripped, children):
            if not stripped.endswith(";"):
                in_child_statement_block = True
            continue

        lines.append(line)

    for parent in sorted(children_by_parent):
        count = len(children_by_parent[parent])
        lines.append(
            "-- schema-diff: %d runtime partition(s) of %s elided;"
            " child names and bounds derive from the generation date"
            % (count, parent)
        )

    return "\n".join(lines) + "\n"


def main() -> int:
    sys.stdout.write(normalize(sys.stdin.read()))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
