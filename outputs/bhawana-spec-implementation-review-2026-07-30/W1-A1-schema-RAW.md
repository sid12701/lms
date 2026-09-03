# Wave 1 Agent A1 — Database Schema Integrity Audit

## 1. Exact scope reviewed

- Flyway migration chain `V1__foundation.sql` through `V113__borrower_lsp_relationship.sql` under `/Users/siddhant/Desktop/lms/backend/src/main/resources/db/migration/`
- External as-is schema spec and sampled table reference at `/Users/siddhant/Desktop/work/ferratum-products-specs-res/areas/bhawana/platform-setup/database-schema/`
- Focus areas: money columns, CHECK constraints, FKs, idempotency indexes, RLS/policies, soft-delete/cascade behavior, mutable vs append-only financial history, JSONB usage, spec-vs-migration mismatches
- **Mode:** read-only; no files modified, no migrations executed against a live database

---

## 2. Files and specifications inspected

| Path | Use |
|------|-----|
| `/Users/siddhant/Desktop/lms/backend/src/main/resources/db/migration/*.sql` | All 109 migration files (V1–V113) |
| `/Users/siddhant/Desktop/work/ferratum-products-specs-res/areas/bhawana/platform-setup/database-schema/spec.md` | Claims inventory, RLS list, integrity rules |
| `/Users/siddhant/Desktop/work/ferratum-products-specs-res/areas/bhawana/platform-setup/database-schema/table-reference.md` | Sampled financial/audit/RLS tables (`loan_payment_transaction`, `disbursement_intent`, `loan_delinquency_state`, `borrower_pii_reveal_audit`, etc.) |
| `/Users/siddhant/Desktop/lms/backend/src/test/java/com/bhawana/lms/support/FlywaySchemaValidationPostgresTest.java` | Schema validation test |
| `/Users/siddhant/Desktop/lms/backend/src/test/java/com/bhawana/lms/web/TenantIsolationPostgresIntegrationTest.java` | RLS integration coverage |
| `/Users/siddhant/Desktop/lms/backend/src/test/java/com/bhawana/lms/web/Issue86RepaymentIdempotencyIntegrationTest.java` | Payment idempotency coverage |

---

## 3. Feature/workflow examined

- Schema evolution via Flyway (platform identity, origination, servicing, disbursement, payments, foreclosure, webhooks, reporting, audits)
- Tenant isolation via PostgreSQL RLS + restricted `tenant_app_role`
- Financial integrity: loan accounts, repayment schedules, payment allocation, disbursement intent/request/outcome, idempotency records
- Borrower global identity + LSP visibility (`borrower_lsp_access`, `borrower_lsp_relationship`)

---

## 4. End-to-end execution path (schema evolution path)

```text
V1 foundation (lsp, RBAC)
  → V3–V6 products, borrowers, applications
  → V17–V22 loan_account, schedule, payments, disbursement_request_log
  → V26 foreclosure quotes
  → V41 tenant RLS + tenant_app_role grants
  → V43 global borrower + borrower_lsp_access (borrower.lsp_id removed)
  → V45 borrower RLS hardening (split SELECT/INSERT/UPDATE/DELETE policies)
  → V65 financial CHECK constraints
  → V72 JSONB conversion for payload columns
  → V92 payment idempotency UNIQUE constraint
  → V101 delinquency state
  → V103–V112 admin/LSP idempotency + lease fields
  → V111 disbursement_intent (money-movement state machine table)
  → V113 borrower_lsp_relationship (dual-write sibling to access table)
```

Effective baseline: **Flyway V113**, **109 SQL files**, version gaps at **V63, V69, V91, V108**.

---

## 5. Relevant database objects

### Flyway inventory
- **Count:** 109 migration files
- **Latest version:** V113 (`borrower_lsp_relationship.sql`)
- **Documented unused slots in spec:** V63, V69, V91 only
- **Actual unused slots:** V63, V69, V91, **V108** (undocumented in spec)

