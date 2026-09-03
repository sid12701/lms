# Bhawana LMS — Consolidated Audit

**Date:** 2026-07-31
**Baseline:** `bfd571f` + dirty worktree (D3 borrower lock, two uncommitted test files)
**Scope:** Backend (411 main files), frontend, 113 Flyway migrations, infra, docs
**Method:** Six bounded sub-audits + comparative research against Apache Fineract source and practitioner engineering literature. **Every Critical was independently verified against the code by the lead before inclusion.** Findings that did not survive verification were dropped and are listed in §12.

---

## 1. Confirmed operating model

Established during the audit and load-bearing for everything below.

| Fact | Consequence |
|---|---|
| Bhawana is the **RE (regulated NBFC)** and the lender of record | All KYC/AML/reporting obligations are Bhawana's and are **non-delegable** |
| Partners are **LSPs**, not co-lenders | Co-Lending Directions 2025 **do not apply**. Parked, not deleted — revisit if balance-sheet participation is ever taken |
| LSPs source customers; borrower flows through Bhawana's channel | LSPs deliver artifacts; Bhawana must **generate and verify** them |
| Disbursal/collection accounts are titled **"Bhawana + LSP"**, controlled by Bhawana, funds transferred by Bhawana | **Flow-of-funds requirement satisfied.** Money moves from Bhawana's account to the borrower, never through an LSP account |
| LSP submits a disbursement request via API; Bhawana disburses | Correct direction. Matches existing `POST .../disbursement-requests` |
| **No borrower-facing UI.** Internal ops + limited LSP surface | De-prioritises customer-UX findings. Does **not** reduce KFS/APR obligations — those are data Bhawana must produce and hand to the LSP |
| Single currency (INR), single product family, full-EMI only | Multi-currency and day-count-convention work is out of scope |

### 1.1 Documentation defect arising from the above

`CONTEXT.md:9` states *"Each LSP operates its **own** pair of bank accounts at the bank"* and `CONTEXT.md:11-17` defines the disbursal account as *"the per-LSP bank account that outgoing disbursements are debited from."*

This is **misleading and now known to be wrong**. The accounts are Bhawana's, titled jointly, under Bhawana's control. The glossary as written describes the exact pattern RBI's Digital Lending Directions prohibit, and will lead the next engineer (or the next auditor) to the wrong conclusion.

**Action:** rewrite the `CONTEXT.md` bank-account glossary to state that accounts are Bhawana-held and per-LSP-designated. **Verify with ICICI** that the account holder of record and sole mandate/signatory is Bhawana Capital, not a joint mandate.

---

## 2. Executive verdict

**This is not an AI-generated scaffold.** Infrastructure and distributed-systems reasoning are genuinely good and match published state of the art. Code craft is above average.

**It is not yet safe for live money.** The defects cluster in four places: the lending domain model, the evidence layer, tenancy enforcement on write paths, and the regulatory apparatus.

The through-line: **the request path was engineered by people who have been burned by distributed systems; the database, the calendar, the ledger, the evidence layer and the regulator got generic answers.** And nothing in the test suite could catch the difference, because 88% of it runs against a database with no constraints, no RLS, and no migrations.

| Axis | Assessment |
|---|---|
| Code craft | **Strong** — zero public setters across 95 entities, zero `@Lazy`, enforced layering, low coupling (max fan-in 12) |
| Distributed-systems design | **Strong** — intent-before-side-effect, `FOR UPDATE SKIP LOCKED` claims, lease reclaim with CAS, outbox, correct keyset pagination |
| Structural architecture | **Conventional** — layered, *not* modular. 139 services and 95 entities in flat packages, no bounded contexts |
| Lending domain model | **Thin** — Fineract's shape at ~¼ resolution: 2 money dimensions of 4, 2 decimal places of 6, one allocation authority short, no transaction taxonomy |
| Evidence / audit layer | **Largely absent** — no tamper evidence, cascade-deletable audit, unaudited bulk PII reads |
| Regulatory apparatus | **Absent** — KFS, APR, CKYC, bureau, screening, retention all at zero |

**Gates:** suitable for management review and synthetic UAT. **Block live disbursement** until §4 Criticals close. **Block partner pilot** until C1, C2, C3, C4 and the LSP-surface rate limit close.

---

## 3. How to read the findings

- **Severity:** Critical / High / Medium / Low
- **Verified:** lead opened the cited file and confirmed the claim
- All line numbers are against baseline `bfd571f`

---

## 4. Critical findings

### C1 — Idempotent LSP writes execute admin-scoped; RLS is off for the entire LSP mutation surface
**Verified.** `service/IdempotencyExecutionCoordinator.java:346-370`

```java
return adminScopedTransactionExecutor.call(() -> {
    ...
    T response = action.get();          // ← the LSP's entire business write
    completeLspOrThrow(leaseToken, response);
```

`AdminScopedTransactionExecutor.call` sets `TenantDataAccessContextHolder.useAdmin()` and opens a `REQUIRES_NEW` transaction, routed to the RLS-exempt admin pool. **`FORCE ROW LEVEL SECURITY` appears in zero of 113 migrations**, so the table owner is exempt from all 28 policies.

An LSP request *with* an `Idempotency-Key` runs its write with RLS entirely off; the same request *without* the header runs on the tenant pool with RLS on. Six endpoints: create application, invalidate, three document paths, foreclosure execute.

Tenant isolation on those paths reduces to a single Java `equals`.

**Fix:** snapshot tenant context before elevating; keep the idempotency ledger write admin-scoped but execute `action.get()` under the caller's original tenant scope. Add `FORCE ROW LEVEL SECURITY` to every tenant table.

---

### C2 — Any LSP can overwrite another LSP's borrower bank account, unaudited
**Verified.** `service/BorrowerOnboardingService.java:69,87-92`; `domain/Borrower.java:330-345`

`findByPan` looks up borrowers **across all tenants** (admin-scoped), then `mergeLatestProfile(profile)` copies the caller's submitted values onto the shared row — including `bankAccountNumber`, `ifscCode`, `accountHolderName`. Only `aadharNumber` is protected (`Borrower.java:320`).

**Attack chain:** LSP-B posts an application for a PAN already onboarded by LSP-A, supplying its own bank account. The active-loan guard (`BorrowerActiveLoanChecker`) only fires when a `loan_account` exists — and one is not created until approval — so any victim application in `INITIALIZED` or `AWAITING_APPROVAL` is exposed. LSP-A's loan is approved and disbursed; `DisbursementIntentWorkflowService:98-99` snapshots the beneficiary from the mutated borrower row.

**No `borrower_bank_details_update_audit` row is written**, because that audit only fires on the dedicated bank-details endpoint (`BorrowerBankDetailsService:276-291`), which also blocks edits during in-flight disbursement and runs a velocity alert. The onboarding path does neither.

**Fix:** block bank-field mutation in `mergeLatestProfile` when the borrower is visible to another LSP; raise `BORROWER_IDENTITY_CONFLICT` for ops review, as the Aadhaar-mismatch path already does. Route every bank-detail mutation through the audited path.

---

### C3 — Deactivating a user terminates nothing; the session renews indefinitely
**Verified.** `service/UserAdminService.java:184-217`; `domain/AppUser.java:176-186`; `service/AuthTokenService.java:163-201`

Session revocation is gated on **role change only**. `updateManagedProfile` sets `status` and never touches `tokenVersion`. `ManagedUserJwtPrincipalResolver.validateSession()` checks the `pwdv` and `tv` claims but **never reads `snapshot.status()`** — even though `AuthPrincipalCache.AppUserSnapshot` carries it. `rotateRefreshToken` performs **no status check** before minting.

`/api/v1/auth/refresh` is `permitAll`. So a deactivated user holding a refresh cookie retains full access **indefinitely** by refreshing every 30 minutes, for as long as the 7-day refresh window keeps rolling.

`ApiClientJwtSessionValidator:70-76` does check both `lspStatus` and `apiClientStatus` on every request. The asymmetry is unexplained.

**Fix:** bump `tokenVersion` on any status transition; check status in `validateSession()`; re-check subject status in `rotateRefreshToken`; call `revokeAllSessions` on status change as well as role change.

---

### C4 — Disabling an LSP kills its API clients but not its UI users
**Verified.** `service/LspStatusService.java:93-115`

`disable()` bumps `lsp.revokeAllSessions()` and iterates `apiClientRepository.findByLsp_Id(...)`. **No `app_user` rows are touched.** An `LSP_UI_READ`/`LSP_UI_WRITE` principal's JWT carries `lspId` but not `authType=API_CLIENT`, so `ApiClientJwtSessionValidator` short-circuits to `success()`. No LSP-status check exists anywhere on the managed-user path.

So "kill this LSP" kills the machine integration completely and does nothing to its humans — who can also **still log in with their password afterwards**.

**Fix:** cascade `revokeAllSessions` over `appUserRepository.findByLsp_Id(...)`; check LSP status in `validateSession()` for any principal carrying an `lspId` claim.

---

### C5 — Foreclosure charges every rupee of future contracted interest
**Verified.** `service/LoanForeclosureCommandService.java:92-97`

```java
BigDecimal outstandingInterest = installments.stream()
        .map(i -> scaleCurrency(i.getInterestDue().subtract(i.getPaidInterest()).max(ZERO)))
        .reduce(ZERO.setScale(2), BigDecimal::add);
BigDecimal settlementAmount = scaleCurrency(outstandingPrincipal.add(outstandingInterest));
```

Sums **every** installment with no date filter. `effectiveDate` is accepted, stored and compared at execution — it never enters the arithmetic.

