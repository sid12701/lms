# Database Audit Remediation Spec

**Date:** 2026-07-05  
**Source:** `docs/database-audit-report-2026-07-03.md` (revision 2)  
**Status:** **In progress** — 16 of 22 actionable items done or partially done (as of **2026-07-06**); **B1 / F-S2 deferred 2026-07-15** (Spec S5 — `docs/deferred-implementation.md`). See [Implementation progress](#implementation-progress-2026-07-06) below.  
**Decisions:** All decision items below were put to the product owner on 2026-07-05; the recorded choices are binding for this spec. Owner deferred B1 implementation on 2026-07-15 without reversing the technical decision (snapshot at approval remains the approved design when resumed).

## Implementation progress (2026-07-06; B1 refreshed 2026-07-15)

Summary of remediation work landed in the working tree. Spec sections below retain the original design detail; this table is the source of truth for **what shipped** (and what is deferred).

| ID | Finding | Status | Evidence (high level) |
|---|---|---|---|
| **A1** | F-S7 installment lost-update | **Done** | `V105`, `@Version` on `LoanRepaymentScheduleInstallment`, `findByIdForUpdate` / `findByAccountAndNumberForUpdate`, `LoanRepaymentConcurrencyIntegrationTest` |
| **A2** | F-S8 claim before allocation | **Done** | `claimLoanPaymentRow` uses same-tx `saveAndFlush` (no `REQUIRES_NEW`); allocation in `LoanRepaymentCommandService` |
| **A3** | F-D3 constraint-violation handler | **Done** | `GlobalExceptionHandler.handleDataIntegrityViolation`, `@Size` caps, `DataIntegrityViolationHandlerIntegrationTest` |
| **A4** | F-S14 naive `TIMESTAMP` | **Done** | `V106__audit_timestamp_columns_timestamptz.sql`, `SchemaJsonColumnsPostgresTest.timestamptzAuditColumns` |
| **A5** | F-Q11 dead unbounded repo methods | **Done** | Unbounded `findDetailedBy` / `findAllByOrderByCreatedAtDesc` removed from loan repos |
| **A6** | F-S11 webhook CSV column | **Open** | `Lsp.webhook_event_types` CSV still in use; no `V111` child table |
| **A7** | F-S17 eager fetch on money graph | **Done** | LAZY on `LoanAccount.loanApplication`, installment/payment associations |
| **A8** | F-Q4 MIS unbounded export | **Done** | Keyset-batched export (`findAccountIdsForExportBatch`), streaming CSV tests |
| **A9** | F-N1 product version bump | **Done** | `ProductVersioningIntegrationTest`; V104 `loan_product_version` |
| **A10** | F-T4 cross-tenant IDOR tests | **Open** | No parameterized LSP negative suite yet |
| **B1** | F-S2 bank snapshot at approval | **Deferred (2026-07-15)** | Spec S5 / DATA-01; no migration yet (next free Flyway **V114+** — V113 used by S19). See `docs/deferred-implementation.md` |
| **B2** | F-Q1/F-Q2 dashboard + alert scans | **Partial** | `V109`, `PortfolioKpiSnapshotWorker`, set-based `AlertRuleEvaluationWorker`; **FE does not render `dataAsOf` yet** (#212) |
| **B3** | F-S1/S12 PII encryption | **Open** | Wire masking helpers exist; no AES-GCM / `V112` |
| **B4** | F-Q8 retention | **Partial** | `IdempotencyRecordRetentionWorker` only; refresh-token + webhook purges and partitions not started |
| **B5** | F-Q7 audit explorer guardrails | **Done** | `V107`, 7d default / 90d max, keyset cursor, indexed `lsp_id`; FE load-more |
| **B6** | F-T1/T2/N1 additive RLS | **Open** | No `V110`; **unblocked** now that B12 prod reconcile is complete |
| **B7** | F-Q9 auth lookup dedupe + cache | **Done** | `AuthPrincipalCache`, deduped JWT principal resolution |
| **B8** | F-Q10 list caps + borrowers page | **Done** | `MAX_FALLBACK_LIMIT = 200`, `@Max(200)`, borrowers FE pagination (page size 50) |
| **B9** | F-S4 dual-written status history | **Open** | `loan_application_status_transition` still written |
| **B10** | F-S16 tenant password fail-fast | **Partial** | Prod `application.yml` has no password default; `application-local.yml` still defaults; no startup assertion |
| **B11** | #201 pool sizing / timeouts | **Open** | No Hikari / `statement_timeout` defaults in `application.yml` |
| **B12** | F-S15 schema drift reconciliation | **Done** | `scripts/schema-diff/`, ADR `0006`, CI `check-reference.sh`, Supabase migrated to V109 |
| **C1–C3** | ICICI / partner deferred | **Deferred** | Unchanged |
| **C4** | storageKey/fileChecksum | **Accepted** | Unchanged |

**Migrations applied (remediation-related):** V105 (A1), V106 (A4), V107 (B5), V109 (B2). Reservations V108, V110–V112 remain open. Additional migrations V100–V104 shipped in the same window (lockout, delinquency state, PII reveal audit, admin idempotency, product version) but are outside this spec's §2 reservation table.

**Suggested next items:** B6 (RLS, now gated only on ownership pre-check), B4 purges, B11 + B10, B1 (before ICICI/C1).

## How this document was produced

Every finding in the source report was re-verified against the **working tree as of 2026-07-05** — which includes ~147 files of uncommitted API-consistency changes (envelope removal, DTO typing, OpenAPI regen). A snapshot of that uncommitted state is preserved at `outputs/db-audit-remediation/baseline-working-tree-2026-07-05.patch`; any implementation on top of it must be diffed against that baseline. Verification was done by reading the actual entities, repositories, services, migrations, and frontend call sites cited below — not by trusting the report.

Structure:

- **Implementation progress** — what shipped as of 2026-07-06 (summary table)
- **§1** Verification & classification register (every finding, verdict, disposition, impl. status)
- **§2** Migration number reservations
- **Part A** Implementation specs — confirmed issues needing **no** business/architectural decision
- **Part B** Implementation specs — decided items (decision recorded + spec)
- **Part C** Deferred by decision (decision recorded + re-entry criteria)
- **Part D** Verified fixed / accepted as-is / report corrections
- **§3** Sequencing and dependencies

---

## 1. Verification & classification register

| Finding | Verified? | Evidence (working tree 2026-07-05) | Classification | Disposition | Impl. (2026-07-06) |
|---|---|---|---|---|---|
| F-S7 installment lost-update | **Confirmed** | No `@Version` in `LoanRepaymentScheduleInstallment`; no lock method in repository; plain resolve at `LoanServicingSupportService.resolveTargetInstallment` (sole prod caller `LoanRepaymentCommandService:200`) | Safe to fix | **A1** | **Done** |
| F-S8 claim commits before allocation | **Confirmed** | `IdempotencyClaimService:34-42` — `REQUIRES_NEW` TransactionTemplate around `saveAndFlush` | Safe to fix | **A2** | **Done** |
| F-D3 no constraint-violation handler | **Confirmed** | Zero matches for `DataIntegrityViolationException` in working-tree `GlobalExceptionHandler`; `externalLoanId` `@NotBlank`-only at `LoanApplicationOpsApiTypes:39`, `lspLoanId` at `LspLoanApplicationApiController:546` | Safe to fix | **A3** | **Done** |
| F-S14 naive TIMESTAMP columns | **Confirmed** | 4 columns: `borrower_bank_details_update_audit.created_at` + `loan_disbursement_bank_mismatch_log.created_at` (V78), `disbursement_outcome_audit.created_at` (V82), `webhook_outbox_redrive_audit.created_at` (V88); no later conversion migration exists | Safe to fix | **A4** | **Done** |
| F-Q11 dead unbounded repo methods | **Confirmed** | `LoanAccountRepository.findDetailedBy():58`, `LoanApplicationRepository.findDetailedByOrderByCreatedAtDesc():37`, `.findAllByOrderByCreatedAtDesc():42` — zero callers (the `OpsAlertRepository` method of the same name is paged and used; leave it) | Safe to fix | **A5** | **Done** |
| F-S11 webhook_event_types CSV column | **Confirmed** | `Lsp.java:152` `split(",")`; V99 string-surgery repair on record | Safe to fix (medium migration) | **A6** | Open |
| F-S17 eager fetch on money graph | **Partially confirmed** | EAGER: `LoanAccount.loanApplication` (`@OneToOne`, :27), `LoanRepaymentScheduleInstallment.loanAccount` (:26), `LoanPaymentTransaction.loanAccount` (:26) + `.repaymentInstallment` (:30). **Report correction:** `LoanAccount`'s denormalized `@ManyToOne`s (lsp/borrower/product) are already LAZY | Safe to fix | **A7** | **Done** |
| F-Q4 MIS unbounded export | **Confirmed** | `PortfolioMisReadRepository.findAccountsForExport:24` full hydration, no limit; correlated EXISTS in `summarize` (:162) | Export streaming safe; PAR-30 depends on B2 | **A8** | **Done** |
| F-N1 product version bump unverified | **Confirmed gap** | V104 grants tenant SELECT, no RLS (→ B6); version-bump-on-edit has no regression test | Safe (test + fix if broken) | **A9** | **Done** |
| F-T4 no cross-tenant IDOR tests | **Confirmed** | No parameterized negative suite over the LSP surface | Safe (tests only) | **A10** | Open |
| F-S2 global borrower bank mutation | **Confirmed** | `BorrowerBankDetailsService.updateBankDetailsForLsp` mutates the shared row | **Decided: snapshot at approval** | **B1** | Deferred 2026-07-15 (S5) |
| F-Q1/F-Q2 dashboard + alert full scans | **Confirmed** | `LoanAccountRepository.findHomeDashboardAccountSnapshots:123`, `findHomeDashboardPriorityAccounts:179`; `AlertRuleEvaluationWorker:207-213` loads all `UNDER_REPAYMENT` + per-loan delinquency summary | **Decided: KPI snapshots + set-based alerts** | **B2** | **Partial** |
| F-S1/F-S12 plaintext PII + webhook secret | **Confirmed** | Schema-level; no crypto anywhere | **Decided: env-injected AES-GCM now** | **B3** | Open |
| F-Q8 retention gaps | **Confirmed** | `RefreshTokenRepository.deleteByExpiresAtBefore` has zero callers; only `IdempotencyRecordRetentionWorker` exists | **Decided: operational purges + partition plan; audit deletion blocked on compliance sign-off** | **B4** | **Partial** |
| F-Q7 audit explorer guardrails | **Confirmed** | `AuditExplorerRepository:64-82` regex-over-jsonb LSP filter; optional time bounds; OFFSET+COUNT | **Decided: full guardrails** | **B5** | **Done** |
| F-T1/F-T2/F-N1 tenant grants without RLS | **Confirmed** | `V41__tenant_isolation_rls.sql:199-201` — table-wide SELECT on `lsp`/`loan_product`/`app_role`; V104 same for `loan_product_version`; audit/ops tables rely on absence-of-grant | **Decided: additive RLS policies** | **B6** | Open |
| F-Q9 per-request auth queries | **Confirmed** | `JwtSecurityBeans:89` + `:130` duplicate `findByUsername`; `SecurityConfig:45` third path | **Decided: dedupe + 30s TTL cache** | **B7** | **Done** |
| F-Q10 unbounded list fallbacks | **Confirmed + FE dependency found** | `BorrowerDirectoryService:83` `PageRequest.of(0, Integer.MAX_VALUE)`; `PaginationResponseBuilder:47`; ops cap `@Max(1000)` at `LoanApplicationOpsController:119`. **Frontend borrowers list currently relies on the unbounded fallback** — backend-only cap would truncate it | **Decided: coordinated FE+BE fix** | **B8** | **Done** |
| F-S4 dual-written status history | **Confirmed** | `LoanApplicationStatusWriter` saves both a transition row and an audit-event row | **Decided: consolidate into audit_event** | **B9** | Open |
| F-S16 default tenant-role password | **Confirmed** | `application.yml:22` and `:120` — `${APP_TENANT_DATASOURCE_PASSWORD:lms_tenant_app_password}` | **Decided: fail-fast + rotate** | **B10** | **Partial** |
| #201 pool sizing / timeouts | **Confirmed** | Only `application-local.yml` sets Hikari (max 5, idle 300000); no prod sizing, no statement timeouts | **Decided: implement** | **B11** | Open |
| F-S15 environment schema drift | **Confirmed** | Version gaps V63/V69/V91; V85/V86 comments admit Supabase divergence | **Decided: reconcile + CI validate** | **B12** | **Done** |
| F-Q5/F-S9/F-Q3 disbursement in-tx, no unique ref, no claim | **Confirmed** | `LoanDisbursementCommandService:163` provider call inside `@Transactional`; V98 `tran_ref_no` has no unique index; worker `findByStatus` unbounded, no SKIP LOCKED | **Decided: defer to ICICI project** | **C1** | Deferred |
| F-S10 no bounce/reversal model | **Confirmed** | `LoanPaymentStatus` has no BOUNCED/REVERSED; no rollback command | **Decided: design with ICICI/NACH** | **C2** | Deferred |
| F-D1/F-D2/F-D5 PAN unmasked; write-side JSON PII | **Confirmed** | Full PAN at `LspLoanApplicationResponses:36,98`; `PanMasking` applied in MIS only | **Decided: defer pending partner consultation** | **C3** | Deferred |
| F-D4 storageKey/fileChecksum on ops responses | **Confirmed but load-bearing** | `LoanApplicationOpsApiTypes:322-323`; admin frontend `loan-applications/api-tabs.ts:189-195` actively renders/uses both | **Decided: keep (accepted as-is)** | **C4** | Accepted |
| F-Q6 idempotency execute-before-claim | **Verified FIXED** | `LspApiIdempotencyService` claims PENDING row first, action after, release on failure | Fixed — no action | **D** | Fixed |
| F-S3, F-S5, F-S6, F-S13, F-Q12, F-T3 | Confirmed, accepted by report | — | No change needed | **D** | Accepted |

---

## 2. Migration number reservations

Numbers below were **reservations relative to V104** at spec time. At implementation, renumber to the next free version; the *relative order* matters (noted per item).

| Reserved | Contents | Spec | Applied |
|---|---|---|---|
| V105 | `loan_repayment_schedule_installment.entity_version` | A1 | **Yes** — `V105__loan_repayment_schedule_installment_entity_version.sql` |
| V106 | 4× `TIMESTAMP` → `TIMESTAMPTZ` conversions | A4 | **Yes** — `V106__audit_timestamp_columns_timestamptz.sql` |
| V107 | `report_access_audit.lsp_id` + backfill + index | B5 | **Yes** — `V107__report_access_audit_lsp_id.sql` |
| V108 | `loan_account` beneficiary snapshot columns + backfill | B1 | **Skipped / deferred 2026-07-15** (S5; use next free Flyway id on resume — **V114+**; V113 is S19 relationship) |
| V109 | `portfolio_kpi_snapshot` table | B2 | **Yes** — `V109__portfolio_kpi_snapshot.sql` |
| V110 | Additive RLS policies + enable-RLS-no-policy backstops | B6 | No |
| V111 | `lsp_webhook_subscription` child table + CSV backfill | A6 | No |
| V112 | `borrower.pan_hash` / encrypted-column widening | B3 | No |

Retention purges (B4) and config changes (B10/B11) need no migrations. Partitioning migrations are **not** reserved — B12 reconciliation is **complete**, so partition DDL (B4) and V110 (B6) may proceed once pre-checks pass.

---

# Part A — Implementation specs (no decision required)

> **Legend:** section titles marked **✅ DONE** are implemented as of 2026-07-06 unless noted.

## A1 · F-S7 — Installment row locking + `@Version` (P0) — **✅ DONE**

**Problem.** Two payments with *different* idempotency keys targeting the same EMI both pass `resolveTargetInstallment` (status ≠ PAID) and amount validation against the same snapshot, both insert payment rows (different keys — both succeed), both run `applyFullInstallmentPayment` and save. No `@Version`, no row lock → the second flush silently overwrites the first. Result: two `RECEIVED` rows totalling 2× EMI, installment showing 1× paid. V65 CHECKs can't catch it (each write is individually consistent). The `synchronized(idempotencyKey.intern())` guard is same-key and single-JVM only.

**Changes.**

1. **Entity** — `backend/src/main/java/com/bhawana/lms/domain/LoanRepaymentScheduleInstallment.java`: add the exact idiom used at `LoanApplication.java:92-94`:
   ```java
   @Version
   private long entityVersion;
   ```
   plus the matching getter (`LoanApplication.java:203`). The snake_case naming strategy maps it to `entity_version` — no `@Column` needed if `LoanApplication` doesn't use one (mirror whatever it does).

2. **Migration V105** (follow V37's comment style — V37 added `entity_version` to the other hot entities):
   ```sql
   ALTER TABLE loan_repayment_schedule_installment
       ADD COLUMN entity_version BIGINT NOT NULL DEFAULT 0;
   ```

3. **Repository** — `LoanRepaymentScheduleInstallmentRepository.java`: add locking variants for **both** lookup shapes used by `resolveTargetInstallment` (read the method first — it resolves by installment id and/or by `(loanAccountId, installmentNumber)`):
   ```java
   @Lock(LockModeType.PESSIMISTIC_WRITE)
   @Query("select i from LoanRepaymentScheduleInstallment i where i.id = :id")
   Optional<LoanRepaymentScheduleInstallment> findByIdForUpdate(@Param("id") UUID id);

   @Lock(LockModeType.PESSIMISTIC_WRITE)
   @Query("select i from LoanRepaymentScheduleInstallment i where i.loanAccount.id = :loanAccountId and i.installmentNumber = :installmentNumber")
   Optional<LoanRepaymentScheduleInstallment> findByAccountAndNumberForUpdate(@Param("loanAccountId") UUID loanAccountId, @Param("installmentNumber") int installmentNumber);
   ```

4. **Service** — `resolveTargetInstallment` (`LoanServicingSupportService:153-175`) has exactly **one** production caller: `LoanRepaymentCommandService:200`, inside the payment transaction. Either switch the lookups inside `resolveTargetInstallment` to the locking variants, or (if any read-only caller exists in tests/tooling) add `resolveTargetInstallmentForUpdate` and call it from the command service. The lock **must** be acquired in the same transaction that validates the amount and applies the allocation — i.e., before `validateExactInstallmentAmount` runs.

**Semantics after fix.** Second concurrent different-key payment blocks on the row lock; when it acquires, it re-reads the installment as PAID and fails the existing business validation (whatever status/amount error that path already produces — do not invent a new error shape). `@Version` is the backstop for any path that mutates an installment without the lock.

**Locking notes.** `PESSIMISTIC_WRITE` = `SELECT … FOR UPDATE`; RLS EXISTS policies on the installment table apply to tenant-scoped reads, but the payment path runs under admin scope via `AdminScopedTransactionExecutor` — no policy interaction. Keep lock scope minimal: lock the installment only, never the account list.

**Tests.**
- Testcontainers IT (base: `PostgresDataJpaTestSupport` or the closest existing payment IT pattern): two threads, `CountDownLatch`, different idempotency keys, same installment → assert exactly one success, one business rejection, installment `paid_amount` = 1× EMI, exactly one `RECEIVED` row.
- Hibernate `ddl-auto: validate` in the IT proves V105 ↔ entity agreement.
- Existing repayment suites re-run (same-key replay unchanged).

**Blast radius.** 1 entity, 1 repo, 1-2 service methods, 1 migration. No API shape change. `SyntheticPortfolioSeedService` and `deleteByLoanAccountId` unaffected (bulk ops bypass versioning).

## A2 · F-S8 — Payment claim + allocation in one transaction (P1) — **✅ DONE**

**Problem.** `IdempotencyClaimService.claimLoanPaymentRow` (`:38-42`) runs `saveAndFlush` in a `REQUIRES_NEW` TransactionTemplate. The payment row **commits** before the outer transaction allocates it. If allocation or webhook enqueue then fails, the outer tx rolls back but the committed row survives as `RECEIVED` with `unallocated_amount = amount` — money on the book applied to nothing, and the partner's retry replays the stored row believing posting succeeded.

**Design.** Single transaction, ordered:

1. Begin tx (existing `@Transactional` on the command path).
2. **Lock installment** (A1).
3. Pre-check existing payment by idempotency key (existing replay/fingerprint logic — unchanged order).
4. Persist payment row via plain `saveAndFlush` **in the same tx** (drop the `REQUIRES_NEW` template for this path only).
5. Allocate to installment, save, enqueue webhook.
6. Commit — row and allocation are atomic.

**Duplicate-key recovery.** The unique constraint `uk_loan_payment_transaction_idempotency_key` (V92) still provides cross-node same-key dedupe. In Postgres a unique violation **aborts the transaction** — recovery cannot run inside it. Extend `recordPaymentTransactionWithRecovery` in `LoanRepaymentCommandService` (which already catches `ObjectOptimisticLockingFailureException` outside the failed tx) to also catch `DataIntegrityViolationException` whose root cause names that constraint: re-read the committed row in a fresh transaction, run the existing fingerprint check (key reuse with a different payload must still fail exactly as today), and replay. The catch must sit **outside** the `@Transactional` boundary — verify proxy placement (self-invocation of a `@Transactional` method won't start a tx).

**Do not touch** `claimLspApiIdempotencyRecord` / `claimAdminApiIdempotencyRecord` — their `REQUIRES_NEW` is the deliberate claim-before-execute pattern (F-Q6, verified fixed).

**Behavioral contract (test each):**
- (a) Same-key replay and same-key concurrent post behave exactly as today (existing tests must pass unmodified).
- (b) Forced failure between claim and allocation → **zero** payment rows (orphan regression test: stub webhook enqueue to throw; assert empty table).
- (c) Different-key same-EMI concurrency → covered by A1's IT.
- Interaction with A3: the service-level `DataIntegrityViolationException` catch runs before the web-layer handler; the global handler must never see the payment-path duplicate (add an assertion to the IT that the response is a replay, not a 409-from-handler).

## A3 · F-D3 — `DataIntegrityViolationException` handler + `@Size` caps (P0) — **✅ DONE**

**Problem.** `GlobalExceptionHandler` (working-tree version — re-verified after the uncommitted refactor) has no handler for `DataIntegrityViolationException`. Unique races (borrower PAN race after onboarding's 3 retries), FK violations, and column-length overflows all surface as 500s — paging on-call for client-input conditions. Two DTO gaps let over-length input reach the DB: `externalLoanId` (`LoanApplicationOpsApiTypes:39`) and `lspLoanId` (`LspLoanApplicationApiController:546`) are `@NotBlank`-only against `VARCHAR(128)`.

**Handler spec** (in `GlobalExceptionHandler`, following its existing envelope/error-code conventions exactly):

```java
@ExceptionHandler(DataIntegrityViolationException.class)
```

Unwrap the root `org.hibernate.exception.ConstraintViolationException` → `getConstraintName()` (lowercase, strip quotes) and the SQLState:

| Signal | Response | Error code |
|---|---|---|
| `uk_loan_payment_transaction_idempotency_key` | 409 | `IDEMPOTENCY_KEY_CONFLICT` (reuse the code the payment path already emits for key conflicts — do not invent a second vocabulary) |
| `uk_borrower_pan` | 409 | `BORROWER_PAN_CONFLICT` |
| `uk_loan_application_lsp_external` | 409 | `DUPLICATE_EXTERNAL_LOAN_ID` |
| any other unique violation (SQLState `23505`) | 409 | `CONFLICT` |
| check violation (`23514`) or string truncation (`22001`) | 400 | `VALIDATION_FAILED` |
| anything else (e.g. FK `23503`) | 500 (current behavior, with correlation id) — FK violations here indicate bugs, not client input | unchanged |

Log the constraint name at WARN for the 4xx mappings (support triage), keep ERROR for the 500 path.

**`@Size` caps** (match Appendix D of the audit): `externalLoanId` and `lspLoanId` `@Size(max = 128)`; `fullName` `@Size(max = 255)` on both LSP and ops create DTOs; `sourceChannel` `@Size(max = 64)`. Sweep every `@NotBlank String` on create/patch DTOs against its DB column width and cap the rest in the same pass.

**Contract artifacts.** `@Size` changes alter the OpenAPI schema: run `OpenApiContractExportTest`, regenerate `openapi/openapi.json`, and regenerate the frontend `generated/schema.ts` with the repo's existing codegen script. Note both files already carry uncommitted changes — regenerate on top, don't hand-edit.

**Tests.** MockMvc: duplicate PAN → 409 + code; 129-char `externalLoanId` → 400 (bean validation, before DB); a synthetic unknown unique violation → 409 `CONFLICT`; assert existing error-envelope shape (the handler must match the working tree's current envelope, which changed in the uncommitted batch).

## A4 · F-S14 — Convert 4 naive `TIMESTAMP` columns to `TIMESTAMPTZ` (P2) — **✅ DONE**

Exactly four columns (verified in V78/V82/V88; every other table uses `TIMESTAMPTZ`):

```sql
-- V106
ALTER TABLE borrower_bank_details_update_audit  ALTER COLUMN created_at TYPE TIMESTAMPTZ USING created_at AT TIME ZONE 'UTC';
ALTER TABLE loan_disbursement_bank_mismatch_log ALTER COLUMN created_at TYPE TIMESTAMPTZ USING created_at AT TIME ZONE 'UTC';
ALTER TABLE disbursement_outcome_audit          ALTER COLUMN created_at TYPE TIMESTAMPTZ USING created_at AT TIME ZONE 'UTC';
ALTER TABLE webhook_outbox_redrive_audit        ALTER COLUMN created_at TYPE TIMESTAMPTZ USING created_at AT TIME ZONE 'UTC';
```

Data is Hibernate-written UTC `Instant`s, so `AT TIME ZONE 'UTC'` is lossless. Each column participates in a composite index (`(x, created_at DESC)`) — Postgres rebuilds them as part of the ALTER; tables are small today, so no locking concern. Java side is already `Instant` — no code change.

**Test.** Extend the `SchemaJsonColumnsPostgresTest` pattern: query `information_schema.columns` and assert `data_type = 'timestamp with time zone'` for all four — this guards against the migration silently no-opping on a drifted environment (the V29/V34 failure mode).

## A5 · F-Q11 — Delete dead unbounded repository methods (P2) — **✅ DONE**

Delete (zero callers verified; compilation is the proof):
- `LoanAccountRepository.findDetailedBy()` (:58) — all-accounts fetch with a 7-path EntityGraph
- `LoanApplicationRepository.findDetailedByOrderByCreatedAtDesc()` (:37)
- `LoanApplicationRepository.findAllByOrderByCreatedAtDesc()` (:42)

Do **not** touch `OpsAlertRepository.findAllByOrderByCreatedAtDesc(Pageable)` — same name, paged, used by `OpsAlertService:113`. Both repository files carry uncommitted changes; delete only these methods.

## A6 · F-S11 — Normalize `lsp.webhook_event_types` CSV to a child table (P2) — **OPEN**

**Problem.** `VARCHAR(500)` CSV parsed by `Lsp.getWebhookEventTypes()` via `split(",")` (`Lsp.java:152`). V99 already had to repair it with `replace()` string surgery after a phantom enum value broke subscriptions; the 500-char cap will silently truncate as the event vocabulary grows (11 event types today).

**Migration V111:**
```sql
CREATE TABLE lsp_webhook_subscription (
    lsp_id     UUID        NOT NULL REFERENCES lsp (id) ON DELETE CASCADE,
    event_type VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (lsp_id, event_type)
);
-- Backfill from the CSV — same pattern V75 used for document types:
INSERT INTO lsp_webhook_subscription (lsp_id, event_type)
SELECT id, trim(t) FROM lsp, unnest(string_to_array(webhook_event_types, ',')) AS t
WHERE webhook_event_types IS NOT NULL AND trim(t) <> ''
ON CONFLICT DO NOTHING;
```
Enum membership stays app-validated (consistent with Appendix C convention — no DB enums anywhere).

**Code.** Map as `@ElementCollection` on `Lsp` (the `Borrower.visibleLspIds` pattern) or a small entity. Rewire: `Lsp.getWebhookEventTypes()`, the subscription-update path in `LspAdminController`/`LspDirectoryService`, and the outbox enqueue filter in `WebhookOutboxService`. Keep the frontend's dot-string↔enum mapping (`lsps/api.ts`) untouched — the API contract (list of event-type strings) does not change.

**Rollout.** Single deploy (monolith): migration backfills, code reads the child table. Keep writing the CSV column in parallel for one release as a rollback hedge; a follow-up migration drops the column. **Regression guard:** re-run the V99-related webhook subscription tests plus an enqueue test proving a subscribed event dispatches and an unsubscribed one doesn't.

## A7 · F-S17 — LAZY fetch on money-graph associations (P2) — **✅ DONE**

Flip to `FetchType.LAZY`:
- `LoanRepaymentScheduleInstallment.loanAccount` (:26)
- `LoanPaymentTransaction.loanAccount` (:26)
- `LoanPaymentTransaction.repaymentInstallment` (:30)
- `LoanAccount.loanApplication` (`@OneToOne`, :27) — **report correction:** this is the FK-owning side with `optional = false`, so Hibernate can proxy it without bytecode enhancement; it *can* go LAZY.

Method: flip all four, run the **full** backend suite, and fix each `LazyInitializationException` by adding `@EntityGraph`/`join fetch` at the specific query — never by flipping back. The audit notes the codebase already uses explicit fetch graphs where the graph is truly needed; expect the webhook payload builders (`LoanWebhookPayloads`) and dashboard/MIS paths to be the likely fix sites. Do this **after** A1/A2 land (same files; avoid entangled diffs).

## A8 · F-Q4 — Streaming MIS export (P1, export half only) — **✅ DONE**

**Problem.** `findAccountsForExport` (`PortfolioMisReadRepository:24`) hydrates every matching account with 4 join-fetches into a heap list, then `AdminReportingService` builds the CSV in memory.

**Spec.** Keyset-batched export: loop `WHERE id > :lastId ORDER BY id LIMIT 1000` (projection, not entity hydration — select exactly the CSV columns), stream each batch through `PortfolioMisCsvWriter` into the R2 upload stream via the existing async `report_request` infrastructure. Memory becomes O(batch). No API change — the report flow already returns a `storage_key`.

**Out of scope here:** the `summarize` correlated-EXISTS PAR-30 (replace with a join against B2's snapshot once that lands — cross-ref B2). **Test:** byte-identical CSV vs the current implementation on seeded data; plus a 3-batch boundary test (2,500 rows).

## A9 · F-N1 — Product-version bump regression guard (P2) — **✅ DONE**

V104 makes `loan_product_version` the contractual snapshot; servicing reads `application.getLoanProductVersion().getInterestRate()`. Nothing proves admin product edits create a new version.

**Spec.** Read `ProductConfigurationService`'s update path. Add a regression test: PATCH interest rate on a `loan_product` → assert a new `loan_product_version` row (`version_number` incremented), new applications reference the new version id, existing applications keep the old id. **If the bump is missing, that is a live P1 bug** — fix it in the same change (create-version-on-term-change inside the product update transaction) and say so in the PR.

## A10 · F-T4 — Cross-tenant IDOR negative test suite (P3) — **OPEN**

Parameterized integration test over every LSP-surface endpoint that takes a resource id (application detail + status, loan detail + schedule, borrower bank details, documents, foreclosure quote, payments). Setup: two LSPs with one borrower/application/account each. For each endpoint: LSP A's token requesting LSP B's resource id. Assert the **existing** not-found convention (verify what the interceptor + services return today — expected 404, and it must be indistinguishable from a nonexistent id so the endpoint isn't an existence oracle). Tests only; any failure it finds is a P0 to escalate, not silently fix.

---

# Part B — Decided items: decision record + implementation spec

## B1 · F-S2 — Beneficiary bank snapshot at approval — **DECIDED: implement** — **DEFERRED 2026-07-15**

**Status update (2026-07-15):** Spec remains valid (superseded in wording by production-report Spec **S5** / DATA-01) but is **not scheduled** for the current implementation pass. Owner accepted residual risk under PAN dedupe + single active loan. Canonical deferral: `docs/deferred-implementation.md`. Resume before real-money rails. Planned migration ID at original deferral was **V113**; that id is now used by Spec S19 — resume S5 on **V114+**.

**Decision (2026-07-05):** Snapshot disbursement-critical bank details onto the loan at approval. Borrower stays global (identity dedupe is a business requirement); per-relationship bank details rejected as over-scoped.

**Spec.**
- **Migration V108:** `loan_account` gains `beneficiary_account_number VARCHAR(64) NOT NULL`, `beneficiary_ifsc VARCHAR(11) NOT NULL`, `beneficiary_snapshot_at TIMESTAMPTZ NOT NULL` — backfilled from the borrower's current values for existing accounts (with `beneficiary_snapshot_at = now()`; these are mock-era accounts, so backfill fidelity is acceptable — note it in the migration comment).
- **Write path:** wherever the `loan_account` is created on approval (trace from the approval command through `LoanApplicationLifecycleService`), copy the borrower's bank fields at that instant.
- **Read path:** `DisbursementPreflightValidator` and the disbursement command's beneficiary construction switch from `borrower.getBankAccountNumber()`/IFSC to the account snapshot. The bank-mismatch log comparison becomes snapshot-vs-provider.
- **Explicitly unchanged:** `BorrowerBankDetailsService.updateBankDetailsForLsp` still updates the global row (future originations use new details); `borrower_bank_details_update_audit` unchanged. A PATCH after approval no longer affects that loan's disbursement — this is the point of the fix.
- **Tests:** approve → PATCH bank details → disburse → assert money targets the snapshot values and the PATCH audit still recorded; preflight validates against snapshot.
- **Sequencing:** must land **before** the ICICI integration (C1) — the intent-row design will consume the snapshot fields.

## B2 · F-Q1 + F-Q2 — KPI snapshot tables + set-based alert rules — **DECIDED: implement** — **PARTIAL**

**Remaining gap:** backend exposes `dataAsOf` on the dashboard API; the home page UI does not render the stamp yet (GitHub #212).

**Decision (2026-07-05):** Implement per existing issues #211/#212. Dashboard serves snapshots with a "data as of" stamp; alert engine becomes set-based and single-instance.

**Spec.**
- **Migration V109:** `portfolio_kpi_snapshot(id UUID PK, lsp_id UUID NULL REFERENCES lsp(id) — NULL = global row, computed_at TIMESTAMPTZ NOT NULL, total_disbursed NUMERIC(19,2), total_outstanding NUMERIC(19,2), total_overdue NUMERIC(19,2), status_counts JSONB NOT NULL, dpd_buckets JSONB NOT NULL, avg_approval_tat_hours NUMERIC(10,2))`, index `(lsp_id, computed_at DESC)`. JSONB for the bucket/status maps keeps the schema stable as vocabularies evolve (object-type CHECK per V72 convention).
- **Worker:** one `@Scheduled` job (15-min cadence), gated to a single instance — use a Postgres advisory lock (`pg_try_advisory_lock`) around the run so multi-pod deploys stay safe regardless of the D1 topology decision. Computes global + per-LSP rows in set-based SQL (one aggregate over accounts joined to the overdue partial index `idx_installment_overdue_lookup`; TAT as a single windowed `GROUP BY` over the 30-day transition window — this replaces the N+1 in `computeAvgApprovalTatHours`).
- **Dashboard:** `HomeDashboardService.getSummary` reads the latest snapshot rows only; response adds `dataAsOf` (additive field — frontend renders the stamp). The live-aggregate repository methods (`findHomeDashboardAccountSnapshots`, `findHomeDashboardPriorityAccounts`) are deleted once unused. Priority-accounts list becomes a bounded indexed query (top-N by DPD from the snapshot job or a LIMITed live query — implementer's choice, must be bounded).
- **Alerts:** each rule in `AlertRuleEvaluationWorker` becomes one bounded SQL query returning violating ids (DPD-bucket transitions join `loan_delinquency_state` and update it set-based; stuck-disbursement and stale-intake become status+age predicates with LIMIT). Per-loan Java evaluation (`getLoanDelinquencySummary` in a loop, `:207-213`) is deleted. Scheduler wrapped in the same advisory-lock gate. Insert-time dedupe retained.
- **Tests:** snapshot math vs a brute-force in-test computation on seeded data; alert parity test (same seeded book produces the same alerts as the old path — write it against the old engine *first*, then swap); advisory-lock exclusion test (second concurrent run no-ops).

## B3 · F-S1 + F-S12 — PII encryption with env-injected AES-GCM — **DECIDED: implement** — **OPEN**

**Decision (2026-07-05):** Application-level AES-GCM, key injected from env/secret store with a documented rotation procedure. Cloud KMS deliberately deferred; design must allow swapping the key provider without re-encrypting from scratch. Scope: `borrower.aadhar_number`, `borrower.bank_account_number` encrypted; `borrower.pan` → HMAC hash column + encrypted value; `lsp.webhook_signing_secret` encrypted. JSON-blob PII is **not** retro-encrypted (write-side masking is C3-deferred — record the residual risk).

**Spec.**
- **Crypto envelope:** `v1:<base64(iv || ciphertext || tag)>` — key-version prefix enables rotation (old key retained read-only until a background re-encrypt completes). AES-256-GCM, random 12-byte IV per value, key from `APP_CRYPTO_KEY` (base64, 32 bytes) + `APP_CRYPTO_HMAC_KEY` (separate key for PAN HMAC). Startup fails if `app.crypto.enabled=true` and keys are absent/malformed; `enabled=false` only permitted in the `local` profile.
- **Mechanism:** JPA `AttributeConverter` (`EncryptedStringConverter`) on the three encrypted fields — transparent to services and DTOs (they see plaintext post-decrypt; response masking is a separate, deferred concern). PAN adds `pan_hash VARCHAR(64)` (hex HMAC-SHA256): `findByPan(pan)` → `findByPanHash(hmac(pan))`; the one-open-loan rule and dedupe run on the hash.
- **Migration V112 + rollout (zero-downtime):**
  1. V112: add `pan_hash` (nullable), widen `aadhar_number`/`bank_account_number`/`pan`/`webhook_signing_secret` to `VARCHAR(512)` (ciphertext > plaintext width; V74's width-tightening comment already anticipated this reversal).
  2. Deploy dual-read code: converter decrypts values with the `v1:` prefix, passes legacy plaintext through; writes always encrypt; `findByPan` ORs hash and plaintext lookups during the window.
  3. Batch backfill job (500 rows/tx): encrypt in place + populate `pan_hash`.
  4. Follow-up migration: `pan_hash` NOT NULL + UNIQUE (replacing `uk_borrower_pan` as the dedupe constraint — keep `uk_borrower_pan` until this step), drop plaintext-tolerant read path.
- **Interaction checks (all verified relevant):** mock-disbursement IFSC scenario markers (`MOCK0*`) live on `ifsc_code`, which stays plaintext — mock flows unaffected. MIS export currently emits raw bank values by deliberate policy — converter-decrypted values keep that behavior. Aadhaar `@Pattern ^[0-9]{12}$` validation runs on DTOs pre-encryption — unaffected.
- **Tests:** converter round-trip; backfill idempotence (re-run on half-encrypted data); PAN dedupe via hash (same PAN, two LSPs — the existing cross-LSP test); key-rotation read (v1-encrypted value readable after key added as historical); startup-fail test for missing key.
- **Runbook (ship with the PR):** key generation, storage location per environment, rotation procedure, and the explicit statement that historical JSON blobs (`intake_audit`, `disbursement_request_log`, idempotency `response_body`, `ops_alert.context_json`) still hold plaintext PII bounded only by B4 retention where applicable.

## B4 · F-Q8 — Retention: operational purges now; partition plan; audit deletion blocked — **DECIDED** — **PARTIAL**

**Shipped:** `IdempotencyRecordRetentionWorker` (LSP + admin idempotency tables).  
**Not yet shipped:** `RefreshTokenRetentionWorker`, webhook artifact purges, monthly partition DDL (compliance sign-off still required for audit deletion).

**Decision (2026-07-05):** Purge operational tables now (refresh tokens, webhook delivery artifacts). Audit streams get monthly range partitions but **nothing is deleted** until a compliance sign-off defines the archival window (RBI norms ≥5–8yr).

**Spec — purges (now):**
- `RefreshTokenRetentionWorker` (clone the `IdempotencyRecordRetentionWorker` pattern: `@Scheduled(fixedDelayString = "${app.retention.refresh-token-purge-fixed-delay-ms:3600000}")`): call the existing-but-never-called `RefreshTokenRepository.deleteByExpiresAtBefore(now − 30d)`. The 30-day grace preserves "token expired" (vs "invalid") error semantics near expiry.
- Webhook artifacts, same worker or sibling: `webhook_event_delivery_attempt` older than 90d; `webhook_event_outbox` in terminal DELIVERED older than 30d. Terminal FAILED rows are retained 90d (redrive window), and `webhook_outbox_redrive_audit` is **not** purged (it's an audit stream). Delete attempts before their outbox rows (FK order). Batch deletes (`LIMIT` loops) to avoid long locks.
- Config namespace `app.retention.*` with purge-enabled flags defaulting on, mirroring `app.idempotency.*`.
- **Tests:** cutoff boundary tests per table; FK-order test; worker-disabled flag test.

**Spec — partition plan (sequenced after B12):** monthly range partitions on the six append-heavy streams (`loan_application_audit_event`, `loan_application_status_transition` — until B9 retires it, `loan_application_intake_audit`, `auth_event_audit`, `webhook_event_delivery_attempt`, both idempotency tables), via table-swap migrations in a maintenance window. Blocked behind B12 because partition DDL on a drifted schema is exactly how V64-class silent no-ops happen. Compliance sign-off item recorded in §10.5 of the audit remains open — no drop-partition automation until it's answered.

## B5 · F-Q7 — Audit explorer guardrails — **DECIDED: full guardrails** — **✅ DONE**

**Decision (2026-07-05):** Mandatory time window, keyset pagination, indexed `lsp_id` on `report_access_audit`. Internal admin surface; FE updated in the same pass.

**Spec.**
- **Migration V107:** `ALTER TABLE report_access_audit ADD COLUMN lsp_id UUID NULL REFERENCES lsp(id)`; one-time backfill extracting `filter_payload->>'lspId'` (jsonb operator, not the regex); index `(lsp_id, created_at DESC)`. Write path sets the column going forward. Delete the regex expression builders (`AuditExplorerRepository:64-82`).
- **API:** `since`/`until` become effectively mandatory — defaults `until = now`, `since = until − 7d`; reject windows > 90d with 400 `VALIDATION_FAILED`. Pagination switches to keyset: cursor = `(occurred_at, stream, native_id)` encoded opaque string; response carries `nextCursor`; the whole-union `COUNT(*)` is removed (the FE shows "load more" instead of total pages).
- **Frontend:** audit explorer page — default 7d window in the filter bar, cursor-based "load more", drop total-count display.
- **Tests:** window default/cap; cursor stability across page boundaries (including ties on `occurred_at`); REPORT_ACCESS LSP filter via the new column returns identical rows to the old regex on backfilled data (parity test before deleting the regex).

## B6 · F-T1 + F-T2 + F-N1 — Additive RLS policies on catalog tables — **DECIDED** — **OPEN** (unblocked — B12 complete)

**Decision (2026-07-05):** Additive policies; no grants revoked. Pre-check Supabase ownership first.

**Spec.**
- **Pre-check (blocking):** on Supabase prod, confirm the admin/migration role **owns** the tables (owners bypass RLS unless FORCE) and that `lms_tenant_app` is not the owner of anything. Script: `SELECT tablename, tableowner FROM pg_tables WHERE schemaname='public'`. If ownership deviates, stop and reconcile under B12 first.
- **Migration V110:**
  - `lsp`: `ENABLE ROW LEVEL SECURITY` + policy `FOR SELECT TO ${tenant_app_role} USING (id = app_current_lsp_id())` — a tenant connection reads only its own row (and therefore only its own `webhook_signing_secret`, which after B3 is ciphertext anyway).
  - `loan_product` and `loan_product_version`: policy `USING (EXISTS (SELECT 1 FROM loan_product_lsp_mapping m WHERE m.loan_product_id = <table>.loan_product_id-or-id AND m.lsp_id = app_current_lsp_id()))` — catalog visible only where mapped.
  - `app_role`: leave as-is (auth runs on the admin pool; revisit only if a tenant-thread read appears).
  - Enable-RLS-with-no-policy backstop on tenant-irrelevant tables currently protected only by absence-of-grant: `refresh_token`, `auth_event_audit`, `lsp_audit_event`, `borrower_bank_details_update_audit`, `loan_disbursement_bank_mismatch_log`, `disbursement_outcome_audit`, `report_access_audit`, `webhook_event_delivery_attempt`, `webhook_outbox_redrive_audit`, `ops_alert`, `alert_rule`, `lsp_api_ip_allowlist`, `lsp_ui_ip_allowlist`, `loan_delinquency_state`.
- **Tests:** extend `TenantIsolationPostgresIntegrationTest`: tenant connection sees exactly one `lsp` row (its own); cannot read another LSP's signing secret; sees only mapped products/versions; LSP product listing and loan-application servicing responses (which read `loan_product_version` on tenant threads) still work. Then the **full** backend suite — this is the change most likely to surface a forgotten tenant-thread read; any failure means a real dependency was found and must be routed through admin scope explicitly, not fixed by widening the policy.
- **Risk note:** V45's loud-fail design (hard `::UUID` cast on unbound GUC) is preserved — none of these policies may soften that.

## B7 · F-Q9 — Auth lookup dedupe + 30s principal cache — **DECIDED** — **✅ DONE**

**Decision (2026-07-05):** Merge the duplicate per-request lookups **and** add a 30s TTL cache. Revocation latency ≤30s is a documented, accepted window.

**Spec.**
- **Dedupe:** `JwtSecurityBeans:89` (session validator) and `:130` (authorities converter) each call `findByUsername` per request. Restructure so one lookup feeds both — load once in the validator, expose via a request-scoped holder (or merge validation+authority derivation into one component). `SecurityConfig:45`'s `UserDetailsService` is the password-login path (per-login, not per-request) — leave it.
- **Cache:** keyed by username → record `(tokenVersion, status, lockedUntil)`; TTL 30s; same for `ApiClientJwtSessionValidator.validate`'s `findByClientId` keyed by clientId. Use Caffeine if already on the classpath, otherwise a bounded `ConcurrentHashMap` with timestamp eviction — no new dependency without checking.
- **Invalidation:** every mutation that bumps `token_version` or changes status/lockout must evict: `UserAdminService` (password reset, status toggle, session revoke), the lockout services (`ApiClientLockoutService`, app-user lockout path), `ApiClientManagementService` (rotate/revoke), and the LSP-level cascade revocation (V54/V77 semantics — LSP token-version bump must evict all cached principals of that LSP's clients/users, so index the cache or evict-all on LSP-level events; evict-all is acceptable at this cache's cost).
- **Documentation:** add the 30s revocation window to `CONTEXT.md` (or an ADR) as an accepted security property.
- **Tests:** second request within TTL hits no repo (verify with a spy); revocation evicts in-process immediately; TTL expiry re-reads; lockout takes effect ≤ TTL.

## B8 · F-Q10 — Coordinated FE+BE list caps — **DECIDED** — **✅ DONE**

**Decision (2026-07-05):** Frontend borrowers page gains pagination first; then backend caps land. No silent truncation.

**Spec.**
- **Frontend:** borrowers list adopts the existing `X-Limit`/`X-Offset`/`X-Total-Count` header pattern used by the other paginated tables (loan applications page is the reference), page size 50.
- **Backend (same deploy or after):** `PaginationResponseBuilder:47` fallback `Integer.MAX_VALUE` → `200`; audit **all** endpoints flowing through the builder for other FE callers relying on the unbounded fallback before flipping (the borrowers list is the one confirmed dependency; sweep `frontend/src/features/*/api*.ts` for list fetches that omit pagination params). `LoanApplicationOpsController:119` `@Max(1000)` → `@Max(200)` (confirm the FE never requests >200 — current pages request ≤100).
- **Tests:** BE — fallback returns 200 rows + correct `X-Total-Count` when 201 exist; FE — pagination controls render and fetch page 2.

## B9 · F-S4 — Consolidate status history into `loan_application_audit_event` — **DECIDED** — **OPEN**

**Decision (2026-07-05):** Single stream; old table retired read-only. Pre-check for external consumers required.

**Spec.**
- **Pre-check (blocking):** confirm nothing outside the repo reads `loan_application_status_transition` (BI, scripts — grep `scripts/`, ask the owner; record the answer in the PR).
- **Sequencing:** after B2, which already removes the biggest reader (`computeAvgApprovalTatHours` moves into the snapshot job).
- **Reader migration (one at a time):** remaining readers — `AlertRuleEvaluationWorker` (B2 rewrites it anyway), servicing read paths, audit-explorer APPLICATION stream branch — switch to `loan_application_audit_event WHERE action = 'STATUS_TRANSITION'`. `rejection_reason_json` content moves into the audit event's `details_json` (extend the writer; backfill not required — readers fall back to the old table for pre-cutover rows).
- **Writer:** once readers are migrated, `LoanApplicationStatusWriter.updateStatus` stops inserting transition rows. The table stays read-only for forensic continuity (the `loan_application_assignment_event` precedent); revoke its tenant INSERT/UPDATE/DELETE grants in the same migration.
- **Tests:** every migrated reader gets a parity test (old query vs new query on seeded transitions) before the writer change lands; audit-explorer APPLICATION stream returns the union of old-table history and new-stream rows across the cutover boundary.

## B10 · F-S16 — Tenant-role password fail-fast + rotation — **DECIDED** — **PARTIAL**

**Shipped:** prod `application.yml` / Flyway placeholder no longer default `lms_tenant_app_password`.  
**Not yet shipped:** startup assertion for legacy default on non-`local` profiles; ops runbook execution; `application-local.yml` still carries a dev default (intentional).

**Spec.** Remove the default from both `application.yml:22` (Flyway placeholder `tenant_app_password`) and `:120` (tenant datasource password): `${APP_TENANT_DATASOURCE_PASSWORD}` with no fallback. `application-local.yml` keeps a dev-only default so local boot still works. Add a startup assertion (an `ApplicationRunner` or `@PostConstruct` in the datasource config): if the resolved password equals the known legacy default and the active profile isn't `local`, fail with an explicit message. **Ops runbook in the PR:** `ALTER ROLE lms_tenant_app PASSWORD '<new>'` on each environment + secret-store update + coordinated restart; rotate Supabase first (it's the exposed one). **Test:** context-load test with profile `prod`-like and the default password → startup failure.

## B11 · #201 — Pool sizing + statement timeouts — **DECIDED** — **OPEN**

**Spec.** Constraint that shapes everything: the Supabase pooler caps ~15 sessions (project memory, verified during the SET ROLE incident). Defaults in `application.yml`, overridable per env:
- Admin pool `maximum-pool-size: 5`, tenant pool `maximum-pool-size: 8` (sum + Flyway headroom < 15), `connection-timeout: 5000`, `max-lifetime` below the pooler's idle cutoff.
- Timeouts via role config, not per-connection SET (session GUCs don't survive transaction-mode pooling on port 6543): migration or runbook step `ALTER ROLE lms_tenant_app SET statement_timeout = '30s'; ALTER ROLE lms_tenant_app SET idle_in_transaction_session_timeout = '60s';` and the same for the admin role with a larger statement budget (`60s`) since MIS export runs there — revisit downward after A8 lands.
- **Test/validation:** boot against local Postgres with the new defaults; assert via `SHOW` queries in an IT that role-level timeouts apply on checked-out connections.

## B12 · F-S15 — Environment reconciliation + CI validation — **DECIDED** — **✅ DONE**

**Delivered:** `scripts/schema-diff/` (reference artifact, prod diff, migrate/verify scripts), `docs/adr/0006-schema-drift-reconciliation.md`, `FlywaySchemaValidationPostgresTest` + CI `check-reference.sh`, Supabase prod reconciled to reference (V109).

**Spec.** (1) `scripts/schema-diff/` — dump `pg_dump --schema-only` from a fresh `flyway migrate` database and from Supabase prod, normalize (strip comments/ownership), diff; commit the diff artifact. (2) Record the reconciliation and the V63/V69/V91 gaps + V80/V85/V86 divergence as `docs/adr/` entry ("schema drift reconciliation"), including any manual corrective statements applied to prod. (3) CI: `flyway validate` against a migrated container in the backend pipeline. (4) Adopt the rule (same ADR): repair scripts get real in-repo version numbers **before** being applied to any environment. **This item gates all partitioning work (B4) and should precede V110's RLS migration** (policy DDL on a drifted schema is high-risk).

---

# Part C — Deferred by decision

## C1 · F-Q5 + F-S9 + F-Q3 — Disbursement rework → **DEFERRED to the ICICI integration project** (decision 2026-07-05)

Confirmed present: provider call inside `@Transactional` (`LoanDisbursementCommandService:163`, status poll at `:259`); no `UNIQUE(tran_ref_no)`; worker scans unbounded with no SKIP LOCKED claim and a racy count-based retry budget.

**Standing risk accepted:** harmless with the mock adapter; **catastrophic with a real bank**. Hard constraint recorded: **the real ICICI adapter must not be enabled until this rework ships.** (Consistent with the existing note that worker concurrency fixes gate the integration.)

**Must-include checklist when picked up (from the audit + this verification):**
1. Intent row persisted + committed (`DISBURSEMENT_REQUESTED`) with deterministic `tran_ref_no` per (loan_account, attempt) **before** any provider call.
2. Partial unique index `UNIQUE (tran_ref_no) WHERE tran_ref_no IS NOT NULL` — pre-check historical mock duplicates, scope by date if any.
3. Provider call outside any transaction; outcome recorded in a second transaction; beneficiary taken from the B1 snapshot.
4. Sweeper reconciling intents with no recorded outcome via the provider status API.
5. Worker: `FOR UPDATE SKIP LOCKED` claim, batch 25, on a status+created_at index; atomic retry counter on the intent row (issues #203/#204).
6. Crash tests: kill between intent-commit and provider call; between provider-accept and outcome-commit; duplicate `tran_ref_no` rejected by the DB.

## C2 · F-S10 — Payment bounce/reversal → **DEFERRED: design with ICICI/NACH integration** (decision 2026-07-05)

**Standing risk accepted:** collections are overstated once real NACH volume starts (5–15% bounce rates) until this lands. Design constraint recorded now: the allocation rollback **must** run under the A1 installment lock, and reversal rows must reference the original payment (`reversed_at`, `reversal_reference`, `BOUNCED`/`REVERSED` statuses). `LoanRepaymentScheduleInstallment.resetAllocation` exists as a starting point but currently has no command path.

## C3 · F-D1 + F-D2 + F-D5 — PAN masking / reveal audit / write-side JSON masking → **DEFERRED pending partner consultation** (decision 2026-07-05)

Confirmed exposure stands: full PAN on LSP list/detail (`LspLoanApplicationResponses:36,98`), ops loan surfaces, admin borrower surfaces; `PanMasking` applied in MIS only; reveal audit only on the LSP bank-details endpoint; JSON payloads store unmasked PII at write time. **Re-entry criterion:** partner consultation on masked-PAN API responses. Until then: no new full-PII surfaces may be added (review-time rule), and the V42 legacy `loan_application_pii_reveal_audit` table is retained untouched (dropping it folds into this pass). B3's encryption reduces at-rest risk but response payloads stay full-fidelity.

## C4 · F-D4 — `storageKey`/`fileChecksum` on ops document responses → **ACCEPTED AS-IS** (decision 2026-07-05)

Verification found the admin frontend **actively uses both** (`frontend/src/features/loan-applications/api-tabs.ts:189-195` — `storageKey` doubles as the file reference, checksum is rendered). Removing them breaks the Documents tab; it's an internal admin surface behind ops auth. Recorded as accepted; revisit only if the ops API is ever exposed beyond internal users. (The related `tokenVersion`-in-`detailsJson` note from the audit was not reproducible in the working tree's LSP audit responses — treat as resolved by the uncommitted refactor unless it resurfaces.)

---

# Part D — Verified fixed, accepted, and report corrections

**Verified fixed (no action):**
- **F-Q6** — LSP/admin idempotency is claim-before-execute: PENDING row inserted via `IdempotencyClaimService.claim*` first; unique-violation → poll/replay; action runs only post-claim; failure path releases the pending row. `admin_api_idempotency_record` (V103) mirrors it. Both purged by the retention worker.

**Accepted as-is (per audit, re-confirmed):**
- **F-S3** `@ElementCollection` for `visibleLspIds` — **partially superseded 2026-07-15:** Spec S19 Slice A introduced `borrower_lsp_relationship` (dual-write). Promote reads/RLS off the ElementCollection and drop it when residual cutover ships (`docs/deferred-implementation.md`).
- **F-S5** deprecated assignment columns/table + nullified `report_request.report_content` — drop in a future cleanup window.
- **F-S6** free-text actor usernames on existing audit tables — canonical format enforced at `Strings.normalizeActor`; new audit tables follow the `auth_event_audit` dual-column pattern.
- **F-S13** identity-table nits — add the `ops_alert (type, subject_id, status)` dedupe index only when alert volume warrants.
- **F-Q12** per-account payment reads — bounded; revisit with C2.
- **F-T3** RLS child-policy cost — denormalize `lsp_id` onto hot child tables only with profiling evidence.

**Report corrections found during verification:**
1. **F-S17** — `LoanAccount`'s denormalized `lsp`/`borrower`/`loan_product` `@ManyToOne`s are **already LAZY**; only the four associations listed in A7 are EAGER. Also, `LoanAccount.loanApplication` *can* be made LAZY without bytecode enhancement (FK-owning side, `optional = false`).
2. **F-D4** — the audit recommended removing `storageKey`/`fileChecksum` without noting the admin frontend consumes them; blind removal would have broken the Documents tab (→ C4).
3. **F-Q10** — the audit proposed a hard cap of 200 without noting the borrowers page depends on the unbounded fallback (→ B8's coordinated ordering).

---

## 3. Sequencing and dependencies

Original tier plan with **implementation status as of 2026-07-06**:

| Tier | Items | Rationale | Status |
|---|---|---|---|
| 1 — immediately | **A1 + A2**, **A3**, **B10**, **A5** | Money-correctness P0s + trivial hardening | **A1, A2, A3, A5 done**; B10 partial |
| 2 — next | **B11**, **A4**, **B4 purges**, **B7** | Config + small workers | **A4, B7 done**; B11 open; B4 partial (idempotency only) |
| 3 | **B12**, **B2**, **A8** | Reconciliation before policy/partition DDL; snapshots | **All done** (B2 FE stamp remains) |
| 4 | **B6**, **B5**, **B8**, **B1**, **A9** | Migrations on reconciled schema | **B5, B8, A9 done**; B6, B1 open |
| 5 | **B3**, **B9**, **A6**, **A7**, **A10** | Larger rollouts and cleanups | **A7 done**; rest open |
| ICICI project | **C1**, **C2** | Per decision | Deferred |
| Awaiting input | **C3**, audit-retention window (B4) | External dependencies | Unchanged |

**Recommended next (post-tier-3):** **B6** → **B4 purges** → **B11 + B10** → **B1** (gates ICICI/C1).

**Cross-cutting implementation rules:**
- The working tree carries a large uncommitted batch — every PR must be diffed against `outputs/db-audit-remediation/baseline-working-tree-2026-07-05.patch` to keep this remediation separable from it.
- Migration numbers in §2 are reservations; renumber to next-free at implementation time, preserving relative order (V110 after B12's reconciliation; V112's follow-up constraint migration after backfill verification).
- Any `@Size`/DTO change regenerates `openapi/openapi.json` + frontend `generated/schema.ts` via the existing tooling.
- New Postgres-dependent tests use the `PostgresDataJpaTestSupport` Testcontainers pattern; they require Docker and must not be reported as passing when skipped.
