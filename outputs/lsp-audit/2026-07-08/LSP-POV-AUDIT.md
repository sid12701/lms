# LSP Point-of-View Audit — Bhawana LMS

**Date:** 2026-07-08
**Deployment context:** **permanent, production, multi-LSP platform processing high loan volume and real money movement** (not a time-boxed pilot). Findings are weighted for that lens: operational rigidity, money-movement integrity, PII-at-scale, credential security, and reconciliation carry more weight than they would for a short pilot.
**Scope:** Every API provisioned to LSPs + the LSP-facing UI, exercised as an LSP integration partner and as an LSP UI user.
**Environment:** live backend `localhost:8080` (Supabase-backed), frontend `localhost:5173`.
**Method:** Admin-provisioned fixtures (2 LSPs for isolation, API clients, UI users, products, mappings); a 50-case API matrix + 13-step lifecycle driver; browser walkthrough via Chrome DevTools; every finding traced to source.

Harness + evidence: `scripts/lsp-audit/` (`harness.py`, `matrix.py`, `lifecycle.py`, `results.json`, `lifecycle_results.json`, `fixtures.json`, `evidence-internal-guard.png`).

---

## 1. End-to-end LSP journey map

```
                    ┌─────────────────── API-only origination (no UI create form) ───────────────────┐
POST /auth/token → POST /loan-applications → POST .../documents(+batch) → [STP gate fires on last     
(client creds)      (borrower auto-created)   (8 KYC docs incl. KFS)       required doc]                
                                                                              │                        
                                                    LoanAutoApprovalRuleEngine │ evaluate               
                                                                              ▼                        
                          INITIALIZED ──> AWAITING_APPROVAL ──> APPROVED_PENDING_DISBURSAL  (or REJECTED)
                                                                              │                        
                            ┌──────────── ADMIN/OPS only (LSP has no disbursement API) ───────────────┐
                            │  POST /internal/ops/.../disbursement-requests → mock-outcome DISBURSED   │
                            └──────────────────────────────────┬──────────────────────────────────────┘
                                                               ▼                                       
   LSP servicing APIs:  GET /lsp/loans/{id} · /repayment-schedule · POST /payments · foreclosure-quote/execute
   LSP borrower APIs:   GET/PATCH /lsp/borrowers/{id}/bank-details (unmasked, audited)                 
   LSP visibility:      GET /lsp/loan-applications (list/detail/by-external-id), /documents, webhooks (push)
```

**UI journey** (roles `LSP_UI_READ` / `LSP_UI_WRITE`): login (email) → forced password change on first login → `/home` (single shortcut card) → `/my-loans` (list) → `/my-loans/:id` (detail: terms, contact, identity, documents, loan-account summary). Write users additionally get **Upload document** and **Mark invalid**. There is **no** UI to create loans, record payments, foreclose, view repayment schedule/payment history, or manage credentials/webhooks — all of that is API-only or admin-only.

---

## 2. Inventory of LSP surface tested

### LSP APIs (all under `/api/v1/lsp/**`, JWT with `lspId` claim, roles `LSP_API_CLIENT` for writes / `LSP_UI_*` for reads)

| Endpoint | Method | Purpose | Roles |
|---|---|---|---|
| `/auth/token` | POST | client-credentials → JWT | (public) |
| `/lsp/products` | GET | provisioned product catalogue | API_CLIENT, UI_* |
| `/lsp/loan-applications` | GET/POST | list (filter/paginate) / create | GET all · POST API_CLIENT |
| `/lsp/loan-applications/{id}` | GET | detail | all |
| `/lsp/loan-applications/external/{extId}` | GET | detail by `lspLoanId` | all |
| `/lsp/loan-applications/{id}/invalid` | POST | mark invalid | API_CLIENT, UI_WRITE |
| `/lsp/loan-applications/invalid-reasons` | GET | reason vocabulary | all |
| `/lsp/loan-applications/{id}/documents` | GET/POST | list / submit metadata / upload (multipart) | GET all · POST API_CLIENT, UI_WRITE |
| `/lsp/loan-applications/{id}/documents/batch` | POST | multi-doc upload | API_CLIENT, UI_WRITE |
| `/lsp/loan-applications/{id}/repayment-schedule` | PUT | upsert GENERATED / LSP_PROVIDED | API_CLIENT |
| `/lsp/loan-applications/{id}/disbursement-bank-check` | POST | preflight bank validation | API_CLIENT |
| `/lsp/loans/{id}` | GET | loan account detail | all |
| `/lsp/loans/{id}/repayment-schedule` | GET | live schedule | all |
| `/lsp/loans/{id}/payments` | GET/POST | list / record repayment | GET all · POST API_CLIENT |
| `/lsp/loans/{id}/foreclosure-quote` | POST | request quote | API_CLIENT, UI_WRITE |
| `/lsp/loans/{id}/foreclosure-quotes/{qid}/execute` | POST | execute foreclosure | API_CLIENT, UI_WRITE |
| `/lsp/borrowers/{id}/bank-details` | GET/PATCH | read (unmasked, audited) / update | GET all · PATCH API_CLIENT |