**₹5,00,000 @ 18% / 24m, foreclosed after installment 2:** correct payoff ≈ ₹4,87,200. This system quotes ≈ **₹5,71,900**. ~₹84,700 of unearned interest, on every foreclosure.

RBI's **Pre-payment Charges on Loans Directions, 2025** (in force 1 Jan 2026) bar pre-payment charges entirely on floating-rate loans to individuals and require the calculation method to be disclosed upfront. This is neither disclosed nor a charge — it is interest that was never earned.

**Fix:** accrue interest to the actual settlement date on the outstanding principal; discard unaccrued future interest. Fineract's `INTEREST_REFUND(33)` transaction type is the reference mechanism.

---

### C6 — A borrower who never pays is never delinquent
**Verified.** `repo/AlertRuleSetQueryRepository.java:126`; `service/LoanRepaymentCommandService.java:344-361`

The delinquency query filters `where app.status = 'UNDER_REPAYMENT'`. The only writer of that status is `transitionToUnderRepaymentIfNeeded`, called **only from the payment path**.

A disbursed loan on which no payment has *ever* been made stays `DISBURSED` forever: excluded from every DPD bucket, no ops alert, no `loan_delinquency_state` row. It ages silently to 90+ DPD.

**First-payment default — the single most important early fraud and credit signal in unsecured Indian retail lending — is invisible to this platform.**

**Fix:** drive delinquency off `loan_repayment_schedule_installment.due_date < today AND not settled` for **all** active loan statuses, with no dependency on payment history. This is Fineract's model (`LoanArrearsAgingService` / COB pipeline).

---

### C7 — No payment reversal exists, and `CLOSED` is terminal and non-reopenable
**Verified.** `domain/LoanPaymentStatus.java`; `domain/LoanApplicationStatus.java:46,67-74`

`LoanPaymentStatus` declares `RECEIVED, PENDING_RECONCILIATION, FAILED`. Only `RECEIVED` is ever written — three write sites, zero for the other two. No reversal, refund, bounce or chargeback concept anywhere. `LoanPaymentTransaction`'s only mutator is `updateAllocation`.

The dead branch at `LoanServicingSupportService.java:249-252` shows the replay engine was *designed* for a non-received payment, but no code path can produce one.

**Failure chain:** final NACH posts `RECEIVED` → closure fires → account closes `FULLY_REPAID` → application `CLOSED`, which the state machine defines as terminal and which manual override explicitly refuses as both source and target → `LOAN_FULLY_REPAID` webhook already delivered → **the mandate bounces two days later**. No path in the ops UI, the LSP API or admin override can undo any of it.

**Fix:** `is_reversed` + `reversed_on_date` on the payment; exclude reversed rows from the replay query; reopen path for a closed loan when a reversal reintroduces a balance. See §8 — this is a **prerequisite** for the replay being correct, not an enhancement.

---

### C8 — No penal charge, late fee, bounce charge or GST model
**Verified.** Schema-wide negative search.

The only fee is `loan_product_version.processing_fee_rate`. The schedule has two money dimensions: `principal_due` and `interest_due`. A missed EMI has exactly one consequence — DPD ticks up. Nothing is ever charged.

**The structural trap:** the only column a penal amount fits is `interest_due`, which capitalises it and compounds it through `expectedInterestForOpening` (`LoanRepaymentScheduleService.java:505-507`) — precisely what RBI's Aug-2023 penal-charges circular prohibits, and it breaks the schedule reconciliation invariant at `:456-466` on the next validation pass.

**Fix:** a `loan_charge` entity with charge-time and calculation-type, a fourth allocation bucket ahead of interest, and a tax component. Retrofitting later means re-deriving every historical schedule. Fineract reference: `LoanCharge`, `ChargeTimeType.OVERDUE_INSTALLMENT(9)`, `LoanOverdueInstallmentCharge`, applied by `ApplyChargeToOverdueLoanInstallmentTasklet`.

---

### C9 — Every ops retry mints a new Idempotency-Key
**Verified.** `frontend/src/lib/idempotency.ts:7-10`; 29 call sites

`newIdempotencyKey()` returns `crypto.randomUUID()` and is called **inside** the submit handler or `mutationFn` at every site — `RepaymentPostDialog.tsx:143`, `TransitionConfirmDialog.tsx:331`, `DisbursementInitiateDialog.tsx:77`, `ForeclosureQuotePanel.tsx:95`. The two "reuse if supplied" branches (`api-detail.ts:497,601`) are never supplied a stable key by any caller.

The backend's unique constraint therefore never fires on a user retry. A 504 after commit → operator clicks again → **two payments**. Same for disbursement and foreclosure execute. `ActionBar.tsx:104-112` keeps the dialog open on failure, actively inviting the retry.

The doc comments encode the bug as intent: *"Fresh BR-5 idempotency key minted at submit time."*

**Fix:** mint once per user **intent** (dialog open, keyed on the target), hold in a ref, reuse across retries until success or explicit abandon.

---

### C10 — The frontend silently converts a rejected transition into an admin override
**Verified.** `frontend/src/features/loan-applications/api-detail.ts:441-447,457-474`

```js
} catch (error) {
  if (error instanceof ApiError && (error.status === 400 || error.status === 403) && isSystemAdmin()) {
    payload = await tryEndpoint("manual-status");
```
```js
? { ...body, note: body.note ?? "Manual override",
    reasonCode: body.reasonCode ?? "MANUAL_ADMIN_OVERRIDE" }
```

An admin clicks a normal transition. The backend's state machine correctly refuses it. The **client** swallows the refusal, re-posts to the privileged override endpoint, **fabricates the audit justification**, and shows success.

The audit log then records a deliberate-looking manual override that no human chose — on the exact control an inspector examines. `isSystemAdmin()` also reads the role from client-held session state (`api-detail.ts:38-40`), and the same idempotency key is reused across two different endpoints.

**Fix:** delete the fallback. Surface the 400/403. Manual override becomes a distinct, deliberate action with mandatory operator-written justification.

---

### C11 — A profile-less deploy boots on the repo-committed JWT secret with the validator disarmed
**Verified.** `application.yml:10-11`; `application-local.yml:70`; `config/UnsafeDeploymentConfigurationValidator.java:17,20-21,27-32,65-82`

`spring.profiles.default: local`. With `SPRING_PROFILES_ACTIVE` unset, `getDefaultProfiles()` returns `[local]`, `DEV_ONLY_PROFILES` contains it, and validation **returns before running**.

`application-local.yml:70` supplies `${APP_SECURITY_JWT_SECRET:local-dev-jwt-secret-at-least-32-characters}` — 43 chars, so it passes the length check. And the validator's blocklist names `change-me-local-dev-secret-change-me-local-dev`, a string **that no longer exists anywhere in the repo**.

Two independent paths to a forgeable `SYSTEM_ADMIN` token signed with a key that is in git. Insecure cookies and the hardcoded tenant DB password (`lms_tenant_app_password`) ride along. `TenantDatasourceSecurityValidator` has the identical profile bypass.

**Fix:** delete `spring.profiles.default`. Remove `${VAR:fallback}` on every security-sensitive property in every profile. Replace the placeholder deny-list with entropy checks plus a test asserting no committed YAML resolves a secret.

---

### C12 — The 11 custom metrics have no registry and no endpoint
**Verified.** `backend/pom.xml`; `application.yml:30-34`

No `micrometer-registry-prometheus` in either POM; actuator exposure is `health,info`. Boot auto-configures a `SimpleMeterRegistry` — an in-memory sink discarded on restart.

The instrumentation itself is good and well-chosen: `lms.disbursement.intent.unknown.count` and `lms.disbursement.intent.unknown.oldest_age_seconds` (`DisbursementIntentMetrics.java:20-30`) are exactly the gauges that tell you money is in limbo. **Nothing can ever read them.**

Compounding it, `TenantIsolationDataSourceConfig.java:92-99` health-checks **only the admin datasource**, and `:53` sets `setInitializationFailTimeout(-1)` on the tenant pool. A misconfigured tenant role boots green, reports healthy, passes readiness, and 500s on the first partner request.

**Fix:** add the Prometheus registry, expose `prometheus` on a management port bound to the private network, treat `unknown.oldest_age_seconds` as a paging SLO. Health-check both pools.

---

### C13 — 88% of backend tests run on H2 with Flyway disabled
**Verified.** `backend/src/test/resources/application-test.yml:3,7-11,15-16`

```yaml
url: jdbc:h2:mem:lms;MODE=PostgreSQL;...
flyway:
  enabled: false
jpa:
  hibernate:
    ddl-auto: create-drop
```

The schema under test is **generated by Hibernate from the entities**, not built by the 113 migrations. No RLS, no grants, no `CHECK` constraints, no cascade semantics, no partial indexes.

And `TenantIsolationDataSourceConfig.java:34-37` returns `adminDataSource` when the JDBC URL isn't `jdbc:postgresql:` — so on H2 **the tenant datasource silently becomes the admin datasource**. Both routing keys point at the same RLS-exempt pool. Every tenancy test passes while testing nothing.

Measured: **93 of 765 backend test methods (12.2%)** run on real Postgres. `PostgresDataJpaTestSupport` is `@Testcontainers(disabledWithoutDocker = true)` — without Docker those 93 silently vanish and the build stays green.

The partial unique index that prevents double disbursement exists **only** in `V111__disbursement_intent.sql:26-28` and not on the entity — so Hibernate `create-drop` never creates it, and the `FOR UPDATE SKIP LOCKED` claim path that runs in production is executed by **zero** tests.

CI runs **1 of 18** Playwright tests (a login-page smoke test) and enforces **no coverage gate** on either side.

**This is the finding that explains most of the others.** Nothing in the suite could have caught C1, the missing `FORCE ROW LEVEL SECURITY`, the cascade deletes, or the absent `CHECK` constraints.

