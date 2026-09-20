#!/usr/bin/env python3
"""Unit tests for normalize_schema.py (M01).

Run directly (`python3 scripts/schema-diff/test_normalize_schema.py`) or via
`python3 -m unittest` from the repository root.
"""

from __future__ import annotations

import unittest
from pathlib import Path

import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))

from normalize_schema import normalize  # noqa: E402


_BASE_DUMP = """\
--
-- PostgreSQL database dump
--

SET statement_timeout = 0;
SELECT pg_catalog.set_config('search_path', '', false);

CREATE TABLE public.loan_event (
    id uuid NOT NULL,
    occurred_at timestamp with time zone NOT NULL,
    payload_json jsonb NOT NULL
)
PARTITION BY RANGE (occurred_at);
ALTER TABLE ONLY public.loan_event FORCE ROW LEVEL SECURITY;
CREATE TABLE public.loan_event_2026_08 (
    id uuid NOT NULL,
    occurred_at timestamp with time zone NOT NULL,
    payload_json jsonb NOT NULL
);
CREATE TABLE public.loan_event_2026_09 (
    id uuid NOT NULL,
    occurred_at timestamp with time zone NOT NULL,
    payload_json jsonb NOT NULL
);
CREATE TABLE public.lsp (
    id uuid NOT NULL,
    code character varying(32) NOT NULL
);
ALTER TABLE ONLY public.loan_event_2026_08
    ADD CONSTRAINT loan_event_2026_08_pkey PRIMARY KEY (occurred_at, id);
ALTER TABLE ONLY public.loan_event_2026_09
    ADD CONSTRAINT loan_event_2026_09_pkey PRIMARY KEY (occurred_at, id);
ALTER TABLE ONLY public.lsp
    ADD CONSTRAINT lsp_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.loan_event ATTACH PARTITION public.loan_event_2026_08 FOR VALUES FROM ('2026-08-01 00:00:00+00') TO ('2026-09-01 00:00:00+00');
ALTER TABLE ONLY public.loan_event ATTACH PARTITION public.loan_event_2026_09 FOR VALUES FROM ('2026-09-01 00:00:00+00') TO ('2026-10-01 00:00:00+00');
CREATE INDEX idx_lsp_code ON public.lsp USING btree (code);
CREATE INDEX idx_loan_event_time ON ONLY public.loan_event USING btree (occurred_at);
CREATE INDEX loan_event_2026_08_time_idx ON public.loan_event_2026_08 USING btree (occurred_at);
CREATE INDEX loan_event_2026_09_time_idx ON public.loan_event_2026_09 USING btree (occurred_at);
ALTER INDEX public.idx_loan_event_time ATTACH PARTITION public.loan_event_2026_08_time_idx;
ALTER INDEX public.idx_loan_event_time ATTACH PARTITION public.loan_event_2026_09_time_idx;
ALTER INDEX public.loan_event_pkey ATTACH PARTITION public.loan_event_2026_08_pkey;
ALTER INDEX public.loan_event_pkey ATTACH PARTITION public.loan_event_2026_09_pkey;
CREATE TRIGGER loan_event_append_only BEFORE UPDATE OR DELETE ON public.loan_event FOR EACH ROW EXECUTE FUNCTION public.reject_loan_event_mutation();
CREATE POLICY loan_event_tenant_select_policy ON public.loan_event FOR SELECT TO lms_tenant_app USING ((lsp_id = public.app_current_lsp_id()));
"""


def _bump_month(year: int, month: int) -> tuple[int, int]:
    return (year + 1, 1) if month == 12 else (year, month + 1)


def _shifted_month_dump() -> str:
    """The same schema generated one month later: only partition names/bounds move."""
    import re

    def bump_name(match: re.Match[str]) -> str:
        year, month = _bump_month(int(match.group(1)), int(match.group(2)))
        return f"loan_event_{year}_{month:02d}"

    def bump_bound(match: re.Match[str]) -> str:
        year, month = _bump_month(int(match.group(1)), int(match.group(2)))
        return f"'{year}-{month:02d}-01 00:00:00+00'"

    text = re.sub(r"loan_event_(\d{4})_(\d{2})", bump_name, _BASE_DUMP)
    return re.sub(r"'(\d{4})-(\d{2})-01 00:00:00\+00'", bump_bound, text)


