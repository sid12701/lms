# Deferred implementation register

Production-readiness and architecture items that are **approved in principle** but intentionally **not scheduled for the current implementation pass**. Each entry records why work is paused, what must be true before resuming, and the canonical spec.

**Source of truth for specs:** `outputs/production-readiness-report-2026-07-12/PRODUCTION-READINESS-REPORT-2026-07-12.md` (§19.3–§19.5).

**Implemented fixes:** `docs/implementation-log.md` (and §19.6 of the report).

**Last register update:** 2026-07-15 — Specs S5, S6, S13, S14, S15, S16, and S18 deferred; **S19 Slice A** closed (relationship table + dual-write + grant API lockdown; access-collection drop / normalizer remain as S19 residual). Specs S7–S12 and **S20** already implemented. Next free Flyway: **V114**.

**How to use:** When picking up deferred work, re-audit the cited code paths first — the tree may have moved since the deferral date. Remove an entry only after the spec's acceptance criteria are met and validation evidence is recorded in the implementation log.

---

## Active deferrals

### S5 / DATA-01 — Approval-time beneficiary snapshot

| | |
|---|---|
| **Canonical spec** | Production report §19.3 Spec S5; aliases DATA-01, F-S2 (payout subset) |
| **Deferred** | 2026-07-15 |
| **Severity if left open** | P1 data-integrity / payout redirection between approval and successful disbursal |
| **Why deferred** | Owner judgment: practical likelihood is low under current operating assumptions — PAN-based borrower deduplication and a single active loan per customer make cross-relationship bank edits redirecting an in-flight payout unlikely in the current product model. Not scheduled for this implementation pass. |
| **Residual risk (accepted for now)** | Disbursement still reads **live** borrower bank account/IFSC at initiate / intent-create (`LoanDisbursementCommandService`, `DisbursementIntentWorkflowService`). Any post-approval bank update before payout can still change the destination. Intent rows only freeze beneficiary fields at intent-create, not at approval. Spec S12 preview returns `beneficiarySource=LIVE_BORROWER` and labels this in the UI so operators are not misled. |
| **Prerequisite / related** | Spec S19 (canonical borrower + per-LSP relationship) is complementary; S3 intent workflow does not replace S5. S12 now shows live beneficiary truthfully; S14 may authorize against snapshot once S5 exists. |
| **Resume when** | Before real-money rails; or earlier if multi-loan concurrency, multi-LSP shared borrowers with writable bank details, or partner/ops bank edits during `APPROVED_PENDING_DISBURSAL` become real; or if compliance requires freeze-at-approval regardless of likelihood. |
| **On resume** | Re-audit `ensureLoanAccountForApprovedApplication`, disbursement initiate/preflight, and bank-details update gates; implement S5 as specified (snapshot columns, fail-closed `BENEFICIARY_DETAILS_CHANGED`, audited re-affirm). Next free Flyway version (**V114+** — V113 is S19 relationship). |

### S6 / MOCK-01 — Mutually exclusive mock/live disbursement modes

| | |
|---|---|
| **Canonical spec** | Production report §19.3 Spec S6; aliases MOCK-01, ODD-06 |
| **Deferred** | 2026-07-15 |
| **Severity if left open** | P1 configuration risk / P0 if a production deployment accidentally runs the mock rail |
| **Why deferred** | Owner requested deferral for the current implementation pass (same pass as S5). Deployment remains management-review / synthetic UAT with mock rails intentional; exclusive `mock`/`icici` provider selection is not scheduled until closer to live adapter work. |
| **Residual risk (accepted for now)** | `MockLoanDisbursementAdapter` remains an unconditional `@Service`. `POST …/disbursement-requests/mock-outcome` remains registered on the ops controller for every profile. Nothing fails startup if a `prod`/`staging-live` instance boots with the mock rail. Spec S17 (ICICI adapter) still lists S6 as a prerequisite — resume S6 before or with S17. |
| **Prerequisite / related** | S3 intent workflow already landed. S17 real ICICI adapter expects `app.disbursement.provider=icici` exclusivity from S6. |
| **Resume when** | Before any non-mock / real-money deployment; no later than Spec S17 implementation. Also resume if a dedicated `prod`/`staging-live` profile is introduced for deployment. |
| **On resume** | Re-audit adapter registration, mock-outcome mapping, worker `auto-resolve-mock-outcome`, and local/test profile defaults; implement S6 as specified (`app.disbursement.provider`, conditional mock adapter + mock-outcome controller, startup guard for `prod`/`staging-live`+mock, actuator provider detail). Prefer test-only ICICI stub until S17 (option A from 2026-07-15 approach review). |