**Fix:** make Testcontainers Postgres + Flyway the default test datasource; H2 the exception. Remove `disabledWithoutDocker`. Delete the H2 branches in `AlertRuleSetQueryRepository:28-54`, `DisbursementIntentRepositoryImpl:28-32` and `PostgresAdvisoryLockSupport:21-24` — with real Postgres in tests, production needs only one code path.

---

## 5. High findings

### 5.1 Money and servicing

| ID | Finding | Evidence |
|---|---|---|
| H1 | **Two allocation authorities that will diverge.** `applyPayment()` allocates to the single installment named by `repayment_installment_id`; `recomputePaymentAllocation()` resets everything and replays oldest-first **ignoring that FK**. After the first recompute the FK is affirmatively wrong. Currently masked by the exact-amount rule (H3) — relax that without unifying and divergence is immediate | `LoanServicingSupportService.java:240-275` |
| H2 | **Money precision: rounds at 2dp at every intermediate step.** `Money.scale()` = 2dp HALF_UP; schedule columns `precision=19, scale=2`. Fineract stores schedule money at **`scale=6`**; Mambu computes at **20 decimals** and rounds only when writing journal entries. Compounds over a 24–36 month schedule, and is why the partner-schedule validator needs tolerances (`interest-row-tolerance-abs: 10.00`) to accept schedules that should reconcile exactly | `common/money/Money.java:11-13`; `domain/LoanRepaymentScheduleInstallment.java:37-65` |
| H3 | **Exact-installment-amount-only payments.** Under-payment 422, over-payment 422, two installments in one transfer 422. `applyPayment` on the entity already computes `PARTIALLY_PAID` correctly — **the domain supports partial payment; the service layer forbids reaching it.** A ₹24,962.37 EMI paid as ₹24,962 cannot be posted at all | `LoanServicingSupportService.java:196-223` |
| H4 | **Foreclosure quotes never expire; `effectiveDate` unvalidated.** No expiry column. A backdated settlement (`payment_date = 2024-01-01`) sorts ahead of every real EMI in the replay ordering, silently rewriting every historical principal/interest split | `domain/LoanForeclosureQuote.java:23-62`; `LoanServicingSupportService.java:245-246` |
| H5 | **Foreclosure over-payment strands money.** Under-payment throws; over-payment is silently accepted, the loan closes `FORECLOSED`, and the surplus sits in `unallocated_amount` on an immutable row that no worker, report or alert ever revisits | `LoanForeclosureCommandService.java:249-266` |
| H6 | **No disbursement cap, no velocity limit.** Preflight checks documents, schedule validity, `principal > 0`, fee ≤ principal. The only amount-sensitive branch in the whole payout path is IMPS-vs-NEFT rail selection | `DisbursementPreflightValidator.java:109-150` |
| H7 | **Non-intent disbursement path calls the bank inside the transaction.** One flag (`app.disbursement.intent-workflow.enabled=false`) away. Crash between the provider call and commit → money left, **no row at all** → worker reissues with a fresh random `tranRefNo` | `LoanDisbursementCommandService.java:186-205` |
| H8 | **Intents in `REQUESTED`/`UNKNOWN` are permanently un-actionable.** `CANCELLED` exists in the enum and **nothing writes it**. After polls exhaust, the worker loops forever: preflight passes, `createIntent` throws `DISBURSEMENT_ALREADY_REQUESTED`, swallowed to a `log.warn`, every 30 seconds. Only remedy is production SQL | `DisbursementIntentState.java:15-21`; `DisbursementIntentWorkflowService.java:83-88` |
| H9 | **Interest on gross while disbursing net; APR computed nowhere.** ₹5,00,000 @ 18% with 1% fee → net ₹4,95,000, true APR ≈ **18.9%**. Every surface reports 18.00% | `LoanRepaymentScheduleService.java:185-190`; `LspLoanApplicationResponses.java:107` |
| H10 | **Product *eligibility* evaluated against the mutable live row** while pricing correctly uses the frozen version. A config edit retroactively changes in-flight applications' eligibility | `LoanAutoApprovalRuleEngine.java:79-92` |
| H11 | **Schedule anchored on `approvedAt`, never re-anchored at disbursement.** RBI's Fair Practices circular names charging interest from sanction rather than actual disbursement as an unfair practice | `LoanRepaymentScheduleService.java:189,497`; `LoanApplicationStatusWriter.java:122,146` |
| H12 | **Business date computed in UTC while the clock is IST.** `TimeConfig.BUSINESS_ZONE = Asia/Kolkata` is used *only* to build the Clock bean. The "today" path is correctly IST; every `Instant → LocalDate` conversion is UTC. A loan approved 02:00 IST gets a schedule dated the previous day. **The mix is the bug** — a uniformly-UTC system would at least be internally consistent | `config/TimeConfig.java:11,15`; `LoanRepaymentScheduleService.java:497`; `AdminReportingService.java:324,328,351,359,399,407,431,432` |

### 5.2 Classification and reporting

| ID | Finding | Evidence |
|---|---|---|
| H13 | **No day-end process exists.** Zero `@Scheduled(cron …)` in the codebase. RBI's Nov-2021 IRAC clarification requires SMA/NPA classification *as part of the day-end process* | codebase-wide |
| H14 | **Buckets are generic 30-day bins.** `CURRENT, DPD_1_30, DPD_31_60, DPD_61_90, DPD_90_PLUS`. No SMA-0/1/2, no NPA flag, no provisioning input, no upgrade rule (which post-Nov-2021 requires clearance of **all** overdues) | `domain/LoanDelinquencyBucket.java:3-9` |
| H15 | **Three DPD computations over three different populations.** Read APIs (any status), alerting (`UNDER_REPAYMENT` only), KPI snapshot (**no status filter at all** — closed, foreclosed, invalid and never-disbursed accounts all land in `CURRENT`). Dashboard and alert engine report different portfolios on the same day; PAR% is understated by the closed-to-open ratio | `LoanDelinquencySupport.java:17-23`; `AlertRuleSetQueryRepository.java:103-128`; `PortfolioKpiSnapshotComputationService.java:171-191` |
| H16 | **MIS is as-of *generation time*, not as-of the period.** No as-of stamp, no timezone, no filter echo, no row count, no request id in the file. The same report re-runs with different numbers. An MIS showing PAR-30 of 4.1% reproduces at 6.8% months later with no way to prove which was reported | `AdminReportingService.java:192,264,484`; `PortfolioMisCsvWriter.java:37-90` |
| H17 | **MIS preview is an unaudited bulk PII export.** `previewPortfolioMisReport` writes **nothing** to `report_access_audit` — only the download paths are audited. Returns name, address, IFSC, income, account number at 500 rows/page, `page` unbounded, 60 req/min: **30,000 PII rows a minute, no trace** | `ReportAdminController.java:58-72` |
| H18 | **Report worker: whole batch in one transaction; can wedge permanently.** `@Transactional` spans CSV generation, the R2 PUT and the SMTP send. `catch (RuntimeException)` does not catch `OutOfMemoryError`. Whole CSV assembled in a `StringBuilder` → `String` → `byte[]` (~4× resident, no row cap). On OOM the batch rolls back, completed reports revert to `PENDING` with orphaned R2 objects, and the worker re-claims the identical batch every 15s forever. `PROCESSING` is not in the claim set and there is no lease — a stranded row is never reclaimed | `ReportRequestService.java:99-160`; `AdminReportingService.java:243-267` |

### 5.3 Evidence and audit

| ID | Finding | Evidence |
|---|---|---|
| H19 | **No tamper evidence of any kind.** No hash chain, sequence number, signature, WORM sink or second copy. Combined with audit tables cascade-deleting from business parents and the tenant role holding `UPDATE, DELETE`, one `DELETE FROM loan_application` erases an application's entire audit history leaving no residue. **The honest answer to the Rule 3(1) question — "demonstrate the audit trail cannot be disabled or edited" — is currently "we cannot."** | codebase-wide |
| H20 | **`ON DELETE CASCADE` from business parents into audit tables.** `loan_application_status_transition` (V9), `loan_application_intake_audit` (V8), `loan_application_assignment_event` (V11), `borrower_pii_reveal_audit` (V102), `app_user_audit_event` (V54), `api_client_audit_event` (V55), `loan_product_audit_event` (V5). Delete an admin → the record of what they did goes with them. **Latent today** (no app code hard-deletes those parents) — becomes live the moment DPDP erasure is built | migrations as listed |
| H21 | **Tenant DB role holds `UPDATE, DELETE` on audit and financial evidence tables.** `V41:206-215`. The tell that this is template-driven: `V42:17` grants only `SELECT, INSERT` on the PII-reveal audit. Right once, blanket CRUD eleven times | `V41__tenant_isolation_rls.sql:206-215` |
| H22 | **Audit explorer masks Aadhaar and nothing else.** A five-name allowlist; `borrowerPan` and `bankAccountNumber` pass through untouched. `GET /internal/admin/audit-events?streams=INTAKE&limit=500` returns full PAN, bank account, IFSC, address, DOB and income for every loan in the window. **Reading the audit log is itself unaudited and unthrottled** | `AuditExplorerService.java:31-37,373-391`; `AuditExplorerController.java:37-68` |
| H23 | **Explorer covers 8 of 15 audit tables.** Missing: `auth_event_audit`, `lsp_audit_event`, `borrower_bank_details_update_audit`, `borrower_pii_reveal_audit`, `webhook_outbox_redrive_audit`, `loan_application_pii_reveal_audit`, `loan_application_status_transition` | `AuditExplorerQuery.java:31-40` |
| H24 | **A product interest-rate change writes an audit row that doesn't contain the rate.** 18%→36% produces *"Updated product PL-01 to ACTIVE with principal 10000-500000 and tenure 3-24 months."* No rate, no before-value, no actor IP | `ProductConfigurationService.java:188-197`; `domain/LoanProductAuditEvent.java:20-41` |
| H25 | **Document replacement orphans the prior evidence.** `update()` overwrites `storageKey`, `fileChecksum`, `fileName`, `fileSizeBytes` in place. The previous R2 object still exists but nothing points to it. No audit row records the replacement | `domain/LoanApplicationDocumentChecklist.java:227-237` |
| H26 | **Zero storage-delete calls; no object lock, no versioning, no lifecycle.** `grep deleteObject\|Files.delete` → **0**. You can neither perform DPDP erasure nor prove PMLA retention. One credential both writes and deletes | `service/R2*StorageService.java` |

### 5.4 Identity and access

| ID | Finding | Evidence |
|---|---|---|
| H27 | **No maker-checker anywhere.** One `SYSTEM_ADMIN` can mint another admin, rewrite borrower bank details, override loan status, release a disbursement, and change a product's interest rate. `enforceSelfEditGuards` protects only against self-inflicted lockout — never against one admin removing another. Combined with C3, a compromised admin **survives deactivation** | `UserAdminService.java:310-318`; `LoanApplicationOpsController.java:408,443` |
| H28 | **Bootstrap admin re-created, re-activated and re-granted `SYSTEM_ADMIN` from config on every boot**, with no `@Profile` guard and **no audit row** on the startup path. Disabling it or stripping its roles is reverted at the next deploy. `SecurityConfig:45-53` also returns an in-memory `UserDetails` whose token can never be revoked | `LocalBootstrapAdminSyncService.java:73-76,122-131`; `AppUser.java:164-174` |
| H29 | **Password change does not revoke refresh tokens** — and a code comment claims it does. `changePassword` sets `passwordChangedAt` but never touches `tokenVersion`. Access tokens die; refresh tokens live the remaining 7 days | `UserAdminService.java:235-240`; `AppUser.java:158-162` |
| H30 | **Lockout is a 5-minute batch job, permanent until admin reset, and never fires for distributed attacks.** Grouped by `(username, actorIp)`, so rotating IPs defeats it — the distributed rule only *alerts*. Conversely five requests permanently lock any known account, with no guard on the last active admin. `ApiClientLockoutService` does this correctly and synchronously for machine clients | `AlertRuleSchedulerWorker.java:31`; `AlertRuleEvaluationWorker.java:281-302,335-380` |
| H31 | **Six tenant-attributable tables have no RLS at all**: `disbursement_intent`, `loan_delinquency_state`, `portfolio_kpi_snapshot`, `borrower_bank_details_update_audit`, `loan_disbursement_bank_mismatch_log`, `borrower_pii_reveal_audit` (plus `webhook_event_delivery_attempt`, `disbursement_outcome_audit`, `report_access_audit`, `lsp_audit_event`). Of ~30 migrations adding tenant tables after V41, five added RLS | V111, V101, V109, V78, V102 |
| H32 | **Tenant DB role can read `password_hash` and `secret_hash`** for its own LSP's principals. Vestigial grants — no tenant-scoped code path queries these tables | `V41:216-217` |
| H33 | **`OPS_USER` can create loan applications for any LSP with no tenant check.** No method-level `@PreAuthorize`; the two-arg overload passes `enforcedLspId = null`, skipping the check entirely. Every money-touching sibling in the same controller carries a `SYSTEM_ADMIN` restriction | `LoanApplicationOpsController.java:351-371`; `LoanApplicationOnboardingService.java:79-81,110-113` |
| H34 | **LSP-facing bank-detail writes and application creation are admin-scoped**, so isolation rests on a single Java `equals`. ADR 0005 sanctions elevation for "principal resolution and perimeter lookups"; these are business writes | `BorrowerBankDetailsService.java:102-117,253-256` |

### 5.5 Partner API surface

| ID | Finding | Evidence |
|---|---|---|
| H35 | **No rate limit on any LSP read endpoint.** The only GET rule covers documents. `GET /lsp/loan-applications?limit=200&offset=N` — full borrower PII, 200 rows/call — is uncapped, and there is no per-request access log to bound a breach after the fact | `application.yml:134-138,164-168` |
| H36 | **`X-Forwarded-For` trusted from the raw header** with no trusted-proxy configuration. The LSP IP allowlist — the control a partner's security team signed off on — is bypassable by sending the header. The forged IP is then recorded in `BorrowerPiiRevealAudit` | `common/web/ClientIpAddresses.java:16-23`; `LspSurfaceIpAllowlistFilter.java:70` |
| H37 | **One webhook signing secret per LSP, no rotation grace.** Rotation is a `PUT` on a single column. A 401 is classified `PERMANENT_FAILURE`, so in-flight events **dead-letter immediately** rather than retrying. No minimum entropy; `http://` endpoints accepted. The same codebase does this correctly for API client secrets (`previousSecretHash` + validity window) | `V23:4`; `LspDirectoryService.java:169-175`; `WebhookOutboxDispatchExecutor.java:357-365` |
| H38 | **Webhook ordering not guaranteed per loan.** The claim query orders by `created_at`; the parallel `executor.submit` discards it across a 10-thread pool. No sequence number in the envelope. `DISBURSEMENT_COMPLETED` can land before `LOAN_STATUS_CHANGED → DISBURSED` | `WebhookOutboxService.java:181-184` |
| H39 | **Payment idempotency keys are globally unique across all tenants**, guarded by `synchronized (idempotencyKey.intern())` — a JVM-local lock in a system that explicitly assumes multiple instances. A cross-tenant collision is invisible to RLS but not to the constraint → 500, and the key is permanently poisoned. The LSP API table gets this right: `(lsp_id, operation_key, idempotency_key)` | `V92:6-7`; `LoanRepaymentCommandService.java:167` |
| H40 | **Rate limiter fails hard-closed with an untyped 500** when Redis is unavailable — including on `/auth/token`, the only way a partner gets a token. The filter sits upstream of the exception handler, so partners receive a Spring default error body their SDK cannot parse. The app also won't boot without Redis | `RateLimitFilter.java:107-109`; `RateLimitConfig.java:33-36` |
| H41 | **Three fused error envelopes**, one of them scraped from **Mambu's** docs (`errorCode`/`errorReason`/`errorSource`; the scraped pages are still in `docs/API-references/`). ~93 error codes exist as bare string literals with no enum, and the generated OpenAPI declares only HTTP 200 with no error schema | `common/api/ApiError.java:45-58` |
| H42 | **90-day plaintext PII dossier in the idempotency store.** `response_body` holds name, DOB, **unmasked PAN**, address, employer, income. Aadhaar and account number *are* masked in the same object — the discipline exists and wasn't extended. A DPDP erasure that deletes the borrower leaves this intact | `domain/LspApiIdempotencyRecord.java:41-42`; `LspLoanApplicationResponses.java:28-86` |
| H43 | **No backup, restore or DR anywhere in the repo.** `infra/docker-compose.yml` is a bare `postgres:17-alpine` with no WAL archiving. `deployment-strategy.md` §8 states "RPO ≤ 5 min, RTO ≤ 1 h" in the present tense. The migration runbook's step 1 — *"restore into an isolated database first"* — cannot be followed | `infra/docker-compose.yml:2-17` |

### 5.6 Observability

| ID | Finding | Evidence |
|---|---|---|
| H44 | **OpsAlert never leaves the database.** `OpsAlertService` ends at `save()`. A `CRITICAL` DPD-90 alert has identical delivery to no alert. Nothing alerts on: intent stuck in `REQUESTED`/`UNKNOWN`, worker liveness, Redis down, webhook backlog. Team's own docs acknowledge this: *"detection without delivery"* | `OpsAlertService.java:59-92` |
| H45 | **Correlation ID written to MDC and never rendered.** No `logback-spring.xml` exists, so the console pattern has no `%X{}` token. `CorrelationIdHolder` is a plain `ThreadLocal` that doesn't cross into worker threads — every worker-authored audit row carries a null correlation ID. 52 log statements across 411 files, stdout only, zero retention. **CERT-In requires 180 days in-country** | `CorrelationIdFilter.java:36`; `CorrelationIdHolder.java:5` |
| H46 | **All 7 `@Scheduled` workers share one thread.** No `TaskScheduler` bean, no `spring.task.scheduling.pool.size` — Boot's default pool size is **1**. A slow bank status-check pass occupies it for minutes while alerting, reporting and webhook dispatch simply don't run. `processStatus` iterates `findByStatus(status)` **unbounded**, with no per-item try/catch, so one throwing application aborts the batch | `LmsApplication.java:12`; `LoanDisbursementWorkerService.java:110-119` |
| H47 | **No deployment artifact.** No Dockerfile, no manifests, no `prod` profile. No `server.shutdown: graceful` — SIGTERM drops in-flight requests and interrupts the scheduler mid-batch. Two Hikari pools bind the same property block, so outside `local` each defaults to 10 → 20 connections per instance, silently. `spring-boot-starter-amqp` is a dependency with **zero** Java references | `infra/`, `application*.yml`, `backend/pom.xml:22-25` |

---

## 6. Medium and Low

| ID | Sev | Finding |
|---|---|---|
| M1 | Med | **Money crosses the wire as a JSON number.** `BigDecimal` → JSON number → TS `number`. Symptom already in-repo: `frontend/src/schemas/lsp-provided-schedule.ts:99` needs a constant named **`FLOAT_NOISE`**. Stripe uses integer minor units; Google uses decimal strings. RFC 8259 guarantees interop only within ±2^53 — `NUMERIC(19,2)` exceeds it |
| M2 | Med | **RLS policy performance defect.** `app_current_lsp_id()` is `STABLE` but **not `LEAKPROOF`/`PARALLEL SAFE`**, and policies use it bare — `USING (lsp_id = app_current_lsp_id())` — so it can't be hoisted into a one-shot InitPlan. `tenant_owns_application()` is a correlated `EXISTS` executed **per row**. Measured elsewhere at 150× slowdown; 178,000ms → 12ms after the same fix. Composite indexes don't lead with `lsp_id` (`idx_loan_application_status`, `idx_loan_application_created_at`) |
| M3 | Med | **`autoCommit=false` on the tenant pool × the Postgres outbox.** Correct for RLS; it structurally increases open transactions, which pin the MVCC horizon and stop the outbox vacuuming. Mitigation is cheap: `idle_in_transaction_session_timeout`, `statement_timeout`, alarm on `age(backend_xmin)` |
| M4 | Med | **No `CREATE INDEX CONCURRENTLY`** in 103 index creations. Every index build blocks writes — a write outage per migration on a live book |
| M5 | Med | **Status columns are bare `VARCHAR` with no `CHECK` constraint.** One `CHECK` across 113 migrations, and it's a `jsonb_typeof` guard. `V65`'s own header documents the omission as deliberate ("so the enum vocabulary can evolve"), which is a defensible trade — but there is then no DB-level bar to `CLOSED → DISBURSED` |
| M6 | Med | **Unmasked PAN in `ops_alert.message`**, string-concatenated and returned to the ops surface outside the PII-reveal audit. `PanMasking` exists two packages away | `OpsAlertEmitters.java:200-205` |
| M7 | Med | **PAN unmasked on every JSON surface, masked only in the CSV.** `PanMasking` has exactly one production call site. Raw PAN goes out on the borrower list, the ops list and detail, and the **LSP surface** — immediately after `AadhaarMasking.mask(...)` on the adjacent line |
| M8 | Med | **CSV formula injection.** RFC-4180 quoting only; no neutralisation of leading `= + - @`. Borrower name and organisation arrive from LSP intake. `\r` also missing from the quoting predicate |
| M9 | Med | **Filesystem storage provider reachable in production**, defaults to `java.io.tmpdir`, and `setProvider(null)` falls back to `LOCAL`. Read path resolves an arbitrary `storageKey` with no `normalize()` / containment check. Upload-side traversal *is* closed |
| M10 | Med | **Idempotency-Key required on 3 endpoints, optional on 4, absent on 4** — including `PATCH /borrowers/{id}/bank-details`, which changes where money goes, and which the payload-size filter also skips (it only covers POST/PUT) |
| M11 | Med | **Offset pagination with unbounded offset** on the partner list endpoint, while a correct keyset implementation exists internally for the audit explorer. Three LSP list endpoints have no pagination at all |
| M12 | Med | **Raw exception messages returned to partners** on 4xx — including Hibernate's `Unable to find com.bhawana.lms.domain.LoanApplication with id …`. 5xx **is** correctly scrubbed |
| M13 | Med | **Frontend: no field-level error mapping.** The backend sends `violations[]` with a `field` on each; `readResponseError` never reads them and `form.setError` has **zero** call sites. Every typed 409/422 code is unhandled |
| M14 | Med | **Frontend: five of six list screens fetch the entire table** and paginate in JS. Every alert on the platform, including message text, transits to the browser and lands in the query cache |
| M15 | Med | **Frontend: a failed read after a successful write reports the write as failed.** `Promise.all([POST, fetchChecklist])` rejects on the GET. Combined with C9 → double disbursement |
| M16 | Med | **Frontend: repayment posted date UTC-truncated.** `new Date(v).toISOString()` then `.slice(0, indexOf("T"))`. A payment at 03:00 IST on 01 Aug books as 31 Jul, and `@PastOrPresent` is satisfied so nothing errors |
| M17 | Med | **Frontend: foreclosure execute is a bare button** with no confirmation. The confirmation dialog built for it (`components/app/foreclosure/*`) has **zero non-test importers** — as do the disbursement dialogs and `ConfirmDestructiveDialog` |
| M18 | Med | **Frontend: roles collapsed to one, with a fail-open fallback to `OPS_USER`.** Backend users hold a `Set<AppRole>`; `selectPrimaryRole` returns a single value and defaults unknown roles to an internal role with cross-tenant read and loan-write |
| L1 | Low | `app_permission` / `app_role_permission` exist in V1 and are referenced by **zero** Java. Dead schema |
| L2 | Low | `loan_application_pii_reveal_audit` — migrated, RLS-protected, in the domain model, and **permanently empty**. Reads as coverage |
| L3 | Low | `allInstallmentsSettled` is vacuously true for an empty schedule (`Stream.allMatch`). Unreachable today; guarded by other code, not by the predicate |
| L4 | Low | No currency dimension anywhere. Defensible for INR-only, but undocumented |
| L5 | Low | Reports downloadable by any `SYSTEM_ADMIN` regardless of requester or covered tenant (downloads *are* audited) |

---

## 7. Regulatory layer

All five are **RE obligations**. Under the LSP model none can be delegated, and RBI is explicit that outsourcing does not dilute or shift obligations — Bhawana remains fully liable for all acts and omissions of its LSPs.

### 7.1 KFS and APR — deadline already passed (1 Oct 2024)

**Obligation.** KFS for all new retail/MSME term loans. Unique Proposal Number; **valid ≥3 working days** (1 for tenors <7 days) and Bhawana is **bound** by those terms if accepted inside the window; must include an **APR computation sheet** and the full amortisation schedule; **forms part of the loan agreement**; charges not disclosed cannot be levied.

**APR includes:** interest, all RE fees, third-party charges recovered on actuals, **and charges deducted from disbursement**. **Excludes:** contingent charges (penal, foreclosure), statutory dues, security deposits.

**Current state.** `LoanApplicationDocumentType.java:10-11` — `KFS` and `LOAN_AGREEMENT` are documents **the LSP uploads**, `requiredForDisbursement = true`. The platform checks a file exists. It never generates one, never validates it against the loan's terms, and computes no APR (zero hits for `apr`).

**This is the wrong party producing the binding artifact.** The RE issues the KFS; the LSP only delivers it.

**Build:**
```
loan_kfs
  id, loan_application_id, proposal_number (unique, RE-scoped)
  apr numeric(7,4), sanctioned_amount, net_disbursed_amount
  tenure_months, interest_rate, rate_type
  charge_breakdown jsonb, schedule_snapshot jsonb
  valid_from, valid_until          -- ≥3 working days, business calendar
  issued_at, issued_by, accepted_at, accepted_channel, borrower_ack_ref
  document_hash                    -- SHA-256 of the rendered PDF
  status: ISSUED | ACCEPTED | EXPIRED | SUPERSEDED
```
Plus an `AprCalculator` (IRR over the actual cash-flow vector; bisection on `BigDecimal` to 4dp, golden-file tested). Generate at **approval**. Block disbursement without an `ACCEPTED` KFS matching the loan. Blocked on C8 — APR is meaningless without a charge model.

**Cooling-off** (1 day short tenor / 3 days above a week, exit on proportionate interest + disclosed processing fee) is a loan-application state with zero representation today.

### 7.2 CKYC — a 10-day clock currently not running

**Obligation.** Upload the KYC record to CERSAI **within 10 days** of commencing the relationship; CERSAI returns a 14-digit KIN. **Search CKYCR first** — if a KIN exists, pull it rather than collecting fresh.

**Current state.** Zero hits for `ckyc`. No KIN field. No integration. The clock is running on every disbursed loan.

The LSP cannot discharge this — CERSAI upload is keyed to Bhawana's reporting-entity registration.

**Build:** `borrower.ckyc_kin/ckyc_status/ckyc_uploaded_at`; a `ckyc_submission` table (UPLOAD/SEARCH/DOWNLOAD, batch ref, status, rejection reason, KIN); a worker with a **day-7 alarm** for rejection slack. Bulk upload is SFTP with per-record zipped images under a signed master zip; API aggregators exist.

**Side benefit:** CKYC search-before-collect gives a better identity key than PAN, which is what enables C2.

### 7.3 Bureau reporting — fortnightly, with a compensation meter

**Obligation.** Since **1 Jan 2025**: report to CICs **fortnightly** (as on the 15th and last day), submitted **within 7 calendar days** of fortnight close. Appoint a **nodal officer** for CIC grievances; notify changes within **5 calendar days**. Disputes: CI has **21 days**, CIC 9 — 30 total. Beyond that the borrower is entitled to **₹100/day**.

**Current state.** Zero hits for `bureau|cibil|experian|equifax|crif`. No membership, pipeline, dispute workflow or nodal officer record.

**Hard dependency:** requires a per-account asset classification per reporting date — exactly what C6 and H13 deny.

**Build order:** day-end classification → durable per-loan classification rows → fortnightly extract → submission → dispute intake with a `ci_due_at` column so the ₹100/day meter is visible before it runs.

### 7.4 Screening — the one with an immediate-action obligation

**Obligation.** Under **UAPA s.51A**, screen against UNSC consolidated lists circulated by RBI. On a match: **freeze immediately — no court order.** Report to the designated UAPA officer and FIU-IND. Re-screen the existing book on every list update. Separately under PMLA: **PEP** identification with senior-management approval and EDD; **STR** to FIU-IND promptly on suspicion; **CTR** for cash ≥ ₹10 lakh/month (likely moot — no cash rail).

Also from the KYC MD: **customer risk categorisation** (Low/Medium/High) driving re-KYC periodicity of 10/8/2 years.

**Current state.** Zero hits for `sanction|pep|watchlist|screen|riskCategor|reKyc`.