### Money column types (consistent pattern)
- Currency amounts: `NUMERIC(19, 2)` — e.g. `loan_account.principal_amount` (```8:8:backend/src/main/resources/db/migration/V17__loan_account.sql```), `loan_payment_transaction.amount` (```5:5:backend/src/main/resources/db/migration/V21__loan_payment_transaction.sql```), `disbursement_intent.amount` (```5:5:backend/src/main/resources/db/migration/V111__disbursement_intent.sql```)
- Rates: `NUMERIC(5, 2)` — product pricing (```5:8:backend/src/main/resources/db/migration/V3__loan_product_foundation.sql```)
- No `MONEY` type observed

### CHECK constraints (financial subset)
- V65 adds non-negativity and allocation invariants for products, applications, accounts, installments, payments (```8:49:backend/src/main/resources/db/migration/V65__check_constraints_data_integrity.sql```)
- `disbursement_intent.amount > 0` inline (```5:5:backend/src/main/resources/db/migration/V111__disbursement_intent.sql```)
- JSONB shape checks in V72/V109 (object-type only, not business invariants)

### Idempotency / uniqueness (financial-relevant)
| Table | Constraint | Location |
|-------|-----------|----------|
| `lsp_api_idempotency_record` | `UNIQUE (lsp_id, operation_key, idempotency_key)` | V40:13–14 |
| `admin_api_idempotency_record` | `UNIQUE (operation_key, idempotency_key)` | V103:12–13 |
| `loan_payment_transaction` | `UNIQUE (idempotency_key)` | V92:6–7 |
| `disbursement_intent` | `UNIQUE (tran_ref_no)`; partial unique live intent per account | V111:24–28 |
| `loan_application` | `UNIQUE (lsp_id, external_loan_id)` | V6:23 |

### RLS-enabled tables (23) and policies (26)
Per spec.md:411–413 and migration evidence:
- V41 enables 18 tables + policies
- V42, V43, V71, V75, V113 add 5 more tables
- V45 splits `borrower` into 4 policies (+ access policy from V43) → **26 total policies**

### Tenant-scoped tables **without** RLS (selected)
| Table | Tenant `lsp_id`/scope | RLS | Tenant GRANT |
|-------|----------------------|-----|--------------|
| `disbursement_intent` | via `loan_account_id` | No | No |
| `disbursement_outcome_audit` | via application/account FK | No | No |
| `loan_delinquency_state` | via `loan_application_id` | No | No |
| `borrower_pii_reveal_audit` | `lsp_id` nullable | No | No |
| `report_access_audit` | `lsp_id` (V107) | No | No |
| `loan_disbursement_bank_mismatch_log` | `lsp_id` | No | No |
| `portfolio_kpi_snapshot` | `lsp_id` | No | No |

Spec documents this pattern at spec.md:413 — admin-only or app-scoped access for non-RLS tables.

---

## 6. Findings (evidence-backed)

### W1-A1-F01 — Tenant role can DELETE financial and audit rows
**Severity:** Critical | **Confidence:** High

`tenant_app_role` receives `DELETE` on `loan_payment_transaction`, `loan_disbursement_request_log`, `loan_repayment_schedule_installment`, `loan_foreclosure_quote`, and multiple audit tables:

```202:215:backend/src/main/resources/db/migration/V41__tenant_isolation_rls.sql
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE borrower TO %I', '${tenant_app_role}');
    ...
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE loan_payment_transaction TO %I', '${tenant_app_role}');
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE loan_foreclosure_quote TO %I', '${tenant_app_role}');
```

RLS policies are `FOR ALL` (includes DELETE) with no operation-specific restriction, e.g. payments:

```327:331:backend/src/main/resources/db/migration/V41__tenant_isolation_rls.sql
CREATE POLICY loan_payment_transaction_tenant_policy ON loan_payment_transaction
    FOR ALL
    TO ${tenant_app_role}
    USING (tenant_owns_loan_account(loan_account_id))
    WITH CHECK (tenant_owns_loan_account(loan_account_id));
```

**Recommended change:** Revoke `DELETE` (and likely `UPDATE` on append-only audits) from tenant role; split policies by command; add immutability triggers on audit/financial history where updates must never occur.

---

### W1-A1-F02 — No database immutability for payment/disbursement/audit history
**Severity:** High | **Confidence:** High

- `loan_payment_transaction` has `updated_at` (```12:13:backend/src/main/resources/db/migration/V21__loan_payment_transaction.sql```) and tenant UPDATE/DELETE grants
- `loan_disbursement_request_log` gained `updated_at` (```1:2:backend/src/main/resources/db/migration/V20__loan_disbursement_request_log_updated_at.sql```)
- `disbursement_intent.state` is a mutable column with no transition CHECK/trigger (```10:10:backend/src/main/resources/db/migration/V111__disbursement_intent.sql```)
- Spec explicitly states audit append-only is application behavior, not DB-enforced (spec.md:399, 599)

**Recommended change:** Append-only pattern via `INSERT`-only grants + `BEFORE UPDATE/DELETE` triggers on financial evidence tables; state machine transitions enforced in DB or single-writer service with row versioning.

---

### W1-A1-F03 — `disbursement_intent` lacks RLS; money-movement state is admin-connection only
**Severity:** Medium | **Confidence:** High

V111 creates `disbursement_intent` with no `ENABLE ROW LEVEL SECURITY` and no tenant GRANT (contrast V41 grants). Spec/table-reference correctly document RLS disabled (table-reference.md:1011).

```1:22:backend/src/main/resources/db/migration/V111__disbursement_intent.sql
CREATE TABLE disbursement_intent (
    id UUID PRIMARY KEY,
    loan_account_id UUID NOT NULL REFERENCES loan_account (id),
    ...
    state VARCHAR(32) NOT NULL,
```

**Risk:** Defense relies entirely on admin datasource discipline; compromised admin credentials expose all tenants' disbursement intents. Spec EC-007 acknowledges admin bypass.

**Recommended change:** If tenant APIs ever touch disbursement intent, add RLS via `tenant_owns_loan_account`; otherwise document and enforce admin-only repository access in code review gates.

---

### W1-A1-F04 — Payment idempotency: NULL keys allowed; fingerprint not DB-enforced
**Severity:** High | **Confidence:** High

V56 introduced partial unique index `WHERE idempotency_key IS NOT NULL`. V92 replaced with table-level `UNIQUE (idempotency_key)`:

```4:7:backend/src/main/resources/db/migration/V92__loan_payment_idempotency_fingerprint_and_unique.sql
DROP INDEX IF EXISTS uk_loan_payment_transaction_idempotency_key;
ALTER TABLE loan_payment_transaction
    ADD CONSTRAINT uk_loan_payment_transaction_idempotency_key UNIQUE (idempotency_key);
```

PostgreSQL UNIQUE allows **multiple NULL** `idempotency_key` values. `request_fingerprint` has no unique/compound constraint with `idempotency_key`.

**Recommended change:** `NOT NULL` on `idempotency_key` for API-originated payments, or restore partial unique index plus `UNIQUE (loan_account_id, reference)` where reference is business key.

---

### W1-A1-F05 — No DB uniqueness on payment `reference`; duplicate payments possible
**Severity:** High | **Confidence:** High

`reference` is nullable (V56) with no unique constraint:

```1:2:backend/src/main/resources/db/migration/V56__loan_payment_target_installment_idempotency.sql
ALTER TABLE loan_payment_transaction
    ALTER COLUMN reference DROP NOT NULL;
```

Payments without idempotency keys can duplicate at DB layer.

**Recommended change:** `UNIQUE (loan_account_id, reference)` where `reference IS NOT NULL`, or mandatory idempotency key.

---

### W1-A1-F06 — Installment schedule lacks cross-row principal conservation checks
**Severity:** Medium | **Confidence:** High

V65 checks per-row non-negativity and `paid + outstanding = installment_amount` (```29:42:backend/src/main/resources/db/migration/V65__check_constraints_data_integrity.sql```) but **no** CHECK that:
- `closing_principal` of installment N equals `opening_principal` of N+1
- Sum of `principal_due` equals `loan_account.principal_amount`

**Recommended change:** Deferred constraint triggers or materialized validation job; at minimum document as application-only invariant.

---

### W1-A1-F07 — Foreclosure, disbursement log, processing fee lack amount CHECK constraints
**Severity:** Medium | **Confidence:** High

- `loan_foreclosure_quote` amounts have no CHECK (```17:19:backend/src/main/resources/db/migration/V26__loan_closure_and_foreclosure.sql```)
- `loan_disbursement_request_log.amount` has no CHECK (```5:5:backend/src/main/resources/db/migration/V19__loan_disbursement_request_log.sql```)
- `loan_account.processing_fee_amount` nullable with no non-negative CHECK (```7:8:backend/src/main/resources/db/migration/V97__loan_account_processing_fee_amount.sql```)
- V65 comment defers status enum CHECKs (```5:6:backend/src/main/resources/db/migration/V65__check_constraints_data_integrity.sql```)