### S13 / MNY-02 — Receipt / allocation / suspense / reversal ledger

| | |
|---|---|
| **Canonical spec** | Production report §19.5 Spec S13; aliases MNY-02, F-S10, F-MNY-08, LSP-F9 |
| **Deferred** | 2026-07-15 |
| **Severity if left open** | P0 for system-of-record collections use — real receipts (partial, bunched, advance, bounce, reversal) cannot be booked faithfully |
| **Why deferred** | Owner requested deferral for the current implementation pass. Synthetic UAT / management review can continue with exact full-EMI posting only; full receipt ledger is postponed. |
| **Residual risk (accepted for now)** | `LoanRepaymentCommandService` still rejects any amount that is not exactly one installment’s outstanding (`PAYMENT_AMOUNT_MISMATCH`). `PARTIALLY_PAID` remains unreachable on the public path. No `payment_receipt` / `receipt_allocation` / `receipt_reversal` tables; no suspense parking; no bounce/reversal command. LMS cannot yet act as the authoritative collections book (D1 remains product intent, not runtime fact). D1b (final overpayment/advance policy) stays pending; interim rule when resumed is park surplus in suspense. |
| **Prerequisite / related** | Decisions D1 / D1a already landed; D1b interim suspense is enough to implement when resumed. Independent of S5/S6. F-S10 bounce/reversal and partner recon (LSP-F10) benefit once S13 exists. |
| **Resume when** | Before the LMS is used as collections system of record / real receipt ingestion; or when ops/partners need partial, multi-EMI, suspense, or reversal booking. |
| **On resume** | Re-audit `LoanRepaymentCommandService`, installment locks, payment idempotency, webhooks (`LOAN_REPAYMENT_RECORDED`), FE repayment dialog, and foreclosure recompute coupling; implement S13 as specified (V114+ ledger migration — confirm next free Flyway version, pure `ReceiptAllocator`, dual-write wrapper, reverse + account reopen, feature flag). |

### S14 / CTRL-01 — Disbursement authorization (STP caps + maker-checker)

| | |
|---|---|
| **Canonical spec** | Production report §19.5 Spec S14; aliases CTRL-01; decision D2 |
| **Deferred** | 2026-07-15 |
| **Severity if left open** | P0 at real rails — single SYSTEM_ADMIN (or worker) can move uncapped money with no second principal |
| **Why deferred** | Owner requested deferral for the current pass; prioritises other work over maker-checker. Management-review / synthetic UAT continues with mock rails and single-admin initiate. |
| **Residual risk (accepted for now)** | `POST …/disbursement-requests` remains single-principal with no amount/budget/velocity caps and no checker queue. Intent workflow (S3) reduces duplicate payout risk but does not substitute financial authorization controls. Threshold values were never risk-signed; placeholders in the spec remain unused. |
| **Prerequisite / related** | S3 intent workflow already landed. S5 beneficiary re-affirm routing branch remains unavailable until S5 resumes. S17 live rails should not proceed without S14 (or an accepted equivalent control). |
| **Resume when** | Before real-money / live ICICI traffic; or when ops wants an approval queue for high-value or manual disbursements even on mock. |
| **On resume** | Re-audit intent create/claim, worker gates, and bootstrap single-admin constraint; implement S14 as specified (authorization_mode, `disbursement_approval` with maker≠checker, budgets, queue APIs/UI, feature flag). Confirm next free Flyway version. Resolve threshold numbers with risk (or ship placeholders behind `authorization.enabled=false` until sign-off). |

