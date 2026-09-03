## W1-A1-F01 — Tenant role can DELETE financial/audit rows

**Verdict: Downgraded** (grant is real; Critical is inflated)

**Evidence**
- V41 grants `DELETE` on `loan_payment_transaction`, `loan_disbursement_request_log`, and audit tables to `${tenant_app_role}`:

```209:213:/Users/siddhant/Desktop/lms/backend/src/main/resources/db/migration/V41__tenant_isolation_rls.sql
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE loan_application_audit_event TO %I', '${tenant_app_role}');
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE loan_application_document_access_audit TO %I', '${tenant_app_role}');
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE loan_disbursement_request_log TO %I', '${tenant_app_role}');
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE loan_repayment_schedule_installment TO %I', '${tenant_app_role}');
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE loan_payment_transaction TO %I', '${tenant_app_role}');
```

- No production HTTP `DELETE` on those tables. Only `@DeleteMapping` hits are IP-allowlist admin endpoints (`/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/web/LspIpAllowlistAdminController.java`, `LspUiIpAllowlistAdminController.java`).
- App does delete schedule installments pre-disbursement via JPA (`deleteByLoanAccountId`), not via HTTP DELETE:

```70:71:/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/LoanRepaymentScheduleService.java
        loanRepaymentScheduleInstallmentRepository.deleteByLoanAccountId(loanAccount.getId());
        loanRepaymentScheduleInstallmentRepository.flush();
```

- Money-out disbursement is admin-scoped: ops controllers use admin principals (`AuthenticationTenantScopeFilter` lines 52–57), and `disbursement_intent` has no tenant grant (`/Users/siddhant/Desktop/lms/backend/src/main/resources/db/migration/V111__disbursement_intent.sql`).
- LSP repayments use the tenant connection (`LspTenantContextInterceptor` + `AuthenticationTenantScopeFilter`), so tenant role is used for payment writes on the LSP path — not exclusively admin.
- RLS limits tenant deletes to the bound LSP (`loan_payment_transaction_tenant_policy`, V41 lines 327–331).

**Revised severity: Medium** (defense-in-depth / compromised-DB-credential scenario; within-tenant audit erasure possible via SQL, not via API)

**Why:** The grant exists, but there is no normal API exploit path. Impact requires leaked `tenant_app_role` credentials or a future bug that issues SQL `DELETE`. Pre-production mock rails further reduce financial blast radius. Critical overstates reachable risk.

---

## W1-A1-F04/F05 — Payment idempotency NULL keys; no reference uniqueness

**Verdict: Downgraded** (historical schema concern; runtime payment path is protected)

**Evidence**
- V21: `reference VARCHAR(128) NOT NULL`, no reference unique index (`/Users/siddhant/Desktop/lms/backend/src/main/resources/db/migration/V21__loan_payment_transaction.sql`).
- V56: nullable `idempotency_key`, partial unique index `WHERE idempotency_key IS NOT NULL` (`V56__loan_payment_target_installment_idempotency.sql`).
- V92: full `UNIQUE (idempotency_key)` but column still nullable at JPA/entity level:

```46:47:/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/domain/LoanPaymentTransaction.java
    @Column(name = "idempotency_key", length = 36)
    private String idempotencyKey;
```

- Installment payment API always requires a key in the service layer:

```113:116:/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/LoanServicingSupportService.java
    public String requireIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.trim().isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key header is required.");
```

- Duplicate same-key retries are blocked (`Issue86RepaymentIdempotencyIntegrationTest`).
- Different keys on the same installment: exactly one succeeds; the other gets `INSTALLMENT_ALREADY_PAID` (`LoanRepaymentConcurrencyIntegrationTest` lines 84–111).
- No unique index on `reference` anywhere in migrations.

**Revised severity: Low** for installment payments; **Medium** if counting foreclosure rows (see New Findings)

**Why:** Duplicate installment posting via API is blocked by required idempotency key + unique constraint + installment state machine, not by reference uniqueness. NULL-key duplicates are theoretically possible in PostgreSQL (`UNIQUE` allows multiple NULLs) and foreclosure explicitly writes `null`, but normal repayment path always sets a key.

