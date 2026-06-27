# Independent E2E Review — Bhawana LMS
**Date:** 2026-06-14 · **Scope:** Re-test the platform from scratch, ignoring all prior matrix verdicts, and audit the Cursor-built test harness for hacky workarounds / shaky foundations.
**Method:** A purpose-built independent test client (`scripts/indep-e2e/`) that trusts nothing pre-existing — it provisions its own LSP/product/mapping/API-client, drives real APIs with strict assertions, and never writes back to the matrix. Plus Chrome DevTools UI drive, code reads, and DB/infra inspection.

> Independent checks run: **~70** explicit assertions across lifecycle, auth/RBAC, tenancy, validation, webhooks/SSRF, reports, audit, alerts, PII, payments, concurrency. **Net new bugs found that the matrix missed: 3.**

---

## TL;DR
- **The platform's core is genuinely solid.** Full origination → KYC → auto-approval → disbursement → 12-installment repayment → closure works end to end (22/22), with correct idempotency, tenancy isolation, RBAC, state-machine guards, validation, and SSRF protection — all independently reproduced.
- **3 real issues the matrix did NOT catch** (one of them a false "Pass" hiding a PII leak).
- **The Cursor harness is legitimate, not faked** — but it self-reports into the xlsx and has at least one incomplete security assertion. Several recorded status codes are stale.

---

## 🔴 Bugs found (missed by the existing matrix)

### B1 — Bank account number is exposed UNMASKED (HIGH, PII)
Aadhaar is masked everywhere; **bank account number is not**, despite a field literally named `bankAccountNumberMasked`.
- **Borrower-360 API** `GET /internal/admin/borrowers/{id}` → `"bankAccountNumberMasked":"952370303908"` (raw).
- **MIS report** (`/reports/portfolio-mis` CSV + preview) → "Bank Account Number" column raw.
- **INTAKE audit** event payload → `"bankAccountNumber":"952370303908"` raw.
- **Operator UI** → Borrower detail "Banking → ACCOUNT NUMBER 952370303908" rendered raw, no toggle. *(screenshot: `scripts/indep-e2e/evidence-borrower-bankacct-unmasked.png`)*
- **Root cause:** `BorrowerAdminController.java:139` passes `borrower.getBankAccountNumber()` straight into the `…Masked` slot; `maskAadhar()` exists, no `maskBankAccount()`. Same raw value flows via `BorrowerProfile.toIntakePayload` and `AdminReportingService`.
- **Contradicts** EC-067 ("bank_account masked as XXXX####", Gap #10) and the field name.
- **Fix:** add `maskBankAccount` (keep last 4) at all three surfaces; add a full-body PII scanner to tests.

### B2 — Processing fee is a reporting "fiction" (MEDIUM, correctness/finance)
MIS shows `processingFeeAmount: 2250.0` while `disbursalAmount: 150000.0` — the borrower receives the **full** principal; no fee is ever deducted, charged, or persisted.
- Confirmed in code: no `LoanFeeCalculator`, no `processing_fee_amount` column/migration; `LoanAccountRepository:104` returns `disbursedAmount = principalAmount`; `LoanDisbursementService:140` uses the fee only as an `allowedShortfall` tolerance.
- **ADR 0004** (status **Proposed**, 2026-06-08) explicitly calls this number "a fiction" and specifies the fix (deduct + persist, borrower gets net cash). **Still unimplemented.**
- **Risk:** auditors/finance will read the MIS fee as money actually charged. Repayment math itself is correct (reads requested principal).

### B3 — LSP validation endpoints return 500 on the failure branch (HIGH)
Two LSP-facing validation endpoints crash with `500 INTERNAL_SERVER_ERROR` *exactly when input is invalid* (the valid path returns cleanly):
- `POST /lsp/loan-applications/{id}/disbursement-bank-check` → **200 `{status:OK}` on match, 500 on ANY mismatch** (name or account). Should be `422 DISBURSEMENT_VALIDATION_FAILED`.
- `PUT /lsp/loan-applications/{id}/repayment-schedule` (mode `LSP_PROVIDED`) with a bad installment sum → **500** (GENERATED mode is fine). Should be `4xx REPAYMENT_SCHEDULE_INVALID`.
- **Shared root cause:** the failure branch performs an ops-side write inside the LSP tenant transaction — `recordHardDisbursementBankMismatch()` (writes `bank_mismatch_log` + ops alert) and `emitLspProvidedScheduleViolation()` (writes an ops alert). That write throws under the LSP tenant role, turning the intended structured 4xx into a 500. (Consistent with the known tenant-context write restrictions.)
- **Impact:** partners calling these pre-checks get an opaque 500 precisely when they need the structured validation reason. Found via the gap tests; not previously in the matrix. (Matrix rows EC-048 / EC-107.)

### H1 — Test-integrity: EC-067 "Pass" is a FALSE PASS (HIGH, for test trust)
`scripts/e2e/phases/phase9_data_adr.py:30` — the masking check only inspects fields whose **key matches `aadhaar|aadhar`**, then reports Pass. It never checks the bank account, even though EC-067 requires it. **This incomplete assertion is exactly what hid B1.**

---

## 🟡 Matrix accuracy discrepancies (platform is correct; the sheet is wrong/stale)
| Case | Matrix says | Reality (independently verified) |
|---|---|---|
| EC-021/022/023 | amount/tenure out of range → **400** | **422** with structured codes `AMOUNT_OUT_OF_RANGE` / `TENURE_OUT_OF_RANGE`, synchronous at intake (correct, arguably better) |
| EC-056 | channels = NEFT/RTGS/IMPS | enum also allows **UPI**; `UPI` payment → 200 (matrix spec is incomplete) |
| EC-091 | audit since>until → 400 | **422** (structured) |
| EC-026 | **Critical: 500** on duplicate PAN | **FIXED** → `409 BORROWER_IDENTITY_CONFLICT` (structured) |
| EC-061 | subscribe nonexistent.invalid → 422 | host validation is save-side; DNS failure only manifests at dispatch |

---

## ✅ Independently re-verified as WORKING (high confidence)
- **Lifecycle (22/22):** create → 8 KYC docs → auto-approval `APPROVED_PENDING_DISBURSAL` → loan account created *only* on approval (EC-104, none before) → disbursement → `UNDER_REPAYMENT` after payment #1 (EC-098) → `CLOSED` after #12 (EC-099). EMI ₹13,503.38; schedule total ₹162,040.58 on ₹150k @14.5%/12mo (reducing-balance, sane).
- **Idempotency:** replay same key+body → same id (EC-027); same key + different body → 409 (EC-027b); Idempotency-Key must be UUID v4; payment idempotent replay → 200 (EC-055).
- **Auth/RBAC:** wrong-pw & unknown-user → 401 identical body (no enumeration); no token → 401; tampered JWT → 401; malformed JSON → 400 no stack leak; LSP→admin 403 (EC-013); OPS→reports/audit 403 (EC-069/014); PRODUCT_ADMIN→lsps/users/audit 403, products 200 (EC-015); valid admin JWT → 200 (no #89 401-loop, EC-102).
- **Tenancy:** payload lspId≠token → 403 (EC-030); LSP-B reads LSP-A app → 404 (EC-012), own → 200; inactive LSP token → 401 (EC-009); secret-rotation grace old+new valid (EC-008).
- **Validation/auto-reject:** invalid PAN 400, missing fields 400, inactive product 422 `PRODUCT_NOT_ACTIVE`.
- **Webhook SSRF (EC-066):** 169.254.169.254 / localhost / 127.0.0.1 / 10.x all rejected 422; valid https → 200.
- **Reports:** MIS sync CSV (text/csv) + async request → COMPLETED → download 200; empty future range → 200 (no 500).
- **Audit:** explorer rows; bad stream 400; limit clamp 200; **aadhaar masked**; rejection reason persisted.
- **Alerts:** list; ack random → 404; note>500 → 400; ack → 200; double-ack → 409.
- **State guards:** payment on CLOSED → 422; CLOSED→DISBURSED → 422; disburse INITIALIZED → 422; foreclosure on INITIALIZED → 400; partial payment → 422; invalid channel → 400; double-pay → 409.
- **Document negatives:** text/plain → 422; bad type → 400; upload to random app → 404.
- **Concurrency (EC-109):** parallel disbursement-requests → [200,200], no 500, no double-disburse.
- **Frontend:** login routing (admin→/home); no console errors on home; filters + pagination boundaries; tenant scope "Internal · All LSPs"; loan-detail masks correctly.

---

## ⚙️ Environment / Ops findings
- **`/actuator/health` returns DOWN (503) and blocks ~18.7s** while `liveness` & `readiness` are UP. The `db` indicator (not in either probe group) times out acquiring a connection from the saturated Supabase session pool — the harness even documents this (`_common.py:37`). All local infra (redis/rabbit/minio/mailhog) is up; not the cause.
  - **Recommend:** point LB/k8s health at `/actuator/health/readiness`; investigate the DB connection-pool pressure (ties to the known tenant-pool/pooler-cap issue) before launch volumes.
- **Rate limits active:** `/auth/login` & `/auth/token` = 10/min per IP (default). Easily tripped during test bursts.

## 🧪 Cursor test-harness audit (verdict)
**Not fabricated / not gamed.** Fixtures (`fixtures.py`) and phase runners hit live endpoints and assert real outcomes (`raise_for_status`, status polling). Caveats:
1. **H1** incomplete masking assertion (above) — the one that matters.
2. The xlsx is **self-written** by the same harness (`_common.update_matrix`) → "previous results" are self-reported, not independent.
3. **Missing sample PDFs** (`postman/assets/sample-pan.pdf`, `blank.pdf`) → doc-upload fixtures can't run as-is today (FileNotFoundError).
4. `lsp_token_for_fixture` auto-rotates the client secret on any 401 — convenient but could mask a real auth failure in happy-path helpers.
5. `apply_tenant_role_grant.py` hand-applies a grant, but it's also a proper migration (V96) → foundation sound, script redundant.

---

## 📋 Recommended additional business-logic tests (gaps still open)
Beyond fixing B1/B2 and the assertion in H1, these were *not* exercisable in this pass and deserve coverage:
1. **Processing-fee deduction (once ADR 0004 ships):** assert `disbursalAmount == principal − fee`, persisted `processing_fee_amount`, LSP disbursement response + `LOAN_DISBURSED` webhook carry `netDisbursedAmount`, and disbursement reversal zeroes the fee.
2. **Webhook DELIVERY outcomes (EC-061/062/063/065):** stand up a *public* HTTPS receiver (SSRF guard blocks localhost) and assert DELIVERED on 2xx, RETRYABLE on 5xx, PERMANENT/dead-letter on 4xx, `X-Webhook-Signature` HMAC correctness, and redrive.
3. **Disbursement retry exhaustion (EC-049):** mock RETRYABLE_FAILURE × max → `DISBURSEMENT_RETRY` + `DISBURSEMENT_RETRY_EXHAUSTED` ops alert.
4. **Schedule validation (EC-107/108):** manual schedule whose installments ≠ principal+interest → 400 `ScheduleViolationType`; re-submit on DISBURSED loan → locked/400.
5. **Foreclosure path (EC-031/032/059/060):** quote → execute → terminal FORECLOSED; expired-quote execute → 4xx; execute on CLOSED → 4xx; FORECLOSED→anything illegal.
6. **Invalidation (UC-023, EC-036/037/038):** pre-disbursal invalidate → INVALID cascade + webhook; invalidate after disbursal → 4xx; invalidation idempotency.
7. **Bank-mismatch & holder-name fuzzy match (EC-048/106):** alter holder name/account before disbursement → mismatch log + alert; verify the fuzzy matcher's tolerance bounds.
8. **Document access audit (EC-095):** every KYC download writes a `LoanApplicationDocumentAccessAudit` row; cross-tenant download → 404 + audited (EC-044).
9. **DPD / delinquency bucketing:** let an installment go overdue (back-date) and assert DPD buckets on dashboard + borrower aggregate + MIS `delinquencyBucket`.
10. **IP allowlist enforcement (EC-010/011):** with enforcement on, calls from non-allowlisted IP (X-Forwarded-For) → 403 + ops alert.
11. **Money/rounding edge cases:** principal/tenure combos that don't divide evenly — verify last-installment rounding so Σ installments == principal+interest exactly (no ±0.01 drift).
12. **Brute-force lockout (EC-003):** N failed logins → lockout window + alert + unlock path (PR #196).
13. **Full-body PII scanner** as a standing test: regex the entire JSON/CSV of every borrower/report/audit response for 12-digit (aadhaar) AND 9–18-digit account patterns — would have caught B1.

---

## Gap tests executed & written into `e2e-test-matrix.xlsx` (2026-06-14)
The recommended additional tests were run and recorded (existing rows updated, new rows EC-112/113/114 added):
- **Pass:** UC-009 (IP allowlist CRUD), UC-020 (doc download), UC-023 (invalidate→INVALID), UC-031 (foreclosure quote), UC-032 (execute→FORECLOSED), UC-042 (webhook DELIVERED via httpbin), EC-010 (IP allowlist 403/200), EC-036 (INVALID transitions blocked), EC-037 (invalidate-after-disburse 4xx), EC-038 (invalidation idempotency), EC-044/045 (doc 404s), EC-049 (retry→DISBURSEMENT_RETRY), EC-058 (foreclosure on INITIALIZED 4xx), EC-060 (re-execute foreclosure 4xx), EC-061 (unresolvable host blocked save-side), EC-062 (5xx→RETRYABLE), EC-063 (hard-4xx→PERMANENT), EC-095 (doc-access audit), EC-105 (webhook ordering), EC-108 (schedule locked post-disburse), EC-003 (brute-force blocked, caveat).
- **Fail:** EC-048 + EC-107 (**B3** 500 on validation failure), EC-067 (**B1** false-pass corrected), EC-103 (**B2** processing-fee fiction), EC-112 + EC-113 (**B1** bank-acct unmasked), EC-106 (blocked by B3).
- **Blocked:** EC-114 (DPD bucketing — needs an overdue installment / business-clock override).

*Artifacts:* independent suite in `scripts/indep-e2e/` (`client.py`, `lifecycle.py`, `auth_rbac.py`, `reports_webhooks_pii.py`, `business_logic.py`, `pay_negatives.py`), raw findings in `scripts/indep-e2e/FINDINGS.md`, UI evidence screenshot alongside.