### S15 / SEC-01(3) — PAN masking policy (partner masked; admin detail full + audit)

| | |
|---|---|
| **Canonical spec** | Production report §19.5 Spec S15; aliases SEC-01 item 3, UX-04, G1, NEW-04; decision D3 |
| **Deferred** | 2026-07-15 |
| **Severity if left open** | P1 privacy / partner contract inconsistency — full PAN returned on LSP and list surfaces; FE field `borrowerPanMasked` falsely asserts backend masking |
| **Why deferred** | Owner judgment: current PAN display behaviour is acceptable for the present pass (management-review / synthetic UAT). Approved D3 matrix remains the target policy but is not scheduled now. |
| **Residual risk (accepted for now)** | LSP and admin list/detail serializers still return raw `getPan()` in most places. `PanMasking` exists (last-4 style) and is used on some reporting paths only — not the approved first-2/last-3 format, and not applied to partner/list responses. Frontend `borrowerPanMasked` still maps raw `panNumber`. Detail-page PAN access is not page-audited. |
| **Prerequisite / related** | Decision D3 already landed. Independent of S5/S6/S13. Encryption-at-rest (D4) remains separately blocked. |
| **Resume when** | Before partner pilot / compliance expects masked PAN on LSP APIs; or when list-surface PAN exposure is no longer acceptable. |
| **On resume** | Re-audit `LspLoanApplicationResponses`, `BorrowerAdminController`, `LoanApplicationOpsResponses`, `BorrowerPiiRevealAuditService`, FE `my-loans/api.ts`; align `PanMasking` to approved format; mask LSP + lists; audit admin detail; rename FE field; document matrix in `CONTEXT.md`. |

### S16 / F-API-02 — Per-LSP rate plans and idempotent bulk intake

| | |
|---|---|
| **Canonical spec** | Production report §19.5 Spec S16; aliases F-API-02, F-API-05, F-TEN-02 (partial), F-MNY-10 (partial); decision D5 |
| **Deferred** | 2026-07-15 |
| **Severity if left open** | P1 partner operability — batch partners drip through a single global write tier; LSP GETs mostly unmetered; no bulk create |
| **Why deferred** | Owner judgment: not a priority for the current pass. Existing Redis limiter already caps each LSP independently at the same global write tier (60/min). Differentiated per-LSP plans and bulk intake can wait until multi-partner SLAs or high-volume intake are real. |
| **Residual risk (accepted for now)** | One global write tier per `lspId` (not per-plan); no admin-editable `write_rpm`/`read_rpm`/`burst`/`bulk_rows_per_min`; no `POST …/loan-applications/bulk`; most LSP GETs unmetered; missing-`lspId` still fail-open on LSP-keyed rules. |
| **Prerequisite / related** | Decision D5 remains product intent. Idempotency lease/recovery (S4) already landed and would underpin bulk replay when resumed. Redis failure policy (F-ISO-01) stays orthogonal. |
| **Resume when** | Before onboarding a high-volume / batch partner; or when a second partner needs a different write/read SLA; or when unmetered LSP reads become an abuse concern. |
| **On resume** | Re-audit `RateLimitFilter`/`KeyStrategy`, `Lsp` admin CRUD, create+idempotency path, and `AuthPrincipalCache`; implement S16 as specified (or a narrowed global-tier + bulk-only variant if product still prefers that). Confirm next free Flyway version. |

### S19 residual — Drop access collection + normalizer/CHECK (after Slice A)

