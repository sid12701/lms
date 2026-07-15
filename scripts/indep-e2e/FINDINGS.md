# Independent E2E — Findings Log (re-run, ignoring prior matrix verdicts)

Tester: Claude (independent harness in `scripts/indep-e2e/`, own contracts, strict asserts).
Backend: localhost:8080 (Supabase-backed). Frontend: localhost:5173 (Vite).

## BUGS / DEFECTS

### B1 [HIGH] Bank account number is NOT masked (aadhaar is) — multiple surfaces
> **Resolved 2026-07-02** (API consistency pass): `BankAccountMasking` (`XXXXXXXX<last4>`) now applied to
> borrower-360 `bankAccountNumberMasked`, LSP loan-application responses, bank-mismatch ops alerts, and the
> `BORROWER_BANK_DETAILS_UPDATED` webhook payload. Full value remains only on the dedicated bank-details
> endpoints and the payment rail. MIS CSV "Bank Account Number" column stays raw **pending a compliance
> decision** (see `docs/api-consistency-backlog.md`); intake-audit payload still masks aadhaar only.
> EC-112/EC-113 in `gap_tests.py` updated to verify the fix dynamically. Original finding kept below.
- **Borrower 360** `GET /api/v1/internal/admin/borrowers/{id}`: response field is literally named
  `bankAccountNumberMasked` but returns the **raw 12-digit account number** (e.g. `952370303908`).
  Aadhaar in the same response IS masked (`XXXXXXXX7735`).
- **MIS report** (`/reports/portfolio-mis` CSV + `/preview`): "Bank Account Number" column is raw; aadhaar masked.
- **Audit INTAKE** events: payload carries `"bankAccountNumber":"952370303908"` raw; aadhaar masked.
- **Root cause:** `BorrowerAdminController.java:139` passes `borrower.getBankAccountNumber()` straight into the
  `bankAccountNumberMasked` slot; `maskAadhar()` exists (line 124/190) but there is no `maskBankAccount()`.
  Same raw value flows via `BorrowerProfile.toIntakePayload` (line 185) and `AdminReportingService`/`ReportAdminController:311`.
- **Contradicts:** EC-067 ("bank_account masked as XXXX####", Gap #10) and the field's own name.
- **Matrix gap:** EC-067/EC-073 were marked Pass because the prior tests only scanned for unmasked *aadhaar*,
  never bank account. This is exactly a "missed in the sheet" defect.
- **UI confirmation:** Borrower detail page `/borrowers/{id}` renders the raw account under "Banking →
  ACCOUNT NUMBER 952370303908" (no sensitive-data toggle gating it). Loan-detail page (ops endpoint) masks
  correctly. Evidence screenshot: `evidence-borrower-bankacct-unmasked.png`.

### B2 [MEDIUM] Processing fee is a reporting "fiction" — ADR 0004 unimplemented
- MIS row shows `processingFeeAmount: 2250.0` while `disbursalAmount: 150000.0` (full principal). Borrower
  receives the full principal; no fee is deducted, charged, or persisted.
- Confirmed in code: no `LoanFeeCalculator`, no `processing_fee_amount` column (no migration),
  `LoanAccountRepository:104` returns `disbursedAmount = principalAmount`. `LoanDisbursementService:140` only
  uses the fee as an `allowedShortfall` tolerance, not a deduction.
- ADR 0004 (status **Proposed**, 2026-06-08) explicitly calls this figure "a fiction" and decides to fix it
  (Model 1: deduct + persist). Still unimplemented. EC-103 was Blocked in the matrix — now characterised.
- **Risk:** MIS/audit present a fee that corresponds to no cash flow; finance/auditors will read it as charged.

## MATRIX ACCURACY DISCREPANCIES (platform behaves correctly; sheet is wrong/stale)

- **EC-021/022/023** (amount/tenure out of range): real response is **422** with structured codes
  `AMOUNT_OUT_OF_RANGE` / `TENURE_OUT_OF_RANGE`, **synchronous at intake** (not async rule-engine reject).
  Matrix recorded "→ 400". Behaviour is correct/better; sheet's status code is wrong.
- **EC-056** (payment channel): `LoanPaymentChannel` enum = NEFT, RTGS, IMPS, **UPI**. The matrix expected
  behaviour lists only NEFT/RTGS/IMPS. UPI is valid (interactive `lsp-api-client.py` using UPI is correct).
- **EC-091** (audit since>until): real response **422** (structured), matrix expected 400. Not a bug.
- **EC-061** (`https://nonexistent.invalid`): saved-side accepted/validated; matrix's claim of behaviour is
  ambiguous — DNS failure only manifests at dispatch, not save. (Got 422 on save in this run — see open item.)

## CONFIRMED-WORKING (independently re-verified, real assertions)

- Full lifecycle 22/22: origination → 8 docs → auto-approval (APPROVED_PENDING_DISBURSAL) → loan account
  created only on approval (EC-104) → disbursement (mock) → 12 payments → UNDER_REPAYMENT after #1 (EC-098)
  → CLOSED after #12 (EC-099). EMI ₹13,503.38; schedule total ₹162,040.58 on ₹150k @14.5%/12mo.
- Idempotency: replay same key+body → same id (EC-027); same key+different body → 409 (EC-027b).
  Idempotency-Key must be UUID v4 (enforced).
- Transition guards: payment on CLOSED → 422 (EC-057); CLOSED→DISBURSED → 422 (EC-034).
- Auth/RBAC: wrong pw/unknown user → 401 with identical body (no enumeration, EC-001/002); no token → 401;
  tampered JWT → 401; malformed JSON → 400 no stack leak; LSP_API_CLIENT→admin 403 (EC-013);
  OPS_USER→reports/audit 403 (EC-069/014); PRODUCT_ADMIN→lsps/users/audit 403, products 200 (EC-015);
  admin endpoints with valid JWT 200 (no #89 401-loop, EC-102).
- Tenancy: payload lspId≠token → 403 (EC-030); LSP B reads LSP A app → 404 (EC-012), own → 200.
- Validation auto-reject: invalid PAN 400 (EC-029); missing fields 400 (EC-028); inactive product 422
  PRODUCT_NOT_ACTIVE (EC-018); inactive LSP token 401 (EC-009); secret rotation grace old+new valid (EC-008).
- Webhook SSRF: 169.254.169.254 / localhost / 127.0.0.1 / 10.x all rejected 422 (EC-066). Valid https → 200.
- Reports: MIS sync CSV (text/csv), async request → COMPLETED → download 200 (UC-037/038); future range 200 no 500 (EC-070).
- Audit: explorer 200 with rows; bad stream 400 (EC-093); limit=99999 clamped 200 (EC-092). Aadhaar masked.
- Alerts: list 200; ack random → 404 (EC-077); note>500 → 400 (EC-078); ack 200 (UC-039); double-ack 409 (EC-079).
- Home dashboard overview 200 with full KPI set (UC-035).
- **EC-026 one-open-loan rule FIXED**: duplicate open-loan PAN → `409 BORROWER_IDENTITY_CONFLICT`
  (structured), NOT the 500 the matrix flagged as Critical. PAN dedup links borrower (EC-101).
- Document negatives: text/plain → 422 (EC-040); bad documentType → 400 (EC-042); upload to random app → 404
  (EC-043). Disburse INITIALIZED → 422 (EC-046); foreclosure quote on INITIALIZED → 400 (EC-058).
- Transition guards: REJECTED w/o reasonCode → 422 (EC-039); REJECTED→APPROVED → 422 (EC-033); rejection
  reason persisted in transition row (EC-100, reasonCode=FAILED_VERIFICATION).
- Payment negatives: missing Idempotency-Key → 400 (EC-051); missing targetInstallmentId → 400 (EC-052);
  partial amount → 422 (EC-053); invalid channel → 400 (EC-056); **UPI channel valid → 200 (EC-056b)**;
  idempotent replay → 200 (EC-055); double-pay installment → 409 (EC-054).
- Concurrency: two parallel disbursement-requests → [200,200], no 500, final status unchanged
  (APPROVED_PENDING_DISBURSAL — neither double-disburses). Endpoint is idempotent-tolerant (EC-109).

## ENVIRONMENT / OPS

- **/actuator/health returns DOWN** while liveness+readiness are UP. The `db` indicator (not in either probe
  group) times out — Supabase session-pool saturation by Hikari pools (the harness `_common.py:37` even notes
  health "blocks ~30s when the DB pool is exhausted"). A LB/k8s pointed at `/actuator/health` (not `/readiness`)
  would mark the instance unhealthy. **Recommend** LBs use `/actuator/health/readiness`.
- `/auth/login` & `/auth/token` rate limit = 10/min per IP (default). Active and easily tripped.

## TEST-HARNESS (Cursor) AUDIT NOTES

- Sample PDFs referenced by fixtures (`postman/assets/sample-pan.pdf`) and `lsp-api-client.py` (`blank.pdf`)
  **do not exist** → those doc-upload fixtures can't run as-is now (would FileNotFoundError).
- `_common.lsp_token_for_fixture` auto-rotates the client secret on any 401 — convenient but could mask a
  genuine auth-failure as success in happy-path helpers.
- `apply_tenant_role_grant.py` hand-applies a Supabase role grant; this IS also a proper migration
  (V96__grant_set_role_on_tenant_app.sql), so the foundation is sound (script is redundant remediation).

### H1 [HIGH — test integrity] EC-067 "Pass" is a FALSE PASS from an incomplete assertion
- `scripts/e2e/phases/phase9_data_adr.py:23-59`: the masking check `has_unmasked_aadhaar()` only inspects
  fields whose **key matches `aadhaar|aadhar`** (line 30). EC-067 then reports "Pass" if no aadhaar hits.
  It NEVER checks the bank account number — even though EC-067's own expected behaviour requires
  `bank_account masked as XXXX####`. This incomplete assertion is precisely what hid bug B1.
- **Net:** the harness is otherwise legitimate (real APIs, real asserts, real status polling), but at least
  one security-critical case passes by asserting only half its contract. Recommend a generic PII scanner that
  regexes the FULL response body for any 12-digit (aadhaar/account) and 10–18-digit account patterns, not
  per-key name matching.

### Harness verdict (overall)
- Not fabricated / not "hacky-to-go-green." Fixtures and phases hit live endpoints and assert real outcomes.
- Caveats: (a) H1 incomplete masking assertion; (b) matrix is self-written by the same harness
  (`_common.update_matrix`) so "previous results" are self-reported; (c) several recorded status codes are
  stale (400 vs actual 422); (d) missing sample PDFs break doc-upload fixtures today;
  (e) `lsp_token_for_fixture` auto-rotates secrets on 401 (could mask auth failures in happy-path helpers).
