# ADR 0014 — Borrower relationship establishment and loan financial invariants

- **Status:** Accepted (audit remediation batch 9)
- **Source:** Consolidated audit `docs/audits/lms-consolidated-audit-and-fix-specs-2026-09-06.md` — M02 (borrower visibility can outlive failed onboarding; race recovery inconsistent), M18 (invariants enforced only in selected service paths)
- **Related:** ADR 0005 (tenant scope fail-closed), ADR 0006 (migration discipline), ADR 0009 (foreclosure pricing policy — Proposed), ADR 0010 (lock order), ADR 0011 (evidence chain), migrations V43, V65, V113, V139; `BorrowerOnboardingService`, `LoanApplicationOnboardingService`, `BorrowerLspRelationshipService`

## Context

M02 asked two things that had already partly landed: the C06 work moved every
existing-borrower visibility write into the caller's tenant transaction, so a failed
loan command no longer leaves a committed access grant. What remained undecided was
the *relationship lifecycle* itself, the PAN find-or-create race, and an onboarding
retry loop that replayed any integrity violation.

The PAN race has a wrinkle that rules out the obvious fix: `borrower` rows are
RLS-invisible to a tenant until a `borrower_lsp_access` row exists for that tenant,
and the only committed read that can see a cross-tenant borrower is the admin-scope
lookup. `INSERT ... ON CONFLICT (pan) DO NOTHING` resolves the write but cannot hand
back the winner's row — an invisible row stays unreadable — so conflict-aware insert
alone is not a resolution.

M18: V65's checks cover nonnegative amounts and paid/outstanding totals, but the
component arithmetic (what an installment amount is *made of*) and cross-entity
ownership (a payment's installment belonging to the payment's account; an account
agreeing with its application and pinned product version) were enforced only in
service code.

## Decision

### M02 — relationships are established at commit, never provisional

1. **There is no provisional relationship state.** A borrower↔LSP relationship —
   the `borrower_lsp_access` grant plus its `borrower_lsp_relationship` row — exists
   only because the loan command that created it committed. Both writes live in the
   caller's tenant transaction together with the application, intake audit, checklist
   and event: a failed or rolled-back command leaves no access, no relationship, no
   profile or bank delta. "Established" therefore means exactly *committed with a
   successful onboarding*; visibility is never granted early and promoted later.
2. **Same-PAN find-or-create is serialized, not retried blind.** The tenant
   transaction takes `pg_advisory_xact_lock(hashtext('lms.borrower.pan'),
   hashtext(pan))` before the identity lookups — the first lock in the command,
   ahead of the borrower row lock, keeping the ADR-0010 order. A contender waits for
   the winner's outcome, then the admin-scope re-lookup reads the committed borrower
   and flows through the existing-borrower path (access grant → row lock → profile
   merge → audited bank policy → relationship row). One identity, reuse on the
   contender — including under the LSP idempotency transaction, where an in-flight
   retry is impossible because the aborted outer transaction cannot be replayed.
3. **Only the identified PAN race is retried.** The create-application retry loop
   replays solely failures whose cause chain carries a `uk_borrower_pan` unique
   violation (whether surfaced mid-transaction as `DataIntegrityViolationException`
   or wrapped in a commit-time `TransactionSystemException`) — the case left over
   when a writer bypasses the advisory lock (e.g. JDBC seeders). Every other
   integrity failure is deterministic input and propagates on first sight. A PAN
   conflict that survives all attempts — or arrives under a caller-owned
   transaction — is the documented `409 BORROWER_PAN_CONFLICT`.
4. **Conflict alerts are not disclosure.** `OpsAlertService.createAlert` still
   commits `BORROWER_IDENTITY_CONFLICT` / `BORROWER_ACTIVE_LOAN_DUPLICATE` in a
   separate admin transaction so a rejected attempt is recorded even though the
   command rolls back. That is a security signal to internal ops, not partner
   visibility — no borrower data becomes readable to any LSP.
5. **Orphan inventory before any cleanup.** Before any future cleanup of
   pre-atomic grants, run and archive:

   ```sql
   -- access grants with no relationship row (dual-write divergence)
   SELECT a.borrower_id, a.lsp_id
   FROM borrower_lsp_access a
   LEFT JOIN borrower_lsp_relationship r
     ON r.borrower_id = a.borrower_id AND r.lsp_id = a.lsp_id
   WHERE r.id IS NULL;

   -- relationship rows with no access grant (reverse divergence)
   SELECT r.borrower_id, r.lsp_id
   FROM borrower_lsp_relationship r
   LEFT JOIN borrower_lsp_access a
     ON a.borrower_id = r.borrower_id AND a.lsp_id = r.lsp_id
   WHERE a.borrower_id IS NULL;

   -- grants that never produced a committed onboarding: the strongest evidence of
   -- a pre-atomic survivor (every successful onboarding writes the application in
   -- the same transaction as the grant)
   SELECT a.borrower_id, a.lsp_id
   FROM borrower_lsp_access a
   WHERE NOT EXISTS (
       SELECT 1 FROM loan_application la
       WHERE la.borrower_id = a.borrower_id AND la.lsp_id = a.lsp_id
   );
   ```

   The third set needs operator review, not automatic deletion: a grant may
   legitimately outlive an application that was later rejected (the relationship
   stays established — the onboarding *did* commit), so only rows with no
   application at all are candidates.

### M18 — database enforcement for universal invariants

6. **Component arithmetic is checked at the database.** On
   `loan_repayment_schedule_installment`: `installment_amount = principal_due +
   interest_due`, `closing_principal = opening_principal - principal_due`,
   `paid_principal <= principal_due`, `paid_interest <= interest_due` — matching
   what the generator produces and what `validateProvidedInstallments` demands of
   LSP-supplied schedules. `paid_amount <= installment_amount` follows transitively
   through the existing V65 `chk_installment_paid_sum`/`chk_installment_total`.
7. **Ownership agreement is enforced by composite foreign keys** (migration V139):
   a payment's `repayment_installment_id`/`foreclosure_quote_id` must belong to the
   payment's `loan_account_id`; an application's `loan_product_version_id` must be a
   version of its `loan_product_id`; an account's `loan_product_version_id` must be
   a version of its `loan_product_id`, and the account's
   `(loan_application_id, borrower_id, lsp_id, loan_product_id,
   loan_product_version_id)` must equal the referenced application's identity row.
   Each FK rides on a composite unique anchor whose first column is the primary
   key. This was chosen over triggers because the referenced columns are immutable
   after insert — the check is declarative, visible to `pg_constraint`, and cannot
   be bypassed by a future service path.
8. **Constraints land NOT VALID then VALIDATE in one migration.** Validation makes
   dirty pre-existing rows fail loudly; the per-constraint inventory queries must
   return zero before the migration is applied to a populated database:

   ```sql
   SELECT count(*) FROM loan_repayment_schedule_installment
   WHERE installment_amount <> principal_due + interest_due;
   SELECT count(*) FROM loan_repayment_schedule_installment
   WHERE closing_principal <> opening_principal - principal_due;
   SELECT count(*) FROM loan_repayment_schedule_installment
   WHERE paid_principal > principal_due OR paid_interest > interest_due;
   SELECT count(*) FROM loan_foreclosure_quote
   WHERE outstanding_principal < 0 OR outstanding_interest < 0 OR settlement_amount < 0;
   SELECT count(*) FROM loan_payment_transaction p
   JOIN loan_repayment_schedule_installment i ON i.id = p.repayment_installment_id
   WHERE i.loan_account_id <> p.loan_account_id;
   SELECT count(*) FROM loan_payment_transaction p
   JOIN loan_foreclosure_quote q ON q.id = p.foreclosure_quote_id
   WHERE q.loan_account_id <> p.loan_account_id;
   SELECT count(*) FROM loan_application a
   JOIN loan_product_version v ON v.id = a.loan_product_version_id
   WHERE v.loan_product_id <> a.loan_product_id;
   SELECT count(*) FROM loan_account ac
   JOIN loan_product_version v ON v.id = ac.loan_product_version_id
   WHERE v.loan_product_id <> ac.loan_product_id;
   SELECT count(*) FROM loan_account ac
   JOIN loan_application a ON a.id = ac.loan_application_id
   WHERE a.borrower_id <> ac.borrower_id OR a.lsp_id <> ac.lsp_id
      OR a.loan_product_id <> ac.loan_product_id
      OR a.loan_product_version_id <> ac.loan_product_version_id;
   ```

9. **Quote component totals are deliberately deferred.** `settlement_amount =
   outstanding_principal + outstanding_interest` holds today but is a *pricing*
   statement: ADR 0009 (H08) is still Proposed, and the signed-off formula may add
   permitted charges to the total. Only the nonnegativity checks land now; the
   equality arrives with the priced components.

## Consequences

- Two concurrent same-PAN onboarding requests now produce one borrower identity and
  both succeed by reuse; a residual race (a writer that skips the advisory lock)
  resolves through the bounded retry, and an unresolvable conflict surfaces as the
  documented `409 BORROWER_PAN_CONFLICT` rather than a 500.
- A contender waits on the PAN advisory lock for the duration of the winner's
  onboarding transaction — the same wait shape as the existing borrower row lock,
  bounded by statement timeout.
- Integrity violations that used to burn up to three transactions before failing
  (e.g. a lost `uk_loan_application_lsp_external` race) now fail on the first
  attempt.
- Direct SQL can no longer create a payment pointed at another account's
  installment or quote, an application pinned to another product's version, or an
  account that disagrees with its application's identity — all rejected at the
  database boundary, not just the service layer.
- `VALIDATE CONSTRAINT` failures at migration time are evidence of real data
  corruption: they abort the deploy with the offending row in the error, never
  silently skip the check.