| | |
|---|---|
| **Canonical spec** | Production report §19.5 Spec S19 remainder; decision D8; F-S3 retirement |
| **Deferred** | 2026-07-15 (Slice A shipped same day) |
| **Severity if left open** | P2 maintainability — dual-write until cutover; F-S3 ElementCollection still present |
| **Why deferred** | Slice A delivers relationship metadata + dual-write without partner-visible churn. Dropping `borrower_lsp_access` / `visibleLspIds`, `BorrowerFieldNormalizer` + DB CHECKs, and generic profile-update audit wait for a second release after dual-read parity stays clean. |
| **Residual risk (accepted for now)** | RLS and visibility still authoritative on `borrower_lsp_access`. Relationship rows are dual-written on grant (`BorrowerLspRelationshipService` only). Divergence would log `borrower_lsp_visibility_parity_divergence` (directory detail). |
| **Prerequisite / related** | Slice A landed (V113). Money isolation still S5. |
| **Resume when** | After dual-read parity stays zero-divergence in UAT; before needing consent/channel as the sole visibility source of truth. |
| **On resume** | Switch visibility reads/RLS EXISTS to `borrower_lsp_relationship`; drop ElementCollection in a follow-up migration; add normalizer + CHECKs; optional profile-update audit. Confirm next free Flyway (V114+). |

### S18 / DATA-02 — Retention lifecycle and partitioning

| | |
|---|---|
| **Canonical spec** | Production report §19.5 Spec S18; aliases F-Q8, DATA-02, F-DB-02, F-AUD-01; decision D7 |
| **Deferred** | 2026-07-15 |
| **Severity if left open** | P1 data lifecycle — audit/webhook/token/report streams grow unboundedly; no partition/archive automation beyond idempotency 90d purge |
| **Why deferred** | Owner judgment: not a priority for the current pass (synthetic UAT / management review). Unbounded growth is unlikely to hurt soon at current volume; partition conversion and financial archive remain real-money / pilot ops gates. |
| **Residual risk (accepted for now)** | Only `IdempotencyRecordRetentionWorker` purges (90d). No `DataRetentionWorker`, purge manifests, legal-hold, report-object delete, or monthly partitioning on hot audit/auth/webhook tables. UUID-only PKs still block clean `PARTITION BY RANGE` without a redesign. |
| **Prerequisite / related** | Decision D7 schedule remains the target when resumed. Idempotency purge stays as-is. S5/S14/S16 are independently deferred. |
| **Resume when** | Before partner pilot scale, compliance retention audits, or when storage/query latency on append streams becomes observable; partition attach no later than real-money capacity planning. |
| **On resume** | Prefer Slice A first (webhook/token/report purges + manifests + legal_hold, disabled-by-default); treat partition conversion + 8y financial archive as a separate maintenance-window milestone. Confirm next free Flyway version. |

---

## Decisions deferred (not implementation specs)

| ID | Item | Reason | Spec when unblocked |
|---|---|---|---|
| **D4** | PII encryption at rest | Cloud/KMS platform not chosen | — (blocked on infra) |
| **D1b** | Overpayment / advance policy | Deliberately pending; S13 itself also deferred 2026-07-15 | S13 uses suspense interim rule when resumed |

---

## Completed (removed from active deferrals)

| Spec | Closed | Record |
|---|---|---|
| S3 / MNY-01 | 2026-07-13 | [Implementation log — S3](implementation-log.md) |
| S2 / NEW-01 | 2026-07-13 | Implementation log / report §19.6 |
| S1 | 2026-07-13 | Report §19.6 |
| S4 / IDEM-01 | 2026-07-13 | Implementation log |
| S7–S12 | 2026-07-15 | [Implementation log — S7–S12](implementation-log.md#s7s12-group-2026-07-15) (S5 remains deferred) |
| S20 / NEW-05 / SCH-01 | 2026-07-15 | [Implementation log — S20](implementation-log.md#s20--new-05--sch-01--partner-schedule-date-and-interest-validation-2026-07-15); partner contract [`partner-schedule-validation.md`](partner-schedule-validation.md) |
| S19 Slice A / D8 | 2026-07-15 | [Implementation log — S19](implementation-log.md#s19--d8--borrowerlsp-relationship-slice-a-2026-07-15); residual (drop access + normalizer) remains [active](#s19-residual--drop-access-collection--normalizercheck-after-slice-a) |
