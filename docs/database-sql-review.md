# Database & SQL Review — LMS

**Mode:** review-only. No code, schema, migration, SQL, index, or config changes have been applied.
**Date:** 2026-05-26
**Reviewed against:** `backend/src/main/resources/db/migration/V1..V60` + `backend/src/main/java/com/bhawana/lms/{domain,repo,service}`.
**Companion tracker:** [`docs/database-optimization-tracker.md`](./database-optimization-tracker.md).

Every finding is tagged either `AFK` (Claude can implement once approved, mechanical/safe change) or `HITL` (needs human-in-the-loop sign-off — affects business rules, PII handling, schema integrity, or production safety). Statuses in the tracker start as `Pending Discussion`.

---

## 1. Executive summary

The LMS backend persists ~50 PostgreSQL tables driven by Flyway migrations `V1`–`V60` and Spring Data JPA / Hibernate. The schema covers every required surface — users/admins, LSPs, LSP API clients + credentials, loan products + LSP mappings, borrowers (shared across LSPs via `borrower_lsp_access`), loan applications, document checklists with metadata, loan accounts, repayment installments, payment transactions, disbursement request logs, foreclosure quotes, webhook outbox + delivery attempts, MIS/report requests, ops alerts + alert rules, refresh tokens, and audit streams (intake, status transition, audit event, document access, PII reveal, app_user audit, api_client audit, loan_product audit). Row-Level Security policies enforce tenant isolation on the LSP-facing surface from `V41` and were hardened in `V45`.

The design is fundamentally sound: enums-as-strings with length caps, UUID PKs, `TIMESTAMPTZ` everywhere, optimistic locking on the four mutable workflow aggregates, partial indexes where they matter (PAR-30, idempotency keys, performance indexes from `V34`/`V48`), and a unified audit explorer over four streams (`AuditExplorerRepository`) built with `NamedParameterJdbcTemplate` — safe from injection.

The review surfaces these themes:

- **Redundant / overlapping indexes** introduced by successive performance-tuning migrations (`V6`+`V34`+`V48`, `V29`+`V34`, `V36`+`V48`, `V41`+`V43`). The system has not yet pruned the indexes superseded by composites or unique-replacing-non-unique.
- **PII at rest is unencrypted**: `borrower.pan`, `borrower.aadhar_number`, `borrower.mobile`, `borrower.bank_account_number`, `borrower.ifsc_code`, `borrower.email`, `borrower.date_of_birth` — all plaintext columns. `lsp.webhook_signing_secret VARCHAR(255)` is plaintext. (Password and API-client secrets are correctly hashed.)
- **Missing DB-level domain integrity**: no `CHECK` constraints for status enums, monetary non-negativity, tenure > 0, PAN/Aadhaar/IFSC regex, or `min_principal <= max_principal`. All invariants live in application code only.
- **EAGER associations** that can N+1 in hot paths: `Borrower.visibleLspIds` is `@ElementCollection(EAGER)`, `AppUser.roles + lsp` are EAGER (mitigated by `@EntityGraph` in admin reads, not in security filters), `LoanAccount.loanApplication` is `@OneToOne(EAGER)`.
- **Derived-delete queries** (`deleteByLoanProduct_Id`, `deleteByLoanAccount_Id`) — Spring Data JPA performs SELECT-then-per-row-DELETE without `@Modifying` bulk semantics, causing 1+N statements when cleaning a product or schedule.
- **Stored JSON payloads as `TEXT`**: `webhook_event_outbox.payload_json`, `loan_disbursement_request_log.{request,response}_payload_json`, `loan_application_status_transition.rejection_reason_json`, `ops_alert.context_json`, `app_user_audit_event.{before,after}_state_json`, `api_client_audit_event.details_json`, `report_request.report_content` — none use `jsonb`. No path-based queries today, so functional impact is low, but type fidelity and future-query options are blocked.
- **Dead/deprecated columns retained** on `loan_application` (`assigned_to_username`, `assigned_by_username`, `assigned_at`) and the `loan_application_assignment_event` table — explicitly marked deprecated in `V53` but still indexed (`idx_loan_application_assignment_event_application_created_at`). No removal required for forensics, but write paths should be confirmed silent.
- **No FK on `webhook_event_outbox.loan_application_id`** introduced in `V58` (backfilled then indexed but not constrained). Orphans possible if loan_application is ever hard-deleted.
- **No FK on `ops_alert.subject_id`** — intentional polymorphic pointer keyed by `subject_type`; documented in this report as a discussion item, not a bug.
- **`report_request.report_content TEXT`** can grow unboundedly (whole report bodies live in the row). Workable for small CSVs; review retention policy.

No SQL injection, unsafe string concatenation of user input into SQL, or `SELECT *` from a user-controlled table was found. The single `select *` is `AuditExplorerRepository` projecting from its own typed inner subquery, which is benign.

---

## 2. Table-by-table review

Status columns are stored as `VARCHAR(...)` mapped to Java enums via `@Enumerated(EnumType.STRING)`. Postgres has no DB-side enforcement; bad strings would only surface on read.

### 2.1 Identity & access