**Recommended change:** Add `>= 0` / `> 0` CHECKs on remaining money columns.

---

### W1-A1-F08 — Zero-amount payments permitted
**Severity:** Medium | **Confidence:** High

```46:46:backend/src/main/resources/db/migration/V65__check_constraints_data_integrity.sql
    ADD CONSTRAINT chk_loan_payment_amount_non_negative CHECK (amount >= 0),
```

Allows `amount = 0`, unlike `disbursement_intent` which requires `amount > 0`.

**Recommended change:** `CHECK (amount > 0)` on payments unless zero-amount reversals are a documented domain case.

---

### W1-A1-F09 — CASCADE deletes destroy audit/visibility rows
**Severity:** Medium | **Confidence:** High

| Child | ON DELETE | Risk |
|-------|-----------|------|
| `borrower_pii_reveal_audit` → `borrower` | CASCADE | ```3:3:backend/src/main/resources/db/migration/V102__borrower_pii_reveal_audit.sql``` |
| `borrower_lsp_access` / `borrower_lsp_relationship` → `borrower`/`lsp` | CASCADE | V43, V113 |
| `loan_delinquency_state` → `loan_application` | CASCADE | ```3:3:backend/src/main/resources/db/migration/V101__loan_delinquency_state.sql``` |
| `lsp_api_idempotency_record` → `lsp` | CASCADE | ```3:3:backend/src/main/resources/db/migration/V40__lsp_api_idempotency.sql``` |

Borrower hard-delete is blocked while `loan_application` exists (`ON DELETE RESTRICT` at V6:13), but admin paths or future FK changes could still cascade-wipe audit.

**Recommended change:** Use `RESTRICT` or `SET NULL` on audit FKs; never CASCADE on compliance evidence.

---

### W1-A1-F10 — Tenant-scoped operational tables without RLS (admin-grant model)
**Severity:** Medium | **Confidence:** High

Examples: `loan_delinquency_state` (table-reference.md:876), `borrower_pii_reveal_audit` (table-reference.md:1501), `disbursement_outcome_audit`, `report_access_audit`. None appear in tenant GRANT migrations.

**Risk:** Safe only if tenant connection never receives grants; admin/owner role bypasses RLS (spec.md:413, EC-007).

**Recommended change:** Add RLS on any table that may ever be granted to tenant role; keep admin-only tables out of tenant role permanently.

---

### W1-A1-F11 — JSONB used for operational invariants that migrated to columns late
**Severity:** Medium | **Confidence:** High

`report_access_audit.filter_payload` is JSONB (V87); `lsp_id` added later and backfilled from JSON (```1:8:backend/src/main/resources/db/migration/V107__report_access_audit_lsp_id.sql```). Tenant scope lived in JSON before becoming a column.

`portfolio_kpi_snapshot.status_counts` / `dpd_buckets` are JSONB aggregates (V109) — no schema for bucket keys beyond `jsonb_typeof = 'object'`.

**Recommended change:** Prefer typed columns for tenant scope and financial aggregates; JSONB only for extensible metadata.

---

### W1-A1-F12 — Idempotency lease state stored as magic TEXT JSON in `response_body`
**Severity:** Medium | **Confidence:** High

```11:27:backend/src/main/resources/db/migration/V112__idempotency_lease.sql
UPDATE lsp_api_idempotency_record
SET lease_expires_at = now()
WHERE response_body = '{"__idempotencyPending":true}'
...
CREATE INDEX ... WHERE response_body = '{"__idempotencyPending":true}';
```

Pending state is not a typed column; fragile if JSON spacing/format changes.

**Recommended change:** Dedicated `status`/`lease_state` column with CHECK constraint.

---

### W1-A1-F13 — Spec documents 3 Flyway gaps; repository has 4 (missing V108)
**Severity:** Low | **Confidence:** High

- Spec: gaps V63, V69, V91 (spec.md:84, 119, 483)
- Repository: gaps V63, V69, V91, **V108** (verified via migration filename scan)
- File count 109 matches `113 - 4 gaps`; spec count is right, gap list is incomplete