### LSP UI capabilities
`/home` (shortcut card) · `/my-loans` (paginated list, status badges) · `/my-loans/:id` (detail, document upload per-type, mark-invalid). Read users: view-only. Write users: upload + mark-invalid on non-terminal loans.

### Not available to LSPs (admin/ops-only)
Disbursement initiation/retry/status-check, manual status override, webhook subscription config, API-client creation/rotation, IP-allowlist config, product/LSP/user management, MIS/reports, audit explorer.

---

## 3. Test results (highlights; full data in `results.json` / `lifecycle_results.json`)

- **API matrix: 50 cases, 45 pass.** The 5 "fails" are test-harness artifacts, each independently re-verified as correct behaviour: AUTH-04/IDEM-01/IDEM-02 hit rate-limits during rapid fire (re-run clean: 400 / 200-replay / 409-conflict); INV-02 mis-picked a reason (clean re-run: 200); BANK-01 used deliberately mismatched bank data (422 is the correct compliance rejection).
- **Lifecycle: 13 steps, full happy path green** — create → batch KYC → **STP auto-approval** → admin disbursement → mock DISBURSED → loan detail → schedule → repayment → foreclosure quote → execute → CLOSED, plus the terminal-state guard (`INVALIDATION_NOT_ALLOWED` on a serviced loan).
- **Tenant isolation: 6/6 pass.** LSP B could not read, list, invalidate, upload-to, pay, or read-bank-details on any LSP A resource — every attempt returned 404/403. List endpoints exclude the other tenant entirely.
- **Idempotency:** create replay (same key+body → same id, 200); key reuse with different body → `409 IDEMPOTENCY_CONFLICT`; payment path replays via stored txn (code-verified `LoanRepaymentCommandService`).
- **Contract quality:** structured error envelope with `code`, `message`, `correlationId`, field `violations`; clear codes (`PRODUCT_NOT_MAPPED`, `DUPLICATE_EXTERNAL_LOAN_ID`, `AMOUNT_OUT_OF_RANGE`, `VALIDATION_FAILED`); `@StrictJson` rejects unknown fields (`INVALID_REQUEST`); pagination via `X-Total-Count`/`X-Limit`/`X-Offset` headers; UUID/format validation returns 400 with readable messages.
- **Role boundaries:** LSP API client → admin/ops endpoints = 403 (server-side); LSP UI user → internal routes = clear "Internal workspace only" screen (client + server enforced).

---

## 4. Prioritized findings

### Confirmed defects

**F1 — [HIGH for UI-driven partners] KFS is unreachable and invisible in the LSP UI.**
The backend requires **KFS** as a disbursement-required document (`LoanApplicationDocumentType.KFS("KFS", false, true)`, and `LoanApplicationDocumentRequirements.isIntakeRequired == isRequiredForDisbursement`), and `LoanAutoApprovalRuleEngine.evaluateDocumentChecklist` blocks approval until every intake-required doc is complete. The frontend document map omits KFS entirely:
- `frontend/src/features/my-loans/components/DocumentsSection.tsx` → `LSP_REQUIRED_DOC_TYPES` lists 7 types, no KFS.
- `frontend/src/features/my-loans/api.ts` → `FE_TO_BE_DOCUMENT_TYPE` has no KFS entry, so `BE_TO_FE_DOCUMENT_TYPE` can't map it back.
**Impact:** (a) KFS cannot be uploaded through the UI, so a loan whose documents are managed via the UI can **never** complete KYC or auto-approve — it stalls in INITIALIZED with no error. (b) A KFS uploaded via API is silently dropped from the UI checklist and "Other documents" (verified live: a loan with 8 API-uploaded docs shows only 7 in the UI).
**Root cause:** UI document taxonomy drifted from the backend enum; KFS was added backend-side without a UI slot.
**Fix:** add `KFS: "KFS"` to `FE_TO_BE_DOCUMENT_TYPE`/`LSP_DOCUMENT_TYPES`/`LSP_DOC_LABELS` and to `LSP_REQUIRED_DOC_TYPES`. Small, contained change.