**This carries the sharpest tail risk on the list.** Everything else produces a regulatory finding. Disbursing to a UNSC-listed person is a different category, and the freeze obligation is immediate and non-delegable.

**Build:** `sanctions_list_version`, `screening_result` (with `match_score`, `disposition`, maker-checker fields), `borrower.risk_category/is_pep/next_rekyc_due_at`. Gates at **onboarding and pre-disbursement** (the list may change between), plus a re-screen sweep on every ingest. Fuzzy name matching (transliteration variance in Indian names is significant) implies a potential-match queue with human disposition — the first genuine maker-checker use case.

### 7.5 Retention and erasure — neither capability exists

| Source | Requirement |
|---|---|
| PMLA §12 | Transaction records **5 years** from transaction; identity records **5 years after** the relationship ends |
| CERT-In 2022 | ICT logs **180 rolling days, in India** |
| MCA Rule 3(1) | Audit trail with edit log, **cannot be disabled** |
| DPDP §8(7), §12 | Erase when purpose served or consent withdrawn |
| DPDP Rules 2025 | Notify the principal **48 hours before** scheduled erasure; retain processing logs ≥1 year |

**Conflict resolution (settled):** DPDP §8(5) permits longer retention where required by law. RBI/PMLA retention **prevails** over an erasure request — but Bhawana must state the specific legal basis and **delete everything not covered by the mandate**. "We keep it all because PMLA" is not a defence.

**Current state.** One retention worker (idempotency records, 90 days). `RefreshTokenRepository.deleteByExpiresAtBefore` exists and is **called by nothing**. Zero storage deletes. No consent entity, no erasure endpoint, no borrower deletion path. 15 audit tables, zero partitioned, projected by the team's own docs at **50–150M rows/year**.

**And the erasure path, when built, will destroy the evidence** — `borrower_pii_reveal_audit` cascade-deletes from `borrower` (H20).

**Build:** `data_retention_policy` (class → legal basis → period → disposition); `erasure_request` with assessment, `notify_at`, tombstone. Plus: audit FKs → `ON DELETE RESTRICT`; tenant role → `SELECT, INSERT` only on audit tables; **R2 Object Lock in compliance mode** with separate write-only and read-only credentials; lifecycle rule expiring generated MIS files (they contain the whole book) at 30–90 days; partition audit tables by month; actually call `deleteByExpiresAtBefore`.

### 7.6 The three prerequisites

Five obligations, three foundations:

- **P1 — a charge model.** Blocks APR, KFS, penal charges, GST. `LoanProductVersion` has exactly two money fields today.
- **P2 — day-end asset classification.** Blocks bureau reporting and NPA/provisioning; fixes C6 and H15.
- **P3 — a borrower identity spine.** CKYC KIN, risk category, screening status, consent, re-KYC due date. Also fixes the cross-tenant PAN dedup behind C2.

---

## 8. Reference comparison — Apache Fineract

Read directly from source on `develop` (2026-07-31), not documentation.

### 8.1 What this validates

**Generate-schedule-up-front-and-mutate-`paid_*`-in-place is Fineract's design too.** `LoanRepaymentScheduleInstallment` (`m_loan_repayment_schedule`) carries `principal_completed_derived`, `interest_completed_derived`, `fee_charges_completed_derived`, `penalty_charges_completed_derived`, mutated by `payPrincipalComponent` / `payInterestComponent` / etc. `obligationsMet` + `obligationsMetOnDate` are the analogues of `status`.

**Reset-and-replay is Fineract's central mechanism, not an anti-pattern.** `reprocessLoanTransactions` calls `resetDerivedComponents()` on every installment and `resetPaidAmount()` on every charge, then re-runs every live transaction from disbursement. Its javadoc: *"required in cases where the LoanTransaction being processed is in the past and falls before existing transactions."*

**Journal entries are optional.** `AccountingRuleType.NONE(1, "No accounting")` is a first-class supported product configuration.

### 8.2 The four differences that matter

| Concept | Fineract | Bhawana |
|---|---|---|
| Money dimensions | principal / interest / **fee** / **penalty**, each ×paid ×waived ×written-off ×accrued | principal / interest |
| Schedule precision | `scale = 6` | `scale = 2` |
| Allocation authority | **One.** `processTransaction` takes **no installment id** — incremental is a one-transaction instance of replay | **Two.** `applyPayment` targets a named installment; replay ignores the FK |
| Transaction↔installment mapping | `m_loan_transaction_repayment_schedule_mapping` — one-to-**many**, with the four-way split per installment, **rebuilt every pass** | single nullable FK, no amounts, **ignored by the replay** |
| Changed allocation on replay | `transactionAmountsMatch` → mismatch → **reverse original + insert replacement + `REPLAYED` relation + raise event** | silent mutation, no record, no event |
| Residual money | `MoneyHolder` threaded through replay → `overpayment_portion_derived` → `total_overpaid_derived` → `LoanStatus.OVERPAID(700)` | undefined |
| Transaction types | **48**, incl. `CHARGEBACK(25)`, `REFUND(16)`, `WRITEOFF(6)`, `WAIVE_INTEREST(4)`, `RECOVERY_REPAYMENT(8)`, `CONTRACT_TERMINATION(38)`, `INTEREST_REFUND(33)`, `REAGE(29)` | none — `LoanPaymentChannel` enumerates *rails*, not financial events |
| Allocation order | Configurable per product; default `mifos-standard-strategy` = penalty → fee → interest → principal; `AdvancedPaymentScheduleTransactionProcessor` + `PaymentAllocationType` (12-value `DueType`×`AllocationType`) is fully data-driven | interest → principal, hard-coded (coherent given no charges) |
| Stored outstanding | **None.** Always derived: `principal − principalCompleted − principalWrittenOff` | stores `outstanding_amount` **and** `closing_principal` — two extra copies that can drift |

Note Fineract suffixes every mutable column `_derived` — encoding at schema level that these are a rebuildable projection. That naming is what licenses the reset-and-replay.

### 8.3 What specifically breaks here that doesn't in Fineract

1. **Divergence between the two allocators** the first time a payment doesn't exactly match its tagged installment — currently masked by H3's exact-amount rule.
2. **The FK cannot represent a payment spanning two installments** — physically impossible for any payment larger than one installment's due — and is affirmatively wrong after the first recompute. Receipts built on it are unreliable.
3. **A changed allocation leaves no audit trail and no event.** A borrower dispute has no answer.
4. **Residuals concentrate under replay** — replay is worse than incremental application here, not better.
5. In-advance vs late is lost. Fineract records `total_paid_in_advance_derived` / `total_paid_late_derived`.

### 8.4 Ranking the four absences

1. **Reversal — universal, not skippable, cheap.** Two columns plus a flag in the replay's transaction query. It is a **prerequisite** for replay being correct: Fineract can only correct a mis-split because it can reverse. Bhawana has the expensive half without the cheap half.
2. **Overpayment — effectively universal, and specifically dangerous given the replay.** Could reduce to a single `overpaid_amount` + `OVERPAID` status — roughly all Fineract has at its core.
3. **Charges — legitimately skippable *if the product truly has none*, but write an ADR.** Retrofitting a penalty bucket ahead of interest means re-deriving every historical schedule.
4. **Journal entries — genuinely skippable, conditionally.** **Open question:** is this platform the accounting book of record? If the GL lives elsewhere and this feeds it, skipping is legitimate and Fineract-blessed. If this *is* the book of record, it flips to mandatory — a principal/interest split with no offsetting entries means interest income is never recognised anywhere.

---

## 9. Stack choices — keep vs revisit

### 9.1 Validated by practitioner evidence — do not change

- **Postgres RLS with a dual-pool, non-owner-role, transaction-scoped design.** The `set_config(..., true)` + `autoCommit=false` combination is the documented fix for the #2 reported failure mode; running as a non-owner role is the fix for the #1. No credible write-up found of a team abandoning RLS in production — the failure stories are all misconfiguration. **The one real misconfiguration here is the missing `FORCE ROW LEVEL SECURITY` (C1).**
- **Modular monolith, single deployable.** Shopify is explicit that splitting *"increases the overall complexity considerably."* Segment's 140-service estate cost three FTEs just to keep alive before consolidation. Nothing at NBFC scale forces a split.
- **No broker; `@Scheduled` + advisory locks + outbox with `FOR UPDATE SKIP LOCKED`.** Documented breaking points (~100 concurrent workers; 800 jobs/sec with competing analytics) are orders of magnitude above this volume. Advisory locks for scheduler singleton and `SKIP LOCKED` for claiming is the correct division.
- **The idempotency design** is Brandur's/Stripe's published design nearly point-for-point, with Adyen's tenant scoping. Fix *where it runs* (C1), don't redesign it. Do pin the retention window (Adyen's ≥7 days is more defensible than Stripe's 24h for a system with bank reconciliation cycles) and decide deliberately whether 5xx responses are stored.
- **`BigDecimal` / `NUMERIC` for stored money.** Correct. The problems are the wire format (M1) and the computation precision (H2), not the storage type.
- **UUIDv4 — do not retro-migrate.** The pro-v7 literature's own migration advice is conservative, and v7 leaks creation timestamps — for externally-visible loan IDs a counterparty could infer origination volume. On PG17 native `uuidv7()` isn't available anyway. Consider v7 only for new high-insert append-only tables if index bloat is ever measured.

### 9.2 Explicitly de-scoped for this system

Researched and found **not** applicable at current stage/scale — recorded so they don't inflate the backlog: ledger-at-scale architecture (Uber LedgerStore, TigerBeetle batching, 5,000 QPS targets); Postgres-queue breaking points; UUIDv7 migration; multi-currency ledger balancing; day-count convention configurability; frontend accessibility and responsive findings (internal ops tool, no borrower UI); **co-lending** (LSP model confirmed — parked, revisit only if balance-sheet participation is taken).