**Recommended change:** Update spec EC-001 / inventory to include V108.

---

### W1-A1-F14 — `borrower_lsp_access` vs `borrower_lsp_relationship` dual-write drift risk
**Severity:** Medium | **Confidence:** High

V113 comment: visibility still enforced via `borrower_lsp_access`; relationship is dual-written (```1:3:backend/src/main/resources/db/migration/V113__borrower_lsp_relationship.sql```). Spec documents same ambiguity (spec.md:597).

**Risk:** RLS uses `borrower_lsp_access` (V45:26–31); relationship metadata can diverge.

**Recommended change:** DB trigger or constraint to keep parity until cutover; single source of truth.

---

### W1-A1-F15 — V111 backfill creates `disbursement_intent` rows in `UNKNOWN` state
**Severity:** Low | **Confidence:** High

```64:64:backend/src/main/resources/db/migration/V111__disbursement_intent.sql
    'UNKNOWN',
```

In-flight disbursements backfilled without validated state machine position.

**Recommended change:** Post-migration reconciliation job; constrain `state` via CHECK enum.

---

### W1-A1-F16 — Spec vs migrations: material counts align; deployment baseline unverified
**Severity:** Observation | **Confidence:** Medium

Spec claims 52 tables, 66 FKs, 99 indexes, 41 CHECKs, 23 RLS tables, 26 policies (spec.md:24–35, 609). Static migration review supports these orders of magnitude; **no live `psql` verification** was run in this audit. Spec notes production verified only through V109 (spec.md:595, 561).

---

## 7. Tests inspected or executed

**Inspected (not executed):**
- `FlywaySchemaValidationPostgresTest` — Flyway validate + no pending migrations
- `TenantIsolationPostgresIntegrationTest.tenantRlsFailsClosedWithoutTenantContextAndAdminPathStillReadsPrimaryAndChildTables` — RLS on `loan_application`, `borrower`, `loan_application_intake_audit`
- `Issue86RepaymentIdempotencyIntegrationTest` — payment idempotency key replay
- `DisbursementIntentWorkflowIntegrationTest` — disbursement intent workflow (exists; not fully read)

**Not executed:** No Testcontainers/Flyway run in this audit session.

**Gap:** No automated test found asserting tenant role **cannot** DELETE payment/audit rows; RLS tests do not cover `disbursement_intent`, `loan_delinquency_state`, or financial-table immutability.

---

## 8. Commands or checks performed

```bash
ls -1 backend/src/main/resources/db/migration/ | wc -l          # 109 files
ls -1 ... | sort -V | tail -5                                   # V109–V113 latest
# Gap detection for missing version numbers → V63, V69, V91, V108
```

```bash
rg -i "ROW LEVEL SECURITY|CREATE POLICY|ENABLE ROW LEVEL" backend/src/main/resources/db/migration/
rg -i "ON DELETE|deleted_at|soft" backend/src/main/resources/db/migration/
rg "JSONB|jsonb" backend/src/main/resources/db/migration/
rg "CHECK \(|ADD CONSTRAINT chk_" backend/src/main/resources/db/migration/
rg -i "idempotency|UNIQUE" backend/src/main/resources/db/migration/
rg "GRANT.*DELETE" backend/src/main/resources/db/migration/
rg "^CREATE TABLE|create table" backend/src/main/resources/db/migration/
```

---

## 9. Documented rationale found

| Source | Rationale |
|--------|-----------|
| V45:6–9 | `ops_alert` intentionally not granted to tenant role |
| V45:11–14 | Hard cast on `app_current_lsp_id()` — fail loud without tenant context |
| V65:1–6 | CHECK constraints as defense-in-depth; regex/status enums deferred |
| V72:1–5 | JSONB for write-time JSON validation |
| V97:1–6 | `processing_fee_amount` nullable for legacy MIS compatibility |
| V113:1–3 | Relationship table dual-write; access table remains RLS source |
| spec.md:398–399, 599 | No soft-delete convention; audit immutability is app-level |
| spec.md:413, EC-007 | RLS does not protect admin/owner connections |

---

## 10. Inferred rationale (clearly labeled)