**F2 — [MEDIUM] Loans created with valid-at-create-time but rule-incomplete borrower data silently stall with no feedback.**
`LspLoanApplicationRequest` (create) treats address, city, state, zip, and reference-person fields as **optional** (`@Size` only), and income as "monthly OR annual". But `LoanAutoApprovalRuleEngine.evaluateBorrowerFields` **requires** `addressLine1`, `city`, `state`, `addressZipCode`, `monthlyIncome > 0`, `referencePersonName`, `referencePersonNumber`. A create that omits any of these returns **200**, uploads succeed, the STP gate fires — and `autoApproveIfEligibleForLsp` returns the application **unchanged** (it only auto-rejects from `AWAITING_APPROVAL`, not `INITIALIZED`), so the loan sits in INITIALIZED indefinitely with no rejection, no reason, no signal to the LSP. Observed directly: my first STP payload omitted address/reference and the loan never left INITIALIZED.
**Impact:** integration reliability — partners will file loans that can never progress and have no API/UI way to learn why.
**Fix (pick one):** (a) enforce the rule-engine-required fields at create time so the failure is a clear 400 up front; or (b) surface a "pending requirements / missing fields" reason on the application (extend `lastActivity` or a `blockedReasons` field) so the LSP can self-diagnose.

**F3 — [MEDIUM, needs stakeholder confirmation] PAN is returned to the LSP unmasked while Aadhaar is masked.**
The LSP detail response returns the raw PAN; `frontend/.../my-loans/api.ts backendToDetail` maps `borrowerPanMasked: payload.panNumber` (misleading name) and the UI renders it in full (verified: `CHRHB0251M`), while Aadhaar shows `XXXXXXXX5149` and the card copy claims "Aadhaar is shown masked." Either PAN masking was intended and is missing, or PAN exposure is deliberate (the LSP originated the loan and supplied the PAN). Flagging for a product decision, not asserting a leak. If intentional, fix the field name and the card copy; if not, apply masking consistent with the Aadhaar treatment.

### Usability / DX gaps (mostly product decisions)

**F4 — Invalidation reasons are placeholder labels.** `LoanInvalidationReason` exposes `Reason A / Reason B / Reason C / Others` to partners over the API and in the Mark-invalid dropdown (verified in UI). These are meaningless for real operations and audit. Replace with a real vocabulary (e.g. DUPLICATE, KYC_MISMATCH, BORROWER_WITHDREW, FRAUD_SUSPECTED, DATA_ENTRY_ERROR).

**F5 — LSP UI omits servicing detail the API already exposes.** The detail page shows only a one-line schedule summary; there is no installment-by-installment schedule, no payment history, no delinquency detail, no foreclosure/settlement figures, and no disbursement status/timeline. `GET /lsp/loans/{id}/repayment-schedule`, `/payments`, the delinquency block, and the application's `lastActivity` all exist server-side but are not rendered (the frontend `backendToDetail` doesn't even map `lastActivity`). A partner using the UI cannot see whether/when money moved or what was repaid.

**F6 — No LSP operational self-service.** Webhook subscription (`LspAdminController`), API-client creation/secret rotation (`ApiClientAdminController`), and IP-allowlist config are all `SYSTEM_ADMIN`-only. An LSP cannot rotate its own compromised secret, point its own webhook, or manage its allowlist without filing an admin request. Acceptable short-term; a real integration friction long-term.

**F7 — LSP list UI has no search/filter.** The list API supports `q`, `status`, `productId`, `sourceChannel`; the UI exposes only rows-per-page. At volume this becomes unusable.

**F8 — Minor:** forced-password-change completes but the session drops to `/login` on reload (re-login works); the Documents "read-only for your account" copy is state-based (terminal loan) not permission-based and reads as a permissions message.

### Non-issues confirmed (do not action)
- Tenant isolation is airtight across all LSP endpoints (6/6).
- Idempotency (create + payment), StrictJson, pagination headers, error envelope, UUID validation, product-mapping enforcement, amount/tenure range checks, duplicate-external-id dedupe, terminal-state guards — all correct.
- Rate limits (`lsp-write` 60/min per LSP, `auth-token` 10/min per IP) work as designed; the disbursement bank-check 422 on mismatched data is correct compliance behaviour.

---

## 4a. Scale, permanence & money-movement findings

These are weighted for a permanent, multi-LSP, high-volume, real-money platform. They are what the "6-month pilot" framing under-weighted.

**F9 — [HIGH] Repayment accepts only an exact, single-installment amount. No partial / over / lump-sum / prepayment.**
`LoanServicingSupportService.validateExactInstallmentAmount` rejects any payment whose amount `!=` the targeted installment's outstanding, with `PAYMENT_AMOUNT_MISMATCH`; `applyFullInstallmentPayment` then asserts the installment is fully settled. `POST /lsp/loans/{id}/payments` targets exactly one `targetInstallmentId` and must pay its exact due. Consequence for real collections at volume: an LSP **cannot** record a partial payment (₹5,000 against a ₹9,073 EMI), an overpayment/round-off, a single lump-sum covering several EMIs, or a prepayment of a differing amount. The only non-exact path to closure is full foreclosure. Real borrowers routinely pay partial, late-with-different-amount, or bunched EMIs — so at scale partners either can't represent actual cash received through the API, or must pre-split every receipt into exact-EMI slices themselves (and can't at all when the borrower simply underpays). This is the single biggest operational-flexibility gap for a money platform.
**Root cause:** the servicing model equates "payment" with "settle exactly one installment"; there is no allocation/waterfall engine for arbitrary amounts.
**Recommendation:** support arbitrary payment amounts with server-side allocation (oldest-due-first waterfall), partial-payment state on installments, and explicit handling of overpayment (advance/credit) — this is core lending functionality for permanence, not an edge case. Scope it deliberately; it is the largest item here.

**F10 — [MEDIUM-HIGH] No LSP-facing reconciliation / MIS / bulk-export API.**
All portfolio reporting lives under `/api/v1/internal/reports/**` (`ReportAdminController`, `SYSTEM_ADMIN`-only). An LSP has no programmatic way to pull a day's disbursements, repayments, settlements, or outstanding book for its own portfolio — it must stitch state per-loan via `GET /lsp/loans/{id}`. For a partner moving real money at volume this makes daily financial reconciliation impractical and pushes every LSP onto admin-generated exports forever.
**Recommendation:** an LSP-scoped reconciliation endpoint (date-ranged disbursements/repayments/settlements + outstanding snapshot), paginated, tenant-scoped like the rest of `/lsp/**`.

**F11 — [MEDIUM] Disbursement is entirely admin/ops-driven — a central bottleneck and an LSP visibility gap.**
No LSP endpoint initiates, retries, or reads disbursement-attempt detail; every disbursement across every LSP funnels through internal ops (`/internal/ops/.../disbursement-requests`, `SYSTEM_ADMIN`). The LSP sees only `loanAccount.status` (e.g. `DISBURSEMENT_FAILED`) with no reason or leg-level detail. At multi-LSP volume this is an operational choke point and leaves partners unable to see *why* money didn't move. Ties into the pending ICICI integration; resolve the LSP-visibility half (failure reason + disbursement/settlement facts on the loan) alongside it.

**Money-safety strengths confirmed (keep):**
- Payment idempotency is DB-enforced: unique constraint `uk_loan_payment_transaction_idempotency_key` (V56 → hardened to a table constraint in V92), with a recovery path (`recordPaymentTransactionWithRecovery`) that catches the integrity/optimistic-lock violation and returns the existing row. This is correct and **safe across horizontally-scaled instances** — the in-process `synchronized(key.intern())` is only a fast path, the DB constraint is the real guard. Idempotency-Key is *mandatory* for payments (`requireIdempotencyKey`, must be a v4 UUID).
- Money is `BigDecimal` with fixed currency scaling throughout; installment allocation is asserted post-apply.
- Cross-tenant isolation held on every money path (record payment, bank details, foreclosure) — 404/403.

**Scale watch-items (verify/plan, not defects):**
- **Rate limits vs whale LSPs:** `lsp-write` 60/min *per LSP* caps bursty bulk onboarding (a partner filing thousands of loans/day will throttle). Make it per-LSP tunable and document it; onboarding tooling must respect it and back off.
- **Idempotency retention:** create-side idempotency records purge at 90 days (`app.idempotency.retention-days`); a partner replaying a key after the window gets a fresh execution, not a replay. Fine if documented; surprising if not.
- **Tenant isolation depth:** every `/lsp/**` read is scoped in application code (`getApplicationForLsp` etc.), which is why isolation tested clean. For a permanent money platform, confirm the DB-level RLS layer (V41) actually backstops *every* LSP query path as defence-in-depth — a single query that forgets the `lspId` filter is a cross-LSP data/money leak, and app-layer scoping alone is one refactor away from a gap.

---

## 5. DX / usability recommendations (materially useful, not over-engineered)

1. **Ship F1 and F2** — these are the only two items that make loans get *stuck*; both are small, high-value fixes and are the difference between "reliable" and "silently drops loans."
2. **Publish a partner-facing OpenAPI subset** for `/api/v1/lsp/**` only (the current doc is gated to authenticated users and mixes the full internal surface). Include auth flow, the `Idempotency-Key` contract, error codes, pagination headers, and the **documented rate limits** (partners doing bulk onboarding will hit 60/min blind).
3. **Give the LSP a real reason vocabulary (F4)** and surface `lastActivity` + delinquency/schedule/payments in the detail page (F5) so the UI answers "what's pending, what failed, what to do."
4. **Add self-service secret rotation + webhook management** (F6) before any partner goes fully live; secret rotation without an admin ticket is a basic security expectation.
5. **Confirm the PAN masking decision (F3)** and align the field name + UI copy either way.

---

## 6. Readiness assessment (permanent, multi-LSP, high-volume, money-moving)

**What is genuinely solid and can carry permanent load.** Tenant isolation (airtight, 6/6), idempotency including **DB-enforced, horizontally-safe payment idempotency**, `BigDecimal` money handling, the error/pagination/StrictJson contracts, product-mapping and range enforcement, and terminal-state guards all behaved correctly under normal, invalid, unauthorized, duplicate, missing-data, retry, and cross-tenant scenarios. The core money-movement *integrity* primitives are trustworthy. This is a good foundation.

**Blocking before onboarding real partners:**
- **F2 — silent stall on rule-incomplete data.** Partners will file loans that can never progress, with zero diagnosability. Highest reliability risk. Small fix.
- **F1 — KFS missing from the UI document taxonomy.** Blocks UI-driven document management outright; trivial fix; no reason to ship without it.

**Now hard requirements (not "deferrable") because the deployment is permanent and money-bearing — schedule them, don't hand-wave:**
- **F9 — exact-installment-only repayment.** A permanent lender that cannot record partial/over/lump-sum/prepayments cannot represent real borrower cash flows. This is core lending capability, not polish. Largest build item; plan it early.
- **F10 — no LSP reconciliation/MIS API.** Partners moving real money need programmatic daily reconciliation of their own book; per-loan stitching does not scale and admin-only reports don't serve partners permanently.
- **F6 — LSP self-service for API-client secret rotation / webhooks / IP allowlist.** Admin-brokered secret rotation is an acceptable *pilot* stopgap; permanently it is a standing security liability (a partner cannot rotate a compromised secret without a ticket) and won't scale across a growing LSP roster.
- **F3 — PAN returned unmasked to the LSP.** A permanent, high-volume PII exposure needs an explicit, documented data-handling decision aligned to your DPA/regulatory posture — it cannot stay an open ambiguity. (Also verify the RLS defence-in-depth watch-item under F4a.)
- **F11 — LSP disbursement visibility + the admin-only disbursement bottleneck.** Resolve the visibility half (failure reason + disbursement/settlement facts on the loan) alongside the real ICICI integration; the central-ops funnel needs a throughput plan for multi-LSP volume.

**Genuinely follow-on (quality, not risk):** F4 (real invalidation reason vocabulary — do it soon; it pollutes every audit/report), F5 (richer servicing UI: schedule/payments/settlement), F7 (list search/filter), F8 (cosmetics). Scale watch-items (per-LSP rate-limit tuning, idempotency retention window, RLS backstop verification) should be validated but are not defects.

**Bottom line.** The platform's *integrity* core (isolation, idempotency, money handling) is production-grade and safe to build on permanently. But it is **not yet ready to be the permanent system of record for multiple LSPs moving real money** until the repayment engine handles real-world payment amounts (F9), partners can reconcile their own book (F10) and manage their own credentials/webhooks (F6), the PAN-exposure decision is made and documented (F3), and the two stall bugs (F1/F2) are fixed. F1/F2 are days of work; F9/F10/F6 are the substantive builds that separate "clean pilot" from "durable multi-tenant lending platform." Sequence them before broad onboarding, not after.