---

## W1-A3-F01 — Optional Idempotency-Key on money-moving endpoints

**Verdict: Partially confirmed / Downgraded**

**Evidence — effectively required (service layer):**
- LSP payments: controller header `required = false`, but service requires key (`LspLoanApiController.java` 115–126 → `LoanRepaymentCommandService` 125).
- Ops payments: same pattern; test enforces 400 without key (`LoanApplicationOpsControllerTest` `paymentRequiresIdempotencyKeyHeader`).
- LSP invalidate / foreclosure execute: always routed through `lspApiIdempotencyService.execute()` which calls `requireUuidV4` — no blank bypass (`LspLoanApplicationApiController.java` 195–217, `LspApiIdempotencyService.java` 26).

**Evidence — truly optional at HTTP wrapper level:**
- Ops disbursement / mock-outcome / status-check: blank key skips `adminApiIdempotencyService` wrapper (`LoanApplicationOpsController.java` 450–527).
- LSP loan create: blank key bypasses wrapper (`LspLoanApplicationApiController.java` 231–232).
- `PUT /repayment-schedule`: no idempotency at all (`LspLoanApplicationApiController.java` 503–536).

**Other uniqueness layers when HTTP idempotency skipped:**
- Disbursement: `disbursement_intent` unique on `tran_ref_no` and live `loan_account_id` (`V111__disbursement_intent.sql` 24–28); `initiateDisbursement` returns early if already `DISBURSEMENT_REQUESTED` (`LoanDisbursementCommandService.java` 132–133).
- Mock outcome: requires account status `DISBURSEMENT_REQUESTED` (`LoanDisbursementCommandService.java` 427–432).
- Loan create without key: `uk_loan_application_lsp_external` (`V6__borrower_and_loan_application.sql`).

**Revised severity: Medium** (not High for payments)

**Why:** “Optional” is true at the controller annotation, but payment endpoints fail closed in the service. Real optional-idempotency money-adjacent gaps are disbursement ops wrappers (with domain guards) and schedule upsert.

---

## W1-A3-F02 — Crash recovery only for loan create

**Verdict: Confirmed gap / Downgraded severity**

**Evidence**
- Only one reconstructor: `LoanApplicationCreateIdempotencyReconstructor` with `OPERATION_KEY = "LOAN_APPLICATION_CREATE"` (`/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/web/LoanApplicationCreateIdempotencyReconstructor.java` 15, 29–31).
- Coordinator throws `IDEMPOTENCY_RECOVERY_REQUIRED` when lease reclaim needs recovery but no reconstructor exists (`IdempotencyExecutionCoordinator.java` 333–343, 386–396).

**Compensating controls elsewhere:**
- Payments: domain recovery in `LoanRepaymentCommandService.recoverPaymentAfterConcurrentWrite` (lines 99–112, 189–213) — not via `IdempotencyRecoveryService`.
- Disbursement: state machine + `disbursement_intent` live-account uniqueness.
- Loan create without HTTP key: recoverable via `external_loan_id` reconstructor when key is present.

**Revised severity: Medium** (availability/reconciliation pain on stale admin/LSP idempotency leases; not duplicate-money for payments/disbursement in normal retry paths)

**Why:** The recovery-service gap is real for wrapped ops/LSP mutations, but money paths have separate domain dedup. High overstates duplicate-post risk.

---

## W1-A3-F10 — Money endpoints with no idempotency

**Verdict: Partially confirmed / Downgraded**

| Endpoint | Idempotency? | Notes |
|---|---|---|
| `POST .../payments` (LSP + ops) | **Required** in service | Not a gap |
| `POST .../disbursement-requests` (+ mock/status) | HTTP wrapper optional | Domain state guards |
| `PUT .../repayment-schedule` | **None** | Pre-disbursement; deletes/replaces installments |
| `POST .../foreclosure-quotes` | None | Quote only, no money posted |
| `POST .../foreclosure-quotes/{id}/execute` | Required if key sent; optional bypass on ops | LSP path always requires key via service |
| `POST /internal/ops/loan-applications` | None | Onboarding, not disbursement |