| Table | PK | Key constraints | Indexes | Notes |
|---|---|---|---|---|
| `lsp` (V1) | `id UUID` | `code UNIQUE`, `status NOT NULL DEFAULT 'ACTIVE'` | — | Webhook columns added in V23 store `webhook_signing_secret VARCHAR(255)` in plaintext. **Finding.** |
| `app_role` (V1) | `id UUID` | `code UNIQUE` | — | Static seed. |
| `app_permission` (V1) | `id UUID` | `code UNIQUE` | — | Unused write-side in the audit; verify still wired in to RBAC. |
| `app_user` (V1) | `id UUID` | `username UNIQUE`, `email UNIQUE`, `lsp_id REFERENCES lsp(id)`, `status NOT NULL` | `idx_app_user_lsp_username (lsp_id, username)` (V48), `idx_app_user_audit_event_user_created` on audit. | `email`/`username` lookups use `IgnoreCase` → `UPPER(...)=UPPER(?)`. Unique index won't be used unless a functional `LOWER(...)` index exists. **Finding.** No CHECK on email format. `token_version BIGINT NOT NULL DEFAULT 0` (V54) used for JWT invalidation. |
| `app_user_role`, `app_role_permission` (V1) | composite | FK CASCADE | — | Clean join tables. |
| `app_user_audit_event` (V54) | `id UUID` | FK `user_id → app_user CASCADE` | `(user_id, created_at DESC)` | `before_state_json`, `after_state_json` as `TEXT`. Consider `jsonb`. |
| `app_role` etc. | — | — | — | No RLS — global lookup tables. |
| `refresh_token` (V47) | `id UUID` | `token_hash UNIQUE` | `(username)`, `(expires_at)` | `username VARCHAR(255)` instead of FK to `app_user.id`. Means rename or delete of user leaves dangling but non-resolvable tokens. **Discussion.** |
| `api_client` (V7,V55) | `id UUID` | `client_id UNIQUE`, FK `lsp_id` RESTRICT | `(lsp_id)` | `secret_hash`, `previous_secret_hash` hashed — good. `last_rotated_at`, `previous_secret_valid_until` support rotation. |
| `api_client_ip_allowlist` (V55) | `id UUID` | FK CASCADE, UNIQUE `(api_client_id, cidr)` | `(api_client_id)` | Good. |
| `api_client_audit_event` (V55) | `id UUID` | FK CASCADE | `(api_client_id, created_at DESC)` | `details_json TEXT`. |
| `lsp_ip_allowlist` (V46) | `id UUID` | FK CASCADE, UNIQUE `(lsp_id, cidr)` | `(lsp_id)` | Admin-only (not granted to tenant role) — correct. |

### 2.2 Loan products

| Table | PK | Constraints | Indexes | Notes |
|---|---|---|---|---|
| `loan_product` (V3) | `id UUID` | `code UNIQUE` | — | No CHECK for `min_principal <= max_principal` or `min_tenure <= max_tenure`. **Finding.** |
| `loan_product_lsp_mapping` (V4) | `id UUID` | UNIQUE `(loan_product_id, lsp_id)`, FK CASCADE both sides | — (only the unique covers both) | `deleteByLoanProduct_Id` triggers JPA per-row delete (no `@Modifying`). **Finding.** RLS-enabled. |
| `loan_product_audit_event` (V5) | `id UUID` | FK CASCADE | `(loan_product_id, created_at DESC)` (V5), `created_at DESC` + `(actor_username, created_at DESC)` (V59) | Triple-indexed for the audit explorer. Good. |

### 2.3 Borrowers (cross-LSP)

| Table | PK | Constraints | Indexes | Notes |
|---|---|---|---|---|
| `borrower` (V6,V10,V33,V43) | `id UUID` | `pan` historically UNIQUE, then dropped in V41, then re-uniqued in V43 (`uk_borrower_pan`) | `uk_borrower_pan` (UNIQUE), `idx_borrower_pan` (V41 — non-unique, **redundant** with the unique), `idx_borrower_mobile`, trigram GINs on `lower(full_name|pan|mobile)` (V48) | Heavy PII: `pan`, `mobile`, `email`, `date_of_birth`, `aadhar_number`, `address_*`, `bank_account_number`, `ifsc_code`. None encrypted. **P0/P1 finding.** No CHECK on PAN regex, Aadhaar length (12), IFSC pattern. |
| `borrower_lsp_access` (V43) | `(borrower_id, lsp_id)` | FK CASCADE both sides | `(lsp_id)` | Drives shared-borrower visibility under RLS. Mapped via `@ElementCollection(EAGER)` on `Borrower.visibleLspIds`. EAGER load causes an extra SELECT per borrower. **Finding.** |

### 2.4 Loan applications & document checklists

| Table | PK | Constraints | Indexes | Notes |
|---|---|---|---|---|
| `loan_application` (V6,V11,V15,V37,V39,V53) | `id UUID` | FKs RESTRICT to borrower/lsp/product; UNIQUE `(lsp_id, external_loan_id)`; `entity_version BIGINT` (V37 optimistic lock) | V6: `(created_at DESC)`, `(borrower_id)`. V34 adds redundant `(status)`, `(lsp_id)`, `(loan_product_id)`, `(created_at DESC)`. V48 adds composite `(lsp_id, created_at DESC)`, `(loan_product_id, created_at DESC)`, `(status, created_at DESC)`, `(source_channel, created_at DESC)`, plus a trigram GIN on `lower(external_loan_id)`. V39 adds `(invalidated_at DESC)`. | **Index redundancy:** V34's single-column indexes (`status`, `lsp_id`, `loan_product_id`, `created_at DESC`) are now covered by V48's composites for any query with an `ORDER BY created_at DESC` shape. The bare `(created_at DESC)` index in V6+V34 is also superseded for most patterns. Deprecated assignment columns retained for forensics (V53). |
| `loan_application_intake_audit` (V8) | `id UUID` | FK CASCADE | `(loan_application_id, created_at DESC)`, V59 adds `(created_at DESC)` + `(actor_username, created_at DESC)` | `payload_json VARCHAR(4000)`. Truncation risk if a borrower payload exceeds 4 KB. Should be `TEXT`. **Finding.** |
| `loan_application_status_transition` (V9,V15,V52,V57) | `id UUID` | FK CASCADE | `(loan_application_id, created_at DESC)` (V9), `(to_status, created_at DESC)` (V57) | `rejection_reason_json TEXT` (V52). |
| `loan_application_audit_event` (V16,V59) | `id UUID` | FK (no CASCADE) | `(loan_application_id, created_at DESC)` (V16), `(created_at DESC)` + `(actor_username, created_at DESC)` (V59) | Single source of truth for the audit explorer. Good coverage. |
| `loan_application_assignment_event` (V11, deprecated V53) | `id UUID` | FK CASCADE | `(loan_application_id, created_at DESC)` | Dead write path. Index now spends maintenance budget for forensic-only reads. **Discussion.** |
| `loan_application_document_checklist` (V12–14,V35,V50) | `id UUID` | FK CASCADE, UNIQUE `(loan_application_id, document_type)` | `(loan_application_id, created_at ASC)` | Status enum collapsed in V50 — backfill is in V50. `file_size_bytes BIGINT`, `file_checksum VARCHAR(128)` good. No CHECK on `file_size_bytes >= 0` or `lms_managed_content` consistency with `storage_key`. |
| `loan_application_document_access_audit` (V28,V59) | `id UUID` | FK (named) | `(loan_application_id, created_at DESC)` + V59 `(created_at DESC)` + `(actor_username, created_at DESC)` | `document_types VARCHAR(500)` is a delimited list, not normalized. Acceptable since query is "list audits for this app". **Discussion.** |
| `loan_application_pii_reveal_audit` (V42) | `id UUID` | FKs to application + lsp | `(loan_application_id, created_at DESC)` | Captures revealed-field list. RLS by `lsp_id`. |

### 2.5 Loan accounts / repayment / payments

| Table | PK | Constraints | Indexes | Notes |
|---|---|---|---|---|
| `loan_account` (V17,V26,V29,V37) | `id UUID` | UNIQUE `loan_application_id`, UNIQUE `account_number`, FKs to borrower/lsp/product/application, `entity_version BIGINT` (V37) | V17: `(borrower_id)`, `(lsp_id)`. V29: `(disbursed_at)`. V34: `IF NOT EXISTS (loan_account_loan_application_id)` (already unique), `(disbursed_at) WHERE disbursed_at IS NOT NULL` (same name as V29 → **second `CREATE INDEX IF NOT EXISTS` is a no-op; intended partial index never created**). V36: `(lsp_id, disbursed_at)`. V48: `(lsp_id, disbursed_at DESC, created_at DESC)`. | **Index issues:** (1) `idx_loan_account_disbursed_at` exists twice in migration history with different semantics — V34's partial version was silently skipped. (2) V36's `(lsp_id, disbursed_at)` is a prefix of V48's `(lsp_id, disbursed_at DESC, created_at DESC)` — redundant. (3) `loan_account_loan_application_id` index from V34 duplicates the unique constraint's implicit index. |
| `loan_repayment_schedule_installment` (V18,V22,V36) | `id UUID` | FK to loan_account; UNIQUE `(loan_account_id, installment_number)` | V36: partial `(loan_account_id, due_date) WHERE outstanding_amount > 0` | Good PAR-30 path. No CHECK that `paid_amount = paid_principal + paid_interest` or `paid_amount + outstanding_amount = installment_amount`. |
| `loan_payment_transaction` (V21,V22,V35,V56) | `id UUID` | FK to loan_account, optional FK to installment | `(loan_account_id, payment_date DESC, created_at DESC)`, `(repayment_installment_id)`, partial UNIQUE on `idempotency_key` | Idempotency well-handled. `reference` is now NULL-able (V56). No CHECK on `amount >= 0`. |
| `loan_disbursement_request_log` (V19,V20) | `id UUID` | FK to loan_account | `(loan_account_id, created_at DESC)` | `request_payload_json` / `response_payload_json` are `TEXT NOT NULL`. May contain PII (borrower bank details, account numbers) shipped to disbursement provider. Encryption at rest unaddressed. **Finding.** |
| `loan_foreclosure_quote` (V26) | `id UUID` | FK to loan_account, UNIQUE `(loan_account_id, version)` | (unique acts as index) | Version-based optimistic flow. Good. |

### 2.6 Webhooks

| Table | PK | Constraints | Indexes | Notes |
|---|---|---|---|---|
| `webhook_event_outbox` (V24,V25,V37,V58) | `id UUID` | FK `lsp_id`. `entity_version` V37. `loan_application_id` added V58 — **no FK constraint**, only an index `(loan_application_id, created_at DESC)`. | `(created_at DESC)`, `(lsp_id, created_at DESC)`, `(status, next_attempt_at, created_at)` dispatch composite | Worker uses `FOR UPDATE SKIP LOCKED` correctly in `WebhookEventOutboxRepositoryImpl#claimIds`. `payload_json TEXT` unbounded — fine, but no retention/purge migration visible. |
| `webhook_event_delivery_attempt` (V25,V27) | `id UUID` | FK to outbox | `(outbox_event_id, created_at DESC)` | V27 backfilled `request_event_type/_delivery_id/_timestamp/_signature` with sentinel defaults (`'UNKNOWN'/'0'/'UNSIGNED'`). Defaults survive on new rows if code paths bypass setters — minor data-quality risk. |

### 2.7 Audit & ops

| Table | PK | Constraints | Indexes | Notes |
|---|---|---|---|---|
| `ops_alert` (V44,V49) | `id UUID` | None on `subject_id` (polymorphic via `subject_type`) | `(status, created_at DESC)` | Intentional admin-only table (not granted to tenant role). `context_json TEXT`. `acknowledgement_note VARCHAR(500)` (V49). |
| `alert_rule` (V60) | `id UUID` | `code UNIQUE` | — | Seeded rules + `config_json` payload. No CHECK on `trigger_kind`/`audience` values; relies on app code. |
| `report_request` (V30,V31,V37,V38) | `id UUID` | FK optional `lsp_id`, `entity_version` | `(created_at)`, `(status)`, `(lsp_id)`, V38 `(status, created_at ASC)` | `report_content TEXT` — entire report body in row. Worker claims via `FOR UPDATE SKIP LOCKED` (Postgres path) — correct. **Retention/finding.** |
| `lsp_api_idempotency_record` (V40) | `id UUID` | FK CASCADE `lsp_id`. UNIQUE `(lsp_id, operation_key, idempotency_key)`. | `(created_at DESC)` | Solid. RLS-enabled. |

### 2.8 RLS / multi-tenancy

`V41` enables RLS on the LSP-visible surface, granting CRUD on tenant data and SELECT on lookups to `${tenant_app_role}`. Policies hinge on `app_current_lsp_id()` (Postgres GUC `app.current_lsp_id`). `V45` splits the borrower policy into per-verb policies and explicitly *omits* `ops_alert` from the tenant grants. `V42`/`V43` extend the model: cross-LSP borrowers gated by `borrower_lsp_access`, PII reveal audit RLS by `lsp_id`. This is one of the strongest layers of the schema.

Caveats noted in `V45` comments:
- `app_current_lsp_id()` deliberately throws on a missing GUC — programming error surfaces loudly. Good.
- `ops_alert` is admin-only; writes must hop to the admin DataSource.

---

## 3. Relationship map

```
lsp ──┬─< app_user (lsp_id)
      ├─< api_client (lsp_id) ──< api_client_ip_allowlist
      │                          api_client_audit_event
      ├─< lsp_ip_allowlist
      ├─< loan_product_lsp_mapping >── loan_product
      ├─< loan_application (lsp_id) ─┐
      ├─< loan_account (lsp_id)      │
      ├─< webhook_event_outbox       │
      │   └─< webhook_event_delivery_attempt
      ├─< lsp_api_idempotency_record │
      ├─< loan_application_pii_reveal_audit
      └─< report_request (optional)  │
                                     │
borrower ──┬─< borrower_lsp_access >─┴── lsp
           ├─< loan_application
           └─< loan_account

loan_application ──┬─< loan_application_intake_audit
                   ├─< loan_application_status_transition
                   ├─< loan_application_audit_event
                   ├─< loan_application_document_checklist
                   ├─< loan_application_document_access_audit
                   ├─< loan_application_assignment_event (deprecated)
                   ├─< loan_application_pii_reveal_audit
                   └─1─ loan_account

loan_account ──┬─< loan_repayment_schedule_installment
               ├─< loan_payment_transaction (optional → installment)
               ├─< loan_disbursement_request_log
               └─< loan_foreclosure_quote

app_user ──< app_user_role >── app_role ──< app_role_permission >── app_permission
app_user ──< app_user_audit_event

webhook_event_outbox ─(loan_application_id NULLABLE, NO FK)→ loan_application   ← orphan risk

ops_alert ─(subject_id polymorphic, NO FK)→ {loan_application | loan_account | webhook_event_outbox | lsp | api_client}

alert_rule (standalone)
refresh_token ─(username string, NOT FK)→ app_user
```

**Orphan-data risks:**
- `webhook_event_outbox.loan_application_id` has no FK (V58). Hard-deleting a loan_application would leave orphan rows. No code path currently hard-deletes applications, but the schema does not prevent it.
- `ops_alert.subject_id` is intentionally polymorphic — referential integrity by convention only.
- `refresh_token.username` denormalizes the user — username rename (not currently supported) would leave tokens that won't resolve. With current `username UNIQUE` and no rename surface, low risk.
- `loan_application_assignment_event` rows can outlive the assignment write path (V53 deprecation).

---

## 4. SQL query findings (file paths inline)

### 4.1 Generally safe patterns

- `backend/src/main/java/com/bhawana/lms/repo/AuditExplorerRepository.java` — UNION ALL across four audit streams, built with named parameters via `NamedParameterJdbcTemplate`. No user input concatenated. Each branch filter is `(:__param is null or column = :__param)`. The outer `select * from (union all) u` is over a typed inner subquery, not a user-controlled relation.
- `backend/src/main/java/com/bhawana/lms/repo/ReportRequestRepositoryImpl.java#claimIds` and `WebhookEventOutboxRepositoryImpl#claimIds` — native `FOR UPDATE SKIP LOCKED` claim queries. Placeholder names are generated by integer index (`:status0`, `:status1`, …) from a closed enum collection, not user input.
- `backend/src/main/java/com/bhawana/lms/repo/LoanApplicationReadRepository.java` — dynamic JPQL builder for the LSP/admin applications list. All filter fragments use parameter binding (`:lspId`, `:queryText`, …). The `queryText` is wrapped with `%…%` for `LIKE`. Safe from injection.
- `backend/src/main/java/com/bhawana/lms/repo/PortfolioMisReadRepository.java` — dynamic JPQL for MIS export & summary; all parameters bound. The PAR-30 EXISTS subquery is index-supported by `idx_installment_overdue_lookup`.

### 4.2 N+1 / cartesian / fetch-strategy concerns

- `backend/src/main/java/com/bhawana/lms/domain/Borrower.java:27-30` — `@ElementCollection(fetch = EAGER)` on `visibleLspIds`. Every load of a `Borrower` triggers an extra SELECT against `borrower_lsp_access`. When borrowers are loaded in a list (admin search, MIS export, audit explorer joins), this becomes N+1. Mitigation today: most list paths fetch via projections, not the entity. Confirm before changing.
- `backend/src/main/java/com/bhawana/lms/domain/LoanAccount.java:27-29` — `@OneToOne(fetch = EAGER, optional = false)` to `LoanApplication`. JPA cannot truly lazy-load owning-side `@OneToOne` without bytecode enhancement, so every load pulls the application eagerly. The `findDetailedBy*` paths use `@EntityGraph` and are fine; ad-hoc `findById` calls bring in the application unconditionally.
- `backend/src/main/java/com/bhawana/lms/domain/AppUser.java:28-30,51-57` — `lsp` and `roles` are EAGER. Acceptable for auth (single-user load), but list endpoints rely on `@EntityGraph(attributePaths = {"lsp","roles"})` (`AppUserRepository#findAllByOrderByUsernameAsc`, `findByLsp_IdOrderByUsernameAsc`, `findDetailedById`) — good.
- `backend/src/main/java/com/bhawana/lms/repo/LoanRepaymentScheduleInstallmentRepository.java:20` — `long deleteByLoanAccount_Id(UUID)`: Spring Data JPA derived-delete without `@Modifying` does SELECT-then-DELETE-per-row. Not a hot path (only when re-amortising), but worth flagging.
- `backend/src/main/java/com/bhawana/lms/repo/LoanProductLspMappingRepository.java:16` — same `deleteByLoanProduct_Id` pattern when an LSP is removed from a product.
- `backend/src/main/java/com/bhawana/lms/service/ApiClientManagementService.java:91`, `ProductConfigurationService.java:45,175` — `repo.findAll().stream()...` with no pageable. Currently bounded by row counts (LSPs/products/api_clients are small). Becomes a hazard if those tables grow.

### 4.3 Index utilisation concerns

- `backend/src/main/java/com/bhawana/lms/repo/AppUserRepository.java` — `existsByUsernameIgnoreCase`, `existsByEmailIgnoreCase`, `findByUsernameIgnoreCase` translate to `UPPER(col) = UPPER(?)`. The unique B-tree on raw `username`/`email` won't be used. No functional `LOWER(username)` index exists. **Finding.**
- `backend/src/main/java/com/bhawana/lms/repo/BorrowerRepository.java` — `findByPanIgnoreCase` same pattern. `pan` is normalised to uppercase on write, so the `UPPER(pan)` predicate matches stored values, but the unique B-tree on `pan` won't be used unless the predicate is on raw `pan`. The trigram GIN `idx_borrower_pan_trgm` only helps `LIKE`/similarity, not equality.

### 4.4 Pagination

- `LoanApplicationReadRepository` supports paginated and unpaginated calls. When `paginationRequested=false`, the full list is returned in memory. Callers should be audited for which use the unbounded form.
- `PortfolioMisReadRepository.findAccountsForExport` is the export path — explicitly unbounded by design.
- `WebhookEventOutboxRepository.findTop200ByLoanApplicationIdOrderByCreatedAtDesc` — bounded at 200, good.
- `LoanApplicationStatusTransitionRepository.findTop20...` — bounded, good.
- `OpsAlertRepository.findAllByOrderByCreatedAtDesc()` — **unbounded list**. Used for the ops console alerts table. If alert history grows, this needs pagination.
- `LoanPaymentTransactionRepository.findByLoanAccount_IdOrderByPaymentDate...` (no Top50) — unbounded. Per-account, so usually small, but no enforced cap.

### 4.5 SELECT * / full scans / inefficient joins

- The only `select *` in the codebase is over `AuditExplorerRepository`'s own typed inner subquery — benign.
- `LoanAccountRepository#findHomeDashboardAccountSnapshots` and `findHomeDashboardPriorityAccounts` are heavy aggregates with `LEFT JOIN LoanRepaymentScheduleInstallment` + `GROUP BY` over many columns. They have supporting partial indexes from `V36` (`idx_installment_overdue_lookup`), and the priority query accepts `Pageable`. Worth checking the actual EXPLAIN under load (Discuss).
- `LoanApplicationRepository#countGroupByStatus` and `summarizeApplicationsByLsp` do full-table GROUP BY. With `(status, created_at DESC)` and `(lsp_id, created_at DESC)` from V48, the planner can choose index-only scans on small column sets, but on a cold buffer cache these can still be heavy. No `WHERE created_at >= cutoff` push-down is applied.

### 4.6 SQL injection / unsafe dynamic SQL

None found. All dynamic SQL paths use parameter binding; the only string concatenation is for fragment composition with fixed literals (column names, operators), never user input.

---

## 5. Indexing recommendations (do not apply yet)

| # | Object | Observation | Recommendation (discuss only) |
|---|---|---|---|
| I1 | `idx_loan_application_created_at` (V6 + V34 same name) | Single-column `(created_at DESC)` is fully covered by V48's `(lsp_id, created_at DESC)`, `(status, created_at DESC)`, etc. for most queries that filter then order. | Consider dropping if no remaining query orders by `created_at DESC` without any of the leading-column filters. **HITL — review query patterns first.** |
| I2 | `idx_loan_application_status`, `idx_loan_application_lsp_id`, `idx_loan_application_loan_product_id` (V34) | All redundant with V48's composite `(<col>, created_at DESC)` indexes when the query also orders by `created_at`. Solo-column lookups (e.g., `countByStatus`) can still use the composite via leading-column scan. | Discuss dropping the V34 single-column trio. **HITL.** |
| I3 | `idx_loan_account_disbursed_at` (V29) vs. partial copy in V34 | Names collide; V34's `IF NOT EXISTS` no-ops the partial version. | If `WHERE disbursed_at IS NOT NULL` queries dominate, drop the V29 full index and recreate as partial. **HITL.** |
| I4 | `idx_loan_account_lsp_disbursed_at` (V36) | Prefix of V48's `(lsp_id, disbursed_at DESC, created_at DESC)`. | Drop V36's index. **HITL.** |
| I5 | `idx_borrower_pan` (V41) | Redundant with `uk_borrower_pan` UNIQUE (V43). | Drop the non-unique index. **AFK** (mechanical) but coordinate with deploy. |
| I6 | `loan_account_loan_application_id` (V34) | Duplicates the implicit index on `UNIQUE loan_application_id` from V17. | Drop. **AFK.** |
| I7 | `app_user.email`, `app_user.username`, `borrower.pan` ignore-case queries | Repo methods use `IgnoreCase` → `UPPER(...)=UPPER(?)`. Existing indexes are on raw values. | Either change callers to use raw equality (since values are normalised to upper on write), or add functional indexes on `UPPER(...)`. **HITL.** |
| I8 | `loan_application_assignment_event` (deprecated, V53) | Index still maintained on every write to the now-dead table. | Confirm zero writes in production, then consider dropping the index (keep table). **HITL.** |
| I9 | `ops_alert` | No index on `subject_id` or `(type, subject_id)` despite `existsByTypeAndSubjectIdAndStatus`. | If existence checks are hot, add `(type, subject_id, status)`. **HITL.** |
| I10 | `refresh_token` | `(username)` index is non-unique. Lookups by `token_hash` use the unique already. | Confirm `(username)` queries are required (logout-all flow). **HITL.** |

Net of the above, the schema is **slightly over-indexed**, not under-indexed.

---

## 6. Migration review (no edits)

- **Versioning:** linear `V1..V60` with `V43` filename gap noted (file is `V43__global_borrowers_with_lsp_access.sql`; sequential order intact). All migrations are forward-only; no `U`/undo migrations defined — typical for Flyway-OSS.
- **Idempotency:** mixed. Newer migrations consistently use `CREATE INDEX IF NOT EXISTS`, `ADD COLUMN IF NOT EXISTS`, `DROP POLICY IF EXISTS`. Older ones (V1–V32) do not, which is fine for forward-only but means partial re-run requires manual surgery.
- **Data backfills:** V32 (status remap), V41 (borrower clone-per-LSP for tenancy), V43 (canonical-borrower merge by PAN + per-LSP access rows), V50 (checklist status collapse), V51 (status rename `PAYMENT_REINITIATION → DISBURSEMENT_RETRY`), V58 (outbox `loan_application_id` backfill). All are deterministic and re-runnable on fresh data; on existing data they assume the prior state. **Production safety:** these are non-trivial DML on the main `loan_application`/`borrower` tables. Long-running on large data sets — should be wrapped in a maintenance window and tested on a staging copy. **HITL.**
- **`V41` creates roles & grants** using `${tenant_app_role}` placeholder substitution. This couples Flyway to environment-specific role names. Ensure deploy pipelines pass the right placeholder values consistently; otherwise migrations fail mid-way. **HITL.**
- **Rollback story:** none formal. Re-running on a fresh DB works. Rolling forward to undo a column requires a hand-rolled migration. For the deprecated assignment columns (V53), the comment-only deprecation is the only "undo".
- **Risk on `V43`:** the borrower clone-then-merge dance assumes no concurrent writes during the migration. **HITL.**
- **`V44__ops_alerts.sql`** numbered out of order vs `V43__global_borrowers_with_lsp_access.sql` — both present in the directory; Flyway orders strictly by numeric `V<n>__`. The filename ordering oddity is cosmetic.
- **Re-runnability on staging:** repeated UPDATEs in V32/V51 are idempotent (WHERE-clauses bound to old statuses). V43 / V41 / V58 backfills are not idempotent — re-running would re-clone or re-key data.

---

## 7. Redundant / unnecessary tables or fields

| Item | File / Migration | Observation |
|---|---|---|
| `loan_application.assigned_to_username`, `assigned_by_username`, `assigned_at` | V11 / V53 (deprecated) | Code marked `@Deprecated` in `LoanApplication.java:67-80`. No write path. Kept for forensic continuity. **Discussion: keep, or migrate to a side table to slim the hot row?** |
| `loan_application_assignment_event` | V11 / V53 (deprecated) | Same. Plus its index `idx_loan_application_assignment_event_application_created_at` still maintained. |
| `idx_borrower_pan` (V41) | V41 vs V43 | Non-unique, superseded by `uk_borrower_pan` UNIQUE in V43. |
| `idx_loan_account_loan_application_id` (V34) | V34 vs V17 | Redundant with `UNIQUE loan_application_id` implicit index. |
| V34's single-column `(status)`, `(lsp_id)`, `(loan_product_id)` on loan_application | V34 vs V48 | Composite indexes from V48 are strict supersets for any ORDER-BY-created_at query. |
| `idx_loan_account_lsp_disbursed_at` (V36) | V36 vs V48 | Prefix of V48 composite. |
| `webhook_event_delivery_attempt.request_event_type = 'UNKNOWN'` default (V27) | V27 | Sentinel-only for the V27 backfill. New code paths should always set a real value — confirm before considering default removal. |
| `report_request.report_content TEXT` | V30 | Storing report bodies in-row. Discuss S3/MinIO offload + content-pointer column. **HITL.** |
| `lsp.webhook_event_types VARCHAR(500) NOT NULL DEFAULT ''` | V23 | Delimited list. Acceptable for a small finite set, but reads can't index-scan a subscription type. |

---

## 8. Data integrity & security findings

### 8.1 NOT NULL / UNIQUE / FK / CHECK

- **No `CHECK` constraints** anywhere in the schema. Every business rule (amount > 0, tenure > 0, status ∈ enum set, PAN format, IFSC format, Aadhaar length, monetary `paid + outstanding = total`) is enforced only in app code.
- **Missing FK** on `webhook_event_outbox.loan_application_id` (V58).
- **Polymorphic FK** on `ops_alert.subject_id` — no constraint by design.
- **`borrower.aadhar_number VARCHAR(16)`** — allows 16 chars; Aadhaar is 12 digits. No regex enforcement.
- **`borrower.pan VARCHAR(10)`** — correct length but no regex.
- **`ifsc_code VARCHAR(32)`** — IFSC is 11 chars; field is oversized.

### 8.2 PII / financial data exposure

- **Unencrypted PII columns** on `borrower`: `pan`, `aadhar_number`, `mobile`, `email`, `date_of_birth`, `address_line_1/2`, `address_zip_code`, `bank_account_number`, `ifsc_code`, `account_holder_name`. **P0 discussion.**
- **`lsp.webhook_signing_secret VARCHAR(255)`** stored in plaintext (V23). HMAC key for webhook signing — must be readable to sign, so encryption requires key-vault integration. **HITL.**
- **`loan_disbursement_request_log.request_payload_json` / `response_payload_json`** — full provider payloads, likely contain bank account & beneficiary PII. **HITL.**
- **`loan_application_intake_audit.payload_json`** can capture intake payloads including PII. Stored unbounded (well, `VARCHAR(4000)`).
- **`webhook_event_outbox.payload_json`** ships PII to LSPs; stored in DB until purge.
- **`app_user.password_hash`** — BCrypt/Argon2 expected (need to verify in `SecurityConfig`); column type fine.
- **`api_client.secret_hash`** — hashed, good. `previous_secret_hash` also hashed, good.
- **`refresh_token.token_hash VARCHAR(64) UNIQUE`** — hashed (likely SHA-256), good.
- **PII reveal audit (V42)** captures who saw what — strong control.

### 8.3 RLS coverage

`V41`/`V45` enable RLS on the tenant-visible LSP surface. Verified-by-policy tables: `borrower`, `loan_application`, `loan_account`, `loan_application_document_checklist`, `loan_application_intake_audit`, `loan_application_status_transition`, `loan_application_assignment_event`, `loan_application_audit_event`, `loan_application_document_access_audit`, `loan_disbursement_request_log`, `loan_repayment_schedule_installment`, `loan_payment_transaction`, `loan_foreclosure_quote`, `webhook_event_outbox`, `app_user`, `api_client`, `loan_product_lsp_mapping`, `lsp_api_idempotency_record`, `loan_application_pii_reveal_audit`, `borrower_lsp_access`.

**Not RLS-enabled** (intentional): `ops_alert`, `alert_rule`, `loan_product`, `loan_product_audit_event`, `app_role`, `app_permission`, `app_user_audit_event`, `api_client_audit_event`, `api_client_ip_allowlist`, `lsp_ip_allowlist`, `refresh_token`, `report_request`, `lsp`. Some of these (e.g., `report_request`) gate tenancy through the application layer and the optional `lsp_id` filter. **Discussion: should `report_request` carry RLS too, given it can contain CSV of cross-LSP data?**

### 8.4 Audit / history / state-transition design

- **Three independent audit streams** for the loan application (`intake_audit`, `status_transition`, `audit_event`) plus document-access and PII-reveal — overlapping by design. `AuditExplorerRepository` unifies them via UNION ALL. Good.
- **Optimistic locking** present on `loan_application`, `loan_account`, `report_request`, `webhook_event_outbox` via `entity_version BIGINT` (V37).
- **No state-transition CHECK constraints**: legal status transitions are enforced only in `LoanApplicationLifecycleService` / equivalents. The `loan_application_status_transition` table records movement after the fact.
- **Append-only audit tables** — none have UPDATE/DELETE paths in repos audited.

---

## 9. Coverage of business surfaces

| Surface | Backing tables | State |
|---|---|---|
| Users / admins | `app_user`, `app_role`, `app_permission`, `app_user_role`, `app_role_permission`, `app_user_audit_event`, `refresh_token` | Covered. RLS by `lsp_id` for tenant users; `lsp_id IS NULL` for system admins. |
| LSPs | `lsp` | Covered. Webhook subscription fields embedded in `lsp` — consider extracting to its own table if multi-endpoint support is planned. |
| LSP API credentials | `api_client`, `api_client_ip_allowlist`, `api_client_audit_event`, `lsp_ip_allowlist`, `lsp_api_idempotency_record` | Covered. Secret rotation (current + previous + valid-until) supported. |
| Loan products | `loan_product`, `loan_product_lsp_mapping`, `loan_product_audit_event` | Covered. |
| Loans (apps + accounts) | `loan_application`, `loan_account` + all surrounding audit/state tables | Covered. Optimistic locking on both. |
| Documents | `loan_application_document_checklist`, `loan_application_document_access_audit` | Covered. Storage abstraction with `lms_managed_content`, `storage_key`, `file_checksum`, `file_size_bytes`. |
| Disbursement | `loan_disbursement_request_log` + `loan_account.disbursed_at`, `loan_account.status` (`DISBURSEMENT_REQUESTED`, `DISBURSED`, etc.) | Covered. Idempotency via `provider_request_id`. |
| Repayment | `loan_repayment_schedule_installment`, `loan_payment_transaction` (+ `loan_payment_allocation` data via `repayment_installment_id`), `loan_foreclosure_quote` | Covered. PAR-30 indexed. |
| MIS / reporting | `report_request` (+ report indexes V38) | Covered. Worker-based async generation with `FOR UPDATE SKIP LOCKED`. |
| Audit logs | Eight audit tables + unified explorer | Covered well. |
| State changes | `loan_application_status_transition`, `loan_application_audit_event`, `loan_account.status`, `webhook_event_outbox` (status), `report_request` (status) | Covered. |
| Ops alerts | `ops_alert`, `alert_rule` | Covered. Polymorphic subject — discussion item. |

---

## 10. Priority fix list

> Severity legend: **P0** = production-blocking / data-loss / compliance / security. **P1** = significant correctness or performance risk. **P2** = clean-up worth doing in the next maintenance window. **P3** = nice-to-have.

| ID | Priority | AFK / HITL | Item |
|---|---|---|---|
| F-01 | P0 | HITL | **PII at rest unencrypted** on `borrower` columns (`pan`, `aadhar_number`, `mobile`, `email`, `dob`, `bank_account_number`, `ifsc_code`, etc.) and on `lsp.webhook_signing_secret`, `loan_disbursement_request_log.{request,response}_payload_json`. Need a column-level encryption / vault strategy. |
| F-02 | P1 | HITL | No `CHECK` constraints for status enums, monetary `>= 0`, tenure `> 0`, `min_principal <= max_principal`, PAN/Aadhaar/IFSC regex. Application-only enforcement. |
| F-03 | P1 | HITL | **Missing FK** on `webhook_event_outbox.loan_application_id` (V58) — orphan risk. |
| F-04 | P1 | HITL | `Borrower.visibleLspIds @ElementCollection(EAGER)` N+1 risk for any list-of-borrowers path. |
| F-05 | P1 | HITL | `LoanAccount.loanApplication @OneToOne(EAGER)` forces eager join on every load. |
| F-06 | P1 | HITL | `OpsAlertRepository.findAllByOrderByCreatedAtDesc()` is unbounded; alerts table grows monotonically. Needs pagination. |
| F-07 | P1 | HITL | `report_request.report_content TEXT` stores full report bodies in-row. Move to object storage with content-pointer column once volume grows. |
| F-08 | P2 | AFK | Index pruning: `idx_loan_application_status`, `_lsp_id`, `_loan_product_id`, `_created_at` (V6+V34) superseded by V48 composites; `idx_loan_account_lsp_disbursed_at` (V36) prefix of V48; `idx_borrower_pan` (V41) duplicates `uk_borrower_pan` (V43); `loan_account_loan_application_id` (V34) duplicates the unique constraint's implicit index. |
| F-09 | P2 | AFK | `loan_application_intake_audit.payload_json VARCHAR(4000)` should be `TEXT` to avoid truncation. |
| F-10 | P2 | HITL | `*_json TEXT` columns should be `jsonb` if any future query needs path/index access (`webhook_event_outbox.payload_json`, `loan_disbursement_request_log.*_payload_json`, `loan_application_status_transition.rejection_reason_json`, `ops_alert.context_json`, `app_user_audit_event.*`, `api_client_audit_event.details_json`). |
| F-11 | P2 | HITL | `findByUsernameIgnoreCase` / `findByEmailIgnoreCase` / `findByPanIgnoreCase` generate `UPPER(col)=UPPER(?)` and bypass the unique B-tree. Either drop `IgnoreCase` (data is already normalised on write) or add functional indexes. |
| F-12 | P2 | HITL | Derived-delete N+1: `deleteByLoanProduct_Id`, `deleteByLoanAccount_Id`. Wrap with `@Modifying @Query` bulk delete. |
| F-13 | P2 | HITL | Drop `idx_loan_application_assignment_event_application_created_at` once dead-write status is confirmed (V53 deprecation). |
| F-14 | P2 | HITL | Re-check V34's intended partial `idx_loan_account_disbursed_at WHERE disbursed_at IS NOT NULL` — `IF NOT EXISTS` silently skipped over the V29 non-partial index. |
| F-15 | P3 | HITL | `borrower.aadhar_number VARCHAR(16)` over-sized (Aadhaar is 12 digits). `ifsc_code VARCHAR(32)` over-sized (11 chars). |
| F-16 | P3 | HITL | `webhook_event_delivery_attempt` sentinel defaults (`'UNKNOWN'`, `'0'`, `'UNSIGNED'`) — verify no live writes still rely on them. |
| F-17 | P3 | HITL | `report_request` is not RLS-enabled; relies on `lsp_id` filter in app code. Consider adding RLS for defence-in-depth. |
| F-18 | P3 | HITL | `lsp.webhook_endpoint_url` / `webhook_event_types` embedded in `lsp` — extract to `lsp_webhook_subscription` if multi-endpoint planned. |
| F-19 | P3 | HITL | `refresh_token.username` denormalised vs FK to `app_user.id`. Acceptable today; revisit if user rename is introduced. |
| F-20 | P3 | HITL | `loan_repayment_schedule_installment` lacks DB CHECK for `paid_amount = paid_principal + paid_interest` and `paid_amount + outstanding_amount = installment_amount`. |
| F-21 | P3 | HITL | `loan_application_document_access_audit.document_types VARCHAR(500)` stores a delimited list. Acceptable since query shape is "audits for this app", but normalising to a child table would enable analytics. |
| F-22 | P3 | HITL | Production-safety of V41/V43 backfills (clone, then merge) for large existing data — re-confirm staging timings before any re-run. |

---

## 11. Top 5 to discuss first

1. **F-01 PII encryption at rest** — covers borrower PII columns, LSP webhook signing secret, and disbursement payload JSON. Drives a multi-quarter workstream (KMS/Vault integration, transparent encryption layer or column-level wrapping, key-rotation story). Must precede F-10 (jsonb migration) because jsonb predicates need to know whether the column is wrapped.
2. **F-08 Index pruning** — pure cleanup, fully `AFK` once approved, immediate disk + write-cost win. Concrete list above. Smallest-surface change in the list.
3. **F-04 + F-05 EAGER associations** — `Borrower.visibleLspIds` and `LoanAccount.loanApplication`. Sometimes critical for hot list paths. Needs benchmarking against current behaviour before changing fetch types (could regress correctness in callers that assume initialised state).
4. **F-02 CHECK constraints** — choose one tier to add (status enums + monetary non-negativity is the highest ROI). Affects every write path; need to confirm all callers are clean before flipping CHECKs on.
5. **F-07 `report_request.report_content` offload** — drives near-future scaling. Architectural choice (S3/MinIO vs separate large-object table vs pg_largeobject). Touches the report worker, download endpoint, retention policy.

---

## 12. Safe phased implementation plan (proposal — not yet executed)

> Each phase is gated on explicit sign-off after the corresponding tracker rows move from `Pending Discussion` to `Approved`. No code or migration ships until then.

**Phase 0 — Discussion & decisions** (this document is the artefact).

**Phase 1 — Cleanup, AFK-safe**
- F-08 index pruning, in a single migration `V61__prune_redundant_indexes.sql`. Use `DROP INDEX CONCURRENTLY IF EXISTS` to avoid table locks. Verify EXPLAIN before/after on production-shape data.
- F-09 widen `loan_application_intake_audit.payload_json` to `TEXT`.
- F-16 audit `webhook_event_delivery_attempt` writers; if all writers always populate the four columns, drop the sentinel defaults.

**Phase 2 — Domain integrity, HITL**
- F-02 CHECK constraints, in two sub-phases:
  - 2a: monetary `>= 0`, tenure `> 0`, `min_principal <= max_principal`, `paid + outstanding = total` (F-20).
  - 2b: PAN, Aadhaar, IFSC regex; only after F-01 decision (encryption may store ciphertext that no longer satisfies a regex).
- F-03 add FK on `webhook_event_outbox.loan_application_id` after a one-shot orphan scan.
- F-15 tighten column sizes after CHECK migration.

**Phase 3 — Fetch & query patterns, HITL**
- F-04 / F-05 review every caller of `Borrower.visibleLspIds` and `LoanAccount#getLoanApplication`. Switch to `@ElementCollection(LAZY)` / `@OneToOne(LAZY)` with bytecode enhancement only after callers are audited.
- F-11 normalise repos to raw-equality where data is upper-cased on write.
- F-12 convert derived-deletes to bulk `@Modifying` queries.
- F-06 paginate `OpsAlertRepository.findAllByOrderByCreatedAtDesc`.

**Phase 4 — Storage & encryption, HITL (longest)**
- F-01 column-level encryption rollout (borrower PII first, then disbursement payloads, then webhook signing secret).
- F-07 offload `report_request.report_content` to object storage.
- F-10 `jsonb` migration for the JSON columns that survive Phase 4.
- F-17 RLS on `report_request`.

**Phase 5 — Cosmetic / forensic**
- F-13 drop deprecated assignment indexes once write paths are confirmed dead.
- F-18 split `lsp` webhook config to a separate table if/when multi-endpoint lands.
- F-19 revisit `refresh_token.username` if user-rename is added.

Each phase is independently revertable. Every migration in Phases 1–3 uses `IF EXISTS` / `IF NOT EXISTS` and `CONCURRENTLY` where Postgres supports it, so the rollback story is "next migration drops/recreates" rather than "downtime".

---

## 13. Out-of-scope notes

- Connection pooling, statement timeouts, `pg_stat_statements` analysis, and runtime tuning are not in this review.
- No production EXPLAIN ANALYZE evidence is included — every "may be slow" item should be backed by EXPLAIN on production-shape data before changes ship.
- Backup / PITR / replication topology not assessed.