---

## 10. What is genuinely well-built

Calibration matters. These are load-bearing and should not be disturbed.

**Money movement**
- **The disbursement intent state machine is correct payments design.** Intent committed with a deterministic `tranRefNo` before any provider call; `REQUESTED` persisted as the point-of-no-return **before** the side effect (`DisbursementIntent.markProviderCallStarted()` — *"Persists the point of no automatic retry before the provider side effect"*); provider call outside any transaction; adapter exceptions become `UNKNOWN`, never `FAILED`, so the intent stays live and blocks reissue. Crash-recovery tests exist.
- **The one-live-intent invariant is a database constraint**, not an application check: a partial unique index on `loan_account_id WHERE state NOT IN ('SUCCEEDED','FAILED','CANCELLED')`.
- **The beneficiary is snapshotted onto the intent, not read live at payout** — a borrower cannot change their account mid-flight. Reinforced by a hard block on bank edits during in-flight disbursement.

**Distributed systems**
- **Idempotency leasing** — live leases make duplicates *wait*; expired leases reclaim via attempt-number CAS gated on an operation-specific reconstructor; unreconstructable outcomes fail loudly with `IDEMPOTENCY_RECOVERY_REQUIRED` rather than guessing. The in-progress 409 carries a computed `Retry-After` from the actual lease expiry.
- **The outbox claim query** — a single CTE with `FOR UPDATE SKIP LOCKED … RETURNING` that atomically claims and correctly reclaims stale `IN_FLIGHT` rows past `claim_expires_at`.
- **Transaction boundaries around the HTTP call** are explicitly structured so the non-transactional call never holds a DB connection, with a 12-line comment explaining why self-invocation would bypass the proxy.
- **Webhook HMAC signing includes the timestamp in the signed material** and sends it as `X-Webhook-Timestamp`, so replay protection is available to partners.

**Tenancy**
- **Tenant context fails closed** — `TenantRoutingDataSource:17` throws rather than defaulting.
- **`TenantAwareDataSource`** — `SET LOCAL` semantics via `set_config(..., true)`; tenant pool forced to `autoCommit=false` so even non-transactional reads sit in a transaction; role name regex-validated before interpolation; connection returned to the pool on failure, with the reasoning written down.
- **RLS fails closed by raising**, not by returning rows — `V45` documents this as intentional ("raises loudly in logs instead of silently returning NULL").

**Code craft**
- **Zero public setters across 95 domain entities.** 515 getters, 109 behaviour methods with intention-revealing names (`applyPayment`, `canTransitionTo`, `markProviderCallStarted`, `claim`, `clearLease`, `close`, `deactivate`).
- **Zero `@Lazy` injections in 411 files**, enforced by a test whose allowlist is empty: *"B7 guardrail: `@Lazy` constructor injection is legacy debt, not an approved pattern."* Circular-dependency debt was eliminated and ratcheted shut.
- **Layering enforced by ArchUnit**, not convention. Max service fan-in is 12 — no god-service.

**Domain**
- **`LoanProductVersion` freezes pricing correctly.** All six money-read call sites traced; none reads the live product row.
- **LSP-provided schedule validation is real domain work** — opening-principal chain, per-row reconciliation, total closure, cadence tolerance, and per-row *and* total interest checked against an independently regenerated schedule, with a prioritised violation taxonomy.
- **Terminal statuses are genuinely terminal**, enforced at the state machine and on manual override.
- **`LoanApplicationStatus.canTransitionTo`** is an explicit transition guard.

**Documents and evidence**
- **No presigned URLs anywhere.** Bytes proxied through an authenticated endpoint — no shareable, replayable URL for a KYC document.
- **Magic-byte validation** on upload (`PNG_SIGNATURE` + `validateContentMatchesMime`), allowlist of pdf/jpeg/png only, `..` filename rejection.
- **Single-document streaming** keeps the object-storage round-trip outside any DB transaction; a rejected access is never logged as successful; if the audit write fails, the stream closes and access is denied.
- **Keyset pagination across a heterogeneous audit union is correct** — the tuple predicate exactly mirrors the ORDER BY, with `limit+1` has-more detection and the cursor built from the last returned row.
- **`LspAuditEventService` and `BorrowerBankDetailsService`** are the model the rest of the codebase should copy — explicit before/after nodes, actor + IP + correlation id on every write.

