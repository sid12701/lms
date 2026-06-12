# ADR 0003 — Loan origination is API-only; LSP UI never creates applications

- **Status:** Accepted (2026-06-07)
- **Supersedes:** Audit-doc framing of #67 ("No LSP UI loan-create form") as a gap.
- **Related:** #62 (rule-engine as the gatekeeper), #79 (LSP API client lifecycle), ADR 0001 (frontend integration model), ADR 0002 (LSP disable kill-chain).

## Context

`POST /api/v1/lsp/loan-applications` is gated on `hasRole('LSP_API_CLIENT')`. Every sibling endpoint on the same controller (list, get by id, invalidate, document upload, document batch, disbursement bank check) already accepts `LSP_API_CLIENT`, `LSP_UI_READ`, and `LSP_UI_WRITE` as appropriate. An LSP staffer signed into the web UI can therefore *triage* applications but cannot *create* them.

Issue #67 was filed against this asymmetry, framed as "spec says LSP UI users may create loans; the only loan-create endpoint is API-client gated." The audit doc's recommended option was to relax the role gate to allow `LSP_UI_WRITE`.

On re-examination, the spec line is the artifact; the product intent is the opposite.

## Decision

LSP loan origination is **API-only by design**. The `LSP_UI_WRITE` role does not grant the ability to create loan applications. The web UI is for triage, document attachment, invalidation, and disbursement bank verification — never for typing a borrower's PAN, Aadhaar, bank details, employment record, and address into a 35-field form.

`LspLoanApplicationApiController.createApplication` stays `@PreAuthorize("hasRole('LSP_API_CLIENT')")`. No new endpoint is added. No frontend create form is built.

## Rationale

1. **Origination is machine-driven.** The product model has the LSP's own onboarding system (KYC, bureau pulls, employer verification) submit applications via the API. Re-typing the same fields into a browser is duplicate data entry, not a workflow.
2. **It would contradict the direction #62 locked in.** #62 made the `LoanAutoApprovalRuleEngine` the trusted approver and removed human gates from the lifecycle. Reintroducing a human as the *originator* would push the lifecycle back toward "human typed it; trust the human" — the exact stance #62 removed.
3. **The audit / alert attribution gets cleaner.** `LSP_BOUND_VIOLATION` alerts (introduced under #62) attribute to a specific LSP credential. A single class of caller (`LSP_API_CLIENT`) is easier to reason about for rate-limit (#81), tenant-isolation (#89), and lockout (#155) than two classes with branched behaviour.
4. **PII exposure surface stays narrow.** A 35-field form on the web UI is a phishing target. Keeping origination off the browser means a compromised UI session cannot create fake applications under a real LSP's identity.
5. **The gap is purely documentary.** The annotation on the POST is self-documenting; the regression test for "UI roles cannot POST" was deemed unnecessary because the `@PreAuthorize` line speaks for itself.

## Consequences

- `LSP_UI_WRITE` continues to authorise: document upload, document batch, invalidation, disbursement bank check. Unchanged.
- The LSP UI loan-application surface stays read-mostly: list, detail, document attachment, invalidation.
- No new endpoint, no new DTO, no new frontend form, no new tests in this delivery.
- Audit Explorer, MIS, and reports continue to see `source_channel = ONBOARDING_API` as the canonical creation channel for LSP applications.
- LSP partners must integrate the create call into their own onboarding pipeline. There is no manual fallback.

## Trigger to re-open

Any one of the following would re-open this decision and require a new ADR:

1. A partner LSP signs without their own onboarding system and asks for a human-entry path.
2. Product changes the model to a hybrid (e.g., LSP enters basic data, ops completes downstream).
3. A regulatory requirement mandates a human attestation step at origination.
4. Volume of manual-correction tickets (e.g., LSP support tickets to ops asking "please create this application for me") crosses a threshold that makes the API-only constraint operationally painful.

If any trigger fires, the right reopen path is to revisit option 3 from the original #67 discussion (relax the role gate) with the safety bag attached: `Idempotency-Key`, source-channel discrimination (`ONBOARDING_LSP_UI`), explicit tenant-isolation regression test, and rate-limit + alert hand-offs to #81 / #62.