**Revised severity: Medium** for schedule upsert; **Low** for disbursement/mock (domain guards); payments are **not** in scope

**Why:** The clearest unwrapped money-adjacent write is schedule upsert. Disbursement without HTTP idempotency is mitigated by intent/status checks in mock/pre-prod.

---

## W1-A2-F09 — LSP write paths elevate to admin datasource

**Verdict: Confirmed intentional / severity appropriate at Medium**

**Evidence**
- `LspApiIdempotencyService.runUnderAdminScope()` (`LspApiIdempotencyService.java` 37–44).
- `BorrowerActiveLoanChecker` documents cross-LSP reads on admin (`BorrowerActiveLoanChecker.java` 20–21, 54–66).
- `LoanApplicationOnboardingService.createApplication` uses `adminScopedTransactionExecutor` (`LoanApplicationOnboardingService.java` 94–95).
- LSP-scoped guards on admin reads: `getApplicationForLsp` / `getLoanAccountForLsp` check `lspId` in Java (`LoanApplicationQueryService.java` 47–53; `LoanServicingSupportService.java` 88–94).
- V45 explicitly keeps `ops_alert` off tenant role to prevent cross-tenant alert access.

**Revised severity: Medium** (unchanged)

**Why:** Elevation is deliberate for cross-tenant D3 checks and idempotency durability. Cross-tenant write would require a missing `lspId` guard while on admin connection (RLS bypassed). Current money/LSP paths consistently enforce `lspId` in application code; risk is latent design debt, not an observed exploit.

---

## New Critical/High findings missed by original agents

### 1. Foreclosure settlement posts payment with `null` idempotency_key — **High**

```249:261:/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/LoanForeclosureCommandService.java
        loanPaymentTransactionRepository.save(new LoanPaymentTransaction(
                loanAccount,
                null,
                normalizedActorUsername,
                quote.getSettlementAmount(),
                settlementDate,
                requiredReference,
                LoanPaymentChannel.FORECLOSURE_SETTLEMENT,
                LoanPaymentStatus.RECEIVED,
                resolvedNote == null ? "Foreclosure settlement for quote v" + quote.getVersion() : resolvedNote,
                CorrelationIdHolder.get(),
                null
        ));
```

Bypasses the payment idempotency model. Quote status (`ACTIVE` → executed) provides partial protection, but there is no row lock on foreclosure execute (unlike disbursement’s `lockApplicationForDisbursement`). Concurrent ops executes without `Idempotency-Key` could race.

### 2. `PUT /repayment-schedule` is unwrapped and deletes all installments — **Medium**

`/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/web/LspLoanApplicationApiController.java` 503–536 + `LoanRepaymentScheduleService.java` 70–72. Pre-disbursement only (`REPAYMENT_SCHEDULE_LOCKED` after disbursement requested), but retry can wipe/recreate schedules without idempotency.

### 3. Admin/LSP idempotency operations without reconstructors can hard-fail retries — **Medium** (availability)

Any non-`LOAN_APPLICATION_CREATE` operation that reclaims an expired lease returns `IDEMPOTENCY_RECOVERY_REQUIRED` (`IdempotencyExecutionCoordinator.java` 386–396). Affects ops disbursement/status transitions when clients always send keys and the first attempt died mid-flight.

### 4. Positive control (not a finding): `disbursement_intent` is admin-only

No `${tenant_app_role}` grant in `V111__disbursement_intent.sql`; aligns with V45’s ops_alert pattern. Disbursement money-out is not tenant-role writable.

---

## Summary matrix

| Claim | Original | Revised | Verdict |
|---|---|---|---|
| W1-A1-F01 | Critical | **Medium** | Downgraded |
| W1-A1-F04/F05 | High | **Low–Medium** | Downgraded |
| W1-A3-F01 | High | **Medium** | Downgraded |
| W1-A3-F02 | High | **Medium** | Downgraded |
| W1-A3-F10 | High | **Medium** | Downgraded |
| W1-A2-F09 | Medium | **Medium** | Confirmed |

No original Critical/High claim survived unchanged at full severity. The strongest newly evidenced issue is **foreclosure payments with null `idempotency_key`**.

[REDACTED]