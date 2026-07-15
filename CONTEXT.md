# Bhawana LMS

A multi-tenant loan management system. Lending Service Providers (LSPs) originate loans via API; an internal rule engine approves them; the platform disburses funds to borrowers and collects repayment, operating each LSP's own bank accounts through a bank integration.

## Language

### LSP bank accounts

Each LSP operates its **own** pair of bank accounts at the bank; the platform acts on the accounts belonging to the LSP that owns the loan, never on a single shared lender pool.

**Disbursal account**:
The per-LSP bank account that outgoing disbursements are debited from. The debit leg of a disbursement for one of LSP A's loans draws on LSP A's disbursal account.
_Avoid_: pool account, source account, lender account

**Collection account**:
The per-LSP bank account that incoming borrower repayments are credited to. Symmetric to the disbursal account, on the repayment side.
_Avoid_: receivables account, repayment account

### Disbursement

**Disbursement**:
The movement of the net loan amount from the LSP's disbursal account to the borrower through the bank. A disbursement decomposes into two legs that settle independently, and is only complete — the loan only reaches `DISBURSED` — once all three bank calls have returned success: the composite disburse call, the debit status check, and the credit status check.
_Avoid_: payout, transfer, payment

**Disbursement attempt**:
A single try at moving the money for a loan. Holds the live state of its three checkpoints (composite disburse, debit status, credit status), the idempotency key used with the bank, and a snapshot of the disbursal account it draws on. A loan account may have several attempts over time, but a new attempt is created only after a prior one's funds are confirmed returned to the LSP's disbursal account — never as a re-initiation past the point of no return.
_Avoid_: retry, disbursement request

**Implementation mapping (2026-07-13):** The durable **disbursement attempt** row is `disbursement_intent` (Spec S3 / MNY-01). It is created and committed **before** any provider call; `tran_ref_no` is unique and deterministic; the worker claims claimable intents (`CREATED`, `UNKNOWN`) with a lease and calls the bank outside a DB transaction. Append-only per-call evidence remains in `loan_disbursement_request_log` (**disbursement log**). Feature flag: `app.disbursement.intent-workflow.enabled` (see `docs/implementation-log.md`).

**Ops money preview (2026-07-15, Spec S12):** `GET /api/v1/internal/ops/loan-applications/{id}/disbursement-preview` and `…/disbursement-reference`. Preview labels `beneficiarySource=LIVE_BORROWER` until Spec S5 (deferred — see `docs/deferred-implementation.md`).

**Disbursement log**:
The append-only record of every individual call made to the bank (the composite disburse call and each status poll), one immutable row per call. It is evidence, never state; it is never overwritten.
_Avoid_: disbursement record, request log (when meaning the mutable state)

**Debit leg**:
The stage where funds leave the LSP's disbursal account at the bank. Confirmed by the bank's debit status check.
_Avoid_: withdrawal, source debit

**Credit leg**:
The stage where funds land in the borrower's bank account. Confirmed by the bank's credit status check, which is eventually-consistent — it may stay pending across multiple polls before resolving.
_Avoid_: payout, beneficiary transfer

**Point of no return**:
Once the debit leg succeeds, the disbursement must never be re-initiated — a second initiation would debit the LSP's disbursal account twice. The only forward path is reconciliation.

**Reconciliation**:
Repeatedly polling the bank's status checks for an in-flight disbursement until it reaches a terminal outcome — credited to the borrower, or returned to the LSP's disbursal account. On a failed or pending credit, the bank's prescribed action is to poll the status check again, not to re-initiate.

**In flight**:
The window in which a disbursement attempt has been initiated but not yet resolved to a terminal outcome. While in flight, the money may have left the LSP's disbursal account, so the loan is hands-off for ops — no manual status changes, no second initiation. Leg-level detail of an in-flight attempt lives only on the attempt; the account simply reads as awaiting its verdict.

## Example dialogue

> **Dev:** The credit failed — should the worker retry the disbursement?
> **Domain expert:** No. If the debit succeeded, we're past the point of no return. We don't re-initiate; we keep polling the credit status check until the bank tells us it either credited the borrower or returned the money to the LSP's disbursal account. Only after a confirmed return is a fresh disbursement safe.

## Security

**JWT principal cache (accepted property):** Managed-user and API-client JWT validation uses a 30-second in-process cache keyed by username or client id. Session revocation, password reset, lockout, and token-version bumps evict the cache entry immediately; worst-case stale acceptance is bounded by the TTL.

**SPA access token (Spec S11):** The browser holds the access JWT in memory only. `localStorage` may keep session metadata (user/roles/expiry) for shell continuity; reload acquires a fresh access token via the HttpOnly refresh cookie. Frontend HTTP clients refuse credential-bearing cross-origin absolute URLs (Spec S7).

**Borrower↔LSP relationship (Spec S19 Slice A, 2026-07-15):** Visibility for RLS remains keyed by `borrower_lsp_access`. Grants **must** go through `BorrowerLspRelationshipService.grantVisibility` (dual-writes access + `borrower_lsp_relationship` with sourced-at / channel / consent placeholders). Public `Borrower.grantVisibilityTo` is removed. Admin borrower detail / Profile tab expose relationship metadata. Remaining D8 work (drop access collection, field normalizer + DB CHECKs, profile-update audit) is deferred — see `docs/deferred-implementation.md`. Money isolation still depends on deferred Spec S5.

**Partner-provided repayment schedules (Spec S20, 2026-07-15):** `LSP_PROVIDED` schedules must satisfy principal integrity **and** accepted date/interest discipline: first due within approval window (1–60 days), anchored monthly cadence (±7 days), horizon within tenure + 75 days grace, and per-row/total interest within tolerance of the frozen product rate / platform generator. Defaults are product-accepted (`app.schedule.validation.*`); date and interest checks are always on. Partner contract note: [docs/partner-schedule-validation.md](docs/partner-schedule-validation.md).
