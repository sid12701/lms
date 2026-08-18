# 12 — Drop the webhook tables and retire the dead-letter alert rule

**What to build:** The webhook path leaves no trace in the schema. The four webhook tables are dropped and
the seeded dead-letter alert rule — which can no longer fire, because nothing produces dead letters any more
— is retired in favour of the alert that matters under the pull design.

The project is pre-production greenfield with no production data and no partner-compatibility constraint, so
this is a hard, destructive cutover. No dual-write window, no expand–contract sequence.

Context: `specs/004-lsp-loan-event-feed/spec.md`, ADR 0007.

**Blocked by:** 11 — Delete the webhook delivery machinery.

**Status:** resolved

- [x] The four webhook tables — subscription, outbox, delivery attempt, and redrive audit — are dropped. —
      `V119__drop_webhook_tables.sql`. **Three tables, not four.** Dropped in FK order:
      `webhook_event_delivery_attempt`, `webhook_outbox_redrive_audit`, then `webhook_event_outbox`, which
      the first two reference. "Subscription" is not a table — `V23__lsp_webhook_subscription.sql` added four
      *columns* to `lsp` (`webhook_enabled`, `webhook_endpoint_url`, `webhook_signing_secret`,
      `webhook_event_types`), deferred here by ticket 11 and dropped in the same migration. ADR 0007 carries
      the same slip ("Roughly 15 files and 4 tables are removed"). Evidence that the trace is gone:
      `pg_dump --schema-only` of a database built from migrations alone matches `webhook` **0 times**
      (it matched 36 before), and `lsp` is down to 9 columns with no `webhook_*` among them.
- [x] The seeded dead-letter alert rule is removed. It describes a failure mode that no longer exists. —
      `DELETE FROM alert_rule WHERE code = 'WEBHOOK_DEAD_LETTER'` retires V60's `…0605` row;
      `DELETE FROM ops_alert WHERE type = 'WEBHOOK_DEAD_LETTER'` goes with it, because `ops_alert.type` is
      `@Enumerated(STRING)` over `OpsAlertType` and a surviving row would be unreadable once the constant is
      gone. `OpsAlertType.WEBHOOK_DEAD_LETTER` and the `AlertRuleDataInitializer` seed entry go too.
      **The Java seed edit is consistency only, not the mechanism**: `seedIfEmpty()` returns early on a
      non-empty table and `ensureRule` skips a code that already exists, so wherever Flyway runs it is the
      migration that actually retires the rule. Checked that neither `alert_rule` nor `ops_alert` has RLS —
      V45 keeps `ops_alert` off the tenant role instead — so V115's `FORCE ROW LEVEL SECURITY` sweep, which
      runs before V119, cannot turn either `DELETE` into a silent no-op.
- [x] **Five migrations touch these tables incidentally** beyond the ten that built them: optimistic locking
      columns, the row-level security grants and policy, the seeded dead-letter alert rule, a JSON-object
      check constraint, and a timestamp column conversion. The removal accounts for all of them. They are not
      reverted — only their webhook-specific statements are superseded. — Both counts verified exactly. The
      ten: V23, V24, V25, V27, V58, V66, V70, V86, V88, V99. The five: V37 (`entity_version` on the outbox),
      V41 (tenant-role grant, `ENABLE ROW LEVEL SECURITY`, `webhook_event_outbox_tenant_policy`), V60 (the
      seeded rule), V72 (`payload_json` → `jsonb` plus `chk_webhook_event_outbox_payload_json_object`), V106
      (`webhook_outbox_redrive_audit.created_at` → `timestamptz`). None edited. `DROP TABLE` supersedes four
      of them outright — indexes, check constraints, foreign keys, the RLS policy and the table grants all go
      with the table — and only V60's seeded row needs an explicit statement. V99, which rewrote
      `lsp.webhook_event_types` *values*, is superseded by the column drop.
- [x] Row-level security policies and role grants that referenced the outbox table are removed cleanly,
      without disturbing the equivalent policies on the loan event log or any other table. — Asserted
      positively, not merely by absence, in `WebhookSchemaRemovalPostgresTest`: `pg_policies` has no
      `webhook%` row **and** `loan_event` still has exactly `loan_event_tenant_insert_policy` +
      `loan_event_tenant_select_policy`; `information_schema.role_table_grants` has no `webhook%` row **and**
      `lms_tenant_app` still holds exactly `INSERT` + `SELECT` on `loan_event`. Run against the untouched
      tree first, those assertions failed with `webhook_event_outbox_tenant_policy` present and 25 webhook
      grant rows — the red the migration turned green.
- [x] Migration history remains valid and replays from empty to current without error. Squashing the
      migration history is explicitly out of scope. — `scripts/schema-diff/generate-reference.sh` (Flyway
      11.1.0 against a fresh `postgres:17-alpine`, from an empty database): *"Successfully applied 115
      migrations to schema \"public\", now at version v119"*, exit 0. Independently, every Postgres-backed
      test class in the suite builds its schema the same way and logs the same 115/v119. Nothing squashed.
- [x] Schema validation passes on startup — no orphaned entity mappings, constraints, or grants. —
      `ddl-auto: validate` under `application-test.yml` across every `@SpringBootTest` and `@DataJpaTest`
      context. `LmsApplicationTests` and `BootstrapSyncPostgresIntegrationTest` both pass. There was nothing
      left to orphan: ticket 11 removed the entities and the `Lsp` fields, and this removes the tables and
      columns they used to map to, in that order.
- [x] Full backend test suite passes and the application starts cleanly against a database built from
      migrations alone. — **818 tests / 1 failure / 0 errors / 2 skipped.** The single failure is
      `LspTenantElevationArchitectureTest.lspApiSurfaceMustNotUseAdminElevationOutsideAllowlist`, byte for
      byte the same failure as the **baseline of 821 / 1 / 0 / 2 measured on this tree before any edit**:
      `OpsAlertEmitters` reads `alert_rule` through `AdminScopedTransactionExecutor` (uncommitted V45/alert
      work) and is absent from `ADMIN_ELEVATION_ALLOWLIST`. It belongs to that change, not to this ticket,
      exactly as ticket 11 recorded. 821 → 818 is arithmetic, not lost coverage:
      `SchemaJsonColumnsPostgresTest` went 70 → 62 (eight parameterised cases keyed on
      `webhook_event_outbox`), offset by the 5 new tests — every other class's count is unchanged.
      Frontend: `tsc -b` clean, 162 test files / 1055 tests pass.

**Not in the ticket, done here:** the frontend `WEBHOOK_DELIVERY` alert subject type, which ticket 11
deferred here by name so that "enum, seed row, display and DB rule" retire together — removed from
`schemas/alert.ts`, `features/alerts/api.ts`, `AlertsFilterBar.tsx`, `lib/alert-display.ts`,
`lib/alert-links.ts` and four test files, plus the `AlertRulesPanel` prose that still promised event-driven
rules firing "on intake, webhooks, and rate limits". No backend code can emit that `subject_type` any more
(`OpsAlertEmitters` emits only `LOAN_APPLICATION` and `SYSTEM`), and `features/alerts/api.ts` already falls
back to `SYSTEM` for an unknown value, so nothing regressed on the way out. Also: the
`webhook_event_outbox.payload_json` row in `docs/database-json-storage.md`, replaced by the
`loan_event.payload_json` row that had never been added; `backend/README.md`'s disbursement-outcome step,
which still said webhooks are written in the follow-up transaction; and the `AlertRuleDataInitializer`
javadoc, which claimed seven rules and an H2 back-fill path that no longer exists.

**Residue swept afterwards:** the frontend sweep above still left webhook vocabulary in four live test
fixtures — `"Webhook retry exhausted"` as the canonical alert title in `alert-display.test.ts` and
`OpenAlertsCard.test.tsx`, and `"Delivery rpt-1 exhausted 5 retries."` left on a fixture that had been
repointed to a `REPORT_REQUEST` subject, where it read as nonsense. Repointed to `"Rate limit breach"` and
`"Report rpt-1 failed after 5 attempts."`. `frontend/src` now matches `webhook` only in the intake
`SourceChannel`/`Channel` enums and openapi-typescript's generated `export type webhooks` — neither is
delivery machinery. One reformatting slip was reverted: the implementer rewrote
`IdempotencyLeaseReclaimTest.java` with normalised line endings, turning a 2-line change into a 60-line
diff; HEAD's bytes were restored and only the `seedLsp()` edit re-applied.

**Left for a decision (not done):**

- **`scripts/schema-diff/artifacts/reference-schema.normalized.sql` is not regenerated here**, though CI's
  `flyway-reference-schema` job regenerates it on any `backend/**` change and fails when it is stale. This
  is unfixable from inside this ticket: the whole of spec 004 (V114–V118 among them) is still uncommitted,
  so an artifact generated from the working tree describes tables whose migrations are not in the commit,
  while one generated from the commit would not match the working tree. Either way the job is red until the
  set lands together. Regenerating it is a single command (`scripts/schema-diff/generate-reference.sh`) and
  belongs to whichever commit finally lands V114–V119 as a unit. Noted while there: that script and
  `check-reference.sh` carry CRLF line endings and are not executable, so `bash scripts/…/generate-reference.sh`
  fails with `set: pipefail: invalid option name` on macOS; CI's `chmod +x` does not fix the line endings.
- **`SchemaJsonColumnsPostgresTest` lost its `webhook_event_outbox.payload_json` coverage and gained no
  replacement for `loan_event.payload_json`.** Deliberate. That column is `JSONB NOT NULL` but carries no
  `jsonb_typeof(...) = 'object'` check constraint, so adding it to `jsonColumns()` would immediately fail
  the `nonObjectJsonInputs` cases, which assert that `[]`, `"scalar"` and `123` are rejected. Closing the
  hole means adding the constraint — a schema change on the loan event log, which is ticket 02's table, not
  this ticket's deletion. Recorded in `docs/database-json-storage.md` so the gap is visible.
- Three findings from the review that belong to the uncommitted work around this ticket, not to it:
  `V96__grant_set_role_on_tenant_app.sql` has been edited in place after being applied, and Flyway's
  `validateOnMigrate` defaults to true, so any environment that already ran V96 will fail to start on a
  checksum mismatch; the `RATE_LIMIT_BREACH` description rewritten in `AlertRuleDataInitializer` can never
  reach a database, for the same early-return reason recorded above, and needs a migration to land;
  and `lib/alert-display.ts` has no labels for `AUTOMATED_DISBURSEMENT_VALIDATION` /
  `AUTOMATED_DISBURSEMENT_BANK_VALIDATION`, both emitted by `LoanDisbursementWorkerProcessor`.