**Documentation**
- **ADR 0005** narrates a real production regression (#89) and explicitly rejects the interim fix that reintroduced implicit-admin. Not generated text.
- **`CONTEXT.md`'s disbursement glossary** (point of no return, in flight, debit/credit legs) is why the intent machine is correct — the bank-account section (§1.1) is the exception.
- **The migration runbook** names the one-shot migrations, states required start state, and frames recovery correctly: *"The first decision must be 'what state are we in?', not 'which migration can we replay?'"*
- **The team's own architecture docs already name several of these gaps** — *"detection without delivery"*, *"not yet deployed"*, *"no partitioning or retention yet"*. A sequencing gap, not a blind spot.

---

## 11. Remediation sequence

### Immediate — days

| # | Item | Closes |
|---|---|---|
| 1 | `IdempotencyExecutionCoordinator`: run `action.get()` under the caller's tenant scope. Add `FORCE ROW LEVEL SECURITY` to every tenant table | C1 |
| 2 | Block bank-field mutation in `mergeLatestProfile` when the borrower is visible to another LSP; raise `BORROWER_IDENTITY_CONFLICT` | C2 |
| 3 | Stable idempotency keys per intent (mint on dialog open, hold in a ref) | C9 |
| 4 | Delete the `/manual-status` fallback in `api-detail.ts` | C10 |
| 5 | Delete `spring.profiles.default`; remove every security-sensitive `${VAR:fallback}` | C11 |
| 6 | Bump `tokenVersion` on status change; check status in `validateSession` **and** `rotateRefreshToken`; cascade `revokeAllSessions` over `app_user` on LSP disable | C3, C4 |
| 7 | Rewrite the `CONTEXT.md` bank-account glossary; confirm sole mandate with ICICI | §1.1 |

### Weeks — before any real borrower

| # | Item | Closes |
|---|---|---|
| 8 | Foreclosure: accrue to settlement date, discard unearned interest | C5 |
| 9 | Delinquency: drive off schedule due dates for all active statuses | C6 |
| 10 | **Unify the allocator** — make incremental a one-transaction case of replay; add the transaction↔installment mapping table with per-component portions; add reversal | C7, H1, H3 |
| 11 | Carry ≥6 decimals through money computation; round once at the persistence boundary | H2 |
| 12 | Disbursement cap + daily per-LSP velocity limit; intent `CANCELLED` path with maker-checker | H6, H8 |
| 13 | Prometheus registry + alert delivery to a human; health-check both pools | C12, H44 |
| 14 | **Sanctions screening** — no dependencies, highest tail risk | §7.4 |
| 15 | LSP read rate limits; trusted-proxy contract; webhook secret rotation grace | H35, H36, H37 |

*Item 10 is roughly two weeks and is the highest-leverage change on the money side: it makes partial payments possible, makes the replay honest, and is the prerequisite for charges, foreclosure rebate and bureau reporting.*

### Quarter — foundational

| # | Item | Closes |
|---|---|---|
| 16 | **Flip the test default to Testcontainers Postgres + Flyway.** Until this lands, every fix above is unverifiable | C13 |
| 17 | **P1** — charge model → APR calculator → KFS generation + cooling-off | C8, §7.1 |
| 18 | **P2** — day-end classification job → durable SMA/NPA tags → bureau reporting + nodal officer + dispute clock | H13, H14, H15, §7.3 |
| 19 | **P3** — CKYC search + upload (10-day clock is running now) | §7.2 |
| 20 | Audit immutability: `SELECT, INSERT` grants only, FKs → `ON DELETE RESTRICT`, hash chain, external append-only sink | H19, H20, H21 |
| 21 | Retention policies, R2 Object Lock, lifecycle rules, partitioning, erasure workflow | H26, §7.5 |
| 22 | As-of-dated reproducible MIS; audit the preview endpoint; mask PAN uniformly | H16, H17, H22, M7 |
| 23 | Maker-checker on money and identity actions | H27 |
| 24 | RLS policy hardening: wrap `current_setting` in a subselect, mark helpers `LEAKPROOF PARALLEL SAFE`, lead tenant indexes with `lsp_id` | M2 |

---

## 12. Open questions

1. **Is this platform the accounting book of record?** Determines whether journal entries are skippable (§8.4). Question for finance, not engineering. Difference between a two-week job and a two-month one.
2. **Is `PRODUCT_ADMIN` intended to change live interest rates alone?** Currently true, undocumented.
3. **Is `OPS_USER` intended to create applications for any LSP?** Currently true (H33), undocumented, and inconsistent with the eight `SYSTEM_ADMIN`-restricted siblings in the same controller.
4. **ICICI account mandate** — sole Bhawana signatory, or joint with the LSP?

---

## 13. Method, limits, and corrections

### Corrections made during the audit

Recorded because they affect how much weight to give the rest:

- **"Business date computed in UTC"** was wrong in shape. The *"today"* path is correctly `Asia/Kolkata` via `BusinessCalendar`. Only `Instant → LocalDate` conversions are UTC. The mix is the defect (H12).
- **"Real systems keep the schedule immutable"** was wrong. Fineract mutates `paid_*` in place. The Bhawana design is standard (§8.1).
- **"Replay is an anti-pattern"** was wrong. It is Fineract's central mechanism. The defect is replay *without a persisted mapping and without reversal* (§8.3).
- **"No double-entry ledger" was over-weighted.** Fineract ships `AccountingRuleType.NONE`. The criticism is conditional on being the book of record (§12.1).
- **Aadhaar is masked on the LSP API surface** — an early suspicion that it wasn't did not survive verification. PAN is not masked (M7).
- **Document upload validation is genuinely good** — an expected hole (client-supplied MIME) turned out to be magic-byte validated.
- **`FifoLoanRepaymentScheduleTransactionProcessor`** does not exist in Fineract; FIFO is the fixed due-date ordering inside `processTransaction`.

### Not reviewed

Live ICICI sandbox traffic or production secrets; real R2 bucket configuration (lifecycle, object lock, versioning, IAM scoping) — findings state only what the application requests; deployed Supabase managed-backup capability outside version control; load/performance beyond static reading; full Playwright E2E execution; legal opinion on licence status; the backend test suite bodies (155 of 158 classified by annotation and name census, not read); most of `AdvancedPaymentScheduleTransactionProcessor`; Fineract's Liquibase changelogs (column names taken from JPA annotations, authoritative for mapping but not for indexes/constraints).

**Nothing was executed.** No build, no test run, no container start, no database query. All findings are static.

### Verification record

| Wave | Scope | Lead verification |
|---|---|---|
| Lead pass | Schema, migrations, money path, tenancy, architecture metrics | Direct |
| Frontend | Auth, mutation safety, error handling, validation parity | C9, C10 verified line-by-line |
| Partner API | Contract, webhooks, idempotency, rate limiting | H39, H40, H41 spot-checked |
| Ops/testing | Observability, workers, DR, test strategy | C11, C12, C13 verified |
| Servicing/money | Foreclosure, DPD, payments, disbursement | C5, C6, C7 verified |
| Identity/tenancy | Sessions, elevation, RLS coverage, escalation | C1, C2 verified; `FORCE RLS` count confirmed |
| Documents/reporting | Storage, MIS, audit trail | H18, H22, H25, H26 verified |
| Research | Fineract source; practitioner literature | Fineract entities read directly from `develop` |

---

## 14. Sources

**Regulatory**
- [RBI — Key Facts Statement for Loans and Advances, 15 Apr 2024](https://rbidocs.rbi.org.in/rdocs/notification/PDFs/CIRCULARKFS1504242AE2500BAF494C2A82442B0B642705C1.PDF) · [APR inclusions/exclusions and validity analysis](https://vinodkothari.com/2024/04/the-key-to-loan-transparency-rbi-frames-kfs-norms-for-all-retail-and-msme-loans/)
- [RBI (Digital Lending) Directions, 2025 — flow of funds, RE liability for LSP acts](https://www.lawrbit.com/article/reserve-bank-of-india-digital-lending-directions-2025/)
- [RBI (Pre-payment Charges on Loans) Directions, 2025](https://elplaw.in/wp-content/uploads/2025/07/Reserve-Bank-of-India-Pre-payment-Charges-on-Loans-Directions-2025-issued-on-July-2-2025.pdf)
- [RBI — Fair Lending Practice: Penal Charges in Loan Accounts](https://corporate.cyrilamarchandblogs.com/2023/09/fair-lending-practices-on-levy-of-penal-charges/)
- [RBI Nov-2021 IRAC clarification — SMA/NPA at day-end, upgrade on full clearance](https://vinodkothari.com/2021/11/npa-classification-norms-2/)
- [RBI Credit Information Reporting Directions 2025 — fortnightly, nodal officer, 21/9-day split, ₹100/day](https://www.caclubindia.com/articles/comprehensive-overview-of-rbis-master-direction-on-credit-information-reporting-2025-52957.asp)
- [RBI (NBFC – Managing Risks in Outsourcing) Directions, 2025 — core functions](https://taxguru.in/rbi/rbi-non-banking-financial-companies-managing-risks-outsourcing-directions-2025.html)
- [RBI Master Direction – KYC, §15 third-party reliance](https://www.rbi.org.in/commonman/Upload/English/Notification/PDFs/MD18KYCF6E92C82E1E1419D87323E3869BC9F13.pdf)
- [RBI Master Direction on IT Governance, Risk, Controls (eff. 1 Apr 2024)](https://www.argus-p.com/updates/updates/rbi-issues-master-directions-on-information-technology-governance-risk-controls-and-assurance-practices/)
- [UAPA s.51A screening and immediate freeze](https://www.casansaar.com/notification-rbi/rbi-updates-unsc-sanctions-list-under-uapa-section-51a/6246.html) · [FIU-IND UNSC list](https://fiuindia.gov.in/files/misc/UNsanctionList.html)
- [CKYC / CERSAI — 14-digit KIN, templates, bulk SFTP](https://vinodkothari.com/wp-content/uploads/2017/03/CKYC_Registry_Uploading_of_KYC_data-1.pdf)
- [UIDAI Aadhaar Data Vault FAQ](https://uidai.gov.in/images/resource/FAQs_Aadhaar_Data_Vault_v1_0_13122017.pdf)
- [PMLA §12 — five-year retention](https://www.lexology.com/library/detail.aspx?g=051102c5-9058-43c8-99c0-9c1def734dc4)
- [DPDP §8(5) vs PMLA/RBI retention — which prevails](https://www.dpo-india.com/Blogs/interplay-india%E2%80%99s-dpdp-act/) · [DPDP Rules 2025](https://www.ey.com/en_in/insights/cybersecurity/transforming-data-privacy-digital-personal-data-protection-rules-2025)
- [CERT-In Directions 2022 — 180-day log retention, 6-hour reporting](https://www.cert-in.org.in/PDF/CERT-In_Directions_70B_28.04.2022.pdf)
- [MCA Rule 3(1) — audit trail cannot be disabled](https://ca2013.com/rule-3-companies-accounts-rules2014/) · [Rule 11(g) auditor obligation](https://www.mbgcorp.com/in/insights/auditors-reporting-for-audit-trail/)

**Reference implementations (source read directly)**
- Apache Fineract `develop` — `LoanRepaymentScheduleInstallment`, `LoanTransactionToRepaymentScheduleMapping`, `LoanTransaction`, `LoanTransactionType`, `LoanCharge`, `ChargeTimeType`, `ChargeCalculationType`, `AccountingRuleType`, `LoanRepaymentScheduleTransactionProcessor` + implementations, `PaymentAllocationType`, `LoanStatus`, `ReprocessLoanTransactionsServiceImpl`, `ApplyChargeToOverdueLoanInstallmentTasklet`
- [Mambu — truncating and rounding interest](https://docs.mambu.com/docs/truncating-and-rounding-interest-loans/)

**Engineering practice**
- [PostgreSQL — Row Security Policies](https://www.postgresql.org/docs/current/ddl-rowsecurity.html) · [SET](https://www.postgresql.org/docs/current/sql-set.html)
- [dian m fay — fixing slow row-level security policies](https://di.nmfay.com/rls-performance) · [Supabase — RLS performance](https://supabase.com/docs/guides/troubleshooting/rls-performance-and-best-practices-Z5Jjwv) · [Bytebase — RLS footguns](https://www.bytebase.com/blog/postgres-row-level-security-footguns/)
- [PlanetScale — keeping a Postgres queue healthy](https://planetscale.com/blog/keeping-a-postgres-queue-healthy) · [Richard Yen — consequences of Postgres as a job queue](https://richyen.com/postgres/2026/05/04/postgres_job_queue.html) · [postgresql-job-queue-benchmarking](https://github.com/hardbyte/postgresql-job-queue-benchmarking)
- [Brandur — implementing Stripe-like idempotency keys in Postgres](https://brandur.org/idempotency-keys) · [Stripe — idempotent requests](https://docs.stripe.com/api/idempotent_requests) · [Shopify — resilient APIs using idempotency](https://shopify.engineering/building-resilient-graphql-apis-using-idempotency) · [Adyen — API idempotency](https://docs.adyen.com/development-resources/api-idempotency)
- [Stripe — currencies / minor units](https://docs.stripe.com/currencies) · [RFC 8259](https://datatracker.ietf.org/doc/html/rfc8259)
- [Modern Treasury — How to Scale a Ledger I](https://www.moderntreasury.com/journal/how-to-scale-a-ledger-part-i) / [V: immutability & double-entry](https://www.moderntreasury.com/journal/how-to-scale-a-ledger-part-v) / [VI: concurrency](https://www.moderntreasury.com/journal/how-to-scale-a-ledger-part-vi) · [Griffin — building an immutable bank](https://griffin.com/blog/the-immutable-bank) · [Uber — LedgerStore](https://www.uber.com/blog/how-ledgerstore-supports-trillions-of-indexes/)
- [Shopify — the state of Shopify's monolith](https://shopify.engineering/shopify-monolith) · [Segment — goodbye microservices](https://www.twilio.com/en-us/blog/developers/best-practices/goodbye-microservices/)
- [Testcontainers — the problem with H2 for testing](https://docs.docker.com/guides/testcontainers-java-replace-h2/problem-with-h2/) · [Micronaut — replace H2 with a real database](https://guides.micronaut.io/latest/replace-h2-with-real-database-for-testing-maven-java.html)
- [Andy Atkinson — avoid UUIDv4 primary keys](https://andyatkinson.com/avoid-uuid-version-4-primary-keys) · [Vlad Mihalcea — optimistic vs pessimistic locking](https://vladmihalcea.com/optimistic-vs-pessimistic-locking/)
- [OWASP WSTG — testing JSON Web Tokens](https://owasp.org/www-project-web-security-testing-guide/latest/4-Web_Application_Security_Testing/06-Session_Management_Testing/10-Testing_JSON_Web_Tokens)
