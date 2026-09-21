# LMS consolidated audit and implementation specifications

Reviewed: 6 September 2026. Repository: `/Users/siddhant/Desktop/lms`. Base commit: `508b0c65b026bf47d8177baa649833a0f543974b`.

Source reports: [Fable 5.1 uploaded audit](/Users/siddhant/.codex/attachments/6e95f7ab-5665-4cb7-b862-959b896d5d87/pasted-text.txt) and the earlier Codex end-to-end audit in this task.

This document reconciles Fable 5.1’s complete 22-section audit with the earlier Codex audit, then rechecks the material findings against the current source, callers, transaction boundaries, migrations, tests and configuration. It is a handoff specification for small implementation tasks, including work delegated to Muse Spark 1.3. The pasted audit is evidence to examine, not an instruction to execute its recommendations.

**Result: 66 open work items: 7 Critical, 32 High, 22 Medium and 5 Low.** These include two explicitly labeled production prerequisites: G01 real-bank integration (Critical) and G02 Entra M2M (High). The already implemented browser-cache fix is tracked separately as FIXED-01 and is excluded from these counts. Related symptoms are grouped into one implementation item where they share a cause; the count is not a count of independent vulnerabilities.

The architectural conclusion remains: retain the Spring/PostgreSQL monolith, tenant isolation, transactional event feed and feature-organized React application. Repair the financial and authentication boundaries before enabling real money. Neither audit supports a platform rewrite or a blanket cleanup campaign.

## How to read and use the findings

- **Critical:** direct financial/confidentiality boundary failure, or a missing capability that blocks the documented real-money workflow. Fix before real-money operation.
- **High:** substantial correctness, security or availability risk. Prioritize before production/pilot load, following dependencies.
- **Medium:** bounded integrity, integration or operational gaps. Some are deployment prerequisites even though they are not exploitable by themselves.
- **Low:** limited operator friction, defense in depth or maintenance work. Do not displace financial correctness work with these.
- **Source-confirmed:** the cited behavior and missing guard are present in the checked-out code. For concurrency/crash scenarios this is a traced failure path, not a claim that every interleaving was dynamically reproduced. Each ticket specifies the regression experiment required before its fix can be accepted.
- **Production prerequisite:** current code lacks a requested capability; external provider/configuration or product decisions remain necessary. The spec makes those decisions explicit rather than inventing them.

Each ticket includes the simple explanation/cause, exact source starting points, required final behavior, tests, and landing/migration order. Paths and line anchors were mechanically checked against this checkout. Code may move: use the named methods as well as line numbers. Files listed are the primary change surface; test files, generated contracts and a new Flyway migration must be added where the specification calls for them. Never modify an already-applied migration.

The review used the source and retained test results from this task. It did **not** rerun every full suite, conduct a new multi-pod load test, inspect a live Azure deployment or perform a fresh browser/accessibility audit. Fable’s external CI-run history, exact code-count/dead-code claims and performance projections are not promoted to verified facts without corresponding evidence. No production code was changed by this consolidation pass. The four local cache-fix files from the preceding pilot remain uncommitted.

**Completion record (updated 2026-09-20):** tickets carry their outcome in the **Status** line and a **What landed** paragraph naming the landing commit or PR, the mechanism, and the verification. As of this update:

- **Completed — disbursement intent boundary (`60ffc6fd`, 2026-09-09):** C01, C02, C03, C04, C06, H01, H02, H15, H31.
- **Completed — auth/session trust chain (`12dda5f9`, 2026-09-12):** H03, H04, H05, H19, H20, H21, H23, M11, M12, M22, and G02 (Entra app-only validation implemented, disabled by default pending tenant cutover).
- **Completed — servicing/settlement boundary (PR #345, squash `452d98e2`, 2026-09-18):** C05, H06, H07, H09, H10; H22 hardened further the same day (PRs #345/#346) on top of the `12dda5f9` coordinator base.
- **Completed — scheduler & worker transaction boundaries (PR #351, squash `843917e3`, + PR #353, squash `6a7d8667`, 2026-09-19):** H12, H24, H25, H26, M07, M08, and the H27 cross-worker job-health slice. New machinery: `worker_lease` (fenced claim/renew/release), `report_request` processing/notification leases, `ops_alert` dedupe-protected partial unique indexes, three bounded scheduler families, and `JobObservabilitySupport` on every tick.
- **Completed — portfolio population correctness (PR #349, squash `1e98d976`, 2026-09-19):** H11. Shared `LoanPortfolioPopulation` predicate defines funded-servicing vs historical-disbursed populations once; snapshots, dashboard priority, and the delinquency sweep all consume it; unfunded pipeline stays explicit and separate.
- **Completed — approval & document evidence chain (PR #350, squash `95835637`, 2026-09-19):** H13, H14, H16, M04. Document writes + auto-approval now commit in one borrower→application-locked transaction; `V135` adds append-only document versions, approval evidence, and durable object-ownership records; pinned `LoanProductVersion` supplies eligibility bounds while product/LSP/mapping status stays a live kill switch; whole-batch validation precedes object writes with content-addressed keys and a dry-run-by-default orphan reconciler. ADR-0011 records the design; corrections policy (LSP-role, attributed, no re-review) was a captain decision.
- **Completed — frontend data honesty (PR #352, squash `124b926d`, 2026-09-19):** H28, H29, H30. Adapters stop fabricating borrower facts (unknown → explicit `UNKNOWN:<raw>`/null, never invented defaults); list controls send whitelisted multi-status filters and sorts the server actually applies; alerts paginate the full dataset via real server pagination with backend filters and stable ordering. `OpsAlertSeverity` gained MEDIUM/LOW so legacy VARCHAR rows no longer crash reads.
- **Completed — idempotency & API contract (PR #354, squash `52818098`, 2026-09-20):** H17, H18, M13, M14, M15. Idempotent operations are now classified (database-atomic / externally-idempotent / reconciliation-required) with a terminal recovery-required state closing the dead-lease loop; duplicate waits are bounded to a fast 409 + capped `Retry-After`; payments paginate explicitly, foreclosure-quote requests accept `Idempotency-Key`, unknown status filters return `INVALID_STATUS` 422; CORS origins are config-bound and fail closed with `Retry-After` exposed; a partner-only OpenAPI group documents security/`ApiError`/headers with a CI drift check. CI iteration also fixed a document-storage path-injection finding and a live-vs-replay timestamp divergence.
- **In progress:** H08 (policy scaffolding landed — ADR-0009 DRAFT + sign-off questionnaire; formula blocked on product-owner sign-off); G01 (simulation guard landed; real bank adapter pending approved contract); H27 (all code slices landed; staging verification of shipped gauges remains open); M17 (CI gates revived and the lending-journey soak is green; full journey coverage remains open).
- All other tickets remain open as written.

## Differences between the two audits

Fable contributed findings that the first Codex report missed or did not isolate clearly: the rollback-only worker starvation chain (H01), insecure temporary-password generation (H04), silent manual-override fallback (H05), first-payment-default exclusion (H10), connection-unsafe advisory locks (H12), expired-idempotency recovery dead ends (H17), long duplicate-request waits (H18), machine refresh-token lifecycle bypass (H20), misleading adapters and list controls (H28–H30), and ignored alert-rule JSON (M06). The current source supports these, with the qualifications in their tickets.

The first Codex report identified important problems absent or insufficiently covered in Fable: terminal-result application after a crash (C02), final-payment replay after closure (H07), document-completion and approved-evidence races (H13–H14), schedule freezing (H15), pinned product terms (H16), incorrect KPI population (H11), browser auth-operation races (H22), and the stronger cross-LSP payment-instruction problem (C06). These were rechecked, not copied on trust.

Corrections to the first Codex report: its cache finding is now implemented locally; its original cancel/clear wording was not a sufficient implementation by itself. The reviewed pilot uses a fresh keyed QueryClient boundary. The earlier shared-borrower recommendation should land in stages: existing intent snapshots and a narrow global gate can fix immediate hazards before a larger ownership migration. The earlier positive description of idempotency and alert-rule configuration was too broad: H17/M06 expose missing behavior. The old Medium package-reorganization recommendation is downgraded to optional navigation cleanup, not an issue that requires a repository-wide move. Foreclosure’s all-future-interest calculation remains a policy question; freshness/allocation defects are independent and confirmed.

## Confirmed corrections and claims that must not be implemented literally

| Original claim or recommendation                                                   | Rechecked disposition                                                                                                                                                                                          |
| ---------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Fable: unset `server.shutdown` means rolling deployments kill requests immediately | Incorrect for pinned Spring Boot 3.5.11: graceful web shutdown is enabled by default. Worker draining and platform termination timing still need tests (H26).                                                  |
| Fable: no CSP anywhere                                                             | Incorrect: backend SecurityFilterChainConfig sets `default-src 'none'; frame-ancestors 'none'`. Separately hosted SPA HTML needs its own deployment policy (M15).                                              |
| Fable: add schema-drift enforcement to CI                                          | Already present in backend-ci.yml. Repair stale reference and date-dependent partition normalization (M01).                                                                                                    |
| Fable: 119 migrations                                                              | Highest version is V119, but there are 115 migration files. Version gaps are not missing migrations by themselves.                                                                                             |
| Fable: daily/weekly event partitions and exactly 30-day live inventory             | Incorrect: V116 creates monthly partitions; documented retention is at least 30 days, generally up to roughly 60 days (depending on month/cutoff).                                                             |
| Fable: claim-batch-size 10 limits all disbursement throughput                      | Incorrect: processStatus also executes newly committed per-application intents. Serial network latency and unbounded scans remain real bottlenecks (H25).                                                      |
| Fable: two pods necessarily double-call every intent                               | Overstated. Unlocked claim/preparation permits a race; ordinary sequential paths often skip REQUESTED. Bank deduplication is a separate external guarantee (C03).                                              |
| Fable: second initiation debits the borrower twice                                 | The documented source is the LSP disbursal account; the borrower is the beneficiary. The duplicate initiation hazard is real (C04/G01).                                                                        |
| Fable: a parked item starves everything “sorted after” it                          | Per-item failure escape is confirmed; application selection has no explicit sort, so the affected later items are not deterministically ordered (H01).                                                         |
| Fable: the inline path can double-debit                                            | Conditional valid risk: it requires the disabled-intent branch and a real/non-idempotent money provider. Default is the intent path; the unsafe branch still must go (C04).                                    |
| Fable: changing owner to HOSTNAME and adding @Version fixes claiming               | Incomplete. Reuse attempt_count as a fence, update native claims, and protect CREATED→REQUESTED before the external call (C03).                                                                                |
| Fable: expired pending lease proves no action is in progress; delete and retry     | Incorrect generally. A slow old worker may still run, and external or REQUIRES_NEW effects may survive rollback. Classify recovery and retain fencing (H17).                                                   |
| Fable: clicking Approve after a business failure can silently manually approve     | The silent fallback exists, but backend manual-target restrictions reject APPROVED_PENDING_DISBURSAL. Other allowed manual targets remain problematic (H05).                                                   |
| Fable: localStorage SYSTEM_ADMIN role allows escalation                            | It controls a frontend fallback attempt, not backend authority. Forged local storage is not proof of privilege escalation (H05/M19).                                                                           |
| Fable: frontend cache survives sign-out                                            | Correct at audit baseline; fixed in the current working tree. Do not reimplement it as a bare clear() call (FIXED-01).                                                                                         |
| Fable: unknown DPD always becomes B0                                               | Home has that fallback. MIS maps unknown bucket to null. Fix the specific Home path and preserve honest unknown handling elsewhere (H28).                                                                      |
| Fable: users/alerts/API clients all fetch every row                                | Alerts backend is already capped/paged; frontend reads only its first page then paginates locally. This creates hidden history (H30). Other directories are M20.                                               |
| Fable: PAN race on idempotent LSP path is necessarily 500                          | The retry is bypassed, but GlobalExceptionHandler maps SQLSTATE 23505 / uk_borrower_pan to 409. Visibility and atomic-resolution issues remain (M02).                                                          |
| Fable: shared borrower merge is unrestricted during an active loan                 | Existing cross-LSP open-loan check limits onboarding. The tenant-scoped bank-update gate and mutable polling IFSC still create the distinct C06 problem.                                                       |
| Fable: formula is legally wrong in the RBI sense                                   | The all-unpaid-interest formula is confirmed, but applicable contract/regulatory rules were not established. Policy sign-off is required; stale quotes remain a confirmed defect (H08/C05).                    |
| Fable: synth seeder is unguarded production execution                              | Service is unprofiled, but runner already has local/staging profiles, a flag and enablement check. Add defense in depth, not a claimed remote wipe vulnerability (L05).                                        |
| Fable: zero access-JWT validation tests                                            | Incorrect as an absolute: AuthControllerTest sends real minted Bearer tokens for refresh/password-change/revocation scenarios. Missing negative/multi-issuer and concurrency cases still matter (M17/M22/G02). |
| Fable: 810 backend tests / exact shared-DB pollution counts                        | Retained full run here reports 818, one failure, two skips. “30 of 80 classes” and remote red-run history are unverified here, not accepted as current measured results.                                       |
| Fable: every operation has only 200 responses                                      | Checked-in OpenAPI has both 200 and 204, two operation security declarations and no global security. Its documentation gaps remain valid (M13).                                                                |
| Fable: leaked advisory locks explain the dashboard p95                             | Plausible hypothesis, not a measured causal result. Connection ownership is broken; rerun profiling after correction (H12).                                                                                    |
| Fable: simply put the lock in one transaction                                      | Correct only if acquisition and protected work share that transaction/connection. Holding it through a whole-book sweep conflicts with short-transaction/feed requirements (H12/M07).                          |
| Fable: add `(type, subject_id) WHERE status='NEW'` to dedup alerts                 | Incomplete: subjectless correlation-key alerts need coverage too, and subject type should be considered (M08).                                                                                                 |
| Fable: mask every event bank number immediately                                    | Full bank fields are explicitly intentional in the current code/contract. Review necessity and version payload changes; do not silently break partner data contracts (M16).                                    |
| Fable: every loanStatusChanged event carries an internal actor                     | Specific field is invalidatedByUsername; it can disclose attribution for invalidation, not all transitions. Review/remove the inappropriate field precisely (M16).                                             |
| Fable: missing-user JWT is unrevocable forever                                     | Missing-subject fallback is real, but expiry, signing-key change and other validators still bound it. Fail closed for managed humans (M12).                                                                    |
| Fable: Azure admin will always read zero rows                                      | Conditional on actual policies/privileges/server version. Test dedicated runtime identities; do not assume server-admin defaults or use the server administrator as the app identity (H23).                    |
| Both: adding a broker, sharding or microservices would solve these failures        | Neither source supports it. Local transactions, immutable evidence, ownership and bounded jobs are the needed changes.                                                                                         |

The shutdown correction is supported by [Spring Boot 3.5 graceful-shutdown documentation](https://docs.spring.io/spring-boot/3.5/reference/web/graceful-shutdown.html). The scheduler finding uses the distinct [single-thread scheduler default](https://docs.spring.io/spring-boot/3.5/reference/features/task-execution-and-scheduling.html); graceful web shutdown does not establish safe worker ownership at termination.

For database deployment, [PostgreSQL RLS rules](https://www.postgresql.org/docs/17/ddl-rowsecurity.html) distinguish owner, FORCE RLS and BYPASSRLS behavior, while [Azure role-management guidance](https://learn.microsoft.com/en-us/azure/postgresql/security/security-access-control) describes version-dependent managed-service capabilities. [PostgreSQL advisory-lock semantics](https://www.postgresql.org/docs/17/explicit-locking.html#ADVISORY-LOCKS) explain why the physical connection/transaction lifetime matters.

## Severity index

| ID          | Severity | Issue                                                                                       | Origin                                                                                    |
| ----------- | -------- | ------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------- |
| [C01](#c01) | Critical | Invalidation and queued submission do not share a safe cancellation boundary                | Both; Fable C4                                                                            |
| [C02](#c02) | Critical | A recorded terminal bank result can remain unapplied to the loan                            | Codex; omitted by Fable despite its crash-recovery claims                                 |
| [C03](#c03) | Critical | Intent ownership and submission are not claimed atomically                                  | Both; Fable C3, with a stronger execution fence than its proposed patch                   |
| [C04](#c04) | Critical | The optional inline path can initiate again after an uncertain payment                      | Fable C2; Codex identified the broader point-of-no-return gap                             |
| [C05](#c05) | Critical | Foreclosure execution accepts stale quotes and excess settlement                            | Both; Codex Critical, Fable Medium — raised for financial correctness                     |
| [C06](#c06) | Critical | Shared borrower bank details are not a stable loan payment instruction                      | Both; Fable describes only part of the shared-profile problem                             |
| [G01](#g01) | Critical | Real-money bank integration is not implemented to the documented business contract          | Both identify mock-only adapter; Codex gives this higher release significance             |
| [H01](#h01) | High     | A parked loan can abort a disbursement tick and starve recovery                             | Both partly; Fable C1 gives the precise rollback-only chain                               |
| [H02](#h02) | High     | Parked payments have no complete reconciliation or immutable observation trail              | Both; Fable §6 understates this as a polling/doc issue                                    |
| [H03](#h03) | High     | Caller-controlled forwarding headers influence IP security decisions                        | Both; Fable C5, downgraded from unconditional Critical                                    |
| [H04](#h04) | High     | Temporary user passwords use a non-cryptographic random source                              | Fable C6; valid, but Critical and practical V8 predictability were overstated             |
| [H05](#h05) | High     | A failed normal action silently attempts a manual override                                  | Fable frontend High; important new finding, with its example corrected                    |
| [H06](#h06) | High     | Repayment and settlement do not serialize changes to the whole loan                         | Fable closure race; extends Codex schedule/settlement concurrency concerns                |
| [H07](#h07) | High     | The final successful payment cannot always be replayed after closure                        | Codex; omitted by Fable                                                                   |
| [H08](#h08) | High     | Foreclosure pricing has no explicit accrued-interest policy                                 | Both; Fable’s unconditional regulatory conclusion is not established                      |
| [H09](#h09) | High     | Foreclosure reallocates earlier receipts away from their installment targets                | Both                                                                                      |
| [H10](#h10) | High     | Borrowers who miss the first payment are excluded from scheduled delinquency                | Fable; new to the first Codex report                                                      |
| [H11](#h11) | High     | Portfolio totals count unfunded schedules as live debt                                      | Codex; omitted by Fable                                                                   |
| [H12](#h12) | High     | Scheduled-job advisory locks are not tied to the connection doing the work                  | Fable; not explicitly identified as a defect in the first Codex report                    |
| [H13](#h13) | High     | Concurrent final document uploads can miss automatic approval                               | Codex; omitted by Fable                                                                   |
| [H14](#h14) | High     | Approval does not freeze the document versions that supported it                            | Codex; omitted by Fable                                                                   |
| [H15](#h15) | High     | Schedule replacement can commit after the disbursement eligibility check                    | Codex; Fable mentions shared locks but omits the concrete race                            |
| [H16](#h16) | High     | Approval eligibility reads current catalog limits instead of pinned terms                   | Codex; omitted by Fable                                                                   |
| [H17](#h17) | High     | Expired idempotency keys can become permanently unrecoverable                               | Fable; valid issue, unsafe blanket simplification rejected                                |
| [H18](#h18) | High     | Duplicate requests can occupy request threads for 30 seconds                                | Fable                                                                                     |
| [H19](#h19) | High     | A missing deployment profile activates insecure local defaults                              | Both                                                                                      |
| [H20](#h20) | High     | API-client refresh tokens bypass the intended client-credential lifecycle                   | Fable; more specific than the first Codex auth review                                     |
| [H21](#h21) | High     | Refresh-token consumption is not atomic and has no reuse-family policy                      | Both                                                                                      |
| [H22](#h22) | High     | A late refresh or logout can overwrite a newer browser session                              | Codex; omitted by Fable; not fixed by the cache pilot                                     |
| [H23](#h23) | High     | Runtime database-role validation does not prove the intended RLS behavior                   | Both; Fable focuses on admin access, Codex on tenant bypass privileges                    |
| [H24](#h24) | High     | Report processing holds a transaction through storage and email                             | Both                                                                                      |
| [H25](#h25) | High     | Large exports and unbounded worker scans have no reliable resource bound                    | Both; Fable’s numerical ceilings and OOM thresholds are unverified                        |
| [H26](#h26) | High     | Unrelated scheduled work shares the default single scheduler thread                         | Both                                                                                      |
| [H27](#h27) | High     | Operational metrics and job health are not connected to a production exporter               | Both                                                                                      |
| [H28](#h28) | High     | Adapters turn unknown or absent values into misleading business facts                       | Fable; confirmed, with mapping nuances                                                    |
| [H29](#h29) | High     | List controls promise filters and sorting that the request does not apply                   | Fable                                                                                     |
| [H30](#h30) | High     | The alerts screen paginates only the backend’s first capped page                            | Fable pagination claim corrected and raised from Medium                                   |
| [H31](#h31) | High     | Generic status commands can diverge from financial evidence                                 | Codex; Fable notices state-policy fragmentation but not this full invariant               |
| [G02](#g02) | High     | The requested Entra machine-identity model is not present                                   | Both; separate High production integration prerequisite                                   |
| [M01](#m01) | Medium   | The schema reference is stale even though CI already checks it                              | Both; Fable’s “add CI enforcement” is redundant                                           |
| [M02](#m02) | Medium   | Borrower visibility can outlive failed onboarding, and race recovery is inconsistent        | Both; Fable’s guaranteed PAN-collision 500 is incorrect                                   |
| [M03](#m03) | Medium   | Mobile-number identity checks lack a matching concurrency rule                              | Fable; database uniqueness is a policy choice, not automatically the correct fix          |
| [M04](#m04) | Medium   | Object uploads and batch metadata have inconsistent failure semantics                       | Both                                                                                      |
| [M05](#m05) | Medium   | Storage clients are recreated per call and lack an explicit request-time budget             | Both                                                                                      |
| [M06](#m06) | Medium   | Alert rule configuration returned by the API is not the configuration evaluated             | Fable; corrects Codex’s overly positive alert-rule-table assessment                       |
| [M07](#m07) | Medium   | Delinquency evaluation is one unbounded writing transaction                                 | Both; Fable supplies the specific evaluator scope                                         |
| [M08](#m08) | Medium   | Alert deduplication is a check-then-insert race                                             | Fable                                                                                     |
| [M09](#m09) | Medium   | Business dates and displayed time zones disagree at day boundaries                          | Both                                                                                      |
| [M10](#m10) | Medium   | Redis rate limiting ignores authentication/TLS settings and has no explicit outage policy   | Both; Fable’s exact 60-second timing is not reproduced                                    |
| [M11](#m11) | Medium   | Human login lockout depends on a delayed scheduled evaluator                                | Fable                                                                                     |
| [M12](#m12) | Medium   | Missing-user JWT fallback and bootstrap password resynchronization weaken revocation        | Both                                                                                      |
| [M13](#m13) | Medium   | Partner OpenAPI documents omit essential integration behavior                               | Fable; exact counts corrected                                                             |
| [M14](#m14) | Medium   | API retry and pagination contracts are inconsistent                                         | Both                                                                                      |
| [M15](#m15) | Medium   | Cross-origin deployment lacks configurable origins and exposes an incomplete retry contract | Both                                                                                      |
| [M16](#m16) | Medium   | Sensitive data is deliberately replicated without a finalized disclosure/retention boundary | Both; Fable’s blanket masking prescription conflicts with the current event contract      |
| [M17](#m17) | Medium   | Local verification gates fail and full lending journeys are not exercised in CI             | Both; remote CI history and exact pollution counts are not independently established here |
| [M18](#m18) | Medium   | Important database invariants are enforced only in selected service paths                   | Codex; Fable’s broad “unique constraints back every lookup” claim needs limits            |
| [M19](#m19) | Medium   | Frontend role selection and navigation do not represent all authorized roles                | Fable                                                                                     |
| [M20](#m20) | Medium   | Other administrative lists still download and filter entire collections                     | Fable; distinct from the confirmed alerts truncation in H30                               |
| [M21](#m21) | Medium   | Frontend requests lack a consistent deadline and cancellation policy                        | Codex; not explicit in Fable                                                              |
| [M22](#m22) | Medium   | Locally minted JWTs lack an explicit audience and planned overlapping key rotation          | Codex; Fable mentions audience as an Entra prerequisite                                   |
| [L01](#l01) | Low      | Frontend table and URL-state duplication adds avoidable maintenance cost                    | Both; optional cleanup                                                                    |
| [L02](#l02) | Low      | Density controls, stale copy and missing polling can confuse operators                      | Fable                                                                                     |
| [L03](#l03) | Low      | Dormant dependencies and duplicate backend contracts can be simplified                      | Both; candidates, not automatic deletion instructions                                     |
| [L04](#l04) | Low      | Transient data retention and revocation delay need explicit policies                        | Both                                                                                      |
| [L05](#l05) | Low      | Synthetic data generation is reachable as a service outside its runner profile              | Fable; defense in depth, not a demonstrated production wipe path                          |

## Critical issues

<a id="c01"></a>

### C01 — Invalidation and queued submission do not share a safe cancellation boundary

**Status:** **Completed (2026-09-09)** — landed on main via commit `60ffc6fd` ("feat(disbursement): close the disbursement audit slices"). **Source comparison:** Both; Fable C4.

**Files and code evidence:**

- [backend/src/main/java/com/bhawana/lms/service/LoanApplicationInvalidationService.java:79](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/LoanApplicationInvalidationService.java:79)
- [backend/src/main/java/com/bhawana/lms/service/DisbursementIntentWorkflowService.java:205](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/DisbursementIntentWorkflowService.java:205)
- [backend/src/main/java/com/bhawana/lms/service/LoanDisbursementWorkerService.java:126](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/LoanDisbursementWorkerService.java:126)

**In simple terms / root cause:** A partner can mark a loan INVALID after a durable intent exists, including while money is in flight. The invalidation guard checks servicing states, not live intents or both in-flight account states. Submission preparation checks intent ownership/state but does not recheck application/LSP eligibility. An invalid account also drops out of the status-poll selection. This can leave cash with a borrower against an invalid loan. The application lock used during intent creation does not cover all these later operations.

**Required fix and final behavior:** Use one documented lock order for loan commands: application, account, then intent (borrower first only where a cross-loan invariant requires it). Resolve and reload state under those locks. Initially reject invalidation whenever a live intent exists or the account is REQUESTED/PENDING_RECONCILIATION; return a stable conflict and leave every row unchanged. If cancellation of an unsubmitted CREATED intent is later required, cancel it atomically with invalidation under the same locks used for submission. Recheck application eligibility and LSP enabled state before the atomic CREATED→REQUESTED transition. Once submission may have begun, reconcile the original instruction. Route accepted invalidations through a writer that preserves invalidation metadata, transition history, audit and the partner event in one transaction.

**Acceptance tests:** Race invalidation against intent creation and submission using two real Postgres transactions and barriers. Exactly one legal outcome must win. Test already REQUESTED, UNKNOWN/parked, and queued intent after LSP disablement. Assert no new provider call after a successful pre-submit cancellation, and continued recovery of submitted work.

**How it should land:** Land the shared lock contract with C03; then guard invalidation and add the eligibility recheck. Inventory existing INVALID accounts with live/terminal intents before rollout; reconcile them using bank evidence, never a blind status reset.

**What landed (2026-09-09):**

- The durable-intent workflow is the only disbursement initiation path; invalidation is fenced against live intents and in-flight account states on the shared cancellation boundary (`60ffc6fd`, landed together with the C02/C03 intent-workflow fencing).

<a id="c02"></a>

### C02 — A recorded terminal bank result can remain unapplied to the loan

**Status:** **Completed (2026-09-09)** — landed on main via commit `60ffc6fd`. **Source comparison:** Codex; omitted by Fable despite its crash-recovery claims.

**Files and code evidence:**

- [backend/src/main/java/com/bhawana/lms/service/DisbursementIntentWorkflowService.java:255](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/DisbursementIntentWorkflowService.java:255)
- [backend/src/main/java/com/bhawana/lms/service/DisbursementIntentWorkflowService.java:162](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/DisbursementIntentWorkflowService.java:162)
- [backend/src/main/java/com/bhawana/lms/service/LoanDisbursementWorkerService.java:73](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/LoanDisbursementWorkerService.java:73)
- [backend/src/main/java/com/bhawana/lms/service/DisbursementOutcomeApplier.java:72](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/DisbursementOutcomeApplier.java:72)

**In simple terms / root cause:** The provider result and terminal intent commit before autoResolveAfterInitiate updates the account/application. If the process stops in between, the intent is no longer claimable and the polling loader rejects its non-PENDING request log. A funded or failed loan can remain REQUESTED indefinitely. Persisting before the network call solves an earlier crash window, not this one.

**Required fix and final behavior:** Keep the bank call outside transactions. In the result transaction, lock/reload the intent, account and application; record the observation and apply its financial outcome, status transition, audit and event atomically through the existing outcome applier. Make re-applying the same observation harmless and prevent a stale pending/failed observation from overwriting an accepted terminal outcome. If atomic application cannot be retained, add an explicit durable unapplied-outcome state with a retry worker; do not infer outstanding work solely from PENDING logs. Replace the later auto-resolve dependency with a compatibility wrapper or remove it from the intent path.

**Acceptance tests:** Inject a failure after writing the result but before applying it; all local writes must roll back together. Simulate restart with a legacy terminal intent/REQUESTED account and demonstrate one idempotent repair. Repeat application, including out-of-order pending after success, and assert one financial transition/event and no second initiation.

**How it should land:** Land separately from bank adapter integration. Add a read-only reconciliation query and a reviewed repair command for existing stranded results. Keep immutable provider evidence and do not silently change historical balances.

**What landed (2026-09-09):**

- Recorded bank observations apply their financial outcome atomically on the intent workflow — the terminal result, status transition, audit and event land under the same intent lock, and crash recovery replays the recorded observation rather than losing it (`60ffc6fd`).

<a id="c03"></a>

### C03 — Intent ownership and submission are not claimed atomically

**Status:** **Completed (2026-09-09)** — landed on main via commit `60ffc6fd`. **Source comparison:** Both; Fable C3, with a stronger execution fence than its proposed patch.

**Files and code evidence:**

- [backend/src/main/java/com/bhawana/lms/service/DisbursementIntentWorkflowService.java:192](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/DisbursementIntentWorkflowService.java:192)
- [backend/src/main/java/com/bhawana/lms/service/DisbursementIntentWorkflowService.java:205](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/DisbursementIntentWorkflowService.java:205)
- [backend/src/main/java/com/bhawana/lms/repo/DisbursementIntentRepositoryImpl.java:20](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/repo/DisbursementIntentRepositoryImpl.java:20)
- [backend/src/main/java/com/bhawana/lms/service/DisbursementIntentWorkflowProperties.java:11](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/DisbursementIntentWorkflowProperties.java:11)
- [backend/src/main/java/com/bhawana/lms/domain/DisbursementIntent.java:20](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/domain/DisbursementIntent.java:20)

**In simple terms / root cause:** The batch CTE locks rows, but the single-application claim reads and stamps a lease without locking or comparing a version. Submission preparation is another unlocked read/change. All processes default to the same owner string. Overlapping consumers can prepare more than one bank call for the same reference. This proves a duplicate-submission risk; whether the bank debits twice depends on its idempotency contract. Sequential callers will often see REQUESTED and skip, so not every two-pod tick duplicates work.

**Required fix and final behavior:** Use one database claim primitive for both paths. Conditional claim must match CREATED, expired/no lease and expected attempt; reuse and atomically increment the existing attempt_count as the fencing number and set a unique process identity (host plus startup UUID). Require owner AND attempt on the subsequent conditional CREATED→REQUESTED update, within the transaction that persists the pre-call evidence. Exactly one successful update grants permission to call the provider. Fence completion against the same intent/attempt. A hostname or @Version alone is insufficient: native SQL claims must participate and the transition before the external call must also be protected. Never make UNKNOWN automatically re-initiable.

**Acceptance tests:** Race fast-path vs fast-path, fast-path vs batch, expired-lease takeover, and duplicate execution within one process. Use independent transactions and provider-call counters. Assert one call, one pre-call record and rejection of stale owners. Include crash before and after REQUESTED commit.

**How it should land:** Reuse the existing attempt_count column where possible; return it from native claims and check it at submission/completion. A new version column is optional defense in depth, not a required duplicate counter. Update every ORM/native write consistently. Pause claiming or drain old workers during incompatible ownership deployment. Increase worker concurrency only after these tests pass.

**What landed (2026-09-09):**

- Intent ownership and submission are claimed atomically on the workflow and command service — the claim/submission fence closes the double-submit window Fable described (`60ffc6fd`).

<a id="c04"></a>

### C04 — The optional inline path can initiate again after an uncertain payment

**Status:** **Completed (2026-09-09)** — landed on main via commit `60ffc6fd`. **Source comparison:** Fable C2; Codex identified the broader point-of-no-return gap.

**Files and code evidence:**

- [backend/src/main/java/com/bhawana/lms/service/LoanDisbursementCommandService.java:137](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/LoanDisbursementCommandService.java:137)
- [backend/src/main/java/com/bhawana/lms/service/LoanDisbursementCommandService.java:167](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/LoanDisbursementCommandService.java:167)
- [backend/src/main/java/com/bhawana/lms/service/DisbursementIntentWorkflowProperties.java:8](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/DisbursementIntentWorkflowProperties.java:8)
- [PRODUCT.md:49](/Users/siddhant/Desktop/lms/PRODUCT.md:49)

**In simple terms / root cause:** When the intent-workflow flag is false, a parked account is accepted for initiation, a fresh bank reference is generated, and the provider is called inside the database transaction. The earlier payment may already have debited the LSP. Database rollback cannot undo that debit. The default true flag reduces exposure but leaves an unsafe deployable alternative. The account is the LSP source account, not a debit to the borrower as Fable sometimes phrases it.

**Required fix and final behavior:** Remove the inline initiation implementation and the unsafe configuration branch; make the durable intent path mandatory. Independently reject re-initiation from REQUESTED and PENDING_RECONCILIATION in the command and domain transition rules. Define a new attempt only after definitive failure before debit or confirmed return under the actual bank contract. Keep old logs readable. Introduce an explicit LoanAccount transition method/table for the disbursement states rather than copying another set of guards into four callers.

**Acceptance tests:** Configuration cannot reactivate inline submission. Every account-state/command pair is covered by a parameterized transition matrix. Pending, timeout and parked results never produce a fresh reference. Definitively safe retries still work, and provider calls always observe no active database transaction.

**How it should land:** Land removal and domain guards together; update config docs and tests. Do not delete historical intent/log rows or reinterpret UNKNOWN as FAILED. New migration is needed only for new states/constraints, not merely to remove Java branches.

**What landed (2026-09-09):**

- The optional inline path was removed outright — the `app.disbursement.intent-workflow.enabled` flag is deleted and the durable-intent path is mandatory, so there is no route left that can re-initiate after an uncertain payment (`60ffc6fd`).

<a id="c05"></a>

### C05 — Foreclosure execution accepts stale quotes and excess settlement

**Status:** **Completed (2026-09-18)** — landed on main via PR #345 (squash `452d98e2`). **Source comparison:** Both; Codex Critical, Fable Medium — raised for financial correctness.

**Files and code evidence:**

- [backend/src/main/java/com/bhawana/lms/service/LoanForeclosureCommandService.java:201](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/LoanForeclosureCommandService.java:201)
- [backend/src/main/java/com/bhawana/lms/domain/LoanForeclosureQuote.java:21](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/domain/LoanForeclosureQuote.java:21)
- [backend/src/main/java/com/bhawana/lms/service/LoanServicingSupportService.java:240](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/LoanServicingSupportService.java:240)

**In simple terms / root cause:** A quote is a balance snapshot. A later installment receipt does not invalidate it. Execution checks ACTIVE and that the supplied settlement date equals the quote date, but not balance freshness or current-date expiry. A quote for 10,000 can still be recorded after a 1,000 receipt; excess can become unallocated while the loan closes. The quote version is a sequence number, not an optimistic financial revision.

**Required fix and final behavior:** Under the common loan lock (H06), calculate and store a financial revision/balance fingerprint, formula version, effective date and explicit valid-until policy. Increment the revision on receipts, adjustments and schedule changes that affect settlement. On execute, resolve idempotent replay first, then check quote ownership/status, validity and revision; reject stale quotes with FORECLOSURE_QUOTE_STALE and require a new quote. Lock the quote or use CAS to allow one execution and link the settlement receipt uniquely to it. Do not silently accept an amount different from the displayed quote. Decide and implement the excess/refund route before accepting real over-receipts.

**Acceptance tests:** Quote→payment→execute rejects with no receipt or status changes. Two executions create one receipt. Same-key replay after FORECLOSED returns the original result. Test expired/backdated quotes, wrong loan/LSP, concurrent receipt and exact settlement.

**How it should land:** Land account serialization and quote freshness before enabling foreclosure. Supersede legacy active quotes that lack a trustworthy revision; do not infer old revisions. Formula policy H08 and allocation H09 are explicit dependencies, not optional follow-ups.

**What landed (2026-09-18):**

- Settlement is bound to a fresh quote: `effectiveDate` must equal the business date (`businessCalendar.today()`); backdated or expired quotes are rejected. This is the interim same-day validity policy — deliberately fail-closed until H08's dating decision (D8) is signed off.
- Quote status machine: legacy `ACTIVE` quotes are superseded by migration `V130` and refused on execute; `EXECUTED` quotes resolve the linked settlement receipt as the committed replay.
- Replay/fingerprint: `requestFingerprint` is stored on settlement receipts — a same-key retry returns the original settlement, an altered payload conflicts via the new `IDEMPOTENCY_CONFLICT` violation (also mapped in the frontend alert contract).
- Execution order: idempotent replay resolves before ownership/status checks; concurrent executions serialize under the H06 loan lock so exactly one receipt is created — proven by forced-overlap (latch, not sleep) race tests on the merged contract.
- Deploy note: any `ACTIVE` quote at deploy time is superseded, so affected borrowers request a fresh quote once.
- Key commits inside PR #345: `3bfa1ef8` (fresh-quote binding + legacy supersede + fingerprint replay), `07138d34`/`13e2082c` (execution-order + audit-gap fixes). Merged-tree verification: 54/54 boundary tests green.

<a id="c06"></a>

### C06 — Shared borrower bank details are not a stable loan payment instruction

**Status:** **Completed (2026-09-09)** — landed on main via commit `60ffc6fd`. **Source comparison:** Both; Fable describes only part of the shared-profile problem.

**Files and code evidence:**

- [backend/src/main/java/com/bhawana/lms/service/BorrowerOnboardingService.java:89](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/BorrowerOnboardingService.java:89)
- [backend/src/main/java/com/bhawana/lms/service/BorrowerBankDetailsService.java:375](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/BorrowerBankDetailsService.java:375)
- [backend/src/main/java/com/bhawana/lms/service/DisbursementIntentWorkflowService.java:162](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/DisbursementIntentWorkflowService.java:162)
- [backend/src/main/java/com/bhawana/lms/domain/Borrower.java:311](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/domain/Borrower.java:311)

**In simple terms / root cause:** Borrower identity is global. Onboarding overwrites mutable profile/bank fields; its cross-LSP open-loan check reduces that path during active loans, so unrestricted redirection is an overstatement. However, bank updates check in-flight accounts through the tenant connection and cannot see another LSP’s account. An existing second-LSP pre-approval application can pass its own update gate. Intents snapshot the beneficiary at creation, but status queries use the borrower’s current IFSC. Two different instructions can therefore be used for initiation and later reconciliation.

**Required fix and final behavior:** Retain global identity, but read every bank submission/status query from one immutable instruction attached to the loan/intent, including beneficiary IFSC and the LSP source account reference. First fix polling to use the existing intent snapshot. Route onboarding bank changes through the same audited policy as explicit edits; serialize profile changes and global eligibility checks on the borrower. Use a narrowly scoped admin lookup for cross-LSP in-flight detection, with tests and the ADR allowlist updated. Separate partner-owned mutable profile/instruction data from global identity where ownership is required. Do not add a broad unrestricted admin write merely to bypass RLS.

**Acceptance tests:** Create applications for A and B before approval; approve A, then attempt B’s bank update. It must not alter A’s instruction or status-query payload. Failed onboarding cannot leave unauthorized visibility/profile changes. Test allowed bank edits after definitive return and concurrent approval/update.

**How it should land:** Land snapshot-based polling first, then guarded merge/global gate, then any additive ownership schema. Backfill historical instructions only from recorded provider requests/intents; flag missing evidence for reconciliation. Never manufacture history from today’s borrower row.

**What landed (2026-09-09):**

- Phase 1: status polling reads the instruction snapshotted on the intent — the original beneficiary IFSC is preserved and polls block on missing evidence rather than drifting to the borrower's current profile.
- Phase 2: cross-LSP borrower/bank work is serialized and bank-change audits are atomic on the tenant connection — no new admin elevation (`60ffc6fd`; migration `V122` adds the borrower-bank-audit tenant access and own-LSP RLS).

<a id="g01"></a>

### G01 — Real-money bank integration is not implemented to the documented business contract

**Status:** **In progress (2026-09-09)** — the simulation guard landed via commit `60ffc6fd`; the real bank adapter itself remains unimplemented. **Source comparison:** Both identify mock-only adapter; Codex gives this higher release significance.

**Files and code evidence:**

- [backend/src/main/java/com/bhawana/lms/service/LoanDisbursementAdapter.java:33](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/LoanDisbursementAdapter.java:33)
- [backend/src/main/java/com/bhawana/lms/service/MockLoanDisbursementAdapter.java:20](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/MockLoanDisbursementAdapter.java:20)
- [backend/src/main/java/com/bhawana/lms/service/LoanDisbursementCommandService.java:421](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/LoanDisbursementCommandService.java:421)
- [CONTEXT.md:22](/Users/siddhant/Desktop/lms/CONTEXT.md:22)
- [PRODUCT.md:33](/Users/siddhant/Desktop/lms/PRODUCT.md:33)

**In simple terms / root cause:** Only the mock bank adapter exists. The project requires per-LSP source/collection accounts, debit and credit confirmation, confirmed returns and append-only call evidence. The current command lacks an explicit source-account snapshot and exposes a single composite status result; these requirements cannot be assumed satisfied by adding an HTTP client. Mock outcome endpoints/configuration are not a sufficient production boundary. This is a Critical release prerequisite, not evidence of money already moving through a real adapter.

**Required fix and final behavior:** First obtain the bank’s actual API/idempotency/status contract and approved account ownership model. Specify source account, beneficiary instruction, each leg/state and the exact evidence permitting retry. Implement a real adapter with bounded network deadlines, no automatic initiation retry after an uncertain submission, safe status polling and immutable observations. Fail production startup if only a mock adapter is configured; restrict mock endpoints/auto-resolve to explicit simulation profiles. Distinguish call-not-sent, unknown, debit accepted, credit pending/success and confirmed return to the extent the bank contract supports them. Apply accepted results through C02 and reconcile through H02.

**Acceptance tests:** Provider sandbox contract tests cover timeout before/after acceptance, duplicate reference, debit success/credit pending, confirmed return, wrong LSP source account and restart. Production configuration cannot resolve a mock success. Operational reconciliation and evidence retention are demonstrated before enabling money.

**How it should land:** Land production mock guard first; C01–C04/C06/H02 must precede real-bank enablement. Additive account/instruction/observation schema comes with reviewed backfill and migration identity. No 150-line estimate or invented ICICI field contract is asserted here.

**What landed (2026-09-09):**

- Production-like profiles now fail closed on the mock/simulation adapter — the accidental-real-money path is blocked (`60ffc6fd`).
- Still open: the real bank adapter itself — pending an approved contract and sandbox evidence, exactly as this ticket's production-prerequisite framing requires.

## High issues

<a id="h01"></a>

### H01 — A parked loan can abort a disbursement tick and starve recovery

**Status:** **Completed (2026-09-09)** — landed on main via commit `60ffc6fd`. **Source comparison:** Both partly; Fable C1 gives the precise rollback-only chain.

**Files and code evidence:**

- [backend/src/main/java/com/bhawana/lms/service/LoanDisbursementWorkerProcessor.java:89](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/LoanDisbursementWorkerProcessor.java:89)
- [backend/src/main/java/com/bhawana/lms/service/LoanDisbursementWorkerProcessor.java:145](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/LoanDisbursementWorkerProcessor.java:145)
- [backend/src/main/java/com/bhawana/lms/service/LoanDisbursementWorkerService.java:110](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/LoanDisbursementWorkerService.java:110)
- [backend/src/main/java/com/bhawana/lms/service/DisbursementIntentWorkflowService.java:83](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/DisbursementIntentWorkflowService.java:83)

**In simple terms / root cause:** The processor skips REQUESTED but not PENDING_RECONCILIATION. A parked loan can reach createIntent, which conflicts with its live intent. The inner transactional failure marks the shared transaction rollback-only; catching it inside the processor does not repair the transaction. Commit can fail outside the catch, and the outer loop has no per-item catch. Later items and the final recovery pass are skipped. The query has no explicit ordering, so Fable’s “sorted after” wording is too definite.

**Required fix and final behavior:** Skip both submitted/parked states before preflight and enforce the same rule in the command. Catch per-item failures outside the transactional processor so the failed transaction has ended. Put compensating database writes in a fresh transaction only after rollback, using identifiers and a state recheck. Run bounded intent recovery independently of application scanning; isolate failures there too. Do not catch and continue inside a rollback-only transaction.

**Acceptance tests:** Park one application alongside eligible loans and an expired CREATED intent. A tick must process/recover the others. Inject a transactional conflict and provider/persistence failure per item; check failure counters and continued progress.

**How it should land:** Small isolated worker patch, dependent on preserving C04’s no-re-initiation guard. No data migration. Severity is High availability/correctness; the unsafe re-initiation itself is separately Critical C04.

**What landed (2026-09-09):**

- The rollback-only starvation chain is closed — durable intent is the only initiation path, and a parked loan carries its own recovery rather than aborting the disbursement tick (`60ffc6fd`).

<a id="h02"></a>

### H02 — Parked payments have no complete reconciliation or immutable observation trail

**Status:** **Completed (2026-09-09)** — landed on main via commit `60ffc6fd`. **Source comparison:** Both; Fable §6 understates this as a polling/doc issue.

**Files and code evidence:**

- [backend/src/main/java/com/bhawana/lms/service/LoanDisbursementWorkerService.java:129](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/LoanDisbursementWorkerService.java:129)
- [backend/src/main/java/com/bhawana/lms/service/LoanDisbursementCommandService.java:409](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/LoanDisbursementCommandService.java:409)
- [backend/src/main/java/com/bhawana/lms/service/DisbursementOutcomeApplier.java:83](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/DisbursementOutcomeApplier.java:83)
- [backend/src/main/java/com/bhawana/lms/domain/LoanDisbursementRequestLog.java:228](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/domain/LoanDisbursementRequestLog.java:228)
- [CONTEXT.md:34](/Users/siddhant/Desktop/lms/CONTEXT.md:34)

**In simple terms / root cause:** After poll exhaustion, accounts leave normal polling. REQUESTED intents may remain absent from the UNKNOWN gauge. Request-log response fields are overwritten and intermediate polls are not stored one per call, contrary to the explicit evidence contract. Operators cannot reconstruct every observation or reliably find all unresolved money.

**Required fix and final behavior:** Select unresolved work from explicit intent/account reconciliation state with next-poll-at/backoff, maximum age escalation and operator ownership. Preserve the original instruction/reference. Persist one immutable bank observation per initiate/poll, with time, attempt, outcome, correlation and protected request/response evidence; derive current state separately. Expose counts AND oldest age for all unresolved states. A manual resolution requires bank evidence and the same atomic applier; it cannot invent success. Do not “fix the documentation” to permit overwriting required financial evidence.

**Acceptance tests:** Exhaust normal polling and show the same payment eventually resolves through reconciliation without initiation. Verify N polls produce N immutable observations, repeated terminal observations are harmless, and every unresolved account is visible to operators and metrics.

**How it should land:** Add observation storage and backfill existing latest records as legacy observations with provenance. Keep old logs until retention policy permits removal. Land before real-bank enablement (G01); do not treat adding a gauge as completing reconciliation.

**What landed (2026-09-09):**

- An immutable observation trail now exists: `V120`/`V121` add the disbursement observation and reconciliation queue, with original-reference recovery and guarded manual resolution behind the reconciliation API (`60ffc6fd`).

<a id="h03"></a>

### H03 — Caller-controlled forwarding headers influence IP security decisions

**Status:** **Completed (2026-09-12)** — landed on main via commit `12dda5f9` ("feat(auth): rebuild the client-IP, session and machine-identity trust chain"). **Source comparison:** Both; Fable C5, downgraded from unconditional Critical.

**Files and code evidence:**

- [backend/src/main/java/com/bhawana/lms/common/web/ClientIpAddresses.java:15](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/common/web/ClientIpAddresses.java:15)
- [backend/src/main/java/com/bhawana/lms/security/LspSurfaceIpAllowlistFilter.java:4](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/security/LspSurfaceIpAllowlistFilter.java:4)
- [backend/src/main/java/com/bhawana/lms/security/SecurityFilterChainConfig.java:26](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/security/SecurityFilterChainConfig.java:26)
- [backend/src/main/resources/application.yml:2](/Users/siddhant/Desktop/lms/backend/src/main/resources/application.yml:2)

**In simple terms / root cause:** The helper trusts the first X-Forwarded-For or X-Real-IP value without checking the connecting proxy. This affects allowlists and audit/lockout attribution. Exploitability depends on whether ingress strips untrusted headers and whether the origin is reachable directly. Header spoofing does not supply a password, client secret or JWT, so it is an allowlist bypass, not authentication by itself.

**Required fix and final behavior:** Use the container-resolved remote address and configure an explicit trusted-proxy range per environment. At ingress remove/replace untrusted forwarded headers and restrict direct origin access. Preserve a single shared address resolver so authentication, rate limits and audits agree. Do not trust all private addresses by default in a shared network; document the actual proxy topology.

**Acceptance tests:** A blocked direct remoteAddr with an allowlisted XFF/X-Real-IP remains blocked. A real trusted proxy chain resolves the legitimate client. Test IPv4, IPv6, malformed/multi-hop headers and audit attribution through the embedded container, not only MockMvc.

**How it should land:** Land resolver plus deployment/runbook changes together. Update Issue64 tests that currently encode header trust. Validate trusted ingress in staging before applying allowlists in production.

**What landed (2026-09-12):**

- `server.forward-headers-strategy` is now `NONE` and enforced at startup — Tomcat can no longer rewrite `getRemoteAddr()` from caller-controlled `X-Forwarded-For`. The raw socket peer is the trust anchor; trusted ingress is explicit via `app.edge.trusted-proxies` (empty by default, IP/CIDR literals only, no DNS), and the edge contract is documented in `docs/edge-proxy-contract.md` (`12dda5f9`).

<a id="h04"></a>

### H04 — Temporary user passwords use a non-cryptographic random source

**Status:** **Completed (2026-09-12)** — landed on main via commit `12dda5f9`. **Source comparison:** Fable C6; valid, but Critical and practical V8 predictability were overstated.

**Files and code evidence:**

- [frontend/src/features/users/api.ts:94](/Users/siddhant/Desktop/lms/frontend/src/features/users/api.ts:94)
- [backend/src/main/java/com/bhawana/lms/service/UserAdminService.java:275](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/UserAdminService.java:275)
- [backend/src/main/java/com/bhawana/lms/web/UserAdminController.java:36](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/web/UserAdminController.java:36)

**In simple terms / root cause:** The browser uses Math.random to generate a real initial password. This is not suitable for credentials. Forced change on first login limits duration but does not make generation sound. The source alone does not demonstrate practical password prediction or full compromise.

**Required fix and final behavior:** Preferred small end-to-end change: reuse a server-side SecureRandom password generator in user creation, hash before persistence, and return the temporary credential in an explicit authorized create response, matching reset-password semantics. Remove client-side minting. Define response replay carefully: existing idempotency response storage must not become an unprotected durable plaintext password store. Use the same approved reveal-once/short-lived protected-secret policy for creation and reset. If the existing API must retain caller-provided passwords for another client, add an explicit server-generated mode and keep compatibility tests.

**Acceptance tests:** Creation produces a policy-compliant password, first login requires change, regular reads never return it, unauthorized requests fail, and retries neither create extra users nor silently change passwords. Assert no credential in logs/analytics or ordinary serialized user DTOs.

**How it should land:** Land backend response/API schema and frontend consumer together, with a temporary compatible contract if deployed separately. No password-algorithm rewrite; do not rotate all existing users merely because this function existed.

**What landed (2026-09-12):**

- Temporary passwords are minted server-side rather than accepted from the client — the non-cryptographic client-supplied path is gone (`12dda5f9`).

<a id="h05"></a>

### H05 — A failed normal action silently attempts a manual override

**Status:** **Completed (2026-09-12)** — landed on main via commit `12dda5f9`. **Source comparison:** Fable frontend High; important new finding, with its example corrected.

**Files and code evidence:**

- [frontend/src/features/loan-applications/api-detail.ts:384](/Users/siddhant/Desktop/lms/frontend/src/features/loan-applications/api-detail.ts:384)
- [backend/src/main/java/com/bhawana/lms/service/LoanApplicationLifecycleService.java:140](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/LoanApplicationLifecycleService.java:140)
- [backend/src/main/java/com/bhawana/lms/domain/LoanApplicationStatus.java:73](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/domain/LoanApplicationStatus.java:73)

**In simple terms / root cause:** After a 400/403, the frontend may retry manual-status using a stored SYSTEM_ADMIN role and fabricate a default reason/note. This obscures the administrator’s intent. Fable’s specific “Approve bypasses the gate” example is not supported: the backend blocks APPROVED_PENDING_DISBURSAL as a manual target. Nor can forged localStorage bypass server authorization. The silent retry remains wrong for supported manual targets.

**Required fix and final behavior:** Remove the implicit fallback and surface the original error. Add or retain a separate visibly named Override action only for states the backend permits. Require a deliberate reason code and typed explanation, show the resulting status, and use a new idempotency key for that separate command. Keep backend role/state enforcement authoritative. Return real audit records rather than inventing a client actor/correlation record for audit history.

**Acceptance tests:** 400 and 403 from normal transitions result in one request and an error. Approve never calls manual-status. Explicit supported override records the exact reason/actor once. Test non-admin and forged stored-role cases against backend authorization.

**How it should land:** Small frontend patch and focused tests first; no broad status-machine rewrite. Audit past synthetic/default override reasons separately if this UI has been used operationally.

**What landed (2026-09-12):**

- Manual status overrides are now explicit in the UI — a failed normal action surfaces as such instead of silently falling back to an override (`12dda5f9`).

<a id="h06"></a>

### H06 — Repayment and settlement do not serialize changes to the whole loan

**Status:** **Completed (2026-09-18)** — landed on main via PR #345 (squash `452d98e2`). **Source comparison:** Fable closure race; extends Codex schedule/settlement concurrency concerns.

**Files and code evidence:**

- [backend/src/main/java/com/bhawana/lms/service/LoanRepaymentCommandService.java:216](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/LoanRepaymentCommandService.java:216)
- [backend/src/main/java/com/bhawana/lms/service/LoanServicingSupportService.java:319](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/LoanServicingSupportService.java:319)
- [backend/src/main/java/com/bhawana/lms/repo/LoanAccountRepository.java:19](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/repo/LoanAccountRepository.java:19)
- [backend/src/main/java/com/bhawana/lms/service/LoanForeclosureCommandService.java:164](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/LoanForeclosureCommandService.java:164)

**In simple terms / root cause:** A payment locks its installment, not the loan. Two payments to different final installments can each observe the other unpaid and both skip closure. In an already UNDER_REPAYMENT application this need not produce an application version conflict. Other interleavings can conflict; recovery then assumes the same key already committed and can throw 500 when its own receipt rolled back. Existing @Version fields do not serialize this multi-row decision.

**Required fix and final behavior:** Acquire the common application/account lock before loading installments and recalculating balances; all payment, foreclosure and schedule commands must honor the same order. Reload state inside the transaction instead of carrying detached stale entities into it. On a known optimistic/serialization conflict, retry the complete safe database command a bounded number of times or return a documented conflict; reread an existing receipt only if that key actually won. Preserve unique keys and exact-EMI validation. Remove intern-based JVM synchronization after database concurrency tests pass.

**Acceptance tests:** Use distinct keys and two independent transactions for the final two installments; both accepted receipts produce one closure/event. Race payment with foreclosure and schedule replacement. Test a rolled-back conflicting receipt with no winning row and verify no unexplained 500.

**How it should land:** Land the shared repository lock and migrate each command in a single compatible change or clearly sequenced commits. Follow-up read-only reconciliation should find zero-outstanding open loans; repair only after checking receipts, never by blanket SQL close.

**What landed (2026-09-18):**

- Repayment, foreclosure, and schedule-replacement commands now serialize on the common application/account lock before installments are loaded and balances recalculated; the shared lock contract and command lock order are documented in `docs/adr/0010-foreclosure-settlement-boundary.md`.
- Two payments racing the final installments can no longer both observe "open": the winner commits and closes; the loser resolves its key as a replay or receives a clean conflict — one closure, one event.
- Cross-loan same-key races no longer surface unexplained 500s: the idempotency key-violation classifier walks the full cause chain and maps the constraint violation to `IDEMPOTENCY_CONFLICT` (`80012440`).
- Race coverage is forced-overlap (latch-driven), not sleep-based: the repayment↔foreclosure and repayment↔schedule tests run on the merged lock contract — the cross-boundary proof only became possible once both halves shared the lock.
- Key commits inside PR #345: `775b1130` (whole-loan repayment serialization + replay-after-closure), `80012440` (key-violation classifier, legacy NULL-fingerprint handling), `07138d34`/`13e2082c` (foreclosure-side gaps on the same contract). Verification: 14/14 focused + 119/119 payment-endpoint + 54/54 merged-boundary tests green.

<a id="h07"></a>

### H07 — The final successful payment cannot always be replayed after closure

**Status:** **Completed (2026-09-18)** — landed on main via PR #345 (squash `452d98e2`). **Source comparison:** Codex; omitted by Fable.

**Files and code evidence:**

- [backend/src/main/java/com/bhawana/lms/service/LoanRepaymentCommandService.java:130](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/LoanRepaymentCommandService.java:130)
- [backend/src/main/java/com/bhawana/lms/service/LoanRepaymentCommandService.java:168](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/LoanRepaymentCommandService.java:168)
- [backend/src/main/java/com/bhawana/lms/service/LoanServicingSupportService.java:134](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/LoanServicingSupportService.java:134)

**In simple terms / root cause:** The repayment method checks whether the loan still accepts payments before looking up an existing idempotency result. The first successful final receipt closes the loan; a legitimate retry can then be rejected as a new payment even though it should return the original success. An outer API idempotency wrapper can mask this on some paths, so cover both wrapped and direct command routes.

**Required fix and final behavior:** Authenticate and establish loan ownership first. Normalize/fingerprint the request and return a matching committed receipt before evaluating eligibility for a new receipt. Keep conflict behavior for a key used on another account or with another payload. Execute new receipts under H06’s lock. Ensure replay does not emit another event/audit record or reallocate balances.

**Acceptance tests:** Post the final EMI, close the loan, retry the identical key through each exposed API path and get the same receipt. A changed amount/date/target with that key conflicts; another tenant cannot discover or replay it.

**How it should land:** Contained ordering fix with no schema migration. Suitable as an early worker task after a regression test; do not use it as an excuse to remove authorization before replay.

**What landed (2026-09-18):**

- Replay now resolves before eligibility: the command establishes ownership, then looks up the committed receipt for the key before evaluating whether the loan accepts a new payment — a retry of the closing payment returns the original receipt instead of a rejection.
- Legacy receipts written before fingerprints existed (NULL `requestFingerprint`) are handled by field-level compare: matching payload → original result, altered → conflict (`80012440`).
- The `requestFingerprint` column exists on settlement receipts (same mechanism as C05), so replay is fingerprint-verified, not positional.
- Authorization still runs before replay — the ordering change narrowed, never removed, the ownership check.
- Key commits inside PR #345: `775b1130` (replay-before-eligibility ordering), `80012440` (legacy NULL-fingerprint compare). Regression coverage: close-then-retry returns the same receipt across the exposed API paths.

<a id="h08"></a>

### H08 — Foreclosure pricing has no explicit accrued-interest policy

**Status:** **In progress (2026-09-18)** — policy scaffolding landed on main via PR #345; the formula itself remains blocked on product-owner sign-off. **Source comparison:** Both; Fable’s unconditional regulatory conclusion is not established.

**Files and code evidence:**

- [backend/src/main/java/com/bhawana/lms/service/LoanForeclosureCommandService.java:92](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/LoanForeclosureCommandService.java:92)
- [backend/src/main/java/com/bhawana/lms/service/LoanForeclosureCommandService.java:62](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/LoanForeclosureCommandService.java:62)
- [backend/src/main/java/com/bhawana/lms/domain/LoanProductVersion.java:17](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/domain/LoanProductVersion.java:17)

**In simple terms / root cause:** The quote sums all unpaid scheduled interest, including future installments. Its effective date does not drive accrual. That behavior is confirmed; whether it violates a specific contract or law is not determined by these audits. A worker must not invent day-count conventions, permitted fees or an RBI compliance conclusion.

**Required fix and final behavior:** Before formula implementation, obtain an approved policy covering fixed/floating rate, borrower/product eligibility, accrual start/end inclusivity, day-count, rounding, prepayment charges/taxes and backdated settlements. Store the policy/formula version with pinned loan terms and quote. Implement a pure deterministic quote calculator returning principal, accrued interest, permitted charge/tax and total; use it for preview and execution checks. Keep the stale-quote controls in C05. Prepare worked examples signed off by the product owner; mark this ticket blocked only on the policy-dependent formula, not on freshness fixes.

**Acceptance tests:** Golden examples for settlement before first due date, on due date, between installments, leap day, prior payment, zero balance and rounding boundaries. Assert no future interest is charged unless the approved contract explicitly requires it.

**How it should land:** Land policy/ADR and examples, then calculator and migration of active quotes. Do not silently reprice historical settlements. This is a high-priority product correctness decision, not a legal finding established here.

**What landed (2026-09-18):**

- `docs/adr/0009-foreclosure-settlement-pricing-policy.md` — **DRAFT/Proposed**, explicitly not accepted and not implementable. It enumerates every decision the product owner must sign off (fixed/floating rate, accrual start/end inclusivity, day-count, rounding, prepayment charges/taxes, backdated settlements, quote validity window), states what the code does today for each, and names the code-level consequence of each option.
- `docs/audits/h08-foreclosure-policy-questions-2026-09-18.md` — the same decisions in plain business language, as the sign-off questionnaire for the product owner (force-added past the `/docs/audits/` gitignore so it travels with the repo).
- Per this ticket's own "how it should land," the policy/ADR step is done; the ticket stays open only on the policy-dependent formula — quote freshness (C05) and allocation (H09) are no longer blockers.
- Interim safety while policy is pending: C05's same-day quote validity is the deliberately fail-closed substitute for the dating decision (ADR-0009 item D8).
- Next step: product owner returns the questionnaire → ADR-0009 is superseded by an Accepted revision carrying signed-off golden vectors → only then does the deterministic quote calculator land.

<a id="h09"></a>

### H09 — Foreclosure reallocates earlier receipts away from their installment targets

**Status:** **Completed (2026-09-18)** — landed on main via PR #345 (squash `452d98e2`). **Source comparison:** Both.

**Files and code evidence:**

- [backend/src/main/java/com/bhawana/lms/service/LoanServicingSupportService.java:240](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/LoanServicingSupportService.java:240)
- [backend/src/main/java/com/bhawana/lms/service/LoanRepaymentCommandService.java:228](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/LoanRepaymentCommandService.java:228)
- [backend/src/main/java/com/bhawana/lms/domain/LoanPaymentTransaction.java:21](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/domain/LoanPaymentTransaction.java:21)

**In simple terms / root cause:** Normal receipts target a particular installment. Foreclosure resets all installment allocations and walks every historical receipt across the schedule without respecting that target. Resulting paid fields can disagree with the receipt-to-installment relationship and the original accounting history.

**Required fix and final behavior:** Keep receipt identity and original installment allocations stable. Allocate only the new foreclosure receipt over the current remaining amounts under H06’s loan lock. If business policy explicitly permits reallocation, record immutable allocation/reversal entries linked to the original receipt and reason rather than overwriting history. Preserve amount = allocated + unallocated and principal/interest components. Do not add blanket @Version annotations as a substitute for an allocation policy.

**Acceptance tests:** Pay a later installment first, then foreclose. Its receipt remains allocated to that installment; only the remaining balance receives settlement. Validate partial historical/legacy states, transaction rollback, repeated execute and component totals.

**How it should land:** Land with C05. Inventory historical allocations before migration; a reconciliation report must identify inconsistencies rather than automatically rewriting financial history.

**What landed (2026-09-18):**

- The foreclosure allocator now applies only the new settlement receipt over the remaining unpaid amounts; historical receipt→installment allocations are left untouched and the blanket `resetAllocation` path is gone.
- A receipt that paid a later installment first stays attached to that installment through foreclosure — receipt identity and original targets are stable, matching the ticket's required behavior.
- The `V130` migration that supersedes legacy `ACTIVE` quotes also removes the old execution path that walked every historical receipt across the schedule — there is no remaining route that reallocates history.
- Coverage: legacy/partial-allocation fixtures plus inconsistency-inventory evidence (identify, not auto-rewrite), transaction rollback, repeated execute, and component-total checks.
- Key commits inside PR #345: `3bfa1ef8` (allocation-preserving settlement path), `07138d34`/`13e2082c` (legacy fixture and audit-gap follow-ups). Verified alongside C05: 54/54 boundary tests green on the merged lock contract.

<a id="h10"></a>

### H10 — Borrowers who miss the first payment are excluded from scheduled delinquency

**Status:** **Completed (2026-09-18)** — landed on main via PR #345 (squash `452d98e2`). **Source comparison:** Fable; new to the first Codex report.

**Files and code evidence:**

- [backend/src/main/java/com/bhawana/lms/repo/AlertRuleSetQueryRepository.java:95](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/repo/AlertRuleSetQueryRepository.java:95)
- [backend/src/main/java/com/bhawana/lms/service/LoanRepaymentCommandService.java:264](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/LoanRepaymentCommandService.java:264)
- [backend/src/main/java/com/bhawana/lms/service/AlertRuleEvaluationWorker.java:128](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/AlertRuleEvaluationWorker.java:128)

**In simple terms / root cause:** The scheduled DPD query includes only UNDER_REPAYMENT. A borrower who has made no receipt stays DISBURSED, so the evaluator misses first-payment defaults even though read APIs can calculate overdue days. DPD means days past due.

**Required fix and final behavior:** Use one funded servicing population that includes DISBURSED and UNDER_REPAYMENT and excludes unfunded/invalid/closed accounts according to the approved policy. Keep the business-date boundary and bucket calculation shared with read APIs. Evaluate the existing delinquency state and append only real transitions; avoid generating duplicate historical alerts when backfilling.

**Acceptance tests:** A DISBURSED loan with first EMI overdue moves to the proper bucket and emits one event/alert. Compare scheduled and API DPD. Include not-yet-due, paid, closed and invalid loans; repeat the sweep without duplicates.

**How it should land:** Small query/fixtures change; no schema migration required. Run one controlled catch-up sweep and monitor the expected first-run alert increase. Do not wait for the later chunking optimization M07.

**What landed (2026-09-18):**

- The scheduled DPD sweep now evaluates the funded servicing population — `DISBURSED` and `UNDER_REPAYMENT` — instead of `UNDER_REPAYMENT` alone, so a borrower whose very first EMI is overdue enters the correct bucket.
- The funded-population predicate is shared with the read APIs, keeping scheduled and on-demand DPD calculations on the same business-date boundary and bucket math.
- Existing delinquency state is consulted before appending — only real transitions emit events/alerts, so repeat sweeps and the first catch-up run do not manufacture duplicate historical alerts.
- No schema migration; query + fixtures change as the ticket prescribed.
- Key commit inside PR #345: `3a6b1b62` ("sweep delinquency over the funded servicing population") with the population-definition tests.

<a id="h11"></a>

### H11 — Portfolio totals count unfunded schedules as live debt

**Status:** **Completed (2026-09-19)** — landed on main via PR #349 (squash `1e98d976`). **Source comparison:** Codex; omitted by Fable.

**Files and code evidence:**

- [backend/src/main/java/com/bhawana/lms/service/PortfolioKpiSnapshotComputationService.java:146](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/PortfolioKpiSnapshotComputationService.java:146)
- [backend/src/main/java/com/bhawana/lms/repo/LoanAccountRepository.java:152](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/repo/LoanAccountRepository.java:152)
- [backend/src/main/java/com/bhawana/lms/service/HomeDashboardService.java:33](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/HomeDashboardService.java:33)

**In simple terms / root cause:** Snapshot SQL conditions disbursed amount on disbursed_at, but sums outstanding and overdue schedule amounts for every account. Accounts with generated schedules but no funding can inflate portfolio debt and delinquency buckets. The dashboard priority query also lacks a funded-population predicate.

**Required fix and final behavior:** Define funded servicing, historical disbursed and closed-loan populations separately. Apply each definition consistently to totals, bucket denominators, priority lists and live fallback queries; do not force all metrics to have the same denominator. Keep unfunded pipeline metrics explicit and separate. Reuse a small SQL predicate/query component or tested definition, not a new reporting framework.

**Acceptance tests:** Seed one unfunded overdue schedule, one invalid loan, one active funded loan and one closed historical loan. Verify each metric’s expected amount/count and agreement between live computation and snapshots.

**How it should land:** Land SQL and business definitions together, then rebuild derived snapshots. No financial receipt migration. Re-run representative dashboard queries before attributing performance changes to this fix.

**What landed (2026-09-19):**

- A single shared predicate component `LoanPortfolioPopulation` now owns the two population definitions: `FUNDED_SERVICING` (application `DISBURSED`/`UNDER_REPAYMENT` + account `DISBURSED` + `disbursed_at` set) and `HISTORICAL_DISBURSED` (`disbursed_at` set, including closed/foreclosed).
- Snapshot outstanding, overdue, and DPD-bucket totals are gated on the funded-servicing flag; lifetime disbursed totals use the historical population; unfunded pipeline stays visible as application-status counts rather than inflating debt.
- The dashboard priority query applies `FUNDED_SERVICING_JPQL`, and the "live" path is the same `computeAndPersistSnapshots` computation — live and snapshot metrics cannot diverge.
- The H10 delinquency sweep was refactored to consume the same shared predicate instead of its own inline clause — one definition, not two.
- `PortfolioPopulationIntegrationTest` seeds the spec's four-loan fixture (unfunded overdue, invalid, active funded, closed historical) across distinct DPD buckets and asserts live/snapshot agreement on exact counts and amounts.
- Rebased across batch-5's sweep rework; a CI failure attributed to this PR turned out to be an upstream tick/cleaner race fixed by PR #353 — verified unrelated to the predicate.

<a id="h12"></a>

### H12 — Scheduled-job advisory locks are not tied to the connection doing the work

**Status:** **Completed (2026-09-19)** — landed on main via PR #351 (squash `843917e3`). **Source comparison:** Fable; not explicitly identified as a defect in the first Codex report.

**Files and code evidence:**

- [backend/src/main/java/com/bhawana/lms/service/PostgresAdvisoryLockSupport.java:17](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/PostgresAdvisoryLockSupport.java:17)
- [backend/src/main/java/com/bhawana/lms/service/PortfolioKpiSnapshotWorker.java:33](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/PortfolioKpiSnapshotWorker.java:33)
- [backend/src/main/java/com/bhawana/lms/service/AlertRuleSchedulerWorker.java:10](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/AlertRuleSchedulerWorker.java:10)

**In simple terms / root cause:** Separate nontransactional JdbcTemplate calls can acquire and release a session lock on different pooled connections. This can leak locks, skip runs or allow reentrant acquisition by the same database session for separate application work. It is not proven that jobs fail a fixed fraction of the time or that this caused a measured dashboard p95.

**Required fix and final behavior:** For short database-only work, acquire pg_try_advisory_xact_lock inside an explicit transaction that spans the protected work on the same connection; self-invocation must not bypass the transaction. For work split into short batches (M07), prefer a worker lease row with unique owner, expiry and fencing, rather than holding one long transaction just to retain a lock. Record acquisition, successful completion, failure and last-run time. Do not merely change the SQL function while leaving acquisition outside the protected transaction.

**Acceptance tests:** Two workers/connections cannot perform the same protected run simultaneously. Failure/rollback releases ownership, the next run succeeds, and the pool has no leaked session locks. Cover the chosen transaction-pooling deployment mode.

**How it should land:** Land both scheduler callers with the helper change. Drain old workers/recycle their pool to remove leaked session locks. Do not release arbitrary advisory locks on unrelated database sessions.

**What landed (2026-09-19):**

- `PostgresAdvisoryLockSupport` replaced the session-level `pg_try_advisory_lock`/`pg_advisory_unlock` pair with `runWithAdvisoryLock(lockId, jobName, work)`: a `REQUIRES_NEW` transaction that takes `pg_try_advisory_xact_lock` and runs the protected work on the same pooled connection — the lock releases at commit/rollback, so transaction pooling can no longer leak locks or unlock a different session's lock.
- Callers: `PortfolioKpiSnapshotWorker` uses the helper directly. `AlertRuleSchedulerWorker` moved to the spec's stated lease alternative — the durable fenced `worker_lease` row (M07, V132), because its evaluation is split into many short transactions where one long lock-holding transaction would defeat the purpose.
- Logging is centralized in the helper (lock acquired, run failed); workers log skip and completion.
- Verification: `PostgresAdvisoryLockIntegrationTest` — a second real connection is excluded by a latch-forced overlap, a failed run rolls back and releases so the next run succeeds, and `pg_locks` is queried to prove zero leaked advisory locks.

<a id="h13"></a>

### H13 — Concurrent final document uploads can miss automatic approval

**Status:** **Completed (2026-09-19)** — landed on main via PR #350 (squash `95835637`). **Source comparison:** Codex; omitted by Fable.

**Files and code evidence:**

- [backend/src/main/java/com/bhawana/lms/service/LoanApplicationDocumentChecklistService.java:138](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/LoanApplicationDocumentChecklistService.java:138)
- [backend/src/main/java/com/bhawana/lms/service/LoanDocumentService.java:89](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/LoanDocumentService.java:89)
- [backend/src/main/java/com/bhawana/lms/service/LoanAutoApprovalGateService.java:44](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/LoanAutoApprovalGateService.java:44)

**In simple terms / root cause:** Approval is triggered only when a caller observes an incomplete→complete checklist edge. Two concurrent uploads can each miss the other’s uncommitted final document. A crash after metadata commit but before the separate gate can also lose the trigger on paths without an outer transaction.

**Required fix and final behavior:** Make complete-and-eligible a repeatable durable condition. Serialize checklist completion on the application or persist an approval-pending work marker with metadata, then invoke/retry the existing borrower-locked approval gate. The gate must recheck current lifecycle and pinned terms and be safe to run again. A plain afterCommit callback without durable recovery does not close the crash gap. Preserve exactly one logical DOCUMENTS_UPLOADED/approval event.

**Acceptance tests:** Upload the last two required documents in concurrent real transactions; the loan eventually reaches the correct approval/rejection result once. Simulate process death after metadata commit, duplicate uploads and a noneligible application.

**How it should land:** Land durable trigger and recovery together. If adding a marker, use an additive migration and backfill only eligible complete applications. Coordinate lock order with borrower-level auto-approval and C01/H06.

**What landed (2026-09-19):**

- Document writes now acquire the borrower lock then the application lock (`lockApplicationForDocumentWrite`, `PESSIMISTIC_WRITE` + post-lock `entityManager.refresh`, ADR-0010 order), and `commitDocumentSubmissions` runs metadata writes and the borrower-locked approval gate inside **one** transaction — concurrent final uploads serialize, a crash rolls back document metadata and the approval decision together, and the "complete-and-eligible" condition is durable rather than an afterCommit hint.
- The gate is safe to re-run: `evaluateIfTriggered` only acts on the documents-complete edge inside the locked transaction, so duplicate uploads and retries converge to exactly one `DOCUMENTS_UPLOADED`/approval event.
- `DocumentEvidenceChainPostgresIntegrationTest` drives concurrent final required-document uploads in real transactions, simulated death after metadata commit, duplicate uploads, and noneligible applications — asserting the one-legal-outcome in each.

<a id="h14"></a>

### H14 — Approval does not freeze the document versions that supported it

**Status:** **Completed (2026-09-19)** — landed on main via PR #350 (squash `95835637`). **Source comparison:** Codex; omitted by Fable.

**Files and code evidence:**

- [backend/src/main/java/com/bhawana/lms/service/LoanApplicationDocumentChecklistService.java:140](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/LoanApplicationDocumentChecklistService.java:140)
- [backend/src/main/java/com/bhawana/lms/service/LoanDocumentService.java:229](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/LoanDocumentService.java:229)
- [backend/src/main/java/com/bhawana/lms/domain/LoanApplicationDocumentChecklist.java:20](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/domain/LoanApplicationDocumentChecklist.java:20)

**In simple terms / root cause:** The current checklist entry and storage metadata can be replaced without a consistent lifecycle restriction. Approval checks the current entry but does not retain an explicit approved document-version set. Later review can see different evidence than the approver used.

**Required fix and final behavior:** First enforce allowed upload/replacement states in the service after ownership checks and before object creation. Add immutable document-version metadata and capture the exact approved versions/checksums when approval commits. If post-approval corrections are a product requirement, make them explicit audited replacements that preserve the old evidence and trigger re-review where required. Keep objects immutable at unique keys.

**Acceptance tests:** After approval, an ordinary upload cannot silently replace approved evidence. Authorized correction keeps both versions, attribution and the original approval reference. Race replacement with approval and verify one coherent evidence set.

**How it should land:** Add version/reference tables or columns without deleting existing objects. Treat existing checklist rows as legacy versions; do not claim they reconstruct already-overwritten files.

**What landed (2026-09-19):**

- Migration `V135` (renumbered from V131 after batch-5 took V131–V134) adds three append-only tables — `loan_application_document_version` (unique per application/type/version number), `loan_application_approval_evidence`, `loan_document_object` — plus `current_version_id` on the checklist, with RLS policies and append-only grants; legacy checklist rows backfill as `LEGACY` versions that honestly record no captured object bytes.
- Approvals now capture `approval_evidence` inside the approval transaction — the exact document version ids, checksums, and storage keys that justified the decision, for both auto and ops approval paths (`approved_by_username` records the actor).
- Post-approval ordinary uploads are rejected `409 DOCUMENT_EVIDENCE_LOCKED`; explicit corrections append a `CORRECTION` version with a required reason, actor attribution, and a reference to the approval evidence they correct — both versions and both objects preserved.
- Policy decision (captain, 2026-09-19): corrections are available to the same LSP roles that upload and do **not** trigger re-review — accepted as shipped; revisit if a stricter policy is wanted.
- Upload policy is enforced after ownership checks and before any object write, and re-checked under the lock — a replacement-vs-approval race produces exactly one coherent evidence set.
- CodeQL fix on the same PR: `FileSystemLoanDocumentStorageService.resolveUnderRoot` confines every caller-provided storage key under the configured root (normalize + `startsWith`), applied to store/retrieve/openStream/delete/listAll.

<a id="h15"></a>

### H15 — Schedule replacement can commit after the disbursement eligibility check

**Status:** **Completed (2026-09-09)** — landed on main via commit `60ffc6fd`. **Source comparison:** Codex; Fable mentions shared locks but omits the concrete race.

**Files and code evidence:**

- [backend/src/main/java/com/bhawana/lms/service/LoanRepaymentScheduleService.java:76](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/LoanRepaymentScheduleService.java:76)
- [backend/src/main/java/com/bhawana/lms/service/LoanRepaymentScheduleService.java:143](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/LoanRepaymentScheduleService.java:143)
- [backend/src/main/java/com/bhawana/lms/service/LoanDisbursementCommandService.java:94](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/LoanDisbursementCommandService.java:94)

**In simple terms / root cause:** Replacement checks PENDING_DISBURSEMENT, then deletes/reinserts installments without the application/account lock used by competing financial commands. A submission can commit between that check and replacement, leaving a funded loan with a schedule that changed after its financial commitment.

**Required fix and final behavior:** Use H06/C01’s shared application/account lock for generated and provided replacements and for the intent-creation boundary. Recheck status and live intent while locked before deleting anything. Freeze a schedule revision/hash when creating the intent and validate it before submission. Continue using pinned rate/date validation and preserve validation audit semantics on rejection.

**Acceptance tests:** Race generated/provided replacement with intent creation and provider preparation. Either replacement completes before the frozen instruction or returns SCHEDULE_LOCKED; it cannot mutate a submitted schedule. Existing receipts always prevent replacement.

**How it should land:** Land lock participation with the other loan commands; add a revision only if needed for explicit evidence/freshness. No historical schedule regeneration.

**What landed (2026-09-09):**

- The repayment schedule is frozen at submission: a schedule hash is checked before submission so a replacement committed after the eligibility check can no longer slip through (`60ffc6fd`; migration `V124` adds `disbursement_intent` schedule hash).

<a id="h16"></a>

### H16 — Approval eligibility reads current catalog limits instead of pinned terms

**Status:** **Completed (2026-09-19)** — landed on main via PR #350 (squash `95835637`). **Source comparison:** Codex; omitted by Fable.

**Files and code evidence:**

- [backend/src/main/java/com/bhawana/lms/service/LoanAutoApprovalRuleEngine.java:78](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/LoanAutoApprovalRuleEngine.java:78)
- [backend/src/main/java/com/bhawana/lms/domain/LoanAccount.java:45](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/domain/LoanAccount.java:45)
- [backend/src/main/java/com/bhawana/lms/service/LoanRepaymentScheduleService.java:501](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/LoanRepaymentScheduleService.java:501)

**In simple terms / root cause:** Loan calculations use the selected product version, but amount and tenure approval checks read mutable LoanProduct bounds. A catalog edit can change whether an already submitted application qualifies, despite its pinned commercial terms.

**Required fix and final behavior:** Read amount/tenure/rate bounds from the application’s pinned LoanProductVersion. Keep current LSP/product/mapping enabled status as an explicit availability kill switch. Define how a missing legacy version is handled: fail clearly or use a reviewed backfill, not silently the latest version. Align preflight and schedule validation with the same policy.

**Acceptance tests:** Submit under version A, edit current product to version B, then approve: commercial checks use A. Disabling the product/LSP/mapping still blocks as intended. Verify no new loan chooses an obsolete version unintentionally.

**How it should land:** Small policy/query change, no schema change for correctly pinned rows. Inventory missing versions and migrate only from reliable historical terms.

**What landed (2026-09-19):**

- `LoanAutoApprovalRuleEngine.evaluateAmountTenureRate` now reads `minPrincipal`/`maxPrincipal`/`minTenureMonths`/`maxTenureMonths` from `application.getLoanProductVersion()` — the version pinned at submission — so later catalog edits cannot alter an in-flight application's eligibility bounds.
- Product/LSP/mapping *status* remains a live check (`PRODUCT_INACTIVE`, `LSP_INACTIVE`, `MAPPING_INACTIVE`) — the kill switch still works — while the commercial terms are pinned. A missing pinned version throws `IllegalStateException` loudly; there is no silent fallback to the latest catalog version.
- `PinnedProductTermsApprovalPostgresIntegrationTest` covers the spec scenario: submit under version A, edit the catalog to version B, approval still evaluates A; disabling product/LSP/mapping still blocks; a new application pins the latest version.
- No schema change — the pin column existed; this was purely the read site, exactly as the spec prescribed.

<a id="h17"></a>

### H17 — Expired idempotency keys can become permanently unrecoverable

**Status:** **Completed (2026-09-20)** — landed on main via PR #354 (squash `52818098`). **Source comparison:** Fable; valid issue, unsafe blanket simplification rejected.

**Files and code evidence:**

- [backend/src/main/java/com/bhawana/lms/service/IdempotencyExecutionCoordinator.java:337](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/IdempotencyExecutionCoordinator.java:337)
- [backend/src/main/java/com/bhawana/lms/service/IdempotencyClaimService.java:19](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/IdempotencyClaimService.java:19)
- [backend/src/main/java/com/bhawana/lms/service/IdempotencyRecoveryService.java:9](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/IdempotencyRecoveryService.java:9)
- [backend/src/main/java/com/bhawana/lms/service/LoanDocumentService.java:277](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/LoanDocumentService.java:277)

**In simple terms / root cause:** After reclaiming an expired lease, unsupported operations throw RECOVERY_REQUIRED before the try/catch that releases the pending claim. Each later retry can reclaim and fail again. Fable is right about the dead-end path. However, lease expiry does not prove the old worker is dead, and database rollback does not undo object writes or independently committed work. “Delete stale claim and retry everything” is not a safe universal fix.

**Required fix and final behavior:** Classify operations as database-atomic/re-executable, externally idempotent, or reconciliation-required. Permit rerun of proven database-atomic actions under the existing owner/attempt completion fence; a stale owner must roll back when completion loses ownership. For external actions use deterministic side-effect identity and recovery/evidence. Represent genuinely unknown outcomes explicitly rather than continually renewing an unusable lease. Keep fingerprint conflict and scope isolation. Share coordinator mechanics only after both LSP/admin behavioral tests exist.

**Acceptance tests:** Crash after claim, during action, after local action before completion and while the old owner is still running. Prove one durable business result and correct replay for each operation class. Unsupported recovery must not repeatedly extend a dead lease. Cover storage success followed by database rollback.

**How it should land:** Land one operation family at a time with explicit recovery policy. Inventory existing pending records; never bulk-delete them. Avoid removing reconstructors/leases wholesale on the assumption that all actions are one database transaction.

**What landed (2026-09-20):**

- Operation classification: new `service/IdempotencyOperationClass.java` (DATABASE_ATOMIC / EXTERNALLY_IDEMPOTENT / RECONCILIATION_REQUIRED) + `service/IdempotencyOperationClasses.java` mapping all 26 operation-key call sites; unlisted keys default to RECONCILIATION_REQUIRED — the failure-safe direction.
- Reclaim path in `IdempotencyExecutionCoordinator` (`executeClaimedLsp`/`executeClaimedAdmin`): fingerprint check → terminal-state check → evidence reconstruction (`IdempotencyRecoveryService.tryRecover`) → class-gated re-execution → fenced terminal parking. Unknown outcomes park in a terminal `RECOVERY_REQUIRED` sentinel body (`IdempotencyRecordState`) via `markRecoveryRequiredIfOwned` (REQUIRES_NEW, fenced on id+attempt+owner+pending) in `IdempotencyClaimService` + both record repositories — subsequent retries get a deterministic `IDEMPOTENCY_RECOVERY_REQUIRED` without reclaiming. The dead-lease renewal loop is closed; no pending rows are deleted.
- Document uploads are EXTERNALLY_IDEMPOTENT: `ConfigurableLoanDocumentStorageService` storage keys are content-addressed (`loan/{applicationId}/{type}/{sha256}-{safeName}`) so re-execution overwrites the same object identity, and new `web/LoanDocumentUploadIdempotencyReconstructor` recovers from committed checklist evidence whose checksum matches the request fingerprint (batch all-or-nothing).
- Completion stays under the owner/attempt fence; losing the mark/complete fence re-reads the row and answers honestly (terminal → conflict, completed → replay, still-pending → in-progress).
- Tests: new `IdempotencyRecoveryCoordinatorTest` (9 tests, LSP+admin: reclaim→re-execute, terminal parking, stale-owner fencing, failed-reexecution release, fingerprint-over-terminal precedence), document-upload crash-window tests (evidence recovery without re-storing, deterministic-key re-execution, batch), updates to `IdempotencyLeaseReclaimTest`, `IdempotencyCrashRecoveryIntegrationTest`, `LspApiIdempotencyServiceRaceTest`.
- Landed on PR #354 via `cde4ae90`, plus CI-surfaced hardening `566f4040` (storage-root containment guard `resolveWithinRoot` on every resolve site in `FileSystemLoanDocumentStorageService` + single-segment safe filename in the key constructor — cleared the CodeQL path-injection gate).

<a id="h18"></a>

### H18 — Duplicate requests can occupy request threads for 30 seconds

**Status:** **Completed (2026-09-20)** — landed on main via PR #354 (squash `52818098`). **Source comparison:** Fable.

**Files and code evidence:**

- [backend/src/main/java/com/bhawana/lms/service/IdempotencyExecutionCoordinator.java:453](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/IdempotencyExecutionCoordinator.java:453)
- [backend/src/main/resources/application.yml:231](/Users/siddhant/Desktop/lms/backend/src/main/resources/application.yml:231)
- [frontend/src/lib/api/http-client.ts:30](/Users/siddhant/Desktop/lms/frontend/src/lib/api/http-client.ts:30)

**In simple terms / root cause:** A live duplicate polls every 50 ms until a configurable 30-second deadline. Under retries this holds request threads and repeatedly opens database transactions. The existing IN_PROGRESS/Retry-After response is a better bounded contract. The frontend currently reads Retry-After only for 429, not every retryable conflict.

**Required fix and final behavior:** Default completion wait to zero or a measured short upper bound (at most two seconds for a deliberate quick replay window). Return IDEMPOTENCY_IN_PROGRESS with a bounded Retry-After and preserve the key. Teach partner docs/clients which conflicts are retryable; update the shared frontend parser if it handles these operations and expose the header through CORS. Distinguish payload conflict from in-progress and reconciliation-required.

**Acceptance tests:** A slow owner plus many duplicate requests returns quickly with consistent headers and no duplicate action. A later identical retry replays success. Changed payload conflicts immediately. Measure bounded polling/connection count.

**How it should land:** Configuration/parser/contract patch, no migration. Verify retries remain jittered and user-triggered financial actions keep the same key; do not apply generic automatic retries to arbitrary 4xx responses.

**What landed (2026-09-20):**

- `app.idempotency.completion-wait-seconds` default 30→0 (`application.yml`, `IdempotencyProperties`); `awaitLspCompletion`/`awaitAdminCompletion` keep a final re-check so a record that completed between the last poll and the timeout still replays instead of 409ing — the zero wait stays honest.
- `Retry-After` bounded by new `app.idempotency.retry-after-cap-seconds` (default 5, `APP_IDEMPOTENCY_RETRY_AFTER_CAP_SECONDS`) = `min(cap, remaining lease)` instead of up to the full lease duration.
- Latent `GlobalExceptionHandler` bug fixed: `Retry-After` was set on an already-built `ResponseEntity` (immutable headers → `UnsupportedOperationException`); now set on the builder — the header contract is actually deliverable.
- Frontend `frontend/src/lib/api/http-client.ts` parses `Retry-After` into `ApiError.retryAfterSeconds` for 409 `IDEMPOTENCY_IN_PROGRESS` (retryable conflict) in addition to 429; `IDEMPOTENCY_CONFLICT` stays non-retryable, `settled` semantics unchanged, no generic 4xx auto-retry.
- Tests: fast bounded 409+`Retry-After` under a slow owner, recovery after lease expiry, replay-after-completion; +5 http-client tests.
- Landed on PR #354 via `cde4ae90`; cross-origin half of the contract completed by M15 (`cc3ffa67` exposes `Retry-After` through CORS). CI iteration `6dc77f0e` updated `Issue86RepaymentIdempotencyIntegrationTest` to the 409→retry→200 contract with a test-scoped pool widening.

<a id="h19"></a>

### H19 — A missing deployment profile activates insecure local defaults

**Status:** **Completed (2026-09-12)** — landed on main via commit `12dda5f9`. **Source comparison:** Both.

**Files and code evidence:**

- [backend/src/main/resources/application.yml:11](/Users/siddhant/Desktop/lms/backend/src/main/resources/application.yml:11)
- [backend/src/main/resources/application-local.yml:70](/Users/siddhant/Desktop/lms/backend/src/main/resources/application-local.yml:70)
- [backend/src/main/java/com/bhawana/lms/config/UnsafeDeploymentConfigurationValidator.java:15](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/config/UnsafeDeploymentConfigurationValidator.java:15)
- [backend/src/main/java/com/bhawana/lms/tenant/TenantDatasourceSecurityValidator.java:40](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/tenant/TenantDatasourceSecurityValidator.java:40)

**In simple terms / root cause:** The default profile is local. Forgetting the deployment profile can load development secrets, disable rate limiting and skip safety validators. This is a configuration failure mode, not proof that a deployed production instance currently uses those defaults.

**Required fix and final behavior:** Remove local as the implicit default. Require explicit local/test selection for development exemptions; unknown/unset profiles must enforce runtime safety. Keep secrets required outside those profiles and validate secure cookies, storage, database roles and simulator configuration together. Avoid a local+production profile combination silently bypassing checks. Provide an explicit local launch command/example so developers do not need weaker runtime defaults.

**Acceptance tests:** Boot with no profile and with a misspelled profile fails on unsafe/missing configuration. Explicit local works. A production-like profile with all valid settings starts, and any development secret/simulator setting fails clearly.

**How it should land:** Land configuration, validator tests and local/runbook commands together. Validate deployment manifests before rollout; no database migration.

**What landed (2026-09-12):**

- `spring.profiles.default: local` is removed — booting with no explicitly active profile now enforces every production safety check instead of silently inheriting local behavior; local development must activate the profile (`12dda5f9`).

<a id="h20"></a>

### H20 — API-client refresh tokens bypass the intended client-credential lifecycle

**Status:** **Completed (2026-09-12)** — landed on main via commit `12dda5f9`. **Source comparison:** Fable; more specific than the first Codex auth review.

**Files and code evidence:**

- [backend/src/main/java/com/bhawana/lms/service/AuthTokenService.java:156](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/AuthTokenService.java:156)
- [backend/src/main/java/com/bhawana/lms/service/AuthTokenService.java:249](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/AuthTokenService.java:249)
- [backend/src/main/java/com/bhawana/lms/web/AuthController.java:36](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/web/AuthController.java:36)
- [backend/src/main/java/com/bhawana/lms/repo/RefreshTokenRepository.java:27](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/repo/RefreshTokenRepository.java:27)

**In simple terms / root cause:** Machine token issuance creates a refresh cookie. Rotation uses current status-only validation and can mint fresh tokens without the client secret or token-endpoint allowlist/lockout checks; a client-secret token-version change is not stored/checked on that refresh credential. The LSP per-request API allowlist still applies on protected API routes, so this is not proof that all API allowlists are bypassed.

**Required fix and final behavior:** Stop issuing refresh credentials for API_CLIENT and reject already stored API-client refresh tokens at refresh. Revoke/purge them in a controlled migration or maintenance step, retaining necessary audit data. Human refresh remains supported. Machine clients reacquire access through their authorized credential flow; if moving to Entra, retire local token issuance only after partner cutover. Document token TTL and secret rotation behavior.

**Acceptance tests:** Machine token response has no refresh cookie; an old API-client refresh cookie cannot mint a token before or after secret rotation. Human refresh still works. Client disablement, LSP disablement and per-request allowlists continue to hold.

**How it should land:** Notify/update machine-client integrations through the project’s normal release process. Keep separate compatibility/cutover policy; do not wait indefinitely for Entra to close the current refresh bypass.

**What landed (2026-09-12):**

- `API_CLIENT` refresh rows are retired and `api_client` gains an external credential-invalidation instant compared against token `iat`, so machine tokens can no longer bypass the intended client-credential lifecycle (`12dda5f9`; migration `V127`).

<a id="h21"></a>

### H21 — Refresh-token consumption is not atomic and has no reuse-family policy

**Status:** **Completed (2026-09-12)** — landed on main via commit `12dda5f9`. **Source comparison:** Both.

**Files and code evidence:**

- [backend/src/main/java/com/bhawana/lms/service/AuthTokenService.java:168](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/AuthTokenService.java:168)
- [backend/src/main/java/com/bhawana/lms/repo/RefreshTokenRepository.java:16](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/repo/RefreshTokenRepository.java:16)
- [backend/src/main/java/com/bhawana/lms/domain/RefreshToken.java:18](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/domain/RefreshToken.java:18)
- [frontend/src/features/auth/auth-service.ts:58](/Users/siddhant/Desktop/lms/frontend/src/features/auth/auth-service.ts:58)

**In simple terms / root cause:** Rotation reads an unrevoked token, revokes it, and inserts a successor without a row lock, CAS or optimistic version. Two transactions can both issue successors. The single-flight frontend promise covers one tab only, while cookies are shared between tabs. Revoked-token reuse does not identify/revoke a family.

**Required fix and final behavior:** Atomically consume an unexpired, unrevoked refresh token using row locking or a conditional update; only the winning transaction may create the successor. Introduce a family/session identifier if implementing family-wide revocation. Define legitimate concurrent-tab behavior separately from theft/reuse so a harmless race does not randomly destroy a fresh session. Coordinate logout and refresh family revocation; do not log raw tokens. Prefer a clear loser response and frontend refresh coordination over an unbounded replay grace period.

**Acceptance tests:** Two simultaneous rotations yield one successor. Reuse after the documented tolerance/policy triggers the intended family revocation. Cover refresh/logout races, password change, disabled user/LSP and two browser tabs.

**How it should land:** Additive schema and repository tests before switching the service. Define treatment of legacy tokens without a family; expire/re-authenticate them rather than guessing lineage. Coordinate with H22.

**What landed (2026-09-12):**

- One `auth_session` family row per human login, bound to the access token's `sid` claim — revoking a session kills its own lineage instead of user-wide state; machine tokens never get a family (`V125`).
- A partial unique index enforces at most one live refresh head per family; rotation revokes the consumed parent before inserting its successor inside a single fenced transaction with a fixed principal→family→token lock order (`V126`) — consumption is atomic.
- A policy epoch derived from the signing key, issuer and both audiences binds each session: rotating a key or audience forces reauthentication (`12dda5f9`).

<a id="h22"></a>

### H22 — A late refresh or logout can overwrite a newer browser session

**Status:** **Completed (2026-09-18)** — coordinator hardened on main via PRs #346 (squash `4ee8ffe3`) and #345 (squash `452d98e2`). **Source comparison:** Codex; omitted by Fable; not fixed by the cache pilot.

**Files and code evidence:**

- [frontend/src/features/auth/session-provider.tsx:58](/Users/siddhant/Desktop/lms/frontend/src/features/auth/session-provider.tsx:58)
- [frontend/src/features/auth/session-provider.tsx:119](/Users/siddhant/Desktop/lms/frontend/src/features/auth/session-provider.tsx:119)
- [frontend/src/features/auth/auth-service.ts:125](/Users/siddhant/Desktop/lms/frontend/src/features/auth/auth-service.ts:125)
- [frontend/src/features/auth/auth-service.ts:129](/Users/siddhant/Desktop/lms/frontend/src/features/auth/auth-service.ts:129)

**In simple terms / root cause:** Refresh completion saves credentials and updates React session state without checking that it still belongs to the current authentication attempt. Logout awaits a server call before cleanup. An older refresh can restore a signed-out session, or a slow old logout can clear a newer login’s stored state. The new per-session query client does not fix these auth-service/cookie races.

**Required fix and final behavior:** Define one authentication generation/operation coordinator spanning SessionProvider, auth-service and storage. Every login/refresh/bootstrap/password-change captures a generation and may publish/save only while current. Logout invalidates prior work immediately and serializes or blocks a subsequent login until old cookie-affecting operations finish. Coordinate browser tabs with a documented mechanism and H21’s backend family policy. Aborting a fetch alone cannot undo a Set-Cookie already processed by the browser; the server and UI sequence must agree.

**Acceptance tests:** Deferred refresh after logout cannot restore credentials/state. Deferred old logout after attempted B login cannot erase B. Test bootstrap, 401 replay, password change, two tabs and refresh failure. Retain all cache-isolation pilot tests and same-identity cache preservation.

**How it should land:** Separate auth-lifecycle patch after designing backend/cookie semantics. Do not patch only setSession or only localStorage. No changes to the completed cache boundary unless an actual regression test requires them.

**What landed (2026-09-12 base; 2026-09-18 hardening):**

- The generation/operation coordinator (`auth-coordinator.ts`) spanning SessionProvider, auth-service, and storage landed in the Sep-12 auth-chain commit `12dda5f9`: monotonic identity generation, publish-only-while-current, logout invalidates prior work, and cross-tab cookie-affecting operations serialize through a Web Lock that fails closed when locking or ordering storage is unavailable — the documented tab-coordination mechanism.
- Hardening added in this batch: after acquiring the Web Lock, the coordinator tolerates only the bounded propagation window for a peer's in-flight marker removal — a confirmed removal lets the newer intent proceed, an actual orphan still fails closed (never cleared by timeout). 20 unit tests pin the behavior (`87e11d6c`, PR #345).
- The H22 e2e proof is now event-driven rather than wall-clock choreography: a `peerGone` socket-close milestone plus a `waitForUnblocked` settle gate make the close-peer-with-outstanding-refresh case deterministic, and the designed stale-loser outcome is asserted explicitly (`AuthStaleResultError`) so an environmental peer death reports at the right assertion line (`42dcb28f`, `9484f99`, `08af2789`, PR #346).
- The acceptance shape holds: a deferred refresh after logout cannot restore credentials/state, and a deferred old logout cannot erase a newer login — spec 9/9 green in CI, and `Cookie transport (H22)` is a required branch gate.
- Scope note: the backend refresh-token family policy this ticket defers to remains tracked under H21, not here.

<a id="h23"></a>

### H23 — Runtime database-role validation does not prove the intended RLS behavior

**Status:** **Completed (2026-09-12)** — landed on main via commit `12dda5f9`. **Source comparison:** Both; Fable focuses on admin access, Codex on tenant bypass privileges.

**Files and code evidence:**

- [backend/src/main/java/com/bhawana/lms/tenant/TenantDatasourceSecurityValidator.java:85](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/tenant/TenantDatasourceSecurityValidator.java:85)
- [backend/src/main/java/com/bhawana/lms/tenant/TenantIsolationDataSourceConfig.java:19](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/tenant/TenantIsolationDataSourceConfig.java:19)
- [backend/src/main/resources/db/migration/V115\_\_force_row_level_security.sql:1](/Users/siddhant/Desktop/lms/backend/src/main/resources/db/migration/V115__force_row_level_security.sql:1)

**In simple terms / root cause:** The tenant startup probe checks current_user equality but not superuser/BYPASSRLS or dangerous role memberships. A correctly named but overprivileged role could bypass RLS. Conversely, FORCE RLS plus tenant-only policies can hide rows from an admin runtime role without a permitted policy or bypass. Local superuser-backed tests do not establish the Azure runtime behavior.

**Required fix and final behavior:** Provision separate migration, admin-runtime and tenant-runtime identities. Tenant must be NOSUPERUSER/NOBYPASSRLS, unable to assume privileged roles, and limited to required grants. Admin runtime needs an explicitly chosen cross-tenant access design: narrow admin policies or a dedicated audited BYPASSRLS role, never the cloud server administrator by default. Probe effective identity/privileges and perform a fixture-based two-tenant read/write smoke test using the actual connection strategy. Fail startup for unsafe tenant capability or nonfunctional admin access.

**Acceptance tests:** Exact non-superuser runtime identities: A cannot read/write B; missing scope fails; tenant cannot SET ROLE to admin; legitimate admin can see required data. Test FORCE RLS, set-role vs separate-pool mode and grant drift.

**How it should land:** Provision and test on the selected Azure PostgreSQL version. Do not remove FORCE RLS to make deployment pass. Fable’s universal “Azure admin sees zero rows” claim is environment/version dependent; validate actual roles.

**What landed (2026-09-12):**

- `TenantDatasourceSecurityValidator` was hardened inside the auth-trust-chain commit — the startup probe now proves the intended RLS behavior for the runtime role rather than only checking name equality (`12dda5f9`).

<a id="h24"></a>

### H24 — Report processing holds a transaction through storage and email

**Status:** **Completed (2026-09-19)** — landed on main via PR #351 (squash `843917e3`). **Source comparison:** Both.

**Files and code evidence:**

- [backend/src/main/java/com/bhawana/lms/service/ReportRequestService.java:100](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/ReportRequestService.java:100)
- [backend/src/main/java/com/bhawana/lms/service/ReportRequestService.java:128](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/ReportRequestService.java:128)
- [backend/src/main/java/com/bhawana/lms/service/ReportRequestService.java:151](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/ReportRequestService.java:151)
- [backend/src/main/java/com/bhawana/lms/repo/ReportRequestRepositoryImpl.java:26](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/repo/ReportRequestRepositoryImpl.java:26)

**In simple terms / root cause:** The claimed batch, CSV generation, object upload, completion update and SMTP notification share a database transaction. It holds connections/locks while external systems work. Email or upload can succeed even if the transaction later rolls back. The current PROCESSING state is generally rolled back with the claim, so it is wrong to assume every crash leaves a permanently committed PROCESSING row.

**Required fix and final behavior:** Claim one bounded batch in a short transaction with owner/lease. Generate and store outside that transaction using a deterministic job output identity. Record completion/failure in another fenced transaction. Add a durable notification-pending state/job and retry it separately after completion commits; distinguish generation failure from notification failure. New short claim transactions require explicit lease recovery for crashes, which cannot be omitted. Catch each job’s failure outside its failed transaction.

**Acceptance tests:** Storage/SMTP timeout does not hold a business transaction. Crash after upload before completion is recoverable without inconsistent duplicate outputs; crash after completion before email retries notification only. One failed job does not poison the batch; duplicate notification policy is explicit.

**How it should land:** Add lease/notification fields or a small outbox table as needed, backfill existing pending work, and drain old report workers for the protocol change. This is a local database job design; RabbitMQ is unnecessary.

**What landed (2026-09-19):**

- `report_request` gained lease columns (`processing_owner`, `processing_fencing_seq`, `processing_lease_expires_at`, notification lease fields) via migration `V131`. `ReportRequestRepositoryImpl` claims a bounded batch in a short `SKIP LOCKED` transaction that stamps owner + fencing sequence; generation, S3/R2 upload and SMTP all run **outside** any database transaction.
- Completion/failure/notification state writes are fenced updates (`WHERE processing_fencing_seq = ?`) — a worker that lost or let expire its lease can no longer mutate the row. `WorkerLeaseRepository`-style claim/renew/expire semantics mirror the disbursement intent lease.
- `InMemoryReportStorageService`/`R2` storage keys are now deterministic per request (request id + version), so a retry after a crash uploads the same object instead of orphaning a new random key each time.
- A durable notification-pending sweep (`ReportRequestProcessingWorker.processPendingNotifications`) retries email after completion commits — generation failure vs notification failure are now separate states.
- Verification: `ReportProcessingTransactionBoundaryIntegrationTest` — the claim is visible from a second connection while upload is still running, crash-after-upload resumes to the same output, stale-owner writes are fenced out, temp files cleaned on failure.

<a id="h25"></a>

### H25 — Large exports and unbounded worker scans have no reliable resource bound

**Status:** **Completed (2026-09-19)** — landed on main via PR #351 (squash `843917e3`). **Source comparison:** Both; Fable’s numerical ceilings and OOM thresholds are unverified.

**Files and code evidence:**

- [backend/src/main/java/com/bhawana/lms/service/AdminReportingService.java:243](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/AdminReportingService.java:243)
- [backend/src/main/java/com/bhawana/lms/service/LoanDisbursementWorkerService.java:111](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/LoanDisbursementWorkerService.java:111)
- [backend/src/main/java/com/bhawana/lms/service/LoanDisbursementWorkerService.java:129](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/LoanDisbursementWorkerService.java:129)
- [backend/src/main/java/com/bhawana/lms/repo/LoanEventRepository.java:132](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/repo/LoanEventRepository.java:132)

**In simple terms / root cause:** CSV query batches still accumulate the entire file in a StringBuilder/String/byte array. Entity state can also accumulate in the surrounding report transaction. Disbursement and polling paths load all matching accounts and call providers serially. Long writing transactions delay the xid8-watermarked partner feed. Fable’s batch-size=10 throughput ceiling is incorrect: the separate per-application path also executes intents.

**Required fix and final behavior:** Stream exports to a bounded temporary file/output stream, use projections or controlled persistence contexts, then upload with a streaming storage port. Preserve consistent report cutoff/snapshot semantics; prefer stable keyset pagination for a full export. Select bounded due IDs for workers and apply per-item isolation, provider deadlines and polling backoff. Tune batch/delay/concurrency using measured provider latency, database occupancy and queue age after C03. Retain the feed’s commit-order correctness and monitor old transaction/feed age.

**Acceptance tests:** Representative large synthetic export shows bounded heap and a complete consistent row set. Slow-provider backlog drains at measured rates without loading the whole queue. Long-transaction injection produces monitored feed lag, not skipped events. Verify cleanup of temporary files on failure.

**How it should land:** Land report streaming after H24’s transaction split; tune workers only after claims are safe. No claimed capacity guarantee from monthly loan count alone. Do not add a broker or increase batches to 100 without measurements.

**What landed (2026-09-19):**

- `AdminReportingService.generatePortfolioMisCsv` now streams keyset-paginated batches through a `BufferedWriter` into a bounded temp file — no more StringBuilder→String→byte[] heap accumulation. The storage port takes a `Path` (`store(descriptor, Path)`); R2 uploads via `RequestBody.fromFile`. `ReportRequestService` deletes the temp file in `finally` on success and failure; the synchronous download endpoint reuses the same flow.
- Export queries gained an `asOf` snapshot cutoff bound on `account.createdAt` — the row set is fixed at report generation time and approved-but-undisbursed loans (null `disbursedAt`) correctly remain included (this exact predicate was the `ProductVersioningIntegrationTest` regression caught in CI).
- Worker scans are bounded ID-only queries: `findIdsByStatus` + `findIdsAwaitingFirstStatusPoll` take `Pageable` (`app.disbursement.worker.scan-batch-size: 200`). The status-check scan now owns only accounts with **no** reconciliation queue row — unresolved repeats ride the existing `next_poll_at` queue backoff via `processReconciliationQueue`, so stuck accounts are not re-polled every tick. Since submission already enqueues pending intents, repeat polls are queue-owned end to end (the three lifecycle tests were updated to drive the sweep — the CI regression this contract change caused).
- `LoanEventFeedWatermarkIntegrationTest` proves the xid8 watermark withholds committed events behind an open long transaction and then feeds them in commit order — monitored lag (`OLDEST_TRANSACTION_AGE` rule already alerts on `pg_stat_activity`), never skipped events.
- Verification: `DisbursementScanBoundTest` (page is a stable prefix of the full ordered set), feed-watermark test, temp-file cleanup test; the six disbursement-worker isolation tests updated to the queue-owned poll path all pass.

<a id="h26"></a>

### H26 — Unrelated scheduled work shares the default single scheduler thread

**Status:** **Completed (2026-09-19)** — landed on main via PR #351 (squash `843917e3`). **Source comparison:** Both.

**Files and code evidence:**

- [backend/src/main/java/com/bhawana/lms/service/LoanDisbursementWorker.java:25](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/LoanDisbursementWorker.java:25)
- [backend/src/main/java/com/bhawana/lms/service/ReportRequestProcessingWorker.java:29](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/ReportRequestProcessingWorker.java:29)
- [backend/src/main/resources/application.yml:7](/Users/siddhant/Desktop/lms/backend/src/main/resources/application.yml:7)
- [pom.xml:10](/Users/siddhant/Desktop/lms/pom.xml:10)

**In simple terms / root cause:** No scheduler pool/separation or virtual-thread override is configured. Under the pinned Spring Boot default, a long report or evaluator can delay disbursement and status checks on that process. Increasing the pool is useful but not a complete one-line safety fix because it exposes existing database races and concurrent whole-book work.

**Required fix and final behavior:** Keep scheduled callbacks short and assign bounded resources to financial submission/status checks versus reporting/maintenance. Use separate schedulers/executors or explicitly sized pools with guarded submission so a slow report cannot consume every worker. Add overload/backlog behavior and stop claiming during shutdown. Preserve per-job leases and do not allow overlapping unsafe scans merely by increasing pool size.

**Acceptance tests:** Block report storage while scheduling financial polling and verify it runs within the agreed interval. Test pool saturation, shutdown while work is claimed and restart recovery. Observe thread/connection counts under load.

**How it should land:** Land after C03, H12 and H24, then benchmark. Spring Boot 3.5 already enables graceful web shutdown; verify SIGTERM timing and worker lease recovery rather than reporting its missing YAML property as a bug.

**What landed (2026-09-19):**

- New `ScheduledJobThreadingConfig` defines three dedicated bounded `ThreadPoolTaskScheduler` beans: `financialTaskScheduler` (2 threads — disbursement submission, status checks, reconciliation), `reportingTaskScheduler` (1), `maintenanceTaskScheduler` (1 — alert evaluation, KPI snapshot, retention, partition lifecycle; serializing whole-book scans by design). Named threads (`lms-financial-job-*` etc.), failure logging, graceful shutdown — in-flight work finishes within 30s and queued runs are cancelled.
- Every `@Scheduled` method now declares its family via `scheduler = ScheduledJobThreadingConfig.<FAMILY>`; `ScheduledJobConcurrencyArchitectureTest` pins the contract so a new job cannot silently land on the shared default pool.
- Overload behavior is queueing, not rejection — `fixedDelay` already means no next run until the current one finishes, so backlog cannot accumulate behind the fixed schedule.
- Verification: `ScheduledJobThreadingIntegrationTest` — a blocked reporting thread does not starve a real financial call (observed running on the financial thread), a saturated financial pool queues without overlap or drop, and shutdown finishes claimed work while refusing new claims.

<a id="h27"></a>

### H27 — Operational metrics and job health are not connected to a production exporter

**Status:** **In progress** — disbursement observability (`60ffc6fd`, 2026-09-09) and the cross-worker job-health slice (PRs #351/#353, 2026-09-19) are landed; staging verification of the shipped gauges against a live collector remains open. **Source comparison:** Both.

**Files and code evidence:**

- [backend/pom.xml:20](/Users/siddhant/Desktop/lms/backend/pom.xml:20)
- [backend/src/main/resources/application.yml:34](/Users/siddhant/Desktop/lms/backend/src/main/resources/application.yml:34)
- [backend/src/main/java/com/bhawana/lms/service/DisbursementIntentMetrics.java:14](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/DisbursementIntentMetrics.java:14)
- [backend/src/main/java/com/bhawana/lms/service/PostgresAdvisoryLockSupport.java:7](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/PostgresAdvisoryLockSupport.java:7)

**In simple terms / root cause:** Custom meters exist, but the checked-in configuration/dependencies do not provide a production metrics export path. Correlation context is collected, but there is no explicit structured logging configuration that consistently prints it, and scheduled runs need their own context. A gauge defined in Java is not an operational alert.

**Required fix and final behavior:** Choose one deployment-supported metrics exporter (Prometheus or OTLP), secure its endpoint/transport, and configure structured logs with correlation ID plus safe tenant/job identifiers. Give workers a run ID and clear MDC after execution. Add last-success, failure count, queue age, pending/unapplied/unknown age, provider latency, report latency and connection-pool metrics. Define dashboards and alert ownership; do not put PII or high-cardinality borrower IDs into metric labels.

**Acceptance tests:** A staging collector receives a known test meter. A forced worker failure and stuck intent trigger the expected signal. HTTP and worker logs contain usable correlation; context does not leak between reused threads.

**How it should land:** Land exporter/config and operational assets together before production pilot. Verify any platform-provided collector before declaring it absent in the actual environment; this finding is about the repository baseline.

**What landed (2026-09-09, disbursement slice):**

- Prometheus registry + `SYSTEM_ADMIN`-only `/actuator/prometheus` endpoint, disbursement age/latency gauges, reconciliation-sweep wiring, and the dashboard/alert-rules/runbook under `docs/observability` (`60ffc6fd`).

**What landed (2026-09-19, worker slice):**

- `JobObservabilitySupport` wraps every scheduled tick: it sets a per-run correlation id (`job-<name>-<id>`) on `CorrelationIdHolder` + MDC (`correlationId`, `jobName`), records `lms.job.run.duration` and `lms.job.runs{job,outcome}`, publishes `lms.job.last_success_epoch_seconds`, and always clears MDC/holder in `finally` so pooled scheduler threads cannot leak context. Wired into all nine scheduled entry points (`disbursement-pending`, `disbursement-status-checks`, `disbursement-reconciliation`, `report-processing`, `report-notifications`, `portfolio-kpi-snapshot`, `alert-rule-evaluation`, `idempotency-retention`, `loan-event-partitions`); disabled jobs record nothing since the wrapper sits inside the enabled check.
- `ReportWorkerMetrics` adds `lms.report.request.pending.count`, `lms.report.request.oldest_age_seconds` (scrape-time admin-scoped gauges) and `lms.report.request.processing.duration{outcome}` per-request timing. Labels carry only job/outcome — no PII or high-cardinality identifiers.
- Verification: `JobObservabilitySupportTest` (success/failure meters, context cleared between runs) and `WorkerJobObservabilityIntegrationTest` (real tick → success outcome + clean context; forced failure → failure outcome + propagation). The test keeps the worker disabled at context start and flips `setEnabled(true)` per test — a class-level `enabled=true` armed real scheduled ticks that raced `cleanIntegrationTestData` on the shared test database (the two CI flakes this fixed).
- Still open: staging verification that a live collector receives the shipped meters.

<a id="h28"></a>

### H28 — Adapters turn unknown or absent values into misleading business facts

**Status:** **Completed (2026-09-19)** — landed on main via PR #352 (squash `124b926d`). **Source comparison:** Fable; confirmed, with mapping nuances.

**Files and code evidence:**

- [frontend/src/features/loan-applications/api-detail.ts:123](/Users/siddhant/Desktop/lms/frontend/src/features/loan-applications/api-detail.ts:123)
- [frontend/src/features/reports/api.ts:179](/Users/siddhant/Desktop/lms/frontend/src/features/reports/api.ts:179)
- [frontend/src/features/alerts/api.ts:26](/Users/siddhant/Desktop/lms/frontend/src/features/alerts/api.ts:26)
- [frontend/src/lib/number.ts:1](/Users/siddhant/Desktop/lms/frontend/src/lib/number.ts:1)
- [frontend/src/features/home/api.ts:98](/Users/siddhant/Desktop/lms/frontend/src/features/home/api.ts:98)

**In simple terms / root cause:** Detail adapters invent gender/marital status/KYC defaults and ACTIVE related entities. MIS has a code path collapsing UNDER_REPAYMENT to DISBURSED and unknown states to INITIALIZED. Missing money becomes zero; unknown severity becomes MEDIUM. Some MIS screens also retain loanStatusDisplay, so not every displayed status is necessarily wrong. These fallbacks conceal contract gaps and can mislead decisions. The Home adapter maps unknown DPD bucket keys to B0 (Current); the MIS bucket mapper itself returns null for unknown values.

**Required fix and final behavior:** Model unknown/absent values explicitly. Use the existing raw-unknown status pattern, nullable unsupported demographic/KYC fields and clear “Not available” rendering. Preserve account status and application status as distinct fields. Validate externally supplied amounts; do not use zero for unavailable debt. Display authoritative values only, and update backend DTOs where the screen genuinely requires missing data. Identify every consuming column/card before changing a mapper. Specifically change Home’s unknown DPD bucket fallback to an explicit Unknown/unclassified bucket; MIS already returns null for unknown buckets and should keep that honest behavior.

**Acceptance tests:** Contract fixtures with null amounts, new statuses/severities, missing demographics and UNDER_REPAYMENT render accurate values or Unknown; never fabricated healthy/current values. Existing known fixtures and filters remain correct.

**How it should land:** Land MIS/debt/status mappings first, then demographic/detail cleanup. Evolve shared schemas and generated contract consumers together; no generic adapter rewrite. Do not claim JavaScript number alone changes the backend’s exact financial arithmetic.

**What landed (2026-09-19):**

- Detail adapters no longer fabricate borrower facts: `gender`, `maritalStatus`, KYC fields, Aadhaar, employment detail, and banking are `null` ("not available") instead of invented defaults like `M`/`SINGLE`/`SALARIED`; `requestedAmount`/`tenureMonths` are `null` when absent rather than ₹0/0 presented as measured.
- Unknown wire statuses surface as `UNKNOWN:<raw>` through the existing raw-unknown pattern (`apiLoanStatus`/`LoanStatusOrUnknown`) instead of collapsing to `INITIALIZED`; the disbursement gate widened to that type and defaults unknown status to the safe pre-disbursement wording.
- Home's unknown DPD bucket maps to an explicit Unknown/unclassified bucket; MIS's null-for-unknown behavior kept as-is; account status and application status stay distinct fields.
- Employment type goes through schema `safeParse` → null when unrecognized; money fields validate rather than defaulting to zero.
- `api-detail.test.ts`, `api.backend-map.test.ts` (home + reports), and component tests assert contract fixtures render honest values or "Not available" — never fabricated healthy data.

<a id="h29"></a>

### H29 — List controls promise filters and sorting that the request does not apply

**Status:** **Completed (2026-09-19)** — landed on main via PR #352 (squash `124b926d`). **Source comparison:** Fable.

**Files and code evidence:**

- [frontend/src/features/loan-applications/api.ts:62](/Users/siddhant/Desktop/lms/frontend/src/features/loan-applications/api.ts:62)
- [frontend/src/features/loan-applications/types.ts:1](/Users/siddhant/Desktop/lms/frontend/src/features/loan-applications/types.ts:1)
- [backend/src/main/java/com/bhawana/lms/web/LoanApplicationOpsController.java:69](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/web/LoanApplicationOpsController.java:69)

**In simple terms / root cause:** The loan list serializes only status[0] and does not send the advertised sort choice. Filtering/sorting a visible server page cannot produce a correctly sorted/filtered whole result set. Operators can select several statuses while seeing only one.

**Required fix and final behavior:** Choose a truthful contract: implement whitelisted server sort plus multi-status filtering with OR semantics, or temporarily reduce the UI to the supported single status and fixed ordering. Preferred final behavior: send all selected statuses, validate allowed sort fields/direction, apply a stable ID tie-breaker before pagination, and return matching total counts. Keep URL, query key, request and displayed controls synchronized.

**Acceptance tests:** Select two statuses with matching rows on different pages; both appear and totals agree. Sort asc/desc across page boundaries and compare server results. Invalid sort/status fails clearly, and reset/back navigation restore the same query.

**How it should land:** Land API/query changes with frontend controls and contract tests. Avoid client-side sorting of only the loaded page as a purported fix; no schema migration unless a measured query needs an index.

**What landed (2026-09-19):**

- The spec's preferred contract landed: `LoanApplicationQueryService.listApplicationsPageStrict` accepts the full status selection with OR semantics (`resolveStatusesStrict` — every value must name a known lifecycle state, else 422), whitelisted sort (`resolveSortByStrict` — only `createdAt`, `updatedAt`, `requestedAmount`, `status`) and direction (`asc`/`desc`, else 422 with field-level detail).
- `buildQueryPath` in `http-client.ts` serializes array params as repeated query params (`?status=A&status=B`) so multi-select reaches the server; URL, query key, request, and displayed controls stay synchronized.
- Repository query applies the multi-status filter and stable ordering server-side; totals reflect the filtered set, not the visible page.
- `LoanApplicationOpsControllerTest` covers multi-status matching across pages, sort asc/desc across boundaries, and 422s for invalid sort/status.
- Follow-up fix on the same PR resolved two CodeQL review threads: the completion log now records `sourceChannel` as a presence boolean (no user-controlled value in the log line), matching the line's existing `queryPresent` convention.

<a id="h30"></a>

### H30 — The alerts screen paginates only the backend’s first capped page

**Status:** **Completed (2026-09-19)** — landed on main via PR #352 (squash `124b926d`). **Source comparison:** Fable pagination claim corrected and raised from Medium.

**Files and code evidence:**

- [frontend/src/features/alerts/api.ts:96](/Users/siddhant/Desktop/lms/frontend/src/features/alerts/api.ts:96)
- [backend/src/main/java/com/bhawana/lms/service/OpsAlertService.java:100](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/OpsAlertService.java:100)
- [backend/src/main/java/com/bhawana/lms/web/OpsAlertController.java:75](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/web/OpsAlertController.java:75)

**In simple terms / root cause:** The backend is already paginated even when offset/limit are omitted. The frontend fetches without those parameters, assumes the returned array is all alerts, then filters/paginates locally. Older matching alerts can be hidden and totals understated. This is worse than simply downloading too many alerts, which is how Fable described it.

**Required fix and final behavior:** Send offset/limit/paginationDetails and read the existing pagination headers. Move severity, subject and text filters into a validated backend query so filtering applies to the full dataset before pagination. Return stable ordering and correct counts. Users/API-clients may have a separate all-rows scaling issue; do not assume every list uses the alerts contract.

**Acceptance tests:** Seed more than the default cap, including a severe matching alert only on a later page. Pagination, search/filter totals and acknowledgement refresh all find it. Verify no duplicate/omitted rows when timestamps tie.

**How it should land:** Small vertical API/repository/frontend change with an integration fixture. No table redesign. Add indexes only for the actual filter/order plan measured on representative data.

**What landed (2026-09-19):**

- The alerts list is honestly paginated: the frontend sends `offset`, `limit`, `paginationDetails=ON` and reads totals from the pagination headers; the local filter/paginate path is removed.
- Severity, subject-type, and text filters moved into the backend (`OpsAlertRepository.searchAlerts` applies them to the full dataset before pagination) with strict validation — unknown severity → 422 `INVALID_SEVERITY`; stable `createdAt desc, id desc` ordering prevents duplicate/omitted rows across timestamp ties.
- `OpsAlertSeverity` gained `MEDIUM`/`LOW` constants — the unconstrained VARCHAR column could already hold those values and `EnumType.STRING` would crash the whole read on a legacy row; emitters still only produce HIGH/CRITICAL and no migration was needed.
- `OpsAlertControllerTest` (19 tests) covers the spec fixture: 55-seed dataset with a severe match only on a later page, forced-timestamp-tie stability, and ack-refresh; frontend `alerts/api.test.ts` covers the contract.
- The dead `includePaginationDetails` parameter was removed from `OpsAlertService.listAlerts` after CodeQL flagged it (review thread resolved with the fix).

<a id="h31"></a>

### H31 — Generic status commands can diverge from financial evidence

**Status:** **Completed (2026-09-09)** — landed on main via commit `60ffc6fd`. **Source comparison:** Codex; Fable notices state-policy fragmentation but not this full invariant.

**Files and code evidence:**

- [backend/src/main/java/com/bhawana/lms/service/LoanApplicationLifecycleService.java:71](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/LoanApplicationLifecycleService.java:71)
- [backend/src/main/java/com/bhawana/lms/domain/LoanApplicationStatus.java:36](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/domain/LoanApplicationStatus.java:36)
- [backend/src/main/java/com/bhawana/lms/service/LoanApplicationStatusWriter.java:58](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/LoanApplicationStatusWriter.java:58)

**In simple terms / root cause:** The standard status matrix permits transitions to financial statuses. A status writer and its audit trail do not by themselves prove a successful bank outcome, matching account state or settled schedule. Manual override has meaningful guards and must not be described as unlimited; the standard command still needs explicit ownership of financial transitions.

**Required fix and final behavior:** Separate ordinary workflow transitions from financial outcome commands. DISBURSED must be written by the accepted bank-outcome path; CLOSED/FORECLOSED by validated settlement commands. Reject direct status requests to those targets with a clear error or dispatch the complete authorized financial command only when all required evidence is present. Centralize account/application transition policy and keep events atomic. Do not let an admin reason substitute for financial evidence.

**Acceptance tests:** Direct standard/manual API calls cannot mark an unfunded loan DISBURSED or an unpaid loan CLOSED. Legitimate bank/settlement paths still produce the expected account/application/audit/event state. Enumerate source/target/context combinations.

**How it should land:** Land endpoint guards before broad domain cleanup, and update clients/OpenAPI. Read-only reconciliation should identify existing mismatched account/application states for review.

**What landed (2026-09-09):**

- Scoped lifecycle guards now reject status commands that would bypass financial-target or in-flight intent states, so generic status commands can no longer diverge from the recorded financial evidence (`60ffc6fd`).

<a id="g02"></a>

### G02 — The requested Entra machine-identity model is not present

**Status:** **Completed (2026-09-12)** — Entra v2 app-only validation landed on main via commit `12dda5f9`, disabled by default pending a configured tenant. **Source comparison:** Both; separate High production integration prerequisite.

**Files and code evidence:**

- [backend/src/main/java/com/bhawana/lms/security/JwtSecurityBeans.java:33](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/security/JwtSecurityBeans.java:33)
- [backend/src/main/java/com/bhawana/lms/service/AuthAuthenticationService.java:35](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/AuthAuthenticationService.java:35)
- [backend/src/main/java/com/bhawana/lms/web/LspAuthenticationSupport.java:13](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/web/LspAuthenticationSupport.java:13)
- [backend/src/main/java/com/bhawana/lms/domain/ApiClient.java:20](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/domain/ApiClient.java:20)

**In simple terms / root cause:** Machine clients currently authenticate with local secrets and receive locally minted JWTs. Entra is a target architecture, not an existing capability. Fable’s two-to-four-day estimate and “four claim readers” do not establish complete implementation scope.

**Required fix and final behavior:** Configure only explicitly trusted issuers/tenants and the LMS API audience. Validate app-only authorization using the chosen assigned app roles or explicit client ACL; map the verified issuer/tenant/client identifier to an enabled local API-client/LSP record. Do not trust a caller-supplied lspId. Keep the human HS256 path distinct with its own audience/key policy. Plan signing-key rollover/JWKS outage behavior and local-secret/refresh retirement. Centralize verified principal-to-LSP mapping after enumerating all controller/filter consumers.

**Acceptance tests:** Wrong issuer, audience, tenant, client mapping, delegated token, missing app permission and disabled LSP all fail closed. A permitted app works only for its mapped LSP. Test cached signing keys/rollover and human auth compatibility.

**How it should land:** Land identity schema/mapping and dual-validator tests, onboard one synthetic partner in staging, then retire local machine credentials through an explicit cutover. No external Entra registration changes are authorized by writing this report.

**What landed (2026-09-12):**

- Entra v2 app-only token validation implemented behind a flag and disabled by default: trusted tenant + issuer, `idtyp=app` with no delegated scope, a required app role, and explicit mapping to an enabled local `api_client`. Token-supplied `jku`/`x5u` never choose a network destination; JWKS comes from one configured URI behind a TTL cache. Local client credentials remain valid until partner cutover (`12dda5f9`).

## Medium issues

<a id="m01"></a>

### M01 — The schema reference is stale even though CI already checks it

**Status:** **Completed (2026-09-21)** — landed on main via PR #356 (squash `c367380d`). **Source comparison:** Both; Fable’s “add CI enforcement” is redundant.

**Files and code evidence:**

- [scripts/schema-diff/artifacts/reference-schema.normalized.sql:1](/Users/siddhant/Desktop/lms/scripts/schema-diff/artifacts/reference-schema.normalized.sql:1)
- [scripts/schema-diff/check-reference.sh:1](/Users/siddhant/Desktop/lms/scripts/schema-diff/check-reference.sh:1)
- [.github/workflows/backend-ci.yml:33](/Users/siddhant/Desktop/lms/.github/workflows/backend-ci.yml:33)
- [backend/src/main/resources/db/migration/V116\_\_create_loan_event_log.sql:1](/Users/siddhant/Desktop/lms/backend/src/main/resources/db/migration/V116__create_loan_event_log.sql:1)

**In simple terms / root cause:** The reference lacks current intent/borrower relationship/event structures and retains webhook tables removed by V119. CI already invokes check-reference.sh. Monthly event partitions are generated relative to the current date, so blindly regenerating the file can create date-dependent drift.

**Required fix and final behavior:** Regenerate against a fresh ephemeral Postgres database with the complete migration chain. Normalize runtime partition inventory separately from stable parent/index/policy/trigger structure, or make the reference generation date deterministic and documented. Preserve detection of real schema changes, especially RLS/grants. Update the reference, normalizer tests and CI path filters (include root pom.xml).

**Acceptance tests:** Two generations at different calendar months produce equivalent stable schemas; a deliberate missing table/policy/index still fails. A fresh migration validates JPA and the committed artifact.

**How it should land:** One schema-tooling PR; do not edit already-applied migrations or mutate production merely to match an obsolete reference.

**What landed (2026-09-21):**

- `normalize_schema.py` now elides the `loan_event` runtime partition inventory behind a marker, so month-relative partition generation no longer drifts the committed reference while stable parent/index/policy/trigger structure is still diffed.
- The reference was regenerated against a fresh ephemeral Postgres database running the complete Flyway migration chain (through `V135`), and CI's `check-reference.sh` path continues to validate the committed artifact.
- `test_normalize_schema.py` (8 tests) covers month-shift determinism — two generations in different calendar months produce equivalent stable schemas — and still detects a deliberately dropped object.

<a id="m02"></a>

### M02 — Borrower visibility can outlive failed onboarding, and race recovery is inconsistent

**Status:** Open — source-confirmed. **Source comparison:** Both; Fable’s guaranteed PAN-collision 500 is incorrect.

**Files and code evidence:**

- [backend/src/main/java/com/bhawana/lms/service/BorrowerOnboardingService.java:128](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/BorrowerOnboardingService.java:128)
- [backend/src/main/java/com/bhawana/lms/service/LoanApplicationOnboardingService.java:88](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/LoanApplicationOnboardingService.java:88)
- [backend/src/main/java/com/bhawana/lms/common/web/GlobalExceptionHandler.java:722](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/common/web/GlobalExceptionHandler.java:722)

**In simple terms / root cause:** Existing-borrower visibility commits in a separate admin transaction before the outer loan operation succeeds. It can survive rollback. The onboarding retry loop is bypassed when an outer idempotency transaction is active and catches all integrity failures otherwise. However, a normal PAN unique violation is translated to 409 BORROWER_PAN_CONFLICT, not inevitably 500.

**Required fix and final behavior:** Define whether a relationship is provisional or established; avoid granting durable partner disclosure for a failed loan command unless explicitly intended. Move the minimal visibility/relationship write into the successful atomic operation using a narrowly reviewed RLS-safe design, or gate all visibility on committed relationship state. Resolve PAN find-or-create using an atomic insert/conflict-aware lookup with correct tenant visibility; retry only the identified PAN race after rollback, not every integrity exception. An ON CONFLICT statement alone does not make an invisible cross-tenant row readable.

**Acceptance tests:** Concurrent same-PAN onboarding has one identity and the documented conflict/reuse result. Force outer validation/commit failure and verify no unintended LSP visibility. Non-PAN integrity errors are not retried blindly.

**How it should land:** Coordinate with C06’s profile ownership. Add a relationship-state migration only if required; inventory access grants lacking a successful relationship before cleanup.

<a id="m03"></a>

### M03 — Mobile-number identity checks lack a matching concurrency rule

**Status:** Open — source-confirmed. **Source comparison:** Fable; database uniqueness is a policy choice, not automatically the correct fix.

**Files and code evidence:**

- [backend/src/main/java/com/bhawana/lms/service/BorrowerOnboardingService.java:73](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/BorrowerOnboardingService.java:73)
- [backend/src/main/java/com/bhawana/lms/repo/BorrowerRepository.java:29](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/repo/BorrowerRepository.java:29)
- [backend/src/main/resources/db/migration/V43\_\_global_borrowers_with_lsp_access.sql:1](/Users/siddhant/Desktop/lms/backend/src/main/resources/db/migration/V43__global_borrowers_with_lsp_access.sql:1)

**In simple terms / root cause:** The code treats a mobile already associated with another PAN as a conflict, but two concurrent inserts with different PANs can pass that lookup. No equivalent mobile uniqueness invariant was found in the migrations. Shared family numbers or recycled numbers could make blanket uniqueness commercially wrong.

**Required fix and final behavior:** First decide whether normalized mobile is a globally unique verified identity, a shared contact, or time-bounded verified association. If unique, add a normalized-key constraint after duplicate review and handle its conflict explicitly. If contact-only, remove the false uniqueness assumption and require stronger identity evidence. Do not deduplicate borrowers solely by phone.

**Acceptance tests:** Concurrent same-mobile/different-PAN requests follow the chosen rule; normalization, recycled numbers and existing duplicates have explicit outcomes.

**How it should land:** Land policy and data-quality report before a uniqueness migration. Preserve PAN identity and use operator review for ambiguous existing records.

<a id="m04"></a>

### M04 — Object uploads and batch metadata have inconsistent failure semantics

**Status:** **Completed (2026-09-19)** — landed on main via PR #350 (squash `95835637`). **Source comparison:** Both.

**Files and code evidence:**

- [backend/src/main/java/com/bhawana/lms/service/LoanDocumentService.java:267](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/LoanDocumentService.java:267)
- [backend/src/main/java/com/bhawana/lms/service/LoanDocumentService.java:306](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/LoanDocumentService.java:306)
- [backend/src/main/java/com/bhawana/lms/service/LoanDocumentStorageService.java:11](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/LoanDocumentStorageService.java:11)

**In simple terms / root cause:** An object can be stored before metadata fails. Batch validation happens while processing items, so earlier storage writes may occur before detecting a later duplicate/invalid item. Outer idempotency transactions change metadata rollback behavior; they cannot roll back S3.

**Required fix and final behavior:** Validate the whole batch before any writes. Define atomic metadata versus partial-success behavior in the API. Use deterministic upload identity/keys where retries must converge and persist metadata plus a durable ownership/state record. Add a bounded orphan reconciler with a grace period and reference recheck before deletion. If using pending/promote storage, remember S3 has copy/delete semantics and promotion itself must be retryable; an afterCommit callback alone is not durable recovery.

**Acceptance tests:** Last-item validation failure causes no objects for a prevalidated batch. Simulate storage success/metadata failure and retry; no duplicate linked documents, and only truly unreferenced objects are eventually cleaned. Cover partial upload failures.

**How it should land:** Land validation first, then storage lifecycle/reconciler with a dry-run inventory. Never bulk-delete unrecognized historical objects.

**What landed (2026-09-19):**

- The batch is validated fully before any object write — a last-item failure leaves zero objects and zero metadata; metadata commits atomically per application.
- Object keys are content-addressed (`loan/{application}/{type}/{sha256}-{file}`), so storage-success/metadata-failure retries converge on the same key — no duplicate linked documents.
- `loan_document_object` rows (added by V135) record durable ownership/state: a `PENDING` ownership row commits `REQUIRES_NEW` **before** the object PUT, then the metadata transaction marks it `LINKED`.
- `DocumentObjectOrphanReconciler` is bounded and dry-run by default (`app.storage.documents.orphan-reconciler.dry-run=true`): only stale `PENDING` rows past a 24h grace are candidates, the delete claim is a single conditional UPDATE that re-checks no version/checklist references the key, objects delete outside any DB transaction, `DELETING` state survives a crash for resume, `relinkIfReferenced` repairs a wrongly-claimed key, and pre-V135 objects have no ownership row so they are never touched — matching the "never bulk-delete unrecognized historical objects" constraint.
- `DocumentEvidenceChainPostgresIntegrationTest` covers last-item failure (no objects), storage-success/metadata-failure retry convergence, partial uploads, referenced-object protection, and unrecognized-historical-object protection.

<a id="m05"></a>

### M05 — Storage clients are recreated per call and lack an explicit request-time budget

**Status:** **Completed (2026-09-21)** — landed on main via PR #359 (squash `3f4f442f`). **Source comparison:** Both.

**Files and code evidence:**

- [backend/src/main/java/com/bhawana/lms/service/R2ReportStorageService.java:77](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/R2ReportStorageService.java:77)
- [backend/src/main/java/com/bhawana/lms/service/R2LoanDocumentStorageService.java:146](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/R2LoanDocumentStorageService.java:146)
- [backend/src/main/java/com/bhawana/lms/service/ReportStorageService.java:6](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/ReportStorageService.java:6)

**In simple terms / root cause:** Both R2 implementations construct and close S3 clients per operation. This forfeits connection reuse; document streams keep their own client alive until closed. Explicit application-level total/attempt timeout policy is absent in those builders. A precise timeout or cost impact has not been measured.

**Required fix and final behavior:** Provide singleton configured clients per endpoint/credential set with managed shutdown, bounded connection pools and API call/attempt deadlines appropriate to uploads/downloads. Close response streams, not the shared client. Extend report storage to stream/file input and downloads where H25 needs bounded memory. Keep local/R2 implementations behind the existing ports; add Azure Blob only when selected for deployment.

**Acceptance tests:** Concurrent store/open/delete reuses clients, closing one stream does not break another, shutdown closes clients once, and slow/unavailable storage fails within the configured deadline without leaked streams.

**How it should land:** Small configuration/service refactor followed by H25 streaming. Do not merge clients with different endpoints or credentials into one accidental global configuration.

**What landed (2026-09-21):**

- A new `R2S3ClientFactory` builds one shared singleton `S3Client` per service with explicit `apiCallTimeout`/`apiCallAttemptTimeout` budgets and a bounded Apache connection pool; `@PreDestroy` closes the client exactly once at shutdown. Per-call client construction was removed from both R2 services.
- Report downloads now stream straight through `HttpServletResponse` outside any database transaction, so H25's bounded-memory path no longer pins a connection or an open transaction through object I/O. Callers close response streams, never the shared client.
- ADR-0012 records the design. Verification: `R2LoanDocumentStorageServiceTest` (8 tests, including a 240-operation concurrency run and FakeS3Server fault injection proving deadline-bound failure without leaked streams) and `R2ReportStorageServiceTest` (5 tests).

<a id="m06"></a>

### M06 — Alert rule configuration returned by the API is not the configuration evaluated

**Status:** Open — source-confirmed. **Source comparison:** Fable; corrects Codex’s overly positive alert-rule-table assessment.

**Files and code evidence:**

- [backend/src/main/java/com/bhawana/lms/domain/AlertRule.java:40](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/domain/AlertRule.java:40)
- [backend/src/main/java/com/bhawana/lms/service/AlertRuleEvaluationWorker.java:77](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/AlertRuleEvaluationWorker.java:77)
- [backend/src/main/java/com/bhawana/lms/config/AlertRuleDataInitializer.java:133](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/config/AlertRuleDataInitializer.java:133)
- [backend/src/main/java/com/bhawana/lms/web/OpsAlertController.java:242](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/web/OpsAlertController.java:242)

**In simple terms / root cause:** configJson is seeded and exposed, but the evaluator uses AlertRuleProperties thresholds. Operators can read a configuration representation that is not the source of execution behavior.

**Required fix and final behavior:** Choose YAML-backed immutable rules or validated database-managed configuration. For the smaller change, expose the effective typed YAML configuration/read-only status and stop presenting stale configJson as editable truth. If runtime editing is required, parse/version a schema per rule and have the evaluator use that snapshot. Keep enabled state and thresholds consistent.

**Acceptance tests:** Changing the supported configuration source changes evaluation at the expected boundary; unsupported/invalid fields fail clearly. API-displayed thresholds equal actual evaluated thresholds.

**How it should land:** Land contract/initializer/evaluator together. Backfill or retire old JSON only after choosing authority; do not silently begin honoring historical unvalidated JSON.

<a id="m07"></a>

### M07 — Delinquency evaluation is one unbounded writing transaction

**Status:** **Completed (2026-09-19)** — landed on main via PRs #351 (squash `843917e3`) and #353 (squash `6a7d8667`, feed-lag gauge refresh). **Source comparison:** Both; Fable supplies the specific evaluator scope.

**Files and code evidence:**

- [backend/src/main/java/com/bhawana/lms/service/AlertRuleEvaluationWorker.java:123](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/AlertRuleEvaluationWorker.java:123)
- [backend/src/main/java/com/bhawana/lms/repo/AlertRuleSetQueryRepository.java:72](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/repo/AlertRuleSetQueryRepository.java:72)
- [backend/src/main/java/com/bhawana/lms/repo/LoanEventRepository.java:132](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/repo/LoanEventRepository.java:132)

**In simple terms / root cause:** All scheduled rules run under one method-level transaction; the servicing query has no limit. At a large active portfolio this retains a connection and old transaction boundary, delaying partner feed visibility. Exact month-six/14M-row failure thresholds in Fable are estimates, not measurements.

**Required fix and final behavior:** Separate rules and process bounded stable-ID/LSP batches in short transactions. Commit delinquency state and its event together per batch; keep an explicit evaluation date/cutoff across the run. Resume safely after interruption. Add query/transaction time budgets and feed-lag metrics. Use H12’s lease alternative when a transaction advisory lock would otherwise recreate one long transaction.

**Acceptance tests:** Large fixture runs with bounded transaction age; crash/restart completes remaining work with no missing or duplicate bucket transitions. One bad rule does not roll back unrelated completed rules.

**How it should land:** Land after H10 correctness. Explain first-run/replay semantics before backfilling; tune batch size from measured database plans.

**What landed (2026-09-19):**

- `AlertRuleEvaluationWorker` is no longer one method-level transaction. Each rule runs inside `evaluateRuleIsolated` — its own `REQUIRES_NEW` transaction with a per-transaction `SET LOCAL statement_timeout` (60s default, `APP_ALERT_RULES_EVALUATION_STATEMENT_TIMEOUT_MS`) — and `markRuleEvaluated` commits in the same transaction, so a failed rule is never recorded as evaluated and one bad rule cannot roll back unrelated completed rules.
- The DPD sweep is keyset-paginated: `findServicingDelinquencyRows` takes `applicationId > :afterId` + `LIMIT evaluationBatchLimit`; each page computes buckets, writes `loan_delinquency_state` and appends `LOAN_DELINQUENCY_BUCKET_CHANGED` in one short transaction (state and event commit together). The `evaluatedAt`/business-date cutoff is fixed once per run; `last_evaluated_at` advances only when the whole population completed.
- H12's lease alternative landed: migration `V132` creates `worker_lease` and `WorkerLeaseRepository` performs an atomic `INSERT … ON CONFLICT … WHERE expires_at < now()` claim returning a fencing sequence; the run renews the fence between rules/pages and releases at the end — a displaced owner stops before opening further transactions. `AlertRuleSchedulerWorker` no longer holds an xact advisory lock.
- Metrics: `lms.alert.evaluation.rule.duration` / `lms.alert.evaluation.page.duration` timers, and `lms.eventfeed.oldest_open_transaction_age_seconds` refreshed every run (the refresh block itself was lost to a stale editor buffer before the #351 commit and restored by PR #353).
- Resume semantics: per-application writes are idempotent — an aborted run re-evaluates from the top and committed pages already show the new bucket, producing no duplicate transitions.
- Verification: `AlertRuleEvaluationBatchingIntegrationTest` — batch-limit=2 over 5 delinquent loans, a forced mid-run failure leaves page 1 committed and the rerun finishes all five with exactly one transition event each; a failed STALE_INTAKE rule leaves the DPD rule fully evaluated.

<a id="m08"></a>

### M08 — Alert deduplication is a check-then-insert race

**Status:** **Completed (2026-09-19)** — landed on main via PR #351 (squash `843917e3`). **Source comparison:** Fable.

**Files and code evidence:**

- [backend/src/main/java/com/bhawana/lms/service/OpsAlertService.java:39](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/OpsAlertService.java:39)
- [backend/src/main/java/com/bhawana/lms/repo/OpsAlertRepository.java:19](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/repo/OpsAlertRepository.java:19)
- [backend/src/main/java/com/bhawana/lms/domain/OpsAlert.java:18](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/domain/OpsAlert.java:18)

**In simple terms / root cause:** Concurrent emitters can both pass the NEW-alert existence check and insert duplicates. Null-subject alerts use type+correlation ID instead. A single partial index on type/subject_id, as Fable suggests, does not cover that second branch and may conflate distinct subject types.

**Required fix and final behavior:** Define a canonical deduplication key including type and subject type/ID, or a stable correlation key for subjectless conditions. Enforce uniqueness only for the active state and handle insert conflicts as an existing alert. Keep repeated occurrences/acknowledgement behavior explicit; alerts that are intentionally separate must have separate keys.

**Acceptance tests:** Two transactions emit the same condition and create one active alert; distinct tenants/subjects are not merged. Cover null subject, acknowledged alert followed by recurrence and a genuinely new condition.

**How it should land:** Inventory/deduplicate existing active rows with preserved audit links before validating a new unique index. Do not discard distinct historical alerts.

**What landed (2026-09-19):**

- Canonical keys enforced at the database. Migration `V133` deduplicates existing active rows first — later duplicates are *acknowledged* with a note linking to the retained alert (history preserved, nothing deleted) — then adds two partial unique indexes scoped to `status = 'NEW'`: `(type, subject_type, subject_id) NULLS NOT DISTINCT` for subject alerts and `(type, correlation_id)` for subjectless ones. `subject_type` is part of the key so the same id under different subject types never conflates.
- Migration `V134` adds `ops_alert.dedupe_protected` and narrows both index predicates to protected rows — this was a CI-caught regression: the always-create emitters (`emitLspBoundViolation` with `alwaysCreate`, `MANUAL_RULE_ENGINE_OVERRIDE`, `OPS_USER_ESCALATION`, `BORROWER_IDENTITY_CONFLICT`, `BORROWER_ACTIVE_LOAN_DUPLICATE`, `LSP_DISABLED`) intentionally file one alert per occurrence and must never hit the fence. `createAlertIfAbsent` marks rows protected; `createAlert` leaves them unprotected. Existing NEW rows were backfilled protected.
- `OpsAlertService.createAlertIfAbsent` keeps the existence check as a fast path; a lost race surfaces as a unique violation — at insert flush *or* at commit (`TransactionSystemException` wrapping SQLSTATE 23505) — and is treated as "alert already exists" (returns null). The insert runs in an inner `REQUIRES_NEW` transaction via `adminScopedTransactionExecutor`.
- Recurrence: acknowledging an alert frees the dedupe key, so a repeat files a fresh row; acknowledgement behavior is unchanged.
- Verification: `OpsAlertDedupeIntegrationTest` — a forced overlap (contender holds an uncommitted row; the service insert blocks on speculative insertion, loses the constraint race, returns null) leaves exactly one active alert; null-subject correlation dedupe; ack→recurrence; distinct subjects/subject types not merged; two always-create occurrences for the same subject both file.

<a id="m09"></a>

### M09 — Business dates and displayed time zones disagree at day boundaries

**Status:** **Completed (2026-09-21)** — landed on main via PR #356 (squash `c367380d`). **Source comparison:** Both.

**Files and code evidence:**

- [backend/src/main/java/com/bhawana/lms/service/LoanRepaymentScheduleService.java:497](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/LoanRepaymentScheduleService.java:497)
- [backend/src/main/java/com/bhawana/lms/config/BusinessCalendar.java:8](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/config/BusinessCalendar.java:8)
- [frontend/src/components/app/secrets/ApiClientSecretMeta.tsx:21](/Users/siddhant/Desktop/lms/frontend/src/components/app/secrets/ApiClientSecretMeta.tsx:21)
- [frontend/src/lib/format.ts:66](/Users/siddhant/Desktop/lms/frontend/src/lib/format.ts:66)
- [backend/src/main/java/com/bhawana/lms/service/LoanApplicationQueryService.java:126](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/LoanApplicationQueryService.java:126)

**In simple terms / root cause:** Schedule approval-date anchoring converts through UTC while business DPD uses Asia/Kolkata. Frontend date formatting generally follows the browser, but one credential card labels local time as IST. A 02:00 IST approval can anchor to the prior UTC date. The loan-list disbursal-date filter also derives UTC day boundaries, so it should be reconciled with the same business-date contract.

**Required fix and final behavior:** Derive contractual dates through BusinessCalendar/Clock. Treat date-only fields as dates, not instants implicitly shifted by a browser timezone. Format actual instants with an explicit chosen timezone and label it accurately; use Asia/Kolkata where the product requires it. Apply exact two-decimal display on actionable financial detail/settlement screens while keeping intentionally rounded overview cards.

**Acceptance tests:** Tests around 00:00–05:30 IST, UTC boundary, leap day and browser zones outside India. Paise values remain visible in payment confirmation; unavailable amounts are not zero.

**How it should land:** Land conversion and UI labels with fixtures. Review affected historical schedules separately; do not regenerate due dates after funding. This is not a mandate to replace all JS numbers with a decimal library.

**What landed (2026-09-21):**

- `BusinessCalendar` gained `businessDate`, `startOfBusinessDay` and `endOfBusinessDayExclusive` (Asia/Kolkata); schedule anchoring, the loan-list disbursal-date filter, `AdminReportingService`, `LspDirectoryService` and seed dates now all derive through the same business-date contract.
- Frontend instants render in IST with accurate labels instead of following the browser zone or mislabeling local time.
- Production fix found in review: Bean Validation `@PastOrPresent`/`@Future` on `LocalDate` was evaluating in the JVM zone, rejecting valid business-zone requests between 18:30–24:00 UTC. `BusinessZoneClockProvider` plus `META-INF/validation.xml` now evaluate those constraints on the business clock, and 15 test fixtures were made business-zone-consistent.

<a id="m10"></a>

### M10 — Redis rate limiting ignores authentication/TLS settings and has no explicit outage policy

**Status:** **Completed (2026-09-21)** — landed on main via PR #359 (squash `3f4f442f`). **Source comparison:** Both; Fable’s exact 60-second timing is not reproduced.

**Files and code evidence:**

- [backend/src/main/java/com/bhawana/lms/security/RateLimitConfig.java:29](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/security/RateLimitConfig.java:29)
- [backend/src/main/java/com/bhawana/lms/security/RateLimitFilter.java:30](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/security/RateLimitFilter.java:30)
- [backend/src/main/resources/application-staging.yml:1](/Users/siddhant/Desktop/lms/backend/src/main/resources/application-staging.yml:1)

**In simple terms / root cause:** The custom Redis client is built from host/port only. Password/TLS/timeout options in ordinary Spring Redis configuration are not carried into this client. There is no explicit per-route response policy for an unavailable rate-limit store; defaults may stall requests or cause startup failure. The exact failure timing depends on the resolved client settings and environment.

**Required fix and final behavior:** Build from a validated Redis configuration including credentials/TLS, connect/command timeout and pooling as supported. Define fail-closed behavior for credential-attack-sensitive endpoints with bounded 503/Retry-After, and consciously choose behavior for other routes. Do not silently fail open everywhere. Keep this availability control separate from financial idempotency/locking.

**Acceptance tests:** Authenticated TLS Redis succeeds; wrong credentials, stalled commands and outage at startup/runtime fail within an agreed measured budget. Login response and metrics follow the chosen policy; recovery does not require restarting every instance.

**How it should land:** Land configuration and outage tests before Azure deployment. Select a justified deadline from latency measurements; 200 ms is a proposal, not a proven universal setting.

**What landed (2026-09-21):**

- The rate-limit client is built from `LettuceConnectionFactory.getNativeClient()`, so every `spring.data.redis.*` setting — password, TLS, connect/command timeouts — now applies to the limiter instead of a host/port-only construction.
- A lazy reconnect provider re-establishes the store with a 5-second cooldown, so recovery after an outage does not require restarting every instance.
- The outage policy is explicit per rule: auth login/token/refresh/password routes fail closed with a bounded 503 + `Retry-After`, other routes fail open, and every store failure is metered on `lms.rate_limit.store.failures`. ADR-0013 records the design.
- Verification: `RateLimitFilterOutagePolicyTest` (5 tests) and `RateLimitRedisOutageIntegrationTest` (3 tests — Testcontainers TLS + AUTH + outage + recovery with runtime-generated certificates).

<a id="m11"></a>

### M11 — Human login lockout depends on a delayed scheduled evaluator

**Status:** **Completed (2026-09-12)** — landed on main via commit `12dda5f9`. **Source comparison:** Fable.

**Files and code evidence:**

- [backend/src/main/java/com/bhawana/lms/service/AuthAuthenticationService.java:69](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/AuthAuthenticationService.java:69)
- [backend/src/main/java/com/bhawana/lms/service/AlertRuleEvaluationWorker.java:130](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/AlertRuleEvaluationWorker.java:130)
- [backend/src/main/resources/application-staging.yml:1](/Users/siddhant/Desktop/lms/backend/src/main/resources/application-staging.yml:1)

**In simple terms / root cause:** Human login checks persisted lock state, but failure grouping/locking is evaluated by a scheduled rule that may be disabled or delayed. The synchronous Redis IP bucket does not replace an account-level attempt policy, especially with distributed IPs.

**Required fix and final behavior:** Add a bounded per-normalized-username failure window/counter evaluated in the authentication path, with an explicit reset/unlock policy and independent audit. Reuse the API-client lockout design where appropriate without sharing credentials or role logic. Avoid revealing whether an account exists; monitor lockout abuse and align IP attribution with H03.

**Acceptance tests:** Threshold is enforced immediately across two instances and survives transaction failure. Unknown usernames have equivalent public errors. Verify reset, administrator unlock and Redis/database outage policy.

**How it should land:** Introduce counter/state migration only if needed; retain scheduler as alerting/reconciliation rather than the primary enforcement mechanism.

**What landed (2026-09-12):**

- Immediate per-user lockout on a failure window persisted in a committed `REQUIRES_NEW` transaction, so the counter survives the failed login's own rollback (`12dda5f9`; migration `V128`). The scheduled evaluator remains as alerting/reconciliation, not primary enforcement.

<a id="m12"></a>

### M12 — Missing-user JWT fallback and bootstrap password resynchronization weaken revocation

**Status:** **Completed (2026-09-12)** — landed on main via commit `12dda5f9`. **Source comparison:** Both.

**Files and code evidence:**

- [backend/src/main/java/com/bhawana/lms/security/ManagedUserJwtPrincipalResolver.java:50](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/security/ManagedUserJwtPrincipalResolver.java:50)
- [backend/src/main/java/com/bhawana/lms/service/LocalBootstrapAdminSyncService.java:30](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/LocalBootstrapAdminSyncService.java:30)
- [backend/src/main/java/com/bhawana/lms/security/JwtSecurityBeans.java:33](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/security/JwtSecurityBeans.java:33)

**In simple terms / root cause:** A signed human JWT whose subject no longer resolves can fall through successful session validation. This does not forge a signature, but can preserve old claims until token expiry after rename/deletion. Calling such a token permanently “unrevocable” overstates it. Bootstrap synchronization runs beyond a local-only profile and can reapply configured credentials on startup.

**Required fix and final behavior:** Require managed-human tokens to resolve an active managed principal; make any bootstrap principal explicitly typed, narrowly enabled and expiring. Keep API-client validation on its own branch. Bootstrap creates an initial admin only when absent, or requires an explicit audited reset mode; routine restarts must not reset an operator-changed password. Remove personal/default identity values from production defaults.

**Acceptance tests:** Rename/delete/disable the managed user and validate a real previously minted JWT. Unsupported subject fails closed. Restart after password change preserves the new hash. Explicit local bootstrap still works and cannot activate implicitly in production.

**How it should land:** Plan recovery access before removing fallback. Existing sessions for renamed principals should reauthenticate; do not preserve them by silently reassigning identity.

**What landed (2026-09-12):**

- The missing-user JWT fallback is removed from the bootstrap and admin paths — a token for a deleted/unknown user no longer silently re-primes an identity, and bootstrap no longer resynchronizes passwords on restart (`12dda5f9`).

<a id="m13"></a>

### M13 — Partner OpenAPI documents omit essential integration behavior

**Status:** **Completed (2026-09-20)** — landed on main via PR #354 (squash `52818098`). **Source comparison:** Fable; exact counts corrected.

**Files and code evidence:**

- [openapi/openapi.json:1](/Users/siddhant/Desktop/lms/openapi/openapi.json:1)
- [backend/src/main/java/com/bhawana/lms/config/OpenApiConfiguration.java:20](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/config/OpenApiConfiguration.java:20)
- [backend/src/test/java/com/bhawana/lms/openapi/OpenApiContractExportTest.java:36](/Users/siddhant/Desktop/lms/backend/src/test/java/com/bhawana/lms/openapi/OpenApiContractExportTest.java:36)
- [backend/src/main/java/com/bhawana/lms/web/LspLoanApplicationApiController.java:60](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/web/LspLoanApplicationApiController.java:60)

**In simple terms / root cause:** The checked-in document contains 107 operations, zero operation summaries/descriptions, no ApiError schema and no global security requirement. Two operations do have security and responses include 204 as well as 200, so Fable’s absolute “only 200” description is inaccurate. A security scheme definition alone does not document which routes require it.

**Required fix and final behavior:** Publish a partner-only group containing supported LSP/auth routes. Declare security per operation/group, leaving login/token public as appropriate; document ApiError, status codes, idempotency/retry/pagination headers, cursor expiry and examples. Generate frontend types from the same canonical export and add a CI drift check. Do not blindly attach every error code to every operation; preserve meaningful route-specific errors.

**Acceptance tests:** Contract tests assert security, standard error schema and representative success/conflict/validation examples. A backend DTO change fails drift checking until the contract/client update lands. Partner group excludes internal admin operations.

**How it should land:** Land documentation annotations/customizers, export and tests together. Keep HTTP compatibility; do not turn a documentation pass into wholesale endpoint renaming.

**What landed (2026-09-20):**

- Partner-only document at `/v3/api-docs/partner` (springdoc `GroupedOpenApi` in `OpenApiConfiguration`, `pathsToMatch("/api/v1/lsp/**", "/api/v1/auth/**")` — excludes `/api/v1/internal/**`; inherits the authenticated `/v3/api-docs/**` rule, 401 unauthenticated verified).
- Single `GlobalOpenApiCustomizer` (`config/OpenApiContractCustomizer.java`) enriches both the default and partner documents: `ApiError`/`FieldViolation`/`ErrorDetail` schemas resolved from the real records via `ModelConverters` (drifts with the code); per-operation `bearerAuth` with the four `permitAll` auth routes documented public; `Idempotency-Key` required vs optional per endpoint verified against the controllers; `Retry-After` on 429 and 409 `IDEMPOTENCY_IN_PROGRESS`; `X-Limit`/`X-Offset`/`X-Total-Count` + `offset`/`limit`/`paginationDetails`/`cursor`/`eventTypes` params on paged/cursor routes; 410 `CURSOR_EXPIRED` on the event feed; route-specific 4xx only where the code actually emits them (`INVALID_STATUS`, `REPAYMENT_SCHEDULE_INVALID`, `FORECLOSURE_QUOTE_STALE`, …) — no blanket error attachment; curated summaries on all 111 operations.
- Drift check: `OpenApiContractExportTest.checkedInSnapshotMatchesGeneratedContract` regenerates the document in-test and tree-compares against checked-in `openapi/openapi.json` — runs in the normal backend gate, no workflow change; proven by failing on the stale snapshot until regenerated.
- `openapi/openapi.json` regenerated (92 paths/111 ops); `frontend/src/lib/api/generated/schema.ts` regenerated via `npm run generate:api-types` (never hand-edited; `tsc -b` clean).
- Landed on PR #354 via `3aac091b`; new `PartnerOpenApiContractTest` (12 tests) asserts security declarations, ApiError schema, representative examples, and internal-op exclusion.

<a id="m14"></a>

### M14 — API retry and pagination contracts are inconsistent

**Status:** **Completed (2026-09-20)** — landed on main via PR #354 (squash `52818098`). **Source comparison:** Both.

**Files and code evidence:**

- [backend/src/main/java/com/bhawana/lms/web/LspLoanApiController.java:98](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/web/LspLoanApiController.java:98)
- [backend/src/main/java/com/bhawana/lms/repo/LoanPaymentTransactionRepository.java:16](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/repo/LoanPaymentTransactionRepository.java:16)
- [backend/src/main/java/com/bhawana/lms/web/LspLoanApplicationApiController.java:103](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/web/LspLoanApplicationApiController.java:103)
- [backend/src/main/java/com/bhawana/lms/web/ApiClientAdminController.java:31](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/web/ApiClientAdminController.java:31)
- [backend/src/main/java/com/bhawana/lms/service/LoanApplicationQueryService.java:115](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/LoanApplicationQueryService.java:115)

**In simple terms / root cause:** Payment history stops at 50 rows without a paging contract. Mutating partner operations differ in idempotency requirements, and quote creation can create/supersede multiple quotes without its own unique request identity. Unknown status handling and pagination envelopes vary. Returning 200 for a create or using PUT for a partial update is a convention issue, not automatically data loss.

**Required fix and final behavior:** Add explicit bounded pagination/total or cursor to payment history and update consumers. Inventory every mutating partner endpoint: require a key or document a natural idempotent identity; give quote creation a replayable request identity under the loan lock. Validate unknown status values before claiming a key. Preserve legacy successful response shapes through an additive/versioned rollout. Share DTO leaf records only where they are the same public contract, not merely coincidentally equal today.

**Acceptance tests:** Read more than 50 receipts without loss; repeated quote creation with one key creates one logical quote. Unknown status returns the documented validation error; changed payload conflicts. Verify existing integrations through compatibility tests.

**How it should land:** Split into payment paging, quote idempotency and contract-validation PRs. Defer cosmetic verb/envelope changes unless versioned and justified. Admin code reusing a tenant-scoped coordinator is not by itself an authorization leak.

**What landed (2026-09-20):**

- `GET /api/v1/lsp/loans/{loanId}/payments` is a bounded page through the existing `PaginationResponseBuilder`: `offset`/`limit` (cap 200)/`paginationDetails` params, `X-Limit`/`X-Offset` always emitted, `X-Total-Count` on `paginationDetails=ON`; body stays a raw array so a param-less request returns the same first-50 rows — the cap is now disclosed and every row is reachable. `LoanPaymentTransactionRepository` gained `findByLoanAccount_Id(Pageable)` with a stable `paymentDate,createdAt,id` sort; the legacy `Top50` remains for the internal ops read path (flagged as a follow-up).
- `POST /api/v1/lsp/loans/{loanId}/foreclosure-quote` accepts optional `Idempotency-Key` routed through `LspApiIdempotencyService.execute` as `FORECLOSURE_QUOTE_REQUEST`, fingerprint `(loanAccountId, effectiveDate)`; keyed replays return one logical quote, a changed payload conflicts `IDEMPOTENCY_CONFLICT`, unkeyed requests keep legacy supersede semantics (backward compatible with `lsp-api-client.py`).
- Unknown `status` filter values now throw `BusinessRuleViolationException("INVALID_STATUS")` → 422 on both the LSP list and the ops list (shared `LoanApplicationQueryService` helper), consistent with the other `INVALID_*` filter errors; blank/absent still means unfiltered.
- Mutation idempotency inventory documented in `docs/API-references/api-spec.md` (key-guarded vs natural-identity endpoints); `ApiClientAdminController` audited — no scope defect, unchanged.
- Frontend: `fetchMyLoanPayments`/`fetchMyLoanPaymentsPage` page through receipts (200/page, bounded), `LoanServicingPanel` renders a truncation disclosure.
- Landed on PR #354 via `8bd5d348`; HTTP-boundary tests prove 53 receipts paged losslessly with `X-Total-Count=53`, keyed quote replay → one row + identical stored response, changed payload → 409, malformed key → 400, unknown status → 422 on both surfaces.
- Follow-up (PR #355, squash `9b2123ce`): the internal ops payments list received the same pagination contract (`LoanApplicationServicingReadService.listPaymentTransactionsPage`, ops UI walks bounded pages with a `truncated` flag, dead `Top50` repo method removed), and entity timestamps repo-wide now go through `common/util/PersistedTimestamp` so live-rendered values always match `timestamptz`-stored replays.

<a id="m15"></a>

### M15 — Cross-origin deployment lacks configurable origins and exposes an incomplete retry contract

**Status:** **Completed (2026-09-20)** — landed on main via PR #354 (squash `52818098`). **Source comparison:** Both.

**Files and code evidence:**

- [backend/src/main/java/com/bhawana/lms/security/SecurityFilterChainConfig.java:158](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/security/SecurityFilterChainConfig.java:158)
- [backend/src/main/java/com/bhawana/lms/security/SecurityFilterChainConfig.java:166](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/security/SecurityFilterChainConfig.java:166)
- [frontend/src/lib/api/http-client.ts:48](/Users/siddhant/Desktop/lms/frontend/src/lib/api/http-client.ts:48)
- [frontend/vite.config.ts:1](/Users/siddhant/Desktop/lms/frontend/vite.config.ts:1)

**In simple terms / root cause:** CORS origins are hardcoded localhost values and Retry-After is not exposed. This can break a real cross-origin frontend. Backend CSP already exists; it does not establish the CSP of separately hosted SPA HTML. Vite’s default root base is correct for root hosting and only a gap if a subpath deployment is chosen.

**Required fix and final behavior:** Bind an explicit allowlist from deployment config, preserve credential restrictions, and expose Retry-After plus the existing pagination/correlation headers. Prefer a same-site deployment compatible with Strict refresh cookies. Configure and test a SPA-specific CSP at the static host/ingress; do not copy the backend default-src none policy onto the UI. Set Vite base only for an actual subpath target.

**Acceptance tests:** Allowed origin works with refresh/pagination/retry headers; unapproved origins fail. Test the deployed HTML CSP, asset navigation and cookie behavior on the chosen topology.

**How it should land:** Land config/runbook and staging smoke tests. Do not use wildcard credentialed CORS or loosen SameSite merely to hide an unexplained deployment failure.

**What landed (2026-09-20):**

- CORS origins bind from `app.security.cors.allowed-origins` (env `APP_SECURITY_CORS_ALLOWED_ORIGINS`, comma-separated) via a new `SecurityProperties.Cors` nested properties class following the `EdgeProperties` convention — blank entries dropped so an unset placeholder binds empty. **Empty default fails closed**: no cross-origin browser call is allowed until an SPA origin is configured; the four localhost dev origins moved to `application-local.yml`/`application-local.yml.example` under the `local` profile only.
- Wildcard origins are rejected at bean construction since `allowCredentials=true`; `Retry-After` added to `exposedHeaders` beside `X-Correlation-Id` (via `CorrelationIdFilter.HEADER_NAME`), `Content-Disposition`, and the pagination headers — the 429/409 retry contract is now readable cross-origin.
- New `docs/spa-deployment-contract.md`: SPA CSP belongs at the static host/ingress (never the backend's `default-src 'none'`), same-site topology keeps `SameSite=Strict` refresh cookies working, Vite `base` only for a real subpath deployment (`vite.config.ts` unchanged).
- Landed on PR #354 via `cc3ffa67`; 8 CORS tests (preflight allow/deny, credentialed exposed-headers assertion, fail-closed empty allowlist, wildcard startup rejection) + 4 profile-matrix binding tests in `SpringConfigProfileMatrixTest`.

<a id="m16"></a>

### M16 — Sensitive data is deliberately replicated without a finalized disclosure/retention boundary

**Status:** Open — source-confirmed. **Source comparison:** Both; Fable’s blanket masking prescription conflicts with the current event contract.

**Files and code evidence:**

- [backend/src/main/java/com/bhawana/lms/service/BorrowerBankDetailsService.java:322](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/BorrowerBankDetailsService.java:322)
- [backend/src/main/java/com/bhawana/lms/service/LoanEventPayloads.java:54](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/LoanEventPayloads.java:54)
- [docs/adr/0007-partner-lifecycle-updates-are-pull-based.md:1](/Users/siddhant/Desktop/lms/docs/adr/0007-partner-lifecycle-updates-are-pull-based.md:1)
- [backend/src/main/java/com/bhawana/lms/service/BorrowerOnboardingService.java:269](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/BorrowerOnboardingService.java:269)

**In simple terms / root cause:** Bank-change events carry full previous/current account numbers and IFSC by an explicit code comment; intake/audit/report data also carries PII. The status payload includes invalidatedByUsername, rather than an actor username on every status change. Full-data events may be an intentional partner contract, so blindly masking all fields can break integration. That does not remove the need to justify each disclosure and retention period.

**Required fix and final behavior:** Create a field-level matrix of purpose, authorized reader, source, retention and masking rules. Keep internal operator identities out of partner payloads unless explicitly required; prefer stable public attribution where needed. Minimize unnecessary copies and version event payload changes so partner consumers can adapt. Enforce retention on appropriate transient/derived stores while preserving required financial/audit evidence. Review report/download authorization and storage encryption/access controls.

**Acceptance tests:** Partner A cannot read B’s fields; disallowed internal identity fields are absent. Approved required bank fields remain correct where the contract requires them. Test retention boundaries and historical event compatibility.

**How it should land:** Product/security decision followed by payload/schema changes and partner contract rollout. No blanket rewrite/deletion of append-only history; document lawful retained evidence instead of assuming UI masking secures stored data.

<a id="m17"></a>

### M17 — Local verification gates fail and full lending journeys are not exercised in CI

**Status:** **In progress** — CI gates revived and the lending-journey soak is green; full journey coverage remains open. **Source comparison:** Both; remote CI history and exact pollution counts are not independently established here.

**Files and code evidence:**

- [.github/workflows/backend-ci.yml:6](/Users/siddhant/Desktop/lms/.github/workflows/backend-ci.yml:6)
- [.github/workflows/frontend-ci.yml:49](/Users/siddhant/Desktop/lms/.github/workflows/frontend-ci.yml:49)
- [backend/src/test/java/com/bhawana/lms/architecture/LspTenantElevationArchitectureTest.java:32](/Users/siddhant/Desktop/lms/backend/src/test/java/com/bhawana/lms/architecture/LspTenantElevationArchitectureTest.java:32)
- [backend/src/main/java/com/bhawana/lms/service/OpsAlertEmitters.java:12](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/OpsAlertEmitters.java:12)
- [frontend/src/features/alerts/components/AlertRulesPanel.tsx:1](/Users/siddhant/Desktop/lms/frontend/src/features/alerts/components/AlertRulesPanel.tsx:1)

**In simple terms / root cause:** The retained backend run reports 818 tests, one architecture failure and two skips; the failed rule concerns OpsAlertEmitters’ narrow admin configuration read. The latest frontend run has 1,065 passing tests but formatting fails on AlertRulesPanel. CI browser execution is login smoke only. Root pom.xml changes do not trigger backend CI. These facts do not prove an exploitable tenant elevation or Fable’s “last four remote runs”/test-pollution count.

**Required fix and final behavior:** Resolve the architecture rule by documenting/testing the exact justified admin read or moving it behind an approved boundary; never broadly exempt service packages. Format the identified file, add root parent-build paths, and keep schema drift enforcement. Add a backend-connected synthetic lending journey to CI with deterministic fixtures; fail clearly when required services/fixtures are absent instead of skipping. Prioritize the concurrency/crash tests attached to the Critical/High tickets over an arbitrary higher coverage percentage.

**Acceptance tests:** Full backend/frontend gates pass; intentional unauthorized elevation still fails ArchUnit. CI runs from a clean checkout and exercises intake→documents→approval→mock disbursement→repayment with real HTTP/database boundaries. Run affected integration classes individually and shuffled before asserting test pollution is fixed.

**How it should land:** Small gate repairs first, then E2E fixture/stack setup. Preserve PostgreSQL integration tests and the destructive-test target guard. This report does not claim a new visual/accessibility audit or a fresh full backend rerun.

**Progress note (2026-09-18):** CI signal health substantially improved — startup runners are explicitly ordered (`@Order` roles→bootstrap→catalog, `StartupRunnerOrderingTest`), schema-drift Postgres readiness uses consecutive probes, H22 cookie-transport spec is event-driven, and the backend-connected lending journey soak now passes (PRs #345–#347). The ticket's full ask — exercising all documented lending journeys in CI — remains open.

<a id="m18"></a>

### M18 — Important database invariants are enforced only in selected service paths

**Status:** Open — source-confirmed. **Source comparison:** Codex; Fable’s broad “unique constraints back every lookup” claim needs limits.

**Files and code evidence:**

- [backend/src/main/resources/db/migration/V65\_\_check_constraints_data_integrity.sql:1](/Users/siddhant/Desktop/lms/backend/src/main/resources/db/migration/V65__check_constraints_data_integrity.sql:1)
- [backend/src/main/java/com/bhawana/lms/domain/LoanPaymentTransaction.java:21](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/domain/LoanPaymentTransaction.java:21)
- [backend/src/main/java/com/bhawana/lms/domain/LoanRepaymentScheduleInstallment.java:22](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/domain/LoanRepaymentScheduleInstallment.java:22)
- [backend/src/main/java/com/bhawana/lms/domain/LoanForeclosureQuote.java:21](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/domain/LoanForeclosureQuote.java:21)

**In simple terms / root cause:** Existing checks cover nonnegative amounts and payment/paid-total arithmetic, but not every component/ownership identity. Payment-to-installment account agreement and account/application/product-version agreement are cross-row invariants. Simply adding @Version to five entities does not establish these relationships.

**Required fix and final behavior:** Inventory each invariant and add database enforcement where it is universal: installment principal+interest=amount, opening-principal−principal-due=closing-principal, paid component bounds, and quote component totals after the pricing policy is fixed. Validate ownership with compatible composite keys/FKs or a narrowly justified trigger where necessary; keep command locks for state transitions. Add entity versions only to mutable records with a concrete concurrent-write scenario, accounting for native SQL writers.

**Acceptance tests:** Direct SQL attempts at each newly prohibited state fail; legitimate rounding and legacy schedules pass. Cross-account payment targets are rejected at API and database boundaries. Verify migration on a copy/synthetic fixture with intentionally invalid rows.

**How it should land:** Add constraints NOT VALID where supported, inventory/repair exceptions with evidence, then validate. Do not disable existing checks or rewrite financial data to force migration success.

<a id="m19"></a>

### M19 — Frontend role selection and navigation do not represent all authorized roles

**Status:** Open — source-confirmed. **Source comparison:** Fable.

**Files and code evidence:**

- [frontend/src/features/auth/auth-service.ts:60](/Users/siddhant/Desktop/lms/frontend/src/features/auth/auth-service.ts:60)
- [frontend/src/components/app/shell/nav-items.ts:38](/Users/siddhant/Desktop/lms/frontend/src/components/app/shell/nav-items.ts:38)
- [frontend/src/routes/router.tsx:76](/Users/siddhant/Desktop/lms/frontend/src/routes/router.tsx:76)

**In simple terms / root cause:** Session construction collapses a role set to one primary role; OPS_USER+PRODUCT_ADMIN can lose product navigation. Home is allowed by a broad route guard but shown only to admins in navigation. These are UI authorization/contract inconsistencies; backend authorization still controls data access.

**Required fix and final behavior:** Represent the actual role set or derived capabilities in the session and share capability checks across nav, route guards and actions. Keep one deliberate landing priority without using that priority as the full authorization set. Decide Home’s intended audience and align route/nav/backend. Do not infer extra permissions from unknown roles or localStorage.

**Acceptance tests:** Enumerate supported role combinations, including OPS+PRODUCT, LSP roles and no recognized roles. Available links/actions agree with route guards and backend permissions; unauthorized data remains inaccessible.

**How it should land:** Contained frontend session/schema change with compatibility for stored identity metadata. Coordinate query-client scope if effective authorization can change without the displayed primary role changing.

<a id="m20"></a>

### M20 — Other administrative lists still download and filter entire collections

**Status:** Open — source-confirmed. **Source comparison:** Fable; distinct from the confirmed alerts truncation in H30.

**Files and code evidence:**

- [frontend/src/features/users/api.ts:103](/Users/siddhant/Desktop/lms/frontend/src/features/users/api.ts:103)
- [frontend/src/features/api-clients/api.ts:1](/Users/siddhant/Desktop/lms/frontend/src/features/api-clients/api.ts:1)
- [backend/src/main/java/com/bhawana/lms/web/UserAdminController.java:36](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/web/UserAdminController.java:36)
- [backend/src/main/java/com/bhawana/lms/web/ApiClientAdminController.java:31](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/web/ApiClientAdminController.java:31)

**In simple terms / root cause:** Users/API-client UI adapters obtain collections and paginate/filter locally. This may be acceptable for a small bounded directory, but it does not scale automatically and can disagree with a future backend cap. It is not the same contract as alerts.

**Required fix and final behavior:** Document a supported directory bound or introduce explicit server filtering/sort/paging with totals and migrate the UI to that contract. Preserve exact role/LSP/status filters and cancellation on navigation. Avoid fetching every page in a hidden loop merely to retain local filtering.

**Acceptance tests:** A fixture above the chosen bound has accurate totals and accessible later rows; filter/sort spans the full result set. Existing small-directory behavior remains simple.

**How it should land:** Do after H30 and only to the extent the real directory size justifies it. Add query indexes based on measured filters, not every possible field.

<a id="m21"></a>

### M21 — Frontend requests lack a consistent deadline and cancellation policy

**Status:** Open — source-confirmed. **Source comparison:** Codex; not explicit in Fable.

**Files and code evidence:**

- [frontend/src/lib/api/http-client.ts:171](/Users/siddhant/Desktop/lms/frontend/src/lib/api/http-client.ts:171)
- [frontend/src/features/loan-applications/hooks/useLoanApplicationDetail.ts:22](/Users/siddhant/Desktop/lms/frontend/src/features/loan-applications/hooks/useLoanApplicationDetail.ts:22)
- [frontend/src/features/auth/auth-service.ts:150](/Users/siddhant/Desktop/lms/frontend/src/features/auth/auth-service.ts:150)

**In simple terms / root cause:** The transport can receive RequestInit signals but does not establish a consistent default deadline, and many query functions do not forward the TanStack Query signal. Slow searches/requests can outlive navigation. This is separate from the now-isolated query cache and from the authentication operation race.

**Required fix and final behavior:** Define request budgets by class (normal read, auth, upload/download) with caller overrides. Compose the caller’s signal with a deadline and clean up timers/listeners on every outcome. Pass query cancellation signals through feature APIs. Do not treat client cancellation of a financial mutation as proof that the server rolled it back; preserve the request key and provide status/retry guidance. Reassess GET coalescing when requests have different cancellation owners.

**Acceptance tests:** A deadline produces a distinct readable error, navigation cancels the relevant read, and one cancelled coalesced caller cannot unexpectedly abort another caller’s live work. Financial requests retain safe replay behavior after timeout.

**How it should land:** Transport primitive and tests first, then affected query APIs. Keep auth deadlines consistent with H21/H22; no global timeout so short that normal uploads fail.

<a id="m22"></a>

### M22 — Locally minted JWTs lack an explicit audience and planned overlapping key rotation

**Status:** **Completed (2026-09-12)** — landed on main via commit `12dda5f9`. **Source comparison:** Codex; Fable mentions audience as an Entra prerequisite.

**Files and code evidence:**

- [backend/src/main/java/com/bhawana/lms/security/JwtSecurityBeans.java:33](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/security/JwtSecurityBeans.java:33)
- [backend/src/main/java/com/bhawana/lms/service/AuthTokenService.java:30](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/AuthTokenService.java:30)
- [backend/src/main/java/com/bhawana/lms/security/SecurityProperties.java:15](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/security/SecurityProperties.java:15)

**In simple terms / root cause:** The local token path pins HS256 and validates issuer/time, but does not validate an LMS audience or provide a deliberate overlapping key-rotation protocol. In a single tightly controlled issuer/verifier this is a hardening gap, not evidence that HS256 is broken.

**Required fix and final behavior:** Mint an explicit human-token audience and validate it separately from Entra audiences. Define a short compatibility window for existing tokens. Either document planned session invalidation on secret replacement or support a small configured key ring with key IDs, current signing key and retiring verification keys. Keep secrets in the deployment secret store and reject unexpected algorithms; never fetch keys from arbitrary JWT-supplied URLs.

**Acceptance tests:** Real signed tokens with wrong audience/issuer/key or expired time are rejected; valid current tokens pass. Rotation follows the chosen overlap/session-invalidation policy and human/API-client types cannot cross surfaces.

**How it should land:** Land audience before G02’s second issuer. A key ring is optional if deliberate forced reauthentication meets the operating requirement; do not build a general identity provider.

**What landed (2026-09-12):**

- Human and machine tokens now carry distinct audiences, each validated on its own branch, with a surface guard closing both crossing directions; tokens minted before audiences existed are rejected with no compatibility window. The policy epoch (signing key + issuer + audiences) forces reauthentication on key/audience rotation (`12dda5f9`).

## Low issues

<a id="l01"></a>

### L01 — Frontend table and URL-state duplication adds avoidable maintenance cost

**Status:** Open — source-confirmed. **Source comparison:** Both; optional cleanup.

**Files and code evidence:**

- [frontend/src/components/app/data/DataTable.tsx:234](/Users/siddhant/Desktop/lms/frontend/src/components/app/data/DataTable.tsx:234)
- [frontend/src/lib/url-state.ts:1](/Users/siddhant/Desktop/lms/frontend/src/lib/url-state.ts:1)
- [frontend/src/lib/admin-list-url-state.ts:1](/Users/siddhant/Desktop/lms/frontend/src/lib/admin-list-url-state.ts:1)
- [frontend/src/features/audit/url-filters.ts:1](/Users/siddhant/Desktop/lms/frontend/src/features/audit/url-filters.ts:1)

**In simple terms / root cause:** DataTable clones the data array each render, invalidating referential memoization. URL parsing/serialization has more than one implementation. This is maintenance/performance debt, not a release blocker or proof of slow rendering.

**Required fix and final behavior:** Keep stable table data identity when the input has not changed, accommodating readonly typing without unsafe mutation. Migrate duplicated URL parsing incrementally to one tested primitive only where semantics match. Share backend contract types from the generated schema while retaining runtime validation where useful.

**Acceptance tests:** Table selection/sort/loading behavior is unchanged; profiling shows no unnecessary row-model rebuild from an unrelated parent render. URL round-trip/back navigation preserves repeated filters and defaults.

**How it should land:** Small per-feature cleanup after correctness tickets. Do not introduce a generic CRUD/configuration framework or repackage every hook.

<a id="l02"></a>

### L02 — Density controls, stale copy and missing polling can confuse operators

**Status:** Open — source-confirmed. **Source comparison:** Fable.

**Files and code evidence:**

- [frontend/src/app/density-context.tsx:52](/Users/siddhant/Desktop/lms/frontend/src/app/density-context.tsx:52)
- [frontend/src/components/app/data/DataTable.tsx:506](/Users/siddhant/Desktop/lms/frontend/src/components/app/data/DataTable.tsx:506)
- [frontend/src/features/loan-applications/hooks/useLoanApplicationDetail.ts:23](/Users/siddhant/Desktop/lms/frontend/src/features/loan-applications/hooks/useLoanApplicationDetail.ts:23)
- [frontend/src/features/loan-applications/components/detail-tabs/BlockingIssuesPanel.tsx:109](/Users/siddhant/Desktop/lms/frontend/src/features/loan-applications/components/detail-tabs/BlockingIssuesPanel.tsx:109)

**In simple terms / root cause:** The root data-density attribute has no matching stylesheet consumer found; individual tables do have density props, so “all density is a no-op” is too broad. Blocking copy refers to a Disbursements tab, and detail queries have no status-dependent polling interval.

**Required fix and final behavior:** Wire the global setting into actual table/layout props or remove the ineffective control. Replace stale location names with the real detail-section link. Add bounded polling only while a disbursement is genuinely in flight, stop at terminal state/unmount and provide a visible last-updated/manual refresh control. Keep refresh load modest.

**Acceptance tests:** Switching density visibly changes intended rows without breaking mobile/keyboard layout. Pending→terminal status updates without a reload, polling stops, and the diagnostic link reaches the correct section.

**How it should land:** UI-only follow-up with browser checks at desktop/mobile sizes. Do not claim the prior component accessibility assertions establish a full visual audit.

<a id="l03"></a>

### L03 — Dormant dependencies and duplicate backend contracts can be simplified

**Status:** Open — source-confirmed. **Source comparison:** Both; candidates, not automatic deletion instructions.

**Files and code evidence:**

- [backend/pom.xml:24](/Users/siddhant/Desktop/lms/backend/pom.xml:24)
- [backend/src/main/java/com/bhawana/lms/repo/ReportRequestRepositoryImpl.java:100](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/repo/ReportRequestRepositoryImpl.java:100)
- [backend/src/main/java/com/bhawana/lms/web/LoanApplicationOpsApiTypes.java:31](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/web/LoanApplicationOpsApiTypes.java:31)
- [backend/src/main/java/com/bhawana/lms/web/LspLoanApiResponses.java:6](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/web/LspLoanApiResponses.java:6)

**In simple terms / root cause:** AMQP is present without a messaging workflow; H2/fallback repository code is inconsistent with the production Postgres target. Multiple allowlist/idempotency/DTO implementations duplicate mechanics. Some candidate helpers appear unused. Exact dead-component counts in Fable were not supplied as a reproducible file inventory.

**Required fix and final behavior:** Remove AMQP and corresponding unused infra/health dependency after confirming no deployment consumer. Remove H2-only branches only after tests/build profiles prove PostgreSQL-only support. Share duplicate leaf DTOs/mappers and coordinator mechanics in small bounded patches while preserving separate tenant/admin policies and intentional public-contract evolution. Produce a concrete reference/import inventory before deleting any purportedly dead class/component. Point deployment probes at the already-defined liveness/readiness groups, not the dependency-aggregating root health endpoint; verify actual deployment manifests. The permission tables, obsolete report_content and unused error/status aliases are cleanup candidates only after a concrete consumer/history inventory.

**Acceptance tests:** Backend packaging and full database tests still pass, health no longer depends on unused RabbitMQ, and public API schemas do not change accidentally. For a deletion, prove no production/import/reflection/configuration consumer remains.

**How it should land:** Low-priority cleanup except the deployment health dependency. No large package-by-feature migration is required; both audits support retaining the monolith and core framework abstractions.

<a id="l04"></a>

### L04 — Transient data retention and revocation delay need explicit policies

**Status:** Open — source-confirmed. **Source comparison:** Both.

**Files and code evidence:**

- [backend/src/main/java/com/bhawana/lms/repo/RefreshTokenRepository.java:18](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/repo/RefreshTokenRepository.java:18)
- [backend/src/main/java/com/bhawana/lms/security/AuthPrincipalCache.java:14](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/security/AuthPrincipalCache.java:14)
- [backend/src/main/java/com/bhawana/lms/security/LspSurfaceIpAllowlistFilter.java:37](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/security/LspSurfaceIpAllowlistFilter.java:37)
- [backend/src/main/java/com/bhawana/lms/repo/PortfolioKpiSnapshotRepository.java:10](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/repo/PortfolioKpiSnapshotRepository.java:10)

**In simple terms / root cause:** An expired-refresh-token purge method has no production caller found. Principal/allowlist cache expiry is per process (30/60 seconds respectively), and derived snapshot/audit retention is not uniformly explicit. This is a lifecycle/operational policy gap; it does not mean cached entries never expire or all revocations require Redis.

**Required fix and final behavior:** Add bounded indexed purge for expired/revoked refresh credentials according to the reuse-detection policy, plus approved retention for derived snapshots and idempotency replay windows. Document and test the cross-instance revocation SLA; add invalidation messaging only if that SLA is insufficient. Expire stale cache entries structurally if key growth is material. Preserve required immutable financial/audit records.

**Acceptance tests:** Purge does not remove live tokens or evidence still required for family reuse detection. Two instances demonstrate the documented maximum revocation delay and no cross-tenant cache key collision.

**How it should land:** Separate retention jobs/migrations by data class. Do not apply a universal TTL to all tables or add Redis-backed authorization solely because more than one instance exists.

<a id="l05"></a>

### L05 — Synthetic data generation is reachable as a service outside its runner profile

**Status:** **Completed (2026-09-21)** — landed on main via PR #356 (squash `c367380d`). **Source comparison:** Fable; defense in depth, not a demonstrated production wipe path.

**Files and code evidence:**

- [backend/src/main/java/com/bhawana/lms/seed/synthetic/SyntheticPortfolioSeedService.java:36](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/seed/synthetic/SyntheticPortfolioSeedService.java:36)
- [backend/src/main/java/com/bhawana/lms/seed/synthetic/SyntheticPortfolioSeedRunner.java:20](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/seed/synthetic/SyntheticPortfolioSeedRunner.java:20)
- [backend/src/main/java/com/bhawana/lms/seed/synthetic/SyntheticPortfolioSeedService.java:101](/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/seed/synthetic/SyntheticPortfolioSeedService.java:101)

**In simple terms / root cause:** The destructive seeder service is always a bean and rejects only profiles literally named prod/production. The actual runner is already restricted to local/staging and requires an explicit flag/config. That substantially limits exposure; no remotely callable production seeding endpoint was established.

**Required fix and final behavior:** Restrict the service/configuration to explicit local/staging or a dedicated test-data profile as well as the runner. Keep the separate enable flag and target-database guard. Make absence of an approved profile fail closed. Review additive versus truncating mode explicitly.

**Acceptance tests:** Production/unknown-profile application context does not contain the destructive service; local/staging invocation still requires both intentional enablement and an allowed target. Verify no production controller can resolve it.

**How it should land:** Small configuration hardening patch, no data changes. Never run the destructive seed during this audit or its verification against a non-ephemeral database.

**What landed (2026-09-21):**

- Seeder beans now register only under the explicit `local`/`staging`/`test-data` profiles — an absent or unrecognized profile fails closed, proven by bean-definition scanning rather than a context-boot attempt.
- A new fail-closed `allowed-database-names` allowlist is checked against `current_database()` before any seeding runs, so an approved profile pointed at the wrong database still refuses.
- `test-data` was deliberately kept out of the dev-exempt profile set, so the destructive path can never activate implicitly through a development shortcut.

## Already implemented locally — do not duplicate

<a id="fixed-01"></a>

### FIXED-01 — Browser query cache isolation between sessions

**Former severity:** Critical in Codex, High in Fable. **Current status:** implemented and verified locally; uncommitted, not claimed deployed.

The singleton QueryClient was replaced by a client whose lifetime is keyed to user ID, LSP ID and role. An authorization-context change remounts query consumers and retires/cancels/clears the old client; late callbacks still holding it cannot populate the new session’s cache. Token refresh for the same authorization context keeps the current client. SessionProvider’s authentication lifecycle was deliberately not changed.

- [frontend/src/app/auth-scoped-query-provider.tsx:1](/Users/siddhant/Desktop/lms/frontend/src/app/auth-scoped-query-provider.tsx:1)
- [frontend/src/app/query-client.ts:1](/Users/siddhant/Desktop/lms/frontend/src/app/query-client.ts:1)
- [frontend/src/app/AppProviders.tsx:1](/Users/siddhant/Desktop/lms/frontend/src/app/AppProviders.tsx:1)
- [frontend/src/features/auth/session-query-isolation.test.tsx:1](/Users/siddhant/Desktop/lms/frontend/src/features/auth/session-query-isolation.test.tsx:1)

The pilot used Muse Spark 1.3 Free through OpenCode Zen. Codex review rejected the initial shared-cache-clear approach after reproducing late-mutation and stale-consumer gaps. The final implementation contains ten regression tests; the retained full frontend verification reports **1,065 tests across 163 files passing**, with type checking, lint, encoding, build and bundle checks passing. Three additional independent boundary checks passed. The aggregate verify command remains red solely on pre-existing formatting in AlertRulesPanel.tsx.

Keep these tests. Do not reintroduce a singleton, add a bare clear-on-logout patch as a replacement, or claim the auth race in H22 is resolved. If M19 later introduces a full role/capability set, update the authorization-context key deliberately so a permission change cannot retain stale protected data.

## Shared implementation contracts for the highest-risk fixes

### A. One lock order and one submission permission

C01/C03/H06/H13/H15 touch overlapping state. Agree a single order before any implementation: where needed, borrower → application → account → intent/quote → installment rows in stable order. Ordinary single-loan commands need not lock an unrelated borrower, but no command may acquire an earlier lock after taking a later one. Audit existing calls for hidden REQUIRES_NEW transactions: a nested transaction on another connection does not share the outer locks or visibility. Write concurrency tests that use separate service/transaction instances rather than relying on Java synchronized blocks.

For C03, a useful small interface is a claim result containing `intentId`, `owner` and `attemptCount`. The existing `attempt_count` column can carry the fence. The implementation must preserve these semantics:

1. In a short transaction, claim only CREATED with an expired/absent lease; increment attempt_count atomically and return the complete claim token. Batch and single-ID paths call the same guarded mechanism.
2. In another short transaction, lock/recheck loan eligibility and conditionally transition CREATED→REQUESTED only if owner and attempt still match and the claim lease is live. Persist the pre-call evidence in that same transaction.
3. Affected-row count zero means no permission to initiate. Return without a provider call.
4. Commit before calling the provider. Do not hold a database transaction through network I/O.
5. Persist the response/unknown observation with the same captured attempt. Since REQUESTED is not automatically reclaimable, lease expiry alone must not authorize another initiation. Retain enough owner/attempt identity through submission to fence completion; the current helper clears lease ownership and must be changed consistently.
6. Apply terminal local consequences atomically (C02). If a status poll has already applied a definitive outcome, a late submission response must be reconciled as evidence, not regress the account.
7. Do not claim UNKNOWN for new initiation. Status queries use the original immutable payment instruction, including IFSC and source identity.

A single extra @Version field does not implement this protocol. An extra fencing column is unnecessary if the existing counter can safely serve that role. Keep the repository primitive narrow and readable; avoid a generic distributed-lock framework.

### B. Financial settlement boundary

H06/C05/H07/H09 share one receipt/settlement boundary. Ownership is established before replay. A matching committed result is returned before evaluating whether a _new_ payment is still allowed. New receipts and quote execution then run under the account lock, reading current balances inside that transaction. The quote’s financial revision is validated; the receipt, allocations, closure, audit and event commit together. Prior receipt targeting remains stable. Never infer that a client timeout means money was not received.

Use an additive quote freshness field or explicit balance fingerprint; a quote’s display version is insufficient. Define expiry and pricing as separate concerns. Active legacy quotes without a trustworthy revision must be superseded or explicitly recomputed, not assigned invented revisions. A safe freshness patch can land before the business chooses H08’s final interest formula.

### C. Database transactions are not external transactions

H17/H24/M04/M05 must not assume a rollback undoes S3, SMTP or a bank call. For each side effect, record its stable operation identity, the point where it may have happened, how to observe/recover the result, and what the retry is allowed to repeat. A short transaction split is incomplete without crash recovery for the newly committed work state. An afterCommit callback is a notification convenience, not durable retry storage.

### D. Partner and UI contracts must remain truthful

For H28–H30/M13–M15/M19, change schema/DTO, adapter, query key, URL controls and rendered state together where they depend on one another. Use existing pagination/header infrastructure rather than inventing a fourth envelope. Do not turn unknown/null into a known healthy state. Do not use local role metadata to relax backend checks. Add HTTP-boundary tests with real-shaped responses when hook mocks would conceal the bug.

## Landing sequence and worker-sized tasks

The sequence below reflects dependencies, not a request to implement everything in one patch.

| Batch                                 | Work                                                                                             | Required completion boundary                                                                                                      |
| ------------------------------------- | ------------------------------------------------------------------------------------------------ | --------------------------------------------------------------------------------------------------------------------------------- |
| 0 — preserve baseline                 | Keep FIXED-01; repair narrow formatting/architecture guard mismatch and schema tooling (M17/M01) | Reproducible gates; unchanged tenant security intent. This is a separate small PR from financial fixes.                           |
| 1 — stop unsafe states                | C04, H01, C01 endpoint guard; H05/H04/H10 can be separate independent tasks                      | Unsafe inline submission removed; parked loans cannot reinitiate/poison ticks; no silent overrides; first-payment defaults found. |
| 2 — establish money ownership         | C03 shared claim fence plus C01 submission eligibility; C02 atomic outcome application           | Cross-process races and both pre-/post-response crash windows tested before adding concurrency.                                   |
| 3 — freeze instructions and reconcile | C06 first-stage snapshot/gates, H02 and G01 simulator guard                                      | Original payment instruction used throughout; unresolved money visible; immutable observations.                                   |
| 4 — servicing correctness             | H06, H07, H15, C05, H09; H08 policy decision in parallel                                         | One loan lock/receipt boundary; replay and stale quote tests pass; no historical allocation rewrite.                              |
| 5 — origination/reporting truth       | H13/H14/H16/H11, H28/H29/H30                                                                     | Durable approval completion, approved evidence preserved, pinned terms and accurate totals/filters.                               |
| 6 — auth and runtime boundaries       | H03/H19–H23, M10–M12/M15/M19/M22                                                                 | Trusted IP source, safe refresh/logout protocol and tested runtime roles. G02 cutover is separately scheduled.                    |
| 7 — sustained operation               | H12/H17/H18/H24–H27, M04–M08/M13/M14/M21                                                         | Bounded work, safe recovery, exposed metrics and usable partner retry contracts.                                                  |
| 8 — production capabilities           | Complete G01/G02 and environment-specific deployment validation                                  | Actual bank/identity contracts and sandbox/staging evidence; no real-money enablement based on mock success.                      |
| 9 — cleanup                           | Remaining Medium/Low items and measured performance work                                         | One justified improvement per change; no platform rewrite.                                                                        |

Useful very small follow-on tasks are H07 replay ordering, H05 removing the silent fallback, H10 first-payment DPD selection, and the H28 Home unknown-bucket correction. C03, C05, C06 and G01 should not be assigned as casual “one-line” tasks: they require the specified transaction/schema review.

**Handoff instructions for an implementing worker:**

1. Select exactly one issue ID, or an explicitly coupled group from the sequence. Read its source files and dependency tickets against the current checkout.
2. State the failure being fixed and the invariant the patch will enforce. If the checkout differs from this report, show the difference before applying an obsolete prescription.
3. For a bug, add a behavioral regression that fails on the current implementation and passes with the fix. Use real Postgres transactions for locking/RLS and deferred HTTP promises for browser session races. Do not “test” the implementation by merely asserting a new helper was called.
4. Keep the patch narrow, readable and conventional. Prefer existing domain/repository primitives. Include migrations, contracts and fixtures that the change truly requires; no opportunistic package rearrangement.
5. Run targeted checks first, then applicable repository verification. Report exact commands/results and distinguish baseline failures from new regressions. Never disable a guard or skip a test simply to make a green summary.
6. For schema changes, use the next available Flyway version at implementation time, show compatibility/backfill/rollback strategy and run migration plus RLS tests. Do not reserve “V120” in this report because another task may land first.
7. Return a diff summary, tests, unresolved decisions and remaining rollout steps. Do not commit/push/deploy or perform data repairs unless that work is separately authorized.
8. An orchestrator reviews the diff and the failure scenario independently before calling the issue fixed. Update the issue’s status with evidence; a worker’s completion message alone is not proof.

Policy-dependent work has a deliberate stopping point: H08 pricing/day-count/fees, C06 partner ownership where the existing contract is insufficient, M03 mobile identity, M16 disclosure/retention, and G01/G02 provider/identity provisioning. The worker may implement independent guards and test scaffolding while awaiting the specific decision; it must not invent the policy.

## Fable’s full report: coverage and disposition ledger

This table covers all 22 sections, including repeated recommendations and claims that do not warrant their own defect ticket. Repeated symptoms map to the same issue ID.

| Fable section               | Claims reviewed                                                                                                                                                                                          | Consolidated disposition                                                                                                                                                                                                                                                                                                                                                                          |
| --------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1 — executive summary       | Six Criticals, foreclosure, locks, DPD, observability, CI                                                                                                                                                | C01/C03/C04 and H01/H03/H04 explain the adjusted criticality; C02/C05/C06 were underrepresented. H08/H10/H12/H27/M01/M17 cover the remaining claims.                                                                                                                                                                                                                                              |
| 2 — architecture            | Monolith, filter/RLS chain, three-transaction intent workflow, refresh auth, seven scheduled methods                                                                                                     | Architecture retained. “Three transactions” omits separate outcome application (C02). Shared scheduler is H26. No current Entra path (G02). Do not confuse design intent with proven cross-process safety.                                                                                                                                                                                        |
| 3 — workload model          | 10 LSPs/100k loans, 12-month cohorts, 14M installments, document/event volume, worker ceiling                                                                                                            | Assumptions are planning scenarios, not measured capacity. Batch-only worker ceiling and daily/weekly partition description corrected. H25/M07 cover actual unbounded paths. Storage volumes depend on real sizes/retention.                                                                                                                                                                      |
| 4 — strengths               | Fail-closed tenant routing, RLS, xid8 feed, decimal money, borrower lock, idempotency, JWTs, errors, forms, test harness, docs                                                                           | Keep these mechanisms, with H17/H23/M16 qualifications. Existing test controls do not prove every race. Exact 18/18 form and 102 axe assertion counts are not independently recounted; no fresh visual/accessibility claim is made.                                                                                                                                                               |
| 5 — C1 parked worker        | Rollback-only exception escapes processor, starvation                                                                                                                                                    | H01 confirmed; C04 prevents unsafe retry. Ordering/execution breadth qualified.                                                                                                                                                                                                                                                                                                                   |
| 5 — C2 inline path          | Fresh-reference re-initiation and transaction around provider                                                                                                                                            | C04 confirmed conditional risk; remove the flag branch.                                                                                                                                                                                                                                                                                                                                           |
| 5 — C3 claim race           | Unlocked single claim, default shared owner, missing version                                                                                                                                             | C03 confirmed; fence submission as well as claim, reuse attempt_count.                                                                                                                                                                                                                                                                                                                            |
| 5 — C4 invalidation         | Missing submitted/parked guard and writer bypass                                                                                                                                                         | C01 confirmed; common locks and queued-eligibility recheck also required.                                                                                                                                                                                                                                                                                                                         |
| 5 — C5 client IP            | First forwarded header trusted                                                                                                                                                                           | H03 confirmed, contingent ingress exploitability and ordinary credential checks retained.                                                                                                                                                                                                                                                                                                         |
| 5 — C6 password randomness  | Math.random for temporary credentials                                                                                                                                                                    | H04 confirmed High; no demonstrated practical V8 state prediction.                                                                                                                                                                                                                                                                                                                                |
| 6 — servicing               | Foreclosure future interest, stale quote, allocation replay, closure race, UTC anchoring                                                                                                                 | H08/C05/H09/H06/M09; pricing policy separate from freshness.                                                                                                                                                                                                                                                                                                                                      |
| 6 — idempotency/workers     | Poisoned reclaim, blocking duplicate wait, single scheduler                                                                                                                                              | H17/H18/H26; blanket delete/retry and unset graceful shutdown conclusions corrected.                                                                                                                                                                                                                                                                                                              |
| 6 — borrower onboarding     | PAN race, persistent visibility, global merge, mobile uniqueness                                                                                                                                         | M02/C06/M03; PAN 409 correction and RLS-aware atomic resolution.                                                                                                                                                                                                                                                                                                                                  |
| 6 — other backend           | Account transitioner, parked polling, mutable logs, orphaned objects, report email, S3 clients, seed guard, unread JSON, intern strings                                                                  | C01/C04/H02/M04/H24/M05/L05/M06/H06. Missing @Version is handled by the relevant invariant, not added indiscriminately.                                                                                                                                                                                                                                                                           |
| 7 — frontend High           | Silent override, cache, false mappings, sort/multi-status                                                                                                                                                | H05/FIXED-01/H28/H29; manual-approval example corrected.                                                                                                                                                                                                                                                                                                                                          |
| 7 — frontend Medium         | Whole-list paging, URL duplication, copied table data, generated-type drift, Home guard, rounded/null money, implicit timezone, full-page 428 redirect                                                   | H30/M20/L01/M13/M19/H28/M09. A full-page 428 redirect enforces password change; changing it to router navigation is optional UX work, not a security defect.                                                                                                                                                                                                                                      |
| 7 — frontend Low            | Ten dead components, global density, unused expiresAt, duplicate permission helpers, multi-role flattening, stale tab copy, missing polling                                                              | L01/L02/M19; exact ten-file dead list is not supplied/reproduced, so no deletion ticket is fabricated. Stored expiresAt not being read is cleanup rather than proof of invalid server token acceptance.                                                                                                                                                                                           |
| 8 — database                | Stale schema, advisory locks, missing versions/loan lock, broad alert transaction, missing dedup index, PII, refresh purge, dead response_status, MIS offset, lsp visibility                             | M01/H12/H06/C03/C05/M07/M08/M16/L04/H25. response_status always 200 is dead/limited replay metadata; change only with endpoint contract needs. Global lsp catalog visibility is a deliberate grant to review for sensitivity, not a demonstrated borrower leak.                                                                                                                                   |
| 9 — authentication          | API refresh bypass, FORCE RLS admin issue, default local, rotation/reuse, bootstrap fallback, human lockout, Redis, CORS, cache lag, default identity/secrets                                            | H20/H23/H19/H21/M12/M11/M10/M15/L04. Per-process revocation delay is 30s principal / 60s allowlist in code. Human audience is M22; Entra is G02.                                                                                                                                                                                                                                                  |
| 10 — Azure/deployment       | Admin role, root health dependencies, export/logging, Redis TLS, graceful shutdown, Vite base/CSP, Blob adapter, dual-pool budget                                                                        | H23/L03/H27/M10/H26/M15/M05/G01/G02. Root liveness/readiness groups already exist; actual probe choice not inspected. Two pools require connection budgeting, not a defect by themselves. Cloud administrator role is not the proposed runtime identity.                                                                                                                                          |
| 11 — API/integration        | Incomplete OpenAPI, spec drift, invalid status, uneven idempotency, quote keys, 200/201, PUT/PATCH, endpoint verbs, mock endpoint, DTO duplication, 50-payment cap, buffering                            | M13/M14/G01/L03/H25. Preserve compatibility for convention-only changes. Only mock bank adapter exists; actual bank integration is a release prerequisite, not a current verified payment feature.                                                                                                                                                                                                |
| 12 — failures               | Duplicate requests, crash/restart, timeout, partial external success, two pods, concurrent writes, DB/Redis outage, removed callbacks                                                                    | Covered in C01–C06/H01/H02/H06/H17/H18/H20–H26/M04/M10. “Intent crash recovery correct” is incomplete without C02. Removed webhook callbacks remain out of scope; partner pull feed is current.                                                                                                                                                                                                   |
| 13 — structure              | Layer packaging, duplicate DTOs/coordinators/allowlists, dead code, red CI, large controller/exception files                                                                                             | Keep packaging; L01/L03/M17. File length is not a defect. Remote CI history and test-pollution attribution remain unverified. No blanket class deletions.                                                                                                                                                                                                                                         |
| 14 — testing/observability  | Missing worker races, event rollback, JWT negatives, Redis outage, transition matrix, payment close/overpay, raw now(), shared fixture cleanup, weak coverage, mocked frontend contracts, smoke-only E2E | Acceptance tests on corresponding tickets; M17/M22/M10/H07/M09. Real JWT-path tests do exist. Coverage 35% is a weak floor, not permission to add meaningless tests. Clock should be injected in changed time-sensitive paths, not churned across 117 claimed call sites without need.                                                                                                            |
| 15 — unnecessary complexity | Delete lease/reconstructor machinery, combine coordinators/tables, remove dependencies/fallbacks, rate-limit abstraction, error aliases, type systems, alert JSON                                        | H17 rejects blanket idempotency simplification. L01/L03/M06 identify bounded cleanup. Separate UI/API allowlist policies and admin/tenant idempotency security scopes may remain even when mechanics are shared.                                                                                                                                                                                  |
| 16 — stronger controls      | Account state machine, common loan lock, conditional claims, leases, RLS probe, lockout, metrics, quote freshness, OpenAPI, explicit override/cache                                                      | C01/C03/C04/H06/H12/H23/M11/H27/C05/M13/H05/FIXED-01.                                                                                                                                                                                                                                                                                                                                             |
| 17 — bottlenecks            | Worker rate, scheduler, snapshot lock, unbounded pending scans, DPD transaction, CSV memory, contains-search, alert growth, audit accumulation                                                           | H25/H26/H12/M07/M08/H30. Exact p95 cause/month/row OOM thresholds unverified. Trigram indexes and bounded audit-list retention are measured follow-ups, not mandatory platform changes.                                                                                                                                                                                                           |
| 18 — debt                   | Transitioner, inline deletion, idempotency simplification, schema/CI, AMQP/H2, S3/streams, DTOs, seed profile, Clock/test cleaner, frontend types/URL/data identity                                      | Mapped above. Dead/duplicate cleanup only after consumer tests; no additional duplicate ticket for the same underlying issue.                                                                                                                                                                                                                                                                     |
| 19 — leave alone            | Package shape, single-issuer HS256, bounded cache SLA, flat handlers, exact EMI, global PAN identity, unsigned cursor, ignored formance tree                                                             | Retained with M22/G02 and C06 qualifications. Unsigned cursor is not a tenant-auth token; preserve RLS/ownership. Do not introduce partial payments or per-LSP databases as an audit fix.                                                                                                                                                                                                         |
| 20 — ranked changes         | “Now”, Azure, later sequence                                                                                                                                                                             | Replaced by the dependency-aware landing plan above. H04/H05/H10 are small; C03/C05/C06/G01 are not trivial.                                                                                                                                                                                                                                                                                      |
| 21 — alternatives           | Queue vs polling, approval directly creates intent, lease table, per-LSP bank data, presigned documents, Redis principal cache                                                                           | Intent table already supports a database work queue. Approval→intent consolidation is optional after fixing ownership and preserving preflight; do not require it to fix C01/C03. Lease table is useful for chunked jobs. Snapshot-first bank fix precedes ownership migration. Presigned URLs require expiring access/audit and actual need. Distributed principal invalidation follows the SLA. |
| 22 — assessment             | Architecture suitable; local financial/operational depth missing; breadth cleanup                                                                                                                        | Broadly agreed, with the additional Codex omissions and corrections in this document. Real-money readiness is not established by merely fixing Fable’s first eight recommendations.                                                                                                                                                                                                               |

## Verification evidence and practical limits

| Evidence                                         | What it establishes                                                                                                                                    | What it does not establish                                                                              |
| ------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------- |
| Current source re-read and method/caller tracing | The documented guards, query predicates, mappings, defaults, transactions and missing recovery paths exist at the stated commit plus local cache patch | A deterministic reproduction of every race, a live tenant exploit, or measured production incident rate |
| Fresh OpenAPI JSON inventory in this pass        | 107 operations; zero summaries/descriptions; two operation security declarations; no global security or ApiError schema; responses include 200/204     | A current runtime export byte-for-byte match; M13 adds that check                                       |
| Current migration files/reference inspection     | 115 files through V119, monthly loan-event partitions, reference artifact stale                                                                        | A newly performed production migration or an inspected Azure role grant                                 |
| Prior backend verification retained in this task | 818 tests: one architecture failure, two skips; packaging and fresh migration had passed in the original audit                                         | That every subsequent proposed financial fix is implemented or tested                                   |
| Cache pilot verification retained in this task   | 1,065 frontend tests pass; type/lint/build/bundle pass; formatting failure isolated to existing AlertRulesPanel; three independent cache checks pass   | A full live browser/visual audit or completion of H22                                                   |
| Primary framework/provider documentation         | Default scheduling/shutdown, advisory/RLS semantics and identity protocol guidance                                                                     | Actual ingress, database users, cloud provisioning, bank permissions or performance capacity            |

The machine refresh recommendation is consistent with [OAuth 2.0 client-credentials token-response guidance](https://www.rfc-editor.org/rfc/rfc6749.html#section-4.4.3). G02 follows [Microsoft claims-validation guidance](https://learn.microsoft.com/en-my/entra/identity-platform/claims-validation) and the [Entra client-credentials flow](https://learn.microsoft.com/en-us/entra/identity-platform/v2-oauth2-client-creds-grant-flow): a valid signature alone does not authorize a client for this API or LSP.

## Release review checklist for the orchestrator

- [ ] Every selected Critical/High ticket has a reviewed regression covering its actual failure boundary, not just happy-path assertions.
- [ ] Cross-process tests use independent transactions and prove one authorized provider submission / one accepted financial result.
- [ ] No old worker or stale browser auth operation can overwrite a newer owner/session.
- [ ] Migrations preserve historical evidence and have a reviewed backfill/compatibility plan.
- [ ] Unknown financial outcomes remain visible and recoverable; no blind retry or success fabrication exists.
- [ ] Partner/UI contracts display real values, complete result sets and accurate status/retry semantics.
- [ ] Production identities, ingress trust, metrics export and actual deployment probes are tested in staging.
- [ ] Full applicable verification is green or any unrelated baseline failures are explicitly recorded; no gate was weakened to hide a regression.
- [ ] Bank/foreclosure/identity policy dependencies are resolved by the responsible owner before their dependent behavior is enabled.

This checklist is a future release acceptance list. Its unchecked state is intentional: this pass created the consolidated audit/specification, not the implementations or a production release.