class NormalizeSchemaTest(unittest.TestCase):
    def test_month_shifted_generations_produce_identical_output(self) -> None:
        self.assertEqual(normalize(_BASE_DUMP), normalize(_shifted_month_dump()))

    def test_partition_inventory_is_replaced_by_a_count_marker(self) -> None:
        out = normalize(_BASE_DUMP)
        self.assertIn(
            "-- schema-diff: 2 runtime partition(s) of public.loan_event elided", out
        )
        for fragment in (
            "loan_event_2026_08",
            "ATTACH PARTITION",
            "loan_event_2026_09_pkey",
            "loan_event_2026_08_time_idx",
        ):
            self.assertNotIn(fragment, out)

    def test_stable_partitioned_parent_structure_is_kept(self) -> None:
        out = normalize(_BASE_DUMP)
        self.assertIn("PARTITION BY RANGE (occurred_at);", out)
        self.assertIn(
            "CREATE INDEX idx_loan_event_time ON ONLY public.loan_event", out
        )
        self.assertIn("CREATE TRIGGER loan_event_append_only", out)
        self.assertIn("CREATE POLICY loan_event_tenant_select_policy", out)
        self.assertIn("FORCE ROW LEVEL SECURITY", out)

    def test_missing_table_still_changes_output(self) -> None:
        dropped = _BASE_DUMP.replace(
            """CREATE TABLE public.lsp (
    id uuid NOT NULL,
    code character varying(32) NOT NULL
);
""",
            "",
        ).replace(
            """ALTER TABLE ONLY public.lsp
    ADD CONSTRAINT lsp_pkey PRIMARY KEY (id);
""",
            "",
        ).replace("CREATE INDEX idx_lsp_code ON public.lsp USING btree (code);\n", "")
        self.assertNotEqual(normalize(_BASE_DUMP), normalize(dropped))

    def test_missing_policy_still_changes_output(self) -> None:
        dropped = _BASE_DUMP.replace(
            "CREATE POLICY loan_event_tenant_select_policy ON public.loan_event FOR SELECT TO lms_tenant_app USING ((lsp_id = public.app_current_lsp_id()));\n",
            "",
        )
        self.assertNotEqual(normalize(_BASE_DUMP), normalize(dropped))

    def test_missing_index_still_changes_output(self) -> None:
        dropped = _BASE_DUMP.replace(
            "CREATE INDEX idx_lsp_code ON public.lsp USING btree (code);\n", ""
        )
        self.assertNotEqual(normalize(_BASE_DUMP), normalize(dropped))

    def test_changed_partition_count_changes_the_marker(self) -> None:
        extra = _BASE_DUMP + (
            "CREATE TABLE public.loan_event_2027_01 (\n"
            "    id uuid NOT NULL,\n"
            "    occurred_at timestamp with time zone NOT NULL,\n"
            "    payload_json jsonb NOT NULL\n"
            ");\n"
            "ALTER TABLE ONLY public.loan_event ATTACH PARTITION public.loan_event_2027_01 "
            "FOR VALUES FROM ('2027-01-01 00:00:00+00') TO ('2027-02-01 00:00:00+00');\n"
        )
        self.assertNotEqual(normalize(_BASE_DUMP), normalize(extra))
        self.assertIn(
            "-- schema-diff: 3 runtime partition(s) of public.loan_event elided",
            normalize(extra),
        )

    def test_environment_noise_is_still_stripped(self) -> None:
        noisy = (
            "-- comment\n"
            "SET idle_in_transaction_session_timeout = 0;\n"
            "CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA public;\n"
            "CREATE SCHEMA public;\n"
            "CREATE TABLE public.t (\n"
            "    id uuid NOT NULL\n"
            ") TABLESPACE fast;\n"
            "ALTER TABLE public.t OWNER TO postgres;\n"
            "COMMENT ON TABLE public.t IS 'x';\n"
        )
        out = normalize(noisy)
        self.assertIn("CREATE TABLE public.t (", out)
        self.assertNotIn("EXTENSION", out)
        self.assertNotIn("CREATE SCHEMA", out)
        self.assertNotIn("TABLESPACE", out)
        self.assertNotIn("OWNER TO", out)
        self.assertNotIn("comment", out)
        self.assertNotIn("SET ", out)


if __name__ == "__main__":
    unittest.main()
