# ADR 0011 — Approval and document evidence

- **Status:** Accepted (2026-09-19)
- **Source:** Consolidated audit `docs/audits/lms-consolidated-audit-and-fix-specs-2026-09-06.md` — H13 (concurrent final uploads miss auto-approval), H14 (approval does not freeze its evidence), H16 (eligibility reads the mutable catalog), M04 (object uploads and batch metadata fail inconsistently)
- **Related:** ADR 0006 (migration discipline), ADR 0010 (lock order), `LoanDocumentService`, `LoanApplicationDocumentChecklistService`, `LoanAutoApprovalGateService`, `LoanDocumentOrphanReconciler`, migration V131

## Context

Auto-approval fired only when a caller observed the checklist go from incomplete to complete.
Uploads took no lock, so two concurrent final uploads could each miss the other's uncommitted
document and neither saw the edge. The document write and the approval ran in separate
transactions (split in #85), so a crash between them committed a complete checklist with no
approval decision and nothing would ever retry it.

Approval checked the current checklist row, which any later upload overwrote in place — the
evidence an approver relied on was not retained. Eligibility read amount/tenure bounds from the
mutable `loan_product` row although the application pins a `loan_product_version`.

Uploads wrote the object before validating the rest of a batch, batch items committed one by
one, and a metadata failure (including an outer idempotency rollback) left objects in storage
that nothing tracked.

## Decision

1. **Completion is decided under the lock, with the approval, in one transaction (H13).** Every
   document write takes borrower → application row locks (the shared loan-command order of
   ADR 0010 and `LoanApplicationLifecycleService`), records its checklist changes, and — when it
   completes the required set — runs the existing borrower-locked auto-approval in the same
   transaction. Concurrent writers serialize, so exactly one sees the edge; a crash or error
   rolls back the documents together with the missing decision, and the retry re-evaluates.
   No approval-pending marker or after-commit callback is needed. This deliberately reverses
   #85's split of the document and approval transactions (the idempotent API path already ran
   both in one transaction). The trigger stays edge-based, so documents replaced while an
   application waits for manual review do not re-run the rule engine.
2. **Every document write is an immutable version; approval captures the version set (H14).**
   `loan_application_document_version` rows are append-only; the checklist points at its current
   version. Both approval paths (auto and manual) write `loan_application_approval_evidence` in
   the approval transaction. Because every write holds the borrower lock the approval also holds,
   an upload racing an approval either lands entirely before it (and is captured) or after it.
3. **Upload policy by lifecycle state, enforced after ownership and before any object write,
   and again under the lock.** INITIALIZED / AWAITING_APPROVAL / REJECTED (#135: ops may reopen)
   accept any submission. APPROVED_PENDING_DISBURSAL / DISBURSEMENT_RETRY freeze approved
   evidence (`DOCUMENT_EVIDENCE_LOCKED`) except (a) supplying the LMS-held copy of a document that
   was approved as an external reference — disbursement requires LMS-held copies — and (b) an
   explicit **correction** (`correctionReason` on the single-document upload), which appends a
   `CORRECTION` version naming the approval evidence it corrects. INVALID and servicing states
   accept nothing (`DOCUMENT_UPLOAD_NOT_ALLOWED`). Re-submitting exactly what an item already
   shows is a no-op, so retries converge.
4. **Pinned terms decide eligibility (H16).** The rule engine and intake read amount/tenure
   bounds from the application's pinned version (intake pins the latest version and validates
   against that same row). Product, LSP and mapping status stay live kill switches. A missing
   pin (impossible since V104's NOT NULL) throws rather than falling back to the catalog.
5. **Objects are validated, owned, then written; metadata is atomic (M04).** A request is fully
   validated (content policy for every file, then upload policy) before any write. Each object's
   `loan_document_object` row is committed `PENDING` in its own transaction before the PUT, so it
   survives an outer rollback. Keys are content-addressed
   (`loan/{application}/{type}/{sha256}-{file}`), so a retry converges on the same object. All
   metadata for a request — single or batch — commits in one transaction that marks its objects
   `LINKED`: a batch is all-or-nothing, with no partial success.
6. **Orphans are reconciled conservatively.** `LoanDocumentOrphanReconciler` considers only
   `PENDING` rows untouched for the grace period (default 24h), claims each with one conditional
   update that rechecks no version or checklist row references the key, deletes the object
   outside any transaction, then marks the row `DELETED`. Objects without a row (pre-V131) are
   never candidates. It ships in dry-run mode (`app.storage.documents.orphan-reconciler.dry-run`)
   so operators review the logged inventory before enabling deletion.

## Consequences

- Document uploads now wait on the borrower lock that approvals take; uploads for one borrower
  serialize. Upload volume per borrower is small, and this is the price of a decidable edge.
- A failure in auto-approval now fails the upload that triggered it (previously the document
  committed and the error was reported anyway). The client retries and gets a consistent result.
- LSPs can no longer silently replace a document after approval; they get `409
  DOCUMENT_EVIDENCE_LOCKED` and must send a correction. Corrections are available to the same
  LSP roles that upload; they are recorded but do not trigger a re-review workflow.
- Existing approved applications have no captured evidence (it is not recoverable); a correction
  on one records no `corrects_evidence_id`.
- Applications stranded complete-but-unapproved by the old race are not swept automatically; an
  inventory query (`INITIALIZED`/`AWAITING_APPROVAL` with a complete checklist) finds them.