- **Inferred:** `disbursement_intent` admin-only (no tenant GRANT) keeps complex disbursement state machine off tenant connection — reduces RLS surface but concentrates risk on admin credentials.
- **Inferred:** `DELETE` grants on tenant role may exist for test/cleanup ergonomics rather than production API use — still a bank-grade exposure if role is used in production.
- **Inferred:** Partial unique index → full unique on `idempotency_key` (V92) simplified constraint at cost of NULL-key duplicate payments.
- **Inferred:** V108 gap may be reserved or accidentally skipped; no migration file explains it.

---

## 11. Missing or contradictory evidence

| Item | Status |
|------|--------|
| Live PostgreSQL V113 `\d+` / `pg_policies` dump | Not run |
| Exact FK/index/CHECK counts vs spec (66/99/41) | Not mechanically verified |
| Whether production has applied V110–V113 | Spec says unverified |
| Whether application code ever issues tenant `DELETE` on financial tables | Not traced in this audit |
| Full 130k-line `table-reference.md` | Systematically sampled, not line-complete |
| Hibernate entity mapping validation | Not run |

---

## 12. Severity summary

| ID | Severity | Confidence |
|----|----------|------------|
| W1-A1-F01 | **Critical** | High |
| W1-A1-F02 | **High** | High |
| W1-A1-F03 | Medium | High |
| W1-A1-F04 | **High** | High |
| W1-A1-F05 | **High** | High |
| W1-A1-F06 | Medium | High |
| W1-A1-F07 | Medium | High |
| W1-A1-F08 | Medium | High |
| W1-A1-F09 | Medium | High |
| W1-A1-F10 | Medium | High |
| W1-A1-F11 | Medium | High |
| W1-A1-F12 | Medium | High |
| W1-A1-F13 | Low | High |
| W1-A1-F14 | Medium | High |
| W1-A1-F15 | Low | High |
| W1-A1-F16 | Observation | Medium |

---

## 13. Recommended changes (prioritized)

1. **Immediate:** Revoke `DELETE` on tenant role for `loan_payment_transaction`, `loan_disbursement_request_log`, `loan_repayment_schedule_installment`, `loan_foreclosure_quote`, and all audit tables; restrict `UPDATE` where rows must be append-only.
2. **High:** Require `idempotency_key NOT NULL` on API payments; add `UNIQUE (loan_account_id, reference)` or equivalent business dedup key.
3. **High:** Add CHECK constraints on remaining money columns (foreclosure, disbursement log, processing fee); consider `amount > 0` on payments.
4. **Medium:** Add RLS to `disbursement_intent` if ever tenant-accessible; otherwise codify admin-only access as architectural invariant.
5. **Medium:** Replace CASCADE on audit FKs with RESTRICT; add immutability triggers on audit/disbursement evidence tables.
6. **Medium:** Typed `idempotency_status` column instead of magic `response_body` JSON sentinel (V112).
7. **Low:** Update spec to document V108 gap; reconcile `borrower_lsp_access` / `borrower_lsp_relationship` to single source of truth.

---

## 14. Questions requiring wider architectural context

1. Is `tenant_app_role` used in production partner traffic, or only admin with `SET ROLE`? (Affects severity of F01.)
2. Are zero-amount payments or payments without idempotency keys valid domain cases?
3. Is `disbursement_intent` intentionally admin-datasource-only for its entire lifecycle?
4. What is the planned cutover from `borrower_lsp_access` to `borrower_lsp_relationship` for RLS?
5. Is hard deletion of borrowers/applications ever permitted in production, or status-only lifecycle?
6. Has any environment deployed beyond V109 in production?

---

## 15. Areas explicitly not reviewed

- Application service/repository write paths (except test file names)
- JPA entity mappings (`backend/src/main/java/com/bhawana/lms/domain/`)
- `scripts/schema-diff/` normalized reference artifact
- Production/staging database connectivity and live row data
- Redis, RabbitMQ, object storage
- Supabase `auth`/`storage` schemas
- Performance/query plans beyond index declarations
- PII encryption (noted as deferred in V65 comments)
- Full line-by-line read of `table-reference.md` (130k+ lines)
- Graphify report (`graphify-out/GRAPH_REPORT.md`) — schema audit used migrations as primary source per spec precedence rules

[REDACTED]