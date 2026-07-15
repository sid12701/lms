# LMS Platform — End-to-End Database Audit

**Date:** 2026-07-03 · **Revision 2:** 2026-07-05 · **Status refresh:** 2026-07-15 (F-S2 / B1 deferred — Spec S5)
**Scope:** Full database layer — schema (V1–V104 migrations), JPA domain model, repositories, query/access patterns, tenant isolation, backend data flow and API shapes, retention/operations — assessed against the scale targets in `docs/scalability-audit-report-2026-06-14.md` (100K loans/day, ~1M+ loans/yr, 6–12M repayments/yr, 50–150M audit rows/yr, 10+ LSP tenants).
**Method:** Read-only audit. Every migration file was read; entities, repositories, and money-path services were read directly; tenant isolation, query patterns, and DTO/API mapping were traced through actual code paths. No schema or code changes were made.

### Document alignment (revision 2)

This revision closes the gap between (a) the original audit brief, (b) the three subagent reports from this chat, and (c) the codebase as of **2026-07-05**, with **remediation status updated 2026-07-06** (see finding status register and `docs/db-audit-remediation-spec-2026-07-05.md`). Added: V101–V104 schema delta, a **finding status register**, and **Appendices B–E** (complete table catalog, enum catalog, validation matrix, OpenAPI/infra notes). Per-table coverage is in Appendix B; domain findings in §3 remain the prioritized narrative.

**2026-07-15:** F-S2 beneficiary snapshot (B1 / production-report Spec S5) is **deferred** for the current implementation pass — see `docs/deferred-implementation.md`. Finding remains accurate in code; not scheduled until real-money rails or operating assumptions change.

---

## 1. Executive summary (non-technical)

The database foundation of this platform is **genuinely good for a system at this stage**: it has a clean relational core, strong referential integrity, money-math CHECK constraints at the database level, a well-designed multi-tenant row-level-security (RLS) model that has an integration test proving cross-tenant reads fail, and a disciplined migration history that has repeatedly cleaned up its own earlier mistakes (index pruning, JSON column typing, width tightening).

However, the platform is **not ready for the stated production scale**, and the risks fall into four buckets:

1. **Money correctness (highest risk).** Two payments arriving at the same moment for the same EMI can silently corrupt the loan book (no lock on the installment row). A disbursement provider call happens *inside* a database transaction, so a crash at the wrong moment with a real bank could double-pay. LSP/admin API idempotency was **fixed** to claim-before-execute (pending row first), but payment allocation races and disbursement-in-transaction remain open. There is no database-level uniqueness on bank/provider transaction references, so double-disbursement cannot be blocked by the database as a last line of defence.
2. **Unbounded growth.** Idempotency records now have a scheduled purge (`IdempotencyRecordRetentionWorker`, 90-day default), but audit tables (50–150M rows/year projected), refresh tokens, webhook logs, and most other append-only streams still have no retention or partitioning. `RefreshTokenRepository.deleteByExpiresAtBefore` remains uncalled.
3. **Read paths that scan the whole book.** The ops dashboard, the alert engine (every 5 minutes, on every server), and MIS reports all read the entire portfolio per request. Measured p95 for the dashboard was already 32 seconds at tiny data volumes. At 500K+ accounts these become platform outages.
4. **PII handling.** Borrower PAN, Aadhaar and bank account numbers are stored in plaintext, and full copies also live inside JSON audit/idempotency blobs. Several APIs still return full PAN; bank-account reveal on the LSP partner surface is now audited via `borrower_pii_reveal_audit` (V102), but the older `loan_application_pii_reveal_audit` (V42) remains dead infrastructure, and most other unmasked-PII paths still have no reveal audit.

**Tenant isolation — the thing that usually sinks multi-tenant lending platforms — is the strongest part of this system.** Data leakage risk between LSP partners is low. The residual tenant risks are performance-related (shared pools) and a few defence-in-depth gaps, not data exposure.

The remediation is largely incremental: row locks, a global error handler, extend retention beyond idempotency, a nightly KPI snapshot table, and partitioning of append-only tables. No re-architecture of the schema is required.

### Finding status register (revision 3 — 2026-07-06)

**Authoritative remediation tracker:** `docs/db-audit-remediation-spec-2026-07-05.md` (implementation progress table). Summary below.

| ID | Original severity | Status (2026-07-06) | Notes |
|---|---|---|---|
| F-S7 | P0 | **Fixed** | A1 — V105, `@Version`, pessimistic lock, concurrency IT |
| F-S8 | P1 | **Fixed** | A2 — payment claim + allocation in one transaction |
| F-D3 | P0 | **Fixed** | A3 — `DataIntegrityViolationException` handler + `@Size` caps |
| F-S14 | P2 | **Fixed** | A4 — V106 `TIMESTAMPTZ` on four audit columns |
| F-Q11 | P2 | **Fixed** | A5 — dead unbounded repo methods removed |
| F-S17 | P2 | **Fixed** | A7 — LAZY fetch on money-graph associations |
| F-Q4 | P1 | **Fixed** | A8 — keyset-batched MIS export |
| F-N1 | P2 | **Fixed** | A9 — product version bump regression test |
| F-Q6 | P0 | **Fixed** | Claim-before-execute for LSP + admin idempotency |
| F-Q7 | P1 | **Fixed** | B5 — audit explorer guardrails (V107, cursor, 90d cap) |
| F-Q9 | P1 | **Fixed** | B7 — auth principal cache + deduped lookups |
| F-Q10 | P1 | **Fixed** | B8 — list cap 200 + borrowers FE pagination |
| F-S15 | P1 | **Fixed** | B12 — schema-diff tooling, CI, prod reconciled to V109 |
| F-Q1 / F-Q2 | P0 | **Partial** | B2 — KPI snapshots + set-based alerts; FE `dataAsOf` stamp not rendered |
| F-Q8 | P0 | **Partial** | B4 — idempotency retention worker only; refresh-token/webhook purges + partitions open |
| F-S16 | P2 | **Partial** | B10 — prod password default removed; startup assertion + rotation runbook open |
| F-S9 / F-Q5 | P0 | **Open** | C1 — deferred to ICICI integration |
| F-S10 | P1 | **Open** | C2 — deferred to ICICI/NACH |
| F-D1 / F-D2 / F-D5 | P0→P1 | **Deferred** | C3 — partner consultation |
| F-T1 / F-T2 | P2 | **Open** | B6 — additive RLS (V110); unblocked post-B12 |
| F-S2 | P1 | **Deferred (2026-07-15)** | B1 / Spec S5 — see `docs/deferred-implementation.md` |
| F-S4 | P2 | **Open** | B9 — status history consolidation |
| F-S1 / F-S12 | P1 | **Open** | B3 — AES-GCM encryption |
| F-S11 | P2 | **Open** | A6 — webhook subscription child table |
| F-T4 | P3 | **Open** | A10 — cross-tenant IDOR test suite |
| #201 | P1 | **Open** | B11 — pool sizing + statement timeouts |

---

## 2. Current schema assessment

### 2.1 Inventory

~49 tables across eight domains, all keyed by `UUID` primary keys (app-generated via `UUID.randomUUID()`, DB default `gen_random_uuid()` for a few):

| Domain | Tables |
|---|---|
| Identity & access | `app_user`, `app_role`, `app_permission`, `app_user_role`, `app_role_permission`, `refresh_token`, `auth_event_audit`, `app_user_audit_event` |
| Tenancy & partners | `lsp`, `api_client`, `api_client_audit_event`, `lsp_api_ip_allowlist`, `lsp_ui_ip_allowlist`, `lsp_audit_event` |
| Product catalog | `loan_product`, `loan_product_version`, `loan_product_lsp_mapping`, `loan_product_audit_event` |
| Borrowers | `borrower`, `borrower_lsp_access`, `borrower_bank_details_update_audit`, `borrower_pii_reveal_audit` |
| Origination | `loan_application`, `loan_application_status_transition`, `loan_application_audit_event`, `loan_application_intake_audit`, `loan_application_assignment_event` (deprecated), `loan_application_document_checklist`, `loan_application_document_access_audit`, `loan_application_document_access_audit_type`, `loan_application_pii_reveal_audit` (legacy, unused) |
| Servicing & money | `loan_account`, `loan_repayment_schedule_installment`, `loan_payment_transaction`, `loan_disbursement_request_log`, `loan_disbursement_bank_mismatch_log`, `disbursement_outcome_audit`, `loan_foreclosure_quote`, `loan_delinquency_state` |
| Integration & ops | `webhook_event_outbox`, `webhook_event_delivery_attempt`, `webhook_outbox_redrive_audit`, `lsp_api_idempotency_record`, `admin_api_idempotency_record`, `report_request`, `report_access_audit`, `ops_alert`, `alert_rule` |

Full per-table notes: **Appendix B**.

### 2.2 What is done well (do not change)

- **Money types are correct and consistent**: `NUMERIC(19,2)` everywhere for currency, `NUMERIC(5,2)` for rates, `BigDecimal` in Java with explicit `setScale(2)`. No floats anywhere in money paths.
- **DB-level money invariants** (`V65__check_constraints_data_integrity.sql`): non-negativity on all amounts plus two genuinely valuable ones — `chk_installment_paid_sum` (`paid_amount = paid_principal + paid_interest`) and `chk_installment_total` (`paid_amount + outstanding_amount = installment_amount`), and `chk_loan_payment_allocation_total` on payments. These make silent book corruption from bad SQL much harder.
- **Referential integrity is near-complete.** Every child table FKs to its parent; `ON DELETE RESTRICT` on the loan graph; a pre-flight orphan check before adding the webhook-outbox FK (`V66`). `V73` replaced free-text `refresh_token.username` with proper XOR-constrained FKs to `app_user`/`api_client` — an unusually rigorous fix.
- **JSON columns are real `jsonb` with object-type CHECKs** (`V72`), validated at write time via `JsonPayloads.requiredObject` and mapped through `@JdbcTypeCode(SqlTypes.JSON)`.
- **The migration history self-corrects**: `V61` pruned redundant indexes with a documented mapping, `V64` reconciled a silently no-opped partial index, `V62`/`V74` fixed column widths in both directions, `V67` canonicalised usernames to make unique indexes usable.
- **Idempotency keys are DB-enforced** (`uk_loan_payment_transaction_idempotency_key`, `uk_lsp_api_idempotency_scope`), with request fingerprints to detect key reuse with different payloads.
- **Optimistic locking** (`@Version`/`entity_version`) on the four mutable hot entities (`loan_application`, `loan_account`, `report_request`, `webhook_event_outbox`), with a recovery path for payment races.
- **Worker claims use `FOR UPDATE SKIP LOCKED`** for webhooks (`WebhookEventOutboxRepositoryImpl.claimIdsPostgres`, with lease expiry `claim_expires_at`) and reports (`ReportRequestRepositoryImpl.claimIds`).
- **Timestamps** are `TIMESTAMPTZ` + `Instant` almost everywhere, `created_at`/`updated_at` maintained by `@PrePersist`/`@PreUpdate` (four exceptions — see F-S2).
- **Normalization level is appropriate.** The schema is ~3NF where it matters, with sensible, deliberate denormalization: `loan_account.lsp_id`/`borrower_id`/`loan_product_id` duplicated from the application for direct filtering; `webhook_event_outbox.loan_application_id` added (`V58`) because `aggregate_id` was polymorphic. Neither over- nor under-normalized.
- **Soft-delete-free design with terminal statuses** (`INVALID`, `REJECTED`, `CLOSED`, `FORECLOSED`) instead of `deleted_at` flags is a good fit for a lending ledger — records are immutable history, not deletable rows.

### 2.3 Structural weaknesses (summary — details in §3)

- No partitioning and no retention anywhere, against 50–150M audit rows/yr.
- Plaintext PII at rest, duplicated into JSON blobs in four different tables.
- No DB uniqueness on provider disbursement references.
- One CSV-in-a-column anti-pattern (`lsp.webhook_event_types`) that has already caused a production-class bug (`V99`).
- Dual-write of status history into two near-identical audit tables.
- Migration history has diverged between environments (missing V63/V69/V91; `V85`/`V86` comments admit Supabase applied different scripts at V80).

---

## 3. Table-by-table and column-by-column findings

Severity scale: **P0** critical (money/outage/compliance), **P1** high, **P2** medium, **P3** low.

### 3.1 `borrower` + `borrower_lsp_access`

**F-S1 · P1 · PII stored in plaintext, and duplicated into JSON blobs**
- **Affected:** `borrower.pan` (UNIQUE), `aadhar_number`, `bank_account_number`, `date_of_birth`, `monthly_income`, `annual_income`; full copies inside `loan_application_intake_audit.payload_json`, `loan_disbursement_request_log.request_payload_json` (beneficiary account number), `lsp_api_idempotency_record.response_body` (serialized API responses), `ops_alert.context_json` (`BorrowerOnboardingService.serializeBorrowerConflictContext` writes full `incomingAadhar`/`existingAadhar`).
- **Why risky:** DPDP Act / RBI guidelines expect demonstrable protection of financial identifiers. A DB snapshot, replica, or backup leak exposes the entire PAN/Aadhaar/bank book. The JSON copies mean masking or crypto-shredding a borrower later is nearly impossible — the data is scattered across 4+ append-only tables.
- **Recommendation:** (a) Application-level AES-GCM encryption (or `pgcrypto`) for `aadhar_number` and `bank_account_number`, keeping `pan` searchable via a deterministic HMAC column (`pan_hash` UNIQUE) with the encrypted value alongside — PAN is the dedupe key, so it needs equality lookup, not plaintext. (b) Stop writing raw Aadhaar/bank values into `ops_alert.context_json` and intake audit payloads — mask at write time, not read time. (c) Purge/TTL `lsp_api_idempotency_record` (see F-Q8) so response-body PII is time-bounded.
- **Benefits:** Compliance posture, breach blast-radius reduction, enables data-subject erasure.
- **Migration:** Backfill encrypt in batches; dual-read during rollout; `V74`'s comment already anticipates this ("F-01 (PII encryption) may later replace these columns"). Blast radius: `Borrower` entity, `BorrowerProfile`, LSP/ops mappers, MIS CSV writer, `findByPan` (switch to hash lookup). Requires a key-management decision first (see §10).

**F-S2 · P2 · Global borrower: one LSP's write mutates every LSP's view**
- **Affected:** `borrower` (single row per PAN since `V43`), `Borrower.mergeLatestProfile` / `refreshProfile` (last-write-wins), `Borrower.updateBankDetails` called from `BorrowerBankDetailsService.updateBankDetailsForLsp`.
- **Why risky:** Not a leak (visibility is gated by `borrower_lsp_access` + RLS), but a data-integrity coupling: LSP B's onboarding overwrites contact/address/income data LSP A relies on; a bank-details PATCH by one partner changes the disbursement account for every partner's in-flight loans. `borrower_bank_details_update_audit` records the change but nothing notifies other LSPs.
- **Recommendation:** Keep the global-identity model (it powers the one-open-loan rule) but decide explicitly which fields are *canonical/shared* (PAN, name, DOB) vs *per-relationship* (bank details arguably belong per (borrower, lsp) or per loan application at disbursement time). Minimal change: snapshot bank details onto the `loan_application`/`loan_account` at approval so later borrower-record edits cannot silently redirect an approved loan's disbursement.
- **Migration:** Additive columns on `loan_account` (beneficiary snapshot); backfill from borrower; change `DisbursementPreflightValidator` to validate against the snapshot. Medium blast radius, high money-safety payoff.

**F-S3 · P3 · `@ElementCollection` for `visibleLspIds`**
- `Borrower.visibleLspIds` maps `borrower_lsp_access` as an element collection; every borrower load with the entity graph pulls the access rows, and Hibernate manages the collection rather than a first-class entity. **Update (2026-07-15):** Spec S19 Slice A added first-class `borrower_lsp_relationship` (metadata + dual-write); access collection remains RLS-authoritative until residual cutover. See `docs/implementation-log.md` / `docs/deferred-implementation.md`.

### 3.2 `loan_application` and its audit satellites

**F-S4 · P2 · Status history is dual-written to two near-identical tables**
- **Affected:** `loan_application_status_transition` (V9, + `reason_code` V15, + `rejection_reason_json` V52) and `loan_application_audit_event` (V16). `LoanApplicationStatusWriter.updateStatus` (lines 69–91) saves a transition row *and* an audit-event row with the same from/to/actor/note/reason on every transition.
- **Why risky:** ~2× write amplification and storage on one of the highest-volume streams (each of ~1M loans/yr makes 4–7 transitions), plus two sources of truth that can diverge (V32/V51/V76 had to backfill *both* tables every time the status vocabulary changed — three times already).
- **Recommendation:** Make `loan_application_audit_event` the single stream (it is a superset: `action` covers non-transition events like `PAYMENT_RECORDED`) and derive "transitions" as `action = 'STATUS_TRANSITION'`. Move `rejection_reason_json` there. Retire writes to `loan_application_status_transition` after migrating readers (`HomeDashboardService.computeAvgApprovalTatHours`, `AlertRuleEvaluationWorker`, servicing read paths, audit explorer APPLICATION stream).
- **Benefits:** Halves audit write volume for lifecycle events, removes divergence risk, simplifies future enum renames.
- **Migration:** Reader-by-reader cutover; keep the old table read-only for forensic continuity (same pattern as the retired assignment table). Low compatibility risk since both tables are internal.

**F-S5 · P3 · Deprecated assignment columns and table retained**
- `loan_application.assigned_to_username/assigned_by_username/assigned_at` and `loan_application_assignment_event` are dead per `V53` (deliberately retained). Fine; drop in a future cleanup window once forensic value expires. Same for `report_request.report_content` (nullified by `V68`, column still present).

**F-S6 · P2 · Free-text actor identity across all audit tables**
- **Affected:** `actor_username VARCHAR(255)` on ~15 tables; `assigned_*_username`; `closed_by_username`; `requested_by_username`.
- **Why risky:** No FK to `app_user`/`api_client`; renames or re-used usernames corrupt attribution; joins for "everything user X did" rely on string equality. `V73` fixed exactly this for `refresh_token` but the audit tables still use strings.
- **Recommendation:** Do **not** retro-fit FKs onto 50M+ append-only audit rows (audit rows should survive user deletion anyway). Instead: (a) enforce a canonical actor format at the single choke point (`Strings.normalizeActor`) — already done; (b) for *new* audit tables, follow the `auth_event_audit` pattern (`username` text **plus** nullable `user_id`/`api_client_id` FKs with `ON DELETE SET NULL`). Accept as-is for existing tables.

### 3.3 `loan_account`, `loan_repayment_schedule_installment`, `loan_payment_transaction`

**F-S7 · P0 · Installment rows have no concurrency protection (double-payment lost update)**
- **Affected:** `loan_repayment_schedule_installment` (no `@Version`, no row lock), `LoanServicingSupportService.resolveTargetInstallment` (plain `findById`, lines 153–175), `LoanRepaymentCommandService.createInstallmentPayment` (lines 188–257).
- **Evidence-based failure mode:** Two payments with *different* idempotency keys targeting the same EMI: both pass `resolveTargetInstallment` (status ≠ PAID) and `validateExactInstallmentAmount` against the same snapshot; both claim their payment rows (different keys — both succeed); both run `applyFullInstallmentPayment` and `save(installment)`. With no `@Version` on the installment, the second flush silently overwrites the first (lost update). Result: **two `RECEIVED` payment rows totalling 2× EMI, an installment showing 1× EMI paid**, and the V65 CHECK constraints cannot catch it because each write is individually consistent. The `ObjectOptimisticLockingFailureException` recovery in `recordPaymentTransactionWithRecovery` only fires for application/account version conflicts, not installments. The `synchronized(idempotencyKey.intern())` guard (line 144) is same-key and single-JVM only.
- **Impact at scale:** Settlement mornings (bulk NACH postings + partner retries) make same-EMI concurrent posts routine at 6–12M repayments/yr. Silent book overstatement, expensive reconciliation, partner disputes.
- **Recommendation:** `SELECT … FOR UPDATE` on the installment inside the payment transaction (add a `findByIdForUpdate` with `@Lock(PESSIMISTIC_WRITE)` to `LoanRepaymentScheduleInstallmentRepository` and use it in `resolveTargetInstallment`), plus add `@Version` to the entity as a backstop. Both are small changes.
- **Benefits:** Eliminates the highest-probability money-corruption path.
- **Migration:** `ALTER TABLE … ADD COLUMN entity_version BIGINT NOT NULL DEFAULT 0` (same pattern as `V37`). Validation: a concurrency test posting two different-key payments to one EMI must yield exactly one success and one 409.

**F-S8 · P1 · Payment row committed before allocation (orphan `RECEIVED` payments)**
- **Affected:** `IdempotencyClaimService.claimLoanPaymentRow` — `REQUIRES_NEW` `saveAndFlush` commits the `loan_payment_transaction` row *before* the outer transaction allocates it to the installment. If allocation or webhook enqueue fails, the outer tx rolls back but the payment row survives as `RECEIVED` with `unallocated_amount = amount`.
- **Impact:** Book shows received money not applied to any EMI; the LSP retry replays the stored row (fingerprint matches) and may believe posting succeeded.
- **Recommendation:** Claim and allocate in the **same** transaction; rely on the unique constraint + `DataIntegrityViolationException` catch within that one transaction (the constraint still provides cross-node dedupe; `REQUIRES_NEW` is not needed for correctness, only for keeping a failed insert from poisoning the outer persistence context — restructure so the claim happens first, then allocation, in one tx).
- **Migration:** Code-only. Validation: kill the process between claim and allocation in a test harness; assert no orphan `RECEIVED` rows.

**F-S9 · P1 (P0 at real-bank go-live) · No DB uniqueness on provider disbursement references**
- **Affected:** `loan_disbursement_request_log.provider_request_id VARCHAR(128) NOT NULL` (V19), `tran_ref_no VARCHAR(64)` (V98) — neither is unique; no repository lookup by either column exists.
- **Why risky:** The application's only double-disbursement guards are the status gate + `findByIdForUpdate` row lock. If the worker retries after a crash-after-provider-success (see F-Q5), nothing at the DB layer refuses a second money movement with the same `tran_ref_no` intent.
- **Recommendation:** Generate `tran_ref_no` deterministically per (loan_account, attempt) and add `UNIQUE (tran_ref_no)` (nullable-safe partial unique index `WHERE tran_ref_no IS NOT NULL`). This is the classic "intent row" uniqueness the June scalability audit's #204 calls for.
- **Migration:** Verify no historical duplicates first (mock adapter data may have them — clean or scope the index to new rows by date).

**F-S10 · P2 · No bounce/reversal model on payments**
- **Affected:** `LoanPaymentStatus` (RECEIVED-centric), no reversal columns, `LoanRepaymentScheduleInstallment.resetAllocation` exists but no command path uses it for bounces.
- **Why:** 5–15% NACH bounce rates on 6–12M payments/yr will overstate collections within the first month of real volume. Matches scalability finding F-MNY-08 (#222).
- **Recommendation:** Add `BOUNCED`/`REVERSED` statuses + `reversed_at`/`reversal_reference` columns and an allocation-rollback command that reverses installment paid amounts under the same installment lock as F-S7.

### 3.4 `lsp`, `api_client`, identity tables

**F-S11 · P2 · `lsp.webhook_event_types` is a CSV column**
- **Affected:** `lsp.webhook_event_types VARCHAR(500)` (V23), parsed by `Lsp.getWebhookEventTypes()` via `split(",")`, "repaired" by `V99` using `replace()` string surgery after subscriptions were bound to a phantom enum value that never matched.
- **Why risky:** The V99 incident is the proof: no DB-level validation of membership, no way to index or join, string-migration repairs are fragile, and the 500-char cap will silently truncate if the event vocabulary grows.
- **Recommendation:** Either a `lsp_webhook_subscription(lsp_id, event_type)` child table (PK on both, FK to lsp) or a `jsonb` array with a CHECK. Child table preferred — enables per-event-type queries the outbox enqueue already does in memory.
- **Migration:** Straightforward backfill from split CSV (V75 did exactly this pattern for document types); keep the column during a dual-read release, then drop.

**F-S12 · P2 · Webhook signing secret stored in plaintext**
- **Affected:** `lsp.webhook_signing_secret VARCHAR(255)`. Contrast: `api_client.secret_hash` is properly hashed, previous-secret rotation is modeled (V55), and the API never returns the webhook secret (`LspAdminController.toWebhookSubscriptionResponse` nulls it — good).
- **Why risky:** HMAC signing requires the recoverable secret, so hashing is impossible — but plaintext-at-rest means a DB leak lets an attacker forge webhook signatures to every partner.
- **Recommendation:** Encrypt at rest with the same app-level KMS/key decided for F-S1. Low urgency relative to borrower PII, same mechanism.

**F-S13 · P3 · Minor identity-table nits**
- `auth_event_audit.username TEXT` vs `VARCHAR(255)` everywhere else (cosmetic).
- `auth_event_audit.user_id` / `api_client_id` FKs (`ON DELETE SET NULL`) have no indexes — a user delete would seq-scan this table; user deletes effectively never happen, so accept, but add indexes if a purge/anonymize path is built.
- `alert_rule` has no `created_at`/`updated_at` and no version column — config table, accept.
- `ops_alert` dedupe queries (`existsByTypeAndSubjectIdAndStatus`, `existsByTypeAndCorrelationIdAndStatus` in `OpsAlertRepository`) have no supporting index (only `(status, created_at)` exists). Add `(type, subject_id, status)` when alert volume grows — alert storms are exactly when this table gets big and the dedupe check gets hot.

### 3.5 Cross-cutting schema findings

**F-S14 · P2 · Four audit tables use `TIMESTAMP` without time zone**
- **Affected:** `borrower_bank_details_update_audit.created_at`, `loan_disbursement_bank_mismatch_log.created_at` (both V78), `disbursement_outcome_audit.created_at` (V82), `webhook_outbox_redrive_audit.created_at` (V88). Every other table uses `TIMESTAMPTZ`.
- **Why risky:** Hibernate binds `Instant` as UTC so data is *currently* consistent, but any SQL comparing these columns to `timestamptz` values (audit explorer, retention jobs, cross-stream ordering) applies the server timezone conversion; a non-UTC server or a direct-SQL consumer will misread compliance timestamps.
- **Recommendation:** `ALTER TABLE … ALTER COLUMN created_at TYPE TIMESTAMPTZ USING created_at AT TIME ZONE 'UTC'`. Small tables today; near-zero risk if done before volume.

**F-S15 · P2 · Migration history has diverged between environments**
- **Evidence:** Version gaps V63, V69, V91 (never existed in repo); `V85` comment: "Supabase may already have these columns from an earlier V80 duplicate"; `V86` comment: "Supabase applied a different script at V80"; broad `IF NOT EXISTS` usage papering over drift.
- **Why risky:** The repo's migration chain is no longer a faithful record of what production ran. Future migrations that assume repo-state (e.g., an index that "should" exist) can fail or silently no-op in one environment only — exactly the V29/V34 partial-index bug (`V64`) mode, but now environment-dependent.
- **Recommendation:** One-time schema diff of production vs a fresh `flyway migrate` database (structure only: tables/columns/indexes/constraints); record the reconciliation as an ADR; then adopt a rule that repair scripts get real version numbers in-repo *before* being applied anywhere. Consider `flyway validate` in CI against a schema snapshot.

**F-S16 · P1 · Default tenant-role password ships in config**
- **Affected:** `application.yml` — `tenant_app_password: ${APP_TENANT_DATASOURCE_PASSWORD:lms_tenant_app_password}`; `V41` creates/alters the `lms_tenant_app` LOGIN role with that Flyway placeholder.
- **Why risky:** If the env var is unset in any deployed environment, the tenant DB role — which has read/write on all loan tables — has a publicly-known password.
- **Recommendation:** Remove the default (fail startup if unset), rotate the role password in all environments, and assert non-default at boot.

**F-S17 · P2 · Eager fetching is the default across the money graph**
- **Affected:** `LoanAccount.loanApplication` (`@OneToOne EAGER`), `LoanRepaymentScheduleInstallment.loanAccount` (EAGER), `LoanPaymentTransaction.loanAccount` + `.repaymentInstallment` (EAGER), `WebhookEventOutbox.lsp` (EAGER), `ReportRequest.lsp` (EAGER), `AppUser.lsp` + `.roles` (EAGER).
- **Why risky:** Loading any installment or payment drags account → application (+ its own lazy proxies) whether needed or not; batch reads multiply join width; and `@OneToOne` eager on `LoanAccount.loanApplication` cannot be made lazy without bytecode enhancement, so every account fetch is a join. This inflates the already-heavy dashboard/MIS scans.
- **Recommendation:** Flip the `@ManyToOne`s to `LAZY` (the codebase already uses explicit `@EntityGraph`/`join fetch` everywhere it truly needs the graph, so blast radius is mostly "find the two callers that relied on implicit eager"). Keep `AppUser.roles` eager (auth path needs it).

### 3.6 Post-audit schema additions (V101–V104, rev 2)

**F-N1 · P2 · `loan_product_version` (V104) — product terms frozen at origination**
- **Schema:** Immutable version rows per `loan_product`; `UNIQUE (loan_product_id, version_number)`; backfill creates v1 from current `loan_product` columns. `loan_application.loan_product_version_id` and `loan_account.loan_product_version_id` are NOT NULL with indexes.
- **Why it matters:** Correctly solves "product rate changed mid-flight" — servicing reads `application.getLoanProductVersion().getInterestRate()` in LSP responses. `loan_product` remains the mutable catalog; versions are the contractual snapshot.
- **Gaps:** `loan_product_version` has tenant `SELECT` grant but **no RLS** (same class as F-T1). No `@Version` on version rows (immutable by convention — OK). New product edits must create new version rows; verify admin UI always bumps version on rate change.
- **Benefits:** Auditability of which terms applied to each loan; enables future "product change" audit without rewriting history.

**F-N2 · P2 · `loan_delinquency_state` (V101) — DPD bucket cache, not a scan fix**
- **Schema:** One row per `loan_application_id` (UNIQUE FK CASCADE); `last_bucket`, `last_max_days_past_due`, `last_evaluated_at`.
- **Usage:** `AlertRuleEvaluationWorker.evaluateDpdBucketTransitions` reads/writes state to detect bucket *transitions* and suppress repeat alerts for unchanged buckets.
- **Gap (F-Q2 remains open):** Worker still loads **all** `UNDER_REPAYMENT` applications and runs `getLoanDelinquencySummary` per row (N+1). State table avoids duplicate alert noise but does **not** reduce read amplification. No RLS/grant in V101 — accessed from admin-scoped alert worker only today; add RLS if tenant threads ever query it.
- **Recommendation:** Keep state table; replace per-loan Java evaluation with set-based SQL or F-Q1 snapshot job as originally planned.

**F-N3 · P3 · `borrower_pii_reveal_audit` (V102) — closes one reveal path**
- See F-D1. Index `(borrower_id, created_at DESC)`. Admin-scoped writes. No retention policy yet.

**F-N4 · P3 · `admin_api_idempotency_record` (V103)**
- Mirrors LSP idempotency for internal admin mutating APIs. `UNIQUE (operation_key, idempotency_key)`; indexed `created_at`. Purged by `IdempotencyRecordRetentionWorker`. Response bodies are TEXT (may contain PII from admin responses) — bounded by 90d purge.

---

## 4. Query and indexing audit

### 4.1 Indexing — largely healthy

The index estate is unusually well-tended: composite `(filter, created_at DESC)` indexes matching real access paths (V48, V57, V59, V82–V89), a partial overdue-installment index for PAR calculations (`idx_installment_overdue_lookup`, V36), `pg_trgm` GIN indexes on `lower(col)` matching the `lower(...) LIKE '%…%'` search predicates (V48), redundancy pruning with documented supersession (V61), and worker-claim indexes (V25 dispatch index, V86 stale-claim partial index, V38). Missing-index findings are limited to F-S13 (`ops_alert` dedupe) and the F-Q7 REPORT_ACCESS regex below.

### 4.2 Query findings

**F-Q1 · P0 · Dashboard runs full-portfolio aggregates per page view**
- **Affected:** `LoanAccountRepository.findHomeDashboardAccountSnapshots` (GROUP BY over *every* account left-joined to *every* installment, returned as an unbounded in-memory list), `findHomeDashboardPriorityAccounts` (same join, sorted), `countGroupByStatus`; assembled in `HomeDashboardService.getSummary` which then computes LSP breakdowns in Java over the full snapshot list. Measured p95 already **32.3s** (scalability report F-RPT-01).
- **Plus an N+1:** `computeAvgApprovalTatHours` loads all approval transitions in a 30-day window (unbounded — at target scale ~3M rows) and then issues **one query per approval** (`findByLoanApplication_IdAndToStatusOrderByCreatedAtAsc`).
- **Recommendation:** Nightly (or 15-min) KPI snapshot tables — `portfolio_kpi_snapshot` (global + per-LSP disbursed/outstanding/DPD buckets) and a windowed-aggregate TAT query (single `GROUP BY` with a self-join or lateral, or computed in the snapshot job). Serve the dashboard exclusively from snapshots with a "data as of" stamp. This matches tracked issues #211/#212 — implement them; nothing cleverer is needed.
- **Migration:** Additive tables + one worker; dashboard API shape unchanged except a timestamp field.

**F-Q2 · P0 · Alert engine scans the portfolio every 5 minutes on every pod**
- **Affected:** `AlertRuleEvaluationWorker`: `evaluateDpdBucketTransitions` → `loanApplicationRepository.findByStatus(UNDER_REPAYMENT)` (unbounded, EntityGraph-hydrated) then per-loan `getLoanDelinquencySummary` → account fetch + full installment list (**O(N) × 2–3 queries**); `evaluateStuckDisbursement` and `evaluateStaleIntake` similar row-by-row follow-ups; `@Scheduled` on all instances with dedupe only at insert.
- **Impact:** At 500K under-repayment loans this is ~1–1.5M queries per tick per pod — sustained read amplification that competes with API traffic; effectively an internal DDoS at scale.
- **Recommendation:** Replace per-loan Java evaluation with set-based SQL (one query per rule returning violating IDs — the overdue partial index makes the DPD rule a single indexed scan), bound each rule's batch, and gate the scheduler to a single worker instance (leader election or the D1 worker-pod topology). Piggyback DPD buckets on the F-Q1 snapshot job.

**F-Q3 · P1 · Disbursement worker: unbounded scan, no claim, count-based retry budget**
- **Affected:** `LoanDisbursementWorkerService.processStatus` → `findByStatus` (unbounded, hydrates borrower/lsp/product for every pending row every 30s); no `SKIP LOCKED` claim — multiple pods fetch the same set and rely on the late `findByIdForUpdate` inside `initiateDisbursement` to serialize; retry budget via `countByLoanAccount_Id` (racy across pods).
- **Note:** The June audit's mega-transaction finding is **fixed** — `LoanDisbursementWorkerProcessor.processApplication` is a separate bean with per-application `@Transactional`. Remaining gaps are the claim and boundedness.
- **Recommendation:** Claim N=25 via `FOR UPDATE SKIP LOCKED` on a status+created_at index (same pattern already proven in the webhook/report repos), and make the retry budget an atomic counter on the claim/intent row (#203).

**F-Q4 · P1 · MIS: unbounded export hydration + correlated EXISTS summary**
- **Affected:** `PortfolioMisReadRepository.findAccountsForExport` (full entity hydration with 4 join-fetches, no limit, into a heap CSV in `AdminReportingService`); `summarize` uses a correlated `EXISTS` sub-select per account for PAR-30. `findAccountsPage` always runs a COUNT per page.
- **Recommendation:** Stream the export (scrollable results/keyset batches, write CSV incrementally to R2 — issue #227); replace the correlated EXISTS with a join against the precomputed delinquency snapshot from F-Q1.

**F-Q5 · P0 · Provider call inside the disbursement transaction**
- **Affected:** `LoanDisbursementCommandService.initiateDisbursement` — `loanDisbursementAdapter.requestDisbursement(command)` executes inside `@Transactional`, before `markDisbursementRequested`/request-log save commit; `pollPendingDisbursement` likewise holds a tx across `checkStatus`.
- **Why risky:** Crash between provider-accept and commit leaves no persisted record that money moved; the retry pays again. Harmless with the mock adapter; catastrophic with ICICI. Also pins a pool connection for the provider's full latency.
- **Recommendation:** Intent-row pattern (#204): persist a `DISBURSEMENT_REQUESTED` intent with the deterministic `tran_ref_no` (F-S9) and commit; call the provider outside any tx; record the outcome in a second tx; a sweeper reconciles intents with no recorded outcome via the provider status API.
- **Validation:** Kill-between-steps crash test; provider-side duplicate-ref rejection test.

**F-Q6 · ~~P0~~ → PARTIALLY FIXED (rev 2) · LSP/admin API idempotency now claim-before-execute**
- **Was:** `LspApiIdempotencyService.execute` ran business logic before claiming the key.
- **Now:** Both `LspApiIdempotencyService` and `AdminApiIdempotencyService` insert a **PENDING** row via `IdempotencyClaimService.claim*IdempotencyRecord` first; on unique-violation they poll/replay; action runs only after claim succeeds (`LspApiIdempotencyService` lines 55–74).
- **Residual risk:** Action still runs under admin scope outside the claim transaction; downstream must remain idempotent on partial failure between action success and `complete*IdempotencyRecord`. Payment idempotency path unchanged (separate unique key on `loan_payment_transaction`).
- **New surface (V103):** `admin_api_idempotency_record` mirrors LSP idempotency for internal admin mutating endpoints (`LoanProductAdminController`, `UserAdminController`, allowlist controllers, etc.) — included in `IdempotencyRecordRetentionWorker` purge.

**F-Q7 · P1 · Audit explorer: 8-branch UNION ALL, optional time bounds, OFFSET+COUNT, regex LSP filter**
- **Affected:** `AuditExplorerRepository.search` — UNION ALL across eight audit streams with every filter optional (`since`/`until` can be null → full-table scans across all streams), `LIMIT/OFFSET` pagination (max 500/page), optional `COUNT(*)` over the whole union, and the REPORT_ACCESS branch filters LSP by **regex over `filter_payload` jsonb** (`substring(cast(filter_payload as text) from '"lspId"…')`) — unindexable by construction.
- **Recommendation:** Mandatory time window (default 7d, cap 90d — issue #214), keyset pagination on `(occurred_at, native_id)`, and an explicit indexed `lsp_id` column on `report_access_audit` (backfill from the payload once) instead of the regex.

**F-Q8 · P0 (partial fix rev 2) · Retention gap: idempotency purge added; everything else still unbounded**
- **Fixed (rev 2):** `IdempotencyRecordRetentionWorker` (`@Scheduled`, default hourly) purges `lsp_api_idempotency_record` and `admin_api_idempotency_record` older than 90 days (`app.idempotency.retention-days`, purge enabled by default). `LspApiIdempotencyRecordRepository.deleteByCreatedAtBefore` and admin equivalent exist and are called.
- **Still open:** `refresh_token` (`deleteByExpiresAtBefore` — **zero callers**), `webhook_event_outbox` + `webhook_event_delivery_attempt`, all 8+ audit streams, `borrower_pii_reveal_audit`, `loan_delinquency_state` (small today). No partitioning.
- **Impact at scale:** 50–150M audit rows/yr still unbounded; index bloat and audit-explorer UNION degradation remain.
- **Recommendation:** (unchanged) Partition append-heavy tables; add refresh-token, webhook, and audit retention policies. Idempotency purge is the template — extend the same worker pattern.

**F-Q9 · P1 · Per-request auth queries with no cache**
- **Affected:** every authenticated request: `JwtSecurityBeans.managedUserSessionValidator` → `findByUsername` (admin pool), `grantedAuthoritiesConverter` → a **second** `findByUsername`, `ApiClientJwtSessionValidator.validate` → `findByClientId`. ~2M+ admin-pool queries/day at target LSP volume; contention on the small admin pool ahead of all business logic.
- **Recommendation:** Short-TTL (30–60s) in-memory cache of `(tokenVersion, status, lockout)` per principal, invalidated on rotation/revocation endpoints (which already bump `token_version` — the cache only delays revocation by the TTL, which is an acceptable, documentable window). Also merge the duplicate `findByUsername` pair into one lookup per request.

**F-Q10 · P2 · Pagination is OFFSET-based everywhere; two list paths can go unbounded**
- **Affected:** `LoanApplicationReadRepository` (offset + optional `count(distinct)`), `AuditExplorerRepository`, `PortfolioMisReadRepository` (COUNT every page), `OpsAlertService` (Spring `Page` → COUNT every call). `BorrowerDirectoryService.listBorrowers` falls back to `PageRequest.of(0, Integer.MAX_VALUE)` when pagination params are omitted (via `PaginationResponseBuilder` limit = `Integer.MAX_VALUE`); ops loan list allows `limit` up to 1000.
- **Recommendation:** Cap the unpaginated fallbacks (hard max 200); make COUNT opt-in on the remaining two paths (the loan-app list already does this); adopt keyset pagination only where deep paging is real (audit explorer — F-Q7); leave the rest as OFFSET (fine at ≤50-row pages with matching composite indexes; don't over-engineer).

**F-Q11 · P2 · Dead unbounded repository methods (loaded footguns)**
- `LoanAccountRepository.findDetailedBy()` (all accounts, 7-path EntityGraph), `LoanApplicationRepository.findDetailedByOrderByCreatedAtDesc()`, `findAllByOrderByCreatedAtDesc()` — no production callers found. Delete them so nobody wires a controller to a full-table hydration.

**F-Q12 · P2 · `LoanServicingSupportService.recomputePaymentAllocation` reads all payments per account**
- `findByLoanAccount_IdOrderByPaymentDateAscCreatedAtAsc` is per-account (bounded by loan life) — acceptable; flagged only because bounce/reversal (F-S10) will make it hot; keep it per-account and indexed (it is).

### 4.3 Concurrency/transactions summary

| Path | Mechanism today | Verdict |
|---|---|---|
| Webhook claim | `FOR UPDATE SKIP LOCKED` + lease (`claim_expires_at`) + `@Version` | ✅ Good |
| Report claim | `FOR UPDATE SKIP LOCKED` … but claim+generate+store+email all in one `@Transactional` (`ReportRequestService.processPendingRequests`) | ⚠️ Split tx (#213); storage/email outside |
| Disbursement initiate | App row lock (`findByIdForUpdate`) + status gates; provider call in-tx | ❌ F-Q5/F-S9 |
| Disbursement worker | Per-app tx (fixed) but unbounded scan, no claim | ⚠️ F-Q3 |
| Payment same-key | DB unique + fingerprint + interned-string lock | ✅ (cluster-safe via the unique constraint) |
| Payment different-key same EMI | Nothing | ❌ F-S7 |
| Payment claim vs allocation | `REQUIRES_NEW` claim commits early | ❌ F-S8 |
| LSP idempotent ops | Claim-before-execute (pending row) | ✅ Fixed rev 2 |
| Document upload | `@Transactional` wraps R2 `store()` (`LoanDocumentService.persistStoredDocumentForLsp`) | ⚠️ store first, short tx after (#225) |

---

## 5. Tenant isolation and security audit

### 5.1 Architecture (verified sound)

- **Dual datasources:** `TenantIsolationDataSourceConfig` defines an admin pool and a tenant pool wrapped by `TenantAwareDataSource`, routed per-thread by `TenantRoutingDataSource` on `TenantDataAccessContextHolder` (ADMIN/TENANT + lspId).
- **GUC binding:** every tenant connection checkout runs `select set_config('app.current_lsp_id', ?, true)` (transaction-local; tenant pool is `autoCommit=false` so the setting is always tx-scoped). `SET ROLE lms_tenant_app` only on Supabase pooler URLs (V96 grants SET).
- **RLS:** V41/V43/V45/V71/V75 policies on all loan-graph tables keyed on `app_current_lsp_id()`; borrower visibility via `borrower_lsp_access` EXISTS policies; V45 deliberately keeps the hard `::UUID` cast so an unbound GUC **errors loudly** rather than returning empty results.
- **Fail-closed:** no tenant context on DB access → `MissingTenantContextException` → 500 `TENANT_SCOPE_MISSING`; LSP endpoints without an lspId claim → 403 (`LspTenantContextInterceptor`); internal roles and LSP assignment are mutually exclusive at user creation (`UserAdminService.validateRoleLspConsistency`).
- **Tested:** `TenantIsolationPostgresIntegrationTest` proves tenant connections can't read foreign rows even without WHERE clauses, that an empty GUC fails, and that same-PAN borrowers across two LSPs stay isolated in LSP list APIs.

**Verdict: cross-tenant data-leakage risk at the application/DB layer is low.** This matches and re-confirms the June audit's F-TEN-01.

### 5.2 Gaps

**F-T1 · P2 · Catalog tables granted to the tenant role without RLS**
- `V41` grants `SELECT` on `lsp`, `loan_product`, `app_role` to `${tenant_app_role}` with **no RLS policies** — a tenant connection can read every LSP's name/status/**webhook_endpoint_url/webhook_signing_secret** (the `lsp` grant is table-wide, and `Lsp` carries the secret column) and the full product catalog at SQL level. No current HTTP endpoint exposes this (LSP product listing filters via `loan_product_lsp_mapping`, which *does* have RLS), but any future repository call on a tenant thread that touches `Lsp` beyond the caller's own row silently works.
- **Recommendation:** Column-level grant on `lsp` (exclude webhook secret) or a policy `USING (id = app_current_lsp_id() OR <catalog columns only via view>)`; simplest robust fix: revoke `lsp` SELECT from the tenant role and route the few tenant-thread LSP reads through admin-scope lookups (most already are), or add an RLS policy limiting tenant reads to their own row + move product catalog reads behind the mapping table.

**F-T2 · P3 · Audit/ops tables protected only by absence of grants**
- `refresh_token`, `auth_event_audit`, `lsp_audit_event`, `borrower_bank_details_update_audit`, `loan_disbursement_bank_mismatch_log`, `disbursement_outcome_audit`, `report_access_audit`, `webhook_event_delivery_attempt`, `webhook_outbox_redrive_audit`, `ops_alert`, `alert_rule`, both IP allowlists — no RLS, no tenant grant. Safe today; a single future mis-grant exposes them with no RLS backstop. Add `ENABLE ROW LEVEL SECURITY` (with no permissive policy) as cheap defence-in-depth on the tenant-relevant ones.

**F-T3 · P2 · RLS child-policy cost at payment volume**
- `tenant_owns_loan_account()` / `tenant_owns_application()` EXISTS functions run per-row on `loan_repayment_schedule_installment`, `loan_payment_transaction`, `loan_disbursement_request_log`, `loan_foreclosure_quote` inserts/reads from tenant connections. At 6–12M payments/yr this is measurable but acceptable *if* pools are sized (June F-DB-05). If it shows up in profiles, denormalize `lsp_id` onto the two hottest child tables and switch their policies to the direct-column form — do this only with evidence.
- Note most LSP write paths actually run under **admin scope** via `AdminScopedTransactionExecutor` with app-level `enforcedLspId`/`hasVisibilityFor` checks (idempotency, onboarding, bank details) — deliberate, documented, and compensated; the RLS cost mainly hits tenant-scoped reads.

**F-T4 · P3 · Test coverage gaps** — no negative IDOR tests (LSP A requesting LSP B's application/loan/borrower IDs on every LSP endpoint), no `SET ROLE`/pooler-path test, no admin-surface tests. Add a parameterized cross-tenant 404 test over the LSP surface.

### 5.3 Security posture notes

- API client credential lockout now exists (`V100` `failed_auth_attempts`/`auth_locked_until` + `ApiClientLockoutService`) — closes June F-SEC-01.
- `app_user` lockout (V94) + brute-force alert rules (V95) with a purpose-built partial index — good.
- Token-version session revocation modeled on `app_user`, `api_client`, **and** `lsp` (V54/V77) with cascade semantics — good design.
- F-S16 (default tenant password) is the highest-priority security config item.

---

## 6. Backend data-flow and API-shape audit

### 6.1 What's right

- **No JPA entity is ever serialized directly.** Every controller maps to Java records (`LspLoanApplicationResponses`, `LoanApplicationOpsResponses`, etc.).
- **Secrets handled correctly on the API:** `secret_hash`/`token_version` never exposed; client secret is reveal-once on create/rotate; webhook signing secret always returned as `null` with a `secretSet` boolean.
- **Strict JSON on the partner surface:** `@StrictJson` + `StrictJsonUnknownPropertyHandler` rejects unknown fields with 400 on LSP DTOs; PAN/Aadhaar/IFSC `@Pattern` validation matches DB widths.
- **Pagination via headers** (`PaginationResponseBuilder`: `X-Limit`/`X-Offset`/`X-Total-Count`), envelope removed intentionally.

### 6.2 Findings

**F-D1 · P1 (partial fix rev 2) · Two PII-reveal audit tables — one active, one dead**
- **`borrower_pii_reveal_audit` (V102):** Active. `BorrowerPiiRevealAuditService.recordBankDetailsReveal` writes on `GET /api/v1/lsp/borrowers/{id}/bank-details` (`LspBorrowerApiController` lines 49–56). Fields: `borrower_id`, `lsp_id`, `actor_username`, `actor_type`, `revealed_fields`, `client_ip`, `correlation_id`.
- **`loan_application_pii_reveal_audit` (V42):** Still **dead** — entity/repo exist; zero production writes. Legacy reveal endpoint removed.
- **Gap:** Full PAN on LSP loan-application reads, admin borrower detail, ops intake/disbursement payloads, MIS preview/CSV, and ops alert `contextJson` still have **no** reveal audit. `PanMasking` exists but is only applied in MIS export (`AdminReportingService`); LSP loan-application responses still return full PAN (`LspLoanApplicationResponses` line 36).
- **Recommendation:** Wire reveal audit (or default masking) on every remaining full-PII surface; drop or repurpose V42 table after confirming no external dependency.

**F-D2 · P1 (partial fix rev 2) · Unmasked PII surfaces; PAN masking started**
- **`PanMasking`** utility now exists (`common/pii/PanMasking.java`) and is applied in **MIS export/preview only** (`AdminReportingService`).
- Full **PAN** still returned on: LSP loan-application list/detail (`LspLoanApplicationResponses` line 36), ops loan list/detail, borrower admin list/detail.
- Full **bank account** still on: LSP bank-details GET (now **audited** — F-D1), bank-details PATCH responses, ops disbursement audit payloads. MIS bank column still full in some paths — verify preview vs export parity.
- Intake-audit payload masking still **Aadhaar-only**.
- **Recommendation:** Apply `PanMasking.mask()` on all list surfaces; keep full values only on audited reveal endpoints.

**F-D3 · P0 · No `DataIntegrityViolationException` handler → constraint violations become 500s**
- `GlobalExceptionHandler` maps validation, business conflicts, optimistic locking (409 `CONCURRENT_MODIFICATION`) — but has no handler for `DataIntegrityViolationException`. Unique races (e.g., borrower PAN race after `LoanApplicationOnboardingService`'s 3 retries), FK violations, and column-length overflows surface as **500 INTERNAL_SERVER_ERROR**. This also pages on-call for what are client-input conditions, and at partner volume becomes a support-ticket generator (June #207).
- **Recommendation:** Add a handler translating unique violations → 409 (with constraint-name → error-code mapping for the known ones), length/check violations → 400. Also close the two `@Size` gaps that let over-length input reach the DB: `externalLoanId`/`lspLoanId` (no `@Size(max=128)` on either DTO), `fullName` and `sourceChannel` on ops/LSP create DTOs.

**F-D4 · P2 · Internal fields leak on ops surfaces**
- `storageKey` + `fileChecksum` in the ops document-checklist response (`LoanApplicationOpsResponses.toDocumentChecklistResponse`) — internal R2 paths don't belong in API payloads (LSP variant correctly omits them). LSP `tokenVersion` appears inside `LspAuditEventResponse.detailsJson`. Raw provider request/response JSON exposed wholesale via ops disbursement endpoints (overlaps F-D2).

**F-D5 · P3 · Stored JSON is full-fidelity; masking is read-side only**
- All `payload_json`-family columns store unmasked data and rely on response-time masking. Combined with F-Q8 (no retention), the DB accumulates permanent unmasked PII in JSON that no masking fix will retroactively clean. Write-side masking (F-D2 recommendation) is the durable fix.

---

## 7. Scalability risks vs. the 2026-06-14 Scalability Audit

Direct reassessment of the database-relevant P0/P1 items from `docs/scalability-audit-report-2026-06-14.md` against today's code:

| June finding | Status today (evidence) |
|---|---|
| F-API-01 / F-DB-01: pool sizing, `statement_timeout` | **Open.** Only `application-local.yml` sets Hikari (max 5); no prod overrides, no statement/idle-in-tx timeouts anywhere. |
| F-MNY-02: disbursement mega-transaction | **Fixed.** `LoanDisbursementWorkerProcessor` bean gives per-application transactions. |
| F-MNY-03 / F-DB-04: no worker claim, unbounded `findByStatus` | **Open** (F-Q3). |
| F-MNY-01: provider call inside tx | **Closed (2026-07-13)** — Spec S3 / `disbursement_intent` workflow; see `docs/implementation-log.md`. |
| F-MNY-04/05: payment claim atomicity, installment lock | **Open** (F-S7, F-S8) — highest money risk. |
| F-MNY-06: idempotency execute-before-claim | **Fixed** (rev 2) — `LspApiIdempotencyService` + `AdminApiIdempotencyService` claim PENDING row first |
| F-RPT-01 / F-DB-03: dashboard live aggregates | **Open** (F-Q1); 32s p95 stands. |
| F-DB-02 / F-AUD-01: partitioning + retention | **Partial** (rev 2) — idempotency purge worker; audit/webhook/refresh-token retention still open |
| F-DB-06/07 / F-AUD-02: audit explorer guards | **Open** (F-Q7). |
| F-SEC-01: API client lockout | **Fixed** (V100 + `ApiClientLockoutService`). |
| F-RPT-02: MIS in-memory CSV | **Open** (F-Q4). |
| F-ISO-04: report batch single tx | **Open.** |
| F-TEN-01: data isolation sound | **Re-confirmed** (§5). |

**Net:** since June, the team fixed the worker transaction structure and API-client lockout, but the five money-correctness P0s and the growth/read-path P0s are all still open. At 100K loans/day the failure sequence predicted in June (pool exhaustion → dashboard/report scans amplifying → settlement-morning payment races) remains the expected outcome.

Additional scale risks this audit adds beyond the June report:
- The **alert engine** is a bigger read-amplifier than the dashboard because it runs unattended every 5 minutes on every pod (F-Q2).
- **`BorrowerDirectoryService` unpaginated fallback** loads the whole borrower table (F-Q10).
- **Auth-path DB load** (~2 queries/request) hits the admin pool exactly when it's smallest (F-Q9).
- **Environment schema drift** (F-S15) makes scale remediations (partitioning, index changes) riskier to roll out.

---

## 8. Prioritized remediation roadmap

### Critical (before any real-money / production volume)

| # | Finding | Change | Blast radius |
|---|---|---|---|
| 1 | F-S7 | Installment `FOR UPDATE` + `@Version` in payment path | 1 repo method, 1 service, 1 migration |
| 2 | F-S8 | Payment claim + allocation in one transaction | `IdempotencyClaimService`, `LoanRepaymentCommandService` |
| 3 | F-Q5 + F-S9 | Disbursement intent row, provider call outside tx, `UNIQUE(tran_ref_no)` | Disbursement command service + 1 migration |
| 4 | ~~F-Q6~~ | ~~Claim-before-execute LSP idempotency~~ **Done (rev 2)** | — |
| 4b | F-N1/F-T1 | RLS on `loan_product_version`; verify version bump on product edit | Migration + admin service |
| 5 | F-D3 | `DataIntegrityViolationException` handler (409/400) + missing `@Size` caps | `GlobalExceptionHandler`, 2 DTOs |
| 6 | F-S16 | Remove default tenant-role password; rotate | Config + ops |
| 7 | — | Hikari sizing per role + Postgres `statement_timeout`/`idle_in_transaction_session_timeout` (June #201) | Config |
| 8 | F-Q1/F-Q2 | KPI snapshot tables; dashboard + alert rules read snapshots; single-instance scheduler | New worker + 2 tables; dashboard API adds "as of" |

### High (before sustained 1M loans/month)

| # | Finding | Change |
|---|---|---|
| 9 | F-Q8 | Partition the six append-heavy tables now; retention worker (idempotency 90d, refresh tokens, webhook attempts, DELIVERED outbox) |
| 10 | F-Q3 | Disbursement worker SKIP LOCKED claim, batch 25, atomic attempt counter |
| 11 | F-D1/F-D2 | Extend reveal audit to remaining full-PII paths; roll out `PanMasking` beyond MIS; write-side JSON masking | Partial — bank-details reveal done |
| 12 | F-Q7 | Audit explorer mandatory window, keyset pagination, indexed `report_access_audit.lsp_id` |
| 13 | F-Q4 | Streaming MIS export; snapshot-based PAR-30 |
| 14 | F-Q9 | Short-TTL principal cache in JWT validators; deduplicate the double `findByUsername` |
| 15 | F-S10 | Payment bounce/reversal statuses + allocation rollback (under the F-S7 lock) |
| 16 | F-S15 | Prod-vs-repo schema diff; reconciliation ADR; `flyway validate` in CI |

### Medium

| # | Finding | Change |
|---|---|---|
| 17 | F-S1/F-S12 | PII encryption at rest (Aadhaar, bank account, PAN-hash pattern); encrypt webhook signing secret — after KMS decision |
| 18 | F-S2 | Snapshot beneficiary bank details onto `loan_account` at approval |
| 19 | F-S11 | Normalize `lsp.webhook_event_types` to a child table |
| 20 | F-S4 | Collapse dual status-history tables into `loan_application_audit_event` |
| 21 | F-T1/F-T2 | Revoke/limit tenant grants on `lsp`; enable RLS-without-policy on ungranted tenant-data tables |
| 22 | F-S14 | Convert 4 `TIMESTAMP` audit columns to `TIMESTAMPTZ` |
| 23 | F-S17 | LAZY fetch defaults on money-graph associations |
| 24 | F-Q10 | Cap unpaginated fallbacks (borrower directory, ops limit=1000) |
| 25 | F-D4 | Remove `storageKey`/`fileChecksum`/`tokenVersion` from ops responses |
| 26 | — | Upload/report storage I/O outside transactions (#225, #213) |

### Low

| # | Finding | Change |
|---|---|---|
| 27 | F-Q11 | Delete dead unbounded repo methods |
| 28 | F-S5 | Drop `report_request.report_content`, assignment columns/table (after retention window) |
| 29 | F-S13 | `ops_alert(type,subject_id,status)` index when alert volume warrants; `auth_event_audit` FK indexes if purge path built |
| 30 | F-T4 | Cross-tenant IDOR negative test suite |
| 31 | F-S3/F-S6 | No action — documented accept |

---

## 9. Target-state architecture direction

No re-architecture required. The target state is the current design plus five structural additions:

1. **Ledger-grade money core:** current tables + installment row locking + disbursement intent rows with unique provider refs + bounce/reversal. The transactional loan graph (`loan_application` → `loan_account` → installments/payments) stays exactly as modeled.
2. **Two-speed read model:** hot OLTP tables stay normalized; all portfolio-wide reads (dashboard, alert rules, PAR/MIS summaries) move to small snapshot tables (`portfolio_kpi_snapshot`, per-LSP and per-bucket rows, refreshed by one scheduled worker). No CQRS, no read replicas yet — snapshot tables are the simple version that fits 1–2M loans/yr; a read replica is the *next* step only if ops query volume outgrows them.
3. **Append-only tier with lifecycle:** audit streams, idempotency records, webhook attempts as monthly range partitions with per-table retention (drop-partition). Unified audit search gets mandatory time windows so it always prunes partitions.
4. **Encrypted PII envelope:** Aadhaar/bank encrypted, PAN as HMAC-hash + encrypted value, JSON payloads masked at write time. Borrower stays global (identity dedupe is a business requirement) but disbursement-critical fields are snapshotted per loan.
5. **Isolation kept as-is, hardened at the edges:** dual-datasource + RLS unchanged (it works); tenant grants trimmed on catalog tables; RLS-enabled-no-policy on the remaining tenant-data tables; per-LSP resource limits (rate/webhook/pool budgets) handle the *performance* isolation the schema can't.

---

## 10. Open questions requiring a decision before implementation

1. **Borrower data ownership:** When two LSPs share a borrower (same PAN), is last-write-wins on profile fields acceptable, or must profile updates be versioned/per-LSP? Who is allowed to change bank details while another LSP has an approved-undisbursed loan? (Drives F-S2 scope.)
2. **PII policy:** Which roles/surfaces legitimately need *full* PAN and bank account (ops verification? MIS regulatory exports? partner reconciliation?), and what is the mandated retention period for PII inside audit/idempotency payloads? (Drives F-D1/F-D2/F-Q8 retention numbers and the F-S1 encryption scope.)
3. **Key management:** Where do encryption keys live (cloud KMS vs. env-injected key with rotation procedure)? Blocks F-S1/F-S12.
4. **Idempotency replay window:** What replay window do partner contracts promise (drives the 90-day purge and the documented API behavior when a key ages out)?
5. **Audit retention & archival:** Regulatory retention for lending audit trails (RBI norms typically ≥5–8 years) — does old data go to cold storage (Parquet on R2) or stay in cheap partitions? (Drives partition/retention design.)
6. **Status-history consolidation (F-S4):** Any external consumer (reports, BI) reading `loan_application_status_transition` directly that would block collapsing it?
7. **Environment reconciliation:** Confirm production (Supabase) is the only environment with divergent history, and whether a maintenance window is available for the schema diff + partition table-swap work.
8. **Deployment topology:** Will the single-worker-pod topology (D1 in the scalability tracker) be adopted? Several fixes (alert scheduler gating, disbursement claim sizing) are simpler if yes.

---

## Appendix A — Evidence index (revision 2)

- Migrations: `backend/src/main/resources/db/migration/V1…V104` (gaps V63/V69/V91; env-drift in V85/V86).
- Money paths: `LoanRepaymentCommandService`, `LoanServicingSupportService`, `IdempotencyClaimService`, `LspApiIdempotencyService`, `AdminApiIdempotencyService`, `LoanDisbursementCommandService`, `LoanDisbursementWorkerService`/`LoanDisbursementWorkerProcessor`.
- Read paths: `HomeDashboardService`, `LoanAccountRepository`, `PortfolioMisReadRepository`, `LoanApplicationReadRepository`, `AuditExplorerRepository`, `AlertRuleEvaluationWorker` (+ `LoanDelinquencyStateRepository`).
- Tenant isolation: `TenantIsolationDataSourceConfig`, `TenantAwareDataSource`, `TenantRoutingDataSource`, `AuthenticationTenantScopeFilter`, `LspTenantContextInterceptor`, V41/V43/V45/V71/V75, `TenantIsolationPostgresIntegrationTest`.
- API shapes: `LspLoanApplicationResponses`, `LoanApplicationOpsResponses`, `BorrowerAdminController`, `LspBorrowerApiController`, `AdminReportingService`, `GlobalExceptionHandler`, `BankAccountMasking`, `AadhaarMasking`, `PanMasking`.
- Retention: `IdempotencyRecordRetentionWorker` (LSP + admin idempotency); `RefreshTokenRepository.deleteByExpiresAtBefore` still uncalled.
- PII audit: `BorrowerPiiRevealAuditService` → `borrower_pii_reveal_audit`; `loan_application_pii_reveal_audit` still unused.
- Config: `application.yml` (`app.idempotency.*`, tenant password default), `application-local.yml` (Hikari max 5).

---

## Appendix B — Complete table catalog

Legend: **RLS** = row-level security for tenant role; **Ret** = retention/purge; **Find** = cross-ref to §3–§6 findings.

### Identity & access

| Table | Purpose / key columns | PK / UK / FK | RLS | Ret | Find / notes |
|---|---|---|---|---|---|
| `app_user` | Internal + LSP UI users; `username` UK, `email` UK, `password_hash`, `status`, lockout cols (V94), `token_version` (V54), optional `lsp_id` FK | PK `id`; FK `lsp_id` | No (admin) | None | F-S6 actor strings elsewhere |
| `app_role` | Role catalog (`SYSTEM_ADMIN`, `LSP_*`, …) | PK `id`; UK `code` | Tenant SELECT, no RLS | N/A | F-T1 |
| `app_permission` | Permission catalog | PK `id`; UK `code` | Admin | N/A | — |
| `app_user_role` | M:N user↔role | PK `(user_id, role_id)` | Admin | N/A | `@ManyToMany` on `AppUser` |
| `app_role_permission` | M:N role↔permission | PK `(role_id, permission_id)` | Admin | N/A | — |
| `refresh_token` | Session refresh; XOR FK to `app_user` OR `api_client` (V73) | PK `id`; UK `token_hash` | No grant | **None** (purge method unused) | F-Q8 |
| `auth_event_audit` | Login success/failure stream; optional `user_id`/`api_client_id` FKs | PK `id` | No grant | None | Brute-force alert input |
| `app_user_audit_event` | User admin mutations; `details_json` jsonb | PK `id` | No grant | None | — |

### Tenancy & partners

| Table | Purpose / key columns | PK / UK / FK | RLS | Ret | Find / notes |
|---|---|---|---|---|---|
| `lsp` | Tenant root; `code` UK; webhook cols incl. **plaintext** `webhook_signing_secret`, CSV `webhook_event_types` | PK `id` | Tenant SELECT, **no RLS** | N/A | F-S11, F-S12, F-T1 |
| `api_client` | LSP API credentials; `client_id` UK; `secret_hash`; lockout cols (V100) | PK `id`; FK `lsp_id` | Via RLS on child ops | N/A | V100 fixed F-SEC-01 |
| `api_client_audit_event` | Client lifecycle audit | PK `id` | No grant | None | — |
| `lsp_api_ip_allowlist` | Per-LSP API IP CIDRs (V79) | PK `id`; FK `lsp_id` | No grant | None | Loaded via admin snapshot |
| `lsp_ui_ip_allowlist` | Per-LSP UI IP CIDRs | PK `id`; FK `lsp_id` | No grant | None | Same |
| `lsp_audit_event` | LSP config/status audit; `details_json` | PK `id`; FK `lsp_id` | No grant | None | May expose `tokenVersion` in API |

### Product catalog

| Table | Purpose / key columns | PK / UK / FK | RLS | Ret | Find / notes |
|---|---|---|---|---|---|
| `loan_product` | Mutable product definition; rates/tenure/principal bounds | PK `id`; UK `code` | Tenant SELECT, no RLS | N/A | F-T1; superseded at origination by version |
| `loan_product_version` | **Immutable** product terms snapshot (V104) | PK `id`; UK `(loan_product_id, version_number)` | Tenant SELECT, no RLS | N/A | F-N1 |
| `loan_product_lsp_mapping` | Which products each LSP may sell | PK `id`; UK `(lsp_id, loan_product_id)` | RLS | N/A | — |
| `loan_product_audit_event` | Product change audit | PK `id` | RLS | None | — |

### Borrowers

| Table | Purpose / key columns | PK / UK / FK | RLS | Ret | Find / notes |
|---|---|---|---|---|---|
| `borrower` | Global identity; **plaintext PII** (`pan` UK, aadhar, bank, DOB, income, address) | PK `id` | RLS via `borrower_lsp_access` | None | F-S1, F-S2 |
| `borrower_lsp_access` | Visibility grants `(borrower_id, lsp_id)` | PK `(borrower_id, lsp_id)` | RLS | N/A | `@ElementCollection` F-S3 |
| `borrower_bank_details_update_audit` | Bank PATCH audit | PK `id` | No grant | None | `TIMESTAMP` F-S14 |
| `borrower_pii_reveal_audit` | PII reveal audit (V102) | PK `id`; FK `borrower_id`, `lsp_id` | No grant | None | F-D1, F-N3; **actively written** |

### Origination

| Table | Purpose / key columns | PK / UK / FK | RLS | Ret | Find / notes |
|---|---|---|---|---|---|
| `loan_application` | Application lifecycle; FKs borrower/lsp/product/**product_version**; `external_loan_id`; `@Version` | PK `id`; UK `(lsp_id, external_loan_id)` | RLS | None | F-S4 dual-write |
| `loan_application_status_transition` | Status history (legacy stream) | PK `id` | RLS | None | F-S4 redundant |
| `loan_application_audit_event` | Unified audit incl. `STATUS_TRANSITION` action | PK `id` | RLS | None | F-S4 target stream |
| `loan_application_intake_audit` | Intake payload snapshots; `payload_json` jsonb | PK `id` | RLS | None | Full PII in JSON F-S1 |
| `loan_application_assignment_event` | **Deprecated** assignment history | PK `id` | RLS | None | F-S5 |
| `loan_application_document_checklist` | Per-doc upload state | PK `id` | RLS | None | — |
| `loan_application_document_access_audit` | Document download audit; M:N `document_access_audit_type` | PK `id` | RLS | None | Audit explorer stream |
| `loan_application_document_access_audit_type` | Doc type tags for access audit | PK `(audit_id, document_type)` | RLS | N/A | — |
| `loan_application_pii_reveal_audit` | **Legacy unused** (V42) | PK `id` | RLS | None | F-D1 dead |

### Servicing & money

| Table | Purpose / key columns | PK / UK / FK | RLS | Ret | Find / notes |
|---|---|---|---|---|---|
| `loan_account` | Active loan; denorm `lsp_id`/`borrower_id`; `@Version`; `processing_fee_amount` (V97); **product_version_id** | PK `id`; UK `account_number`; UK `loan_application_id` | RLS | None | F-S17 eager |
| `loan_repayment_schedule_installment` | EMI schedule; paid/outstanding amounts; V65 CHECKs; **no `@Version`** | PK `id` | RLS (child EXISTS) | None | **F-S7 P0** |
| `loan_payment_transaction` | Payments; `idempotency_key` UK; allocation cols; V65 CHECK | PK `id` | RLS | None | F-S8 |
| `loan_disbursement_request_log` | Provider request/response; `provider_request_id`, `tran_ref_no` (V98) — **not UK** | PK `id` | RLS | None | F-S9 |
| `loan_disbursement_bank_mismatch_log` | Bank mismatch at disbursement | PK `id` | No grant | None | F-S14 timestamp |
| `disbursement_outcome_audit` | Outcome audit for adapter | PK `id` | No grant | None | F-S14 |
| `loan_foreclosure_quote` | Foreclosure quote versions | PK `id` | RLS | None | Per-account bounded reads |
| `loan_delinquency_state` | Last DPD bucket cache (V101) | PK `id`; UK `loan_application_id` | **No grant/RLS in V101** | None | F-N2; alert worker only |

### Integration & ops

| Table | Purpose / key columns | PK / UK / FK | RLS | Ret | Find / notes |
|---|---|---|---|---|---|
| `webhook_event_outbox` | Outbound webhooks; `@Version`; `claim_expires_at` (V86) | PK `id` | RLS | None | SKIP LOCKED ✅ |
| `webhook_event_delivery_attempt` | Delivery log; response bodies TEXT | PK `id` | No grant | None | F-Q8 |
| `webhook_outbox_redrive_audit` | Manual redrive audit | PK `id` | No grant | None | F-S14 |
| `lsp_api_idempotency_record` | LSP idempotency; fingerprint; response TEXT | PK `id`; UK `(lsp_id, operation_key, idempotency_key)` | Admin | **90d purge** | F-Q6 fixed |
| `admin_api_idempotency_record` | Admin idempotency (V103) | PK `id`; UK `(operation_key, idempotency_key)` | Admin | **90d purge** | F-N4 |
| `report_request` | Async MIS jobs; `@Version`; `storage_key` | PK `id` | RLS (V71) | None | F-ISO-04 tx |
| `report_access_audit` | MIS download audit; `filter_payload` jsonb | PK `id` | No grant | None | F-Q7 regex LSP filter |
| `ops_alert` | Ops alert queue; dedupe by type/subject | PK `id` | No grant | None | F-S13 index gap |
| `alert_rule` | Alert rule config | PK `id`; UK `code` | No grant | N/A | — |

---

## Appendix C — Enum catalog (Java ↔ database)

Statuses and categorical columns are stored as **`VARCHAR` / `@Enumerated(STRING)`** in PostgreSQL — no native PG enums. Validation is split between Jakarta annotations (request DTOs) and application code (state machines).

| Java enum | Typical DB column(s) | CHECK constraint? | Notes |
|---|---|---|---|
| `LoanApplicationStatus` | `loan_application.status` | No (app-enforced) | V32/V51/V76 migrations renamed values |
| `LoanAccountStatus` | `loan_account.status` | No | — |
| `LoanRepaymentScheduleInstallmentStatus` | `installment.status` | No | — |
| `LoanPaymentStatus` | `payment.status` | No | F-S10 bounce gap |
| `LoanPaymentChannel` | `payment.channel` | No | — |
| `LoanDelinquencyBucket` | `loan_delinquency_state.last_bucket` | No | F-N2 |
| `LoanForeclosureQuoteStatus` | `quote.status` | No | — |
| `LoanProductStatus` | `loan_product.status` | No | — |
| `LspStatus` | `lsp.status` | No | — |
| `ApiClientStatus` | `api_client.status` | No | — |
| `UserStatus` | `app_user.status` | No | — |
| `WebhookEventOutboxStatus` | `webhook_event_outbox.status` | No | — |
| `WebhookEventType` | `webhook_event_outbox.event_type`, CSV in `lsp.webhook_event_types` | No | F-S11; V99 repair |
| `WebhookEventDeliveryAttemptStatus` | `delivery_attempt.status` | No | — |
| `ReportRequestStatus` | `report_request.status` | No | — |
| `ReportType` | `report_request.report_type` | No | — |
| `OpsAlertType` / `OpsAlertSeverity` / `OpsAlertStatus` | `ops_alert.*` | No | — |
| `AlertRuleTriggerKind` / `AlertRuleAudience` | `alert_rule.*` | No | — |
| `LoanApplicationAuditAction` | `loan_application_audit_event.action` | No | Includes `STATUS_TRANSITION` |
| `LoanApplicationDocumentChecklistStatus` | `checklist.status` | No | V50 collapsed states |
| `LoanApplicationDocumentType` | checklist + audit types | No | — |
| `LoanApplicationStatusReasonCode` | `transition.reason_code`, audit | No | — |
| `AuthEventType` / `AuthEventFailureReason` | `auth_event_audit` | No | — |
| `RoleCode` | via `app_role.code` | No | Mutually exclusive with LSP assignment |
| `DisbursementDisposition` / `DisbursementPaymentMode` / `DisbursementDeclineKind` | disbursement logs (JSON + cols) | Partial | Provider integration |
| `MockDisbursementOutcome` | test adapter only | N/A | Not in prod schema |

**Gap:** No DB CHECK tying `status` columns to enum membership — renames require Flyway backfills (already happened 3× for loan application status).

---

## Appendix D — Validation boundary matrix (request DTO ↔ DB)

| Field | DB column / width | LSP DTO validation | Ops/admin DTO validation | Failure mode if over-length |
|---|---|---|---|---|
| PAN | `borrower.pan` VARCHAR(10) | `@Pattern` 10 chars | `@Pattern` | 500 if uncaught (F-D3) |
| Aadhaar | `borrower.aadhar_number` VARCHAR(12) | `@Pattern ^[0-9]{12}$` | Not on ops create | — |
| Bank account | `borrower.bank_account_number` VARCHAR(64) | `@Size(max=64)` | `@Size(max=64)` | 500 |
| IFSC | `borrower.ifsc_code` VARCHAR(11) | `@Pattern` | `@Pattern` | 500 |
| `externalLoanId` / `lspLoanId` | `loan_application.external_loan_id` VARCHAR(128) | `@NotBlank` only | `@NotBlank` only | **500 — gap** (F-D3) |
| `fullName` | `borrower.full_name` VARCHAR(255) | **No `@Size`** | Partial | 500 |
| `sourceChannel` | VARCHAR(64) | — | `@NotBlank` only | 500 |
| Idempotency key | idempotency tables VARCHAR(64) | UUID v4 enforced in service | Same | 400/409 |
| Money amounts | NUMERIC(19,2) | `@DecimalMin` / scale in services | Same | CHECK or 500 |
| Unknown JSON fields | — | `@StrictJson` → 400 | Lenient (no `@StrictJson` on some admin PATCH) | Ignored silently |

**Recommendation unchanged:** Add `@Size(max=…)` on all string fields matching DB widths + global `DataIntegrityViolationException` handler (F-D3).

---

## Appendix E — Subagent coverage map & infra notes

This appendix confirms every area requested in the original audit brief and traced in this chat is addressed somewhere in the report.

| Original brief area | Report section | Subagent / source |
|---|---|---|
| Every table, column, relationship, constraint, index, migration | Appendix B; §2–3; F-S* findings | Schema read V1–V104 |
| Redundant/duplicated/poorly typed data | §3 F-S4, F-S11, F-S14, F-S15 | Manual |
| Normalization / production-readiness | §2.2–2.3, §9 | Manual |
| PK/FK/UK/nullability/timestamps/money | §2.2, Appendix B | Manual + V65 |
| Soft deletes / audit trails | §2.2 (terminal statuses); audit tables in Appendix B | Manual |
| Tenant isolation end-to-end | §5 | [Tenant isolation audit](e851c6c7-91e0-4546-9710-9f25915a5e8f) |
| ORM → DTO → API data flow | §6, Appendix D | [DTO/API audit](c9ed55b2-417f-43d0-b417-4e50d5c2a743) |
| Query/access patterns, N+1, pagination, locking | §4 | [Query patterns audit](7830bd2e-6794-44ae-8492-0875feea9ad2) |
| Scalability audit cross-check | §7 | `docs/scalability-audit-report-2026-06-14.md` |
| Issue template (severity, impact, fix, migration) | §3–§6 per finding | All sources |
| OpenAPI contract | Appendix E (here) | DTO subagent — `OpenApiContractExportTest` exports full surface to `openapi/openapi.json` |
| RabbitMQ | Not used for DB/workers — all async work is `@Scheduled` + Postgres `SKIP LOCKED`. Infra may provision RabbitMQ but application code does not enqueue to it. |

**Index inventory:** Not duplicated column-by-column here — §4.1 documents index *strategy* and known gaps. A full index listing lives in Flyway migrations V25–V89 (composite, partial, trigram, worker-claim indexes). Run `\di` on a migrated DB for authoritative inventory.

**Graphify / GRAPH_REPORT:** Skipped during audit (file exceeded read limit); analysis used direct migration and code reads instead.
