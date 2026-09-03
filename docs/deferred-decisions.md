# LMS deferred decisions

Product, design and operational decisions that surfaced during the Impeccable UI/UX audits and
were **deliberately left open** rather than resolved in the pass that found them. Each entry says
what the decision is, why it was deferred, and what would force it.

**This is not the engineering deferral register.** Approved-in-principle *specs* that are simply
not scheduled — S5 beneficiary snapshot, S13 ledger, S14 disbursement authorization, S15 PAN
masking, and so on — live in [`deferred-implementation.md`](./deferred-implementation.md) with
acceptance criteria and resume conditions. This file is for decisions where **the answer itself is
not yet chosen**, not work that is merely queued. Where the two touch, the entry links across.

**Sources** [`impeccable-audit.md`](./impeccable-audit.md) (phases 1–3) ·
[`impeccable-audit-phase4.md`](./impeccable-audit-phase4.md) (phase 4 and its post-audit).

---

## Open — awaiting a decision

### D1 · Maker-checker for a manual disbursement attempt

**Decision taken for now: keep the "Mark disbursement retry" control as it is.**

The audit flagged that this control is offered on `APPROVED_PENDING_DISBURSAL` with no
preconditions, and that it does not actually hold the loan — the disbursement worker processes
`APPROVED_PENDING_DISBURSAL` and `DISBURSEMENT_RETRY` alike and only stands down after
`maxAttempts` failures, so a loan with zero prior attempts is still disbursed on the next tick.

The owner's intent is to **build a maker-checker flow on top of it**: an operator *requests* a
disbursement attempt for a given loan and amount, and a second principal must approve before it
executes. That makes the control a request surface rather than a state toggle, and resolves the
"button that looks like a brake" problem by giving it a real mechanism.

- **Why deferred** The control is harmless while the flow is being designed, and removing it now
  would only have to be rebuilt.
- **What it needs** The approval model, not the button. See
  [`deferred-implementation.md` → S14 / CTRL-01](./deferred-implementation.md) — disbursement
  authorization with maker≠checker, caps and a checker queue is already specified there. **D1 is
  the UI half of S14, extended to cover the manual attempt path**, and should be designed with it
  rather than separately.
- **Until then** The control does not stop a disbursement. Anyone relying on it as a stop lever is
  mistaken, and there is currently no other way to pause a payout (see D2).

### D2 · No operator lever pauses an in-flight disbursement

Once a `disbursement_intent` exists, `DisbursementIntentWorkflowService` executes it synchronously
with no re-check of LSP status. Deactivating the LSP prevents an intent being *created*; it cannot
stop one already created.

This is consistent with the stated rule — `CONTEXT.md`'s "in flight means hands-off", where the
money may already have left the disbursal account and a second action risks a double debit. The
open question is whether the **absence of any lever at all** is intended, given how much ops tooling
elsewhere in the product assumes one exists.

- **Forces the decision** Real-money rails; or the first incident where ops needs to stop a payout
  and finds there is no mechanism.
- **Related** D1, and S14.

### D3 · Local-dev disbursement worker default

`app.disbursement.worker.enabled` defaults to `true` and `application-local.yml` does not override
it. `application-staging.yml` sets it to `false` explicitly. **Staging is manual-first by design;
local dev is auto-first by accident of the default.**

The consequence is not theoretical: it is why the disbursement UI was never seen in a browser
across two audits. Every loan reaching `APPROVED_PENDING_DISBURSAL` was auto-disbursed in ~23
seconds. Phase 4 worked around it with a runtime override; no file was changed.

- **The decision** Should local dev match staging (`APP_DISBURSEMENT_WORKER_ENABLED=false`)?
- **For** Makes the disbursement UI reachable by default and matches every other environment's
  intended operating mode.
- **Against** Changes how every developer's machine behaves; anyone relying on auto-disbursement in
  local flows would need to opt back in.

### D4 · "Repayment" line on terminal and rejected loans

The At-a-glance repayment line now reads **"Awaiting disbursement"** for the four pre-disbursement
statuses, replacing a misleading "On track". `REJECTED` and `INVALID` were deliberately excluded —
they will never disburse, so that copy would be equally untrue — and they currently fall through to
"On track", which is also wrong.

`CLOSED` and `FORECLOSED` have the same shape: a settled loan is not "on track", it is finished.

- **The decision** What the line should say for loans that ended without repayment (`REJECTED`,
  `INVALID`) and for loans that completed (`CLOSED`, `FORECLOSED`). Candidates: a status-specific
  phrase, or `—` with the "unavailable, not zero" convention the product already uses.
- **Why deferred** Scoped out of the fix so the change stayed inside the decision that was actually
  made. Lower impact than the pre-disbursement case, which appeared on the stuck-disbursement
  screen itself.

### D5 · Bulk selection and batch actions

Carried forward unchanged from phase 3 (§4.4). The largest net-new feature the audits identified:
a selection model, per-action permission gating, partial-failure reporting and an undo story.

- **Why deferred** Its own piece of work, not an audit close-out.
- **Cost of leaving it** Nielsen heuristic 7 (flexibility and efficiency) stays at 2/4 with a known
  cause. No surface in the product offers multi-record action.

### D6 · Backend test suite now requires Docker

Phase 3 recorded 86 Docker-gated tests that **skipped**. A Testcontainers refactor under
`src/test/java/.../support/` means that with Docker down, **587 of 810 tests error** rather than
skip. Every failure is `Could not find a valid Docker environment`; none is a real test failure.

- **The decision** Is "Docker required to run the backend suite" the intended development
  contract? If yes it belongs in the README and CI docs. If no, the Docker-dependent tests need a
  skip path so the suite stays green without it.
- **Why it matters** As it stands, the backend suite cannot be verified on a machine without
  Docker, which silently weakens every future "tests pass" claim.

### D7 · Document upload latency

Uploading the 8 required KYC documents took **36–42 seconds** server-side against R2, measured in
three separate runs. A partner integration uploading one-by-one rather than batched would be
exposed for longer.

- **The decision** Whether this is acceptable for the origination path, or wants parallelism, a
  progress contract, or a different storage posture.
- **Why deferred** Found while seeding audit data, not while investigating performance. Needs a
  measurement pass of its own before anyone changes anything.

### D8 · A parked disbursement can be re-initiated, and that contradicts the point of no return

`CONTEXT.md` § Disbursement is unambiguous: a parked attempt is *in flight* and past the **point of
no return**, so there is "no second initiation" until the funds are confirmed returned to the LSP's
disbursal account. `LoanDisbursementCommandService.initiateDisbursement` and
`DisbursementPreflightValidator.ensureDisbursementRequestAllowed` both re-admit an account sitting in
`DISBURSEMENT_PENDING_RECONCILIATION` for a fresh attempt. If the debit leg of the parked attempt
succeeded, a second initiation debits the disbursal account twice.

**The obvious fix is worse than the deviation, which is why this is a decision and not a bug fix.**
Every other disbursement entry point — the auto-resolve hook, the status-check poll, and the
mock-outcome path — requires `DISBURSEMENT_REQUESTED`, and `LoanDisbursementWorkerService` selects
only that status. So `initiateDisbursement` is the *single* forward path out of the parked state.
Closing it strands parked loans permanently, with no transition to move them on and only an ops alert
to show for it.

What is actually missing is the step `CONTEXT.md` describes but the system never models: a
**confirmation that funds were returned**, which is what makes a fresh attempt safe. Until that
exists, the gate cannot simply be closed.

- **The decision** Whether to model funds-returned confirmation as a real transition (and gate
  re-initiation behind it), give ops an explicit reconciliation-resolution action, or accept
  re-initiation from the parked state and amend `CONTEXT.md` to match the code.
- **Why deferred** Found while implementing the loan event feed's reconciliation event (spec 004,
  issue 07), which had no mandate to change the disbursement state machine. Closing the gate without
  the replacement path would have been a regression, and building that path is a feature.
- **Held in the meantime** The issue-07 seam test deliberately stops at the park rather than driving
  a second attempt, so no regression test cements the current behaviour. Reconciliation parks are now
  visible on the partner feed as `DISBURSEMENT_PENDING_RECONCILIATION`, and raise an ops alert.
- **Forces the decision** Real-money rails — this is a double-debit risk the moment the mock provider
  is replaced. Should be settled before any live disbursal account is connected.
- **Related** D1, D2, and S14. D2 is the same "in flight means hands-off" rule seen from the other
  side: D2 is the absence of a lever to *stop* an attempt, D8 is the presence of a lever that
  *restarts* one.

### D9 · The oldest-transaction alert's threshold is the same number as the interval that evaluates it

`app.alert-rules.oldest-transaction-age-seconds` defaults to **300**, and
`app.alert-rules.scheduler-fixed-delay-ms` — the interval on which every scheduled rule is evaluated —
defaults to **300000**. So the rule asks "has a transaction been open five minutes?" once every five
minutes.

**What that introduces is a delivered latency of up to twice the configured number.** A transaction
that opens one second after an evaluation is not looked at again for five minutes; at that point it
is still a hair under the threshold at the next evaluation, so it is not caught until the run after
that — by which time it is close to ten minutes old. The number an operator configured is 5 minutes;
the worst-case age at which they are actually paged is 10.

(The alert copy itself does not mislead: it reports the transaction's *observed* age, so a page that
arrives late says "open for 9 minutes", not "5". The defect is detection latency, not the wording.)

This is bad specifically because of what the alert is for. ADR 0007 makes it a launch blocker on the
grounds that operators should learn a feed is stalled *before* partners start calling. Doubling the
worst-case time-to-page eats most of that margin, and nothing in the configuration makes the doubling
visible — the two properties live in the same block and look independent.

**The same rule also has no way to tell the platform's own long transactions from a foreign one.** It
excludes the evaluating backend (`pid <> pg_backend_pid()`) and autovacuum (`backend_type = 'client
backend'`), and that is all. Loan-event partition maintenance, portfolio-KPI snapshot computation and
report generation all run as ordinary client backends on the same cluster, so any of them holding a
transaction past the threshold pages an operator about the platform rather than about a `pg_dump`.
That is not a false positive — those transactions genuinely do stall every LSP's feed, which is the
whole point of the alert — but it is the difference between "something is wrong" and "the system is
doing its job slowly", and the operator cannot tell which from the page. Whether that distinction is
worth encoding depends on how long those jobs actually run in production, which is not known yet.

- **The decision** Whether to (a) drop the threshold below the evaluation interval so worst-case
  latency stays near the configured number, (b) shorten the evaluation interval for this rule alone,
  or (c) accept 5–10 minutes and say so in the alert copy instead of quoting the raw threshold. And,
  separately, whether the platform's own known-long jobs should be excluded by `application_name`,
  called out in the copy as platform-owned, or left indistinguishable.
- **Why deferred** Both answers depend on production measurements nobody has yet: how long partition
  maintenance, KPI snapshots and report generation actually hold a transaction, and how much feed
  staleness partners tolerate in practice. Picking numbers now would be guessing, and the guess would
  calcify into the default.
- **Held in the meantime** 300s and 300000ms, the values ADR 0007's failure mode implies rather than
  ones anyone measured. Both are plain configuration, so changing either is an env var, not a deploy
  of new code. The alert carries `application_name` and `pid` in its context, so an operator can
  always see *which* backend it is even when the copy does not say.
- **Forces the decision** The first production page from this alert. Either it names one of the
  platform's own jobs — in which case the exclusion question is live — or it arrives late enough that
  a partner called first, in which case the latency question is.
- **Related** D6 (the suite's Docker dependency) is the other place this rule's dependence on live
  cluster state has already cost something: it is inert by default under the `test` profile
  (`oldest-transaction-age-seconds: 86400`) precisely because its outcome is a function of ambient
  session state rather than of any test's own fixtures, and left at the production default it would
  have broken two unrelated DPD-transition assertions that check the worker emitted nothing.

### D10 · An ops alert with no subject has no identity in the UI

Every alert before this one pointed at something — a loan application, a borrower, an app user, an
LSP. `OpsAlert.subjectId` carried that aggregate's id and the alerts inbox rendered it as a short id,
linked where the subject has a page. `OpsAlertService.createAlertIfAbsent` has always had a
`subjectId == null` branch that falls back to deduplicating on `type + correlationId`, but until the
oldest-transaction alert nothing called it: this is a cluster-wide condition with no aggregate to
point at.

**What that introduces is an alert row that reads "System · unknown".** `features/alerts/api.ts` maps
a null `subjectId` to the literal string `"unknown"`, `shortId("unknown")` returns it unchanged
because it is under eight characters, and `subjectType: SYSTEM` is not one of the types
`resolveAlertSubjectHref` links, so the cell is inert text. Nothing crashes and no data is wrong.

It is bad for a narrow reason rather than a cosmetic one: "unknown" is the same word the UI would use
for a subject it *failed* to resolve, so a deliberately subject-less alert is presented identically to
a broken one. An operator scanning the inbox cannot tell "this condition has no subject by nature"
from "this alert lost its subject". The information the operator actually wants — which backend, which
application name — is present in `contextJson` and simply not surfaced on the row.

- **The decision** Whether subject-less alerts should render an explicit "no subject" treatment
  distinct from a resolution failure, promote a type-specific field from `contextJson` into the
  subject cell (here, `pid` or `applicationName`), or keep the current fallback and accept the
  ambiguity.
- **Why deferred** It is a frontend presentation question found while implementing a backend ticket
  (spec 004, issue 10) that had no mandate to touch the alerts inbox, and the right answer depends on
  whether subject-less alerts stay a single special case or become a category.
- **Held in the meantime** The fallback renders safely and the identifying detail is in `contextJson`,
  which the alert detail view already exposes.
- **Forces the decision** A second subject-less alert type. One special case is tolerable; two mean the
  inbox has a category it cannot describe.
- **Related** D9 — both are consequences of this alert watching the cluster rather than a loan.

### D11 · The alert-rule gate reads a table the tenant connection cannot see

`OpsAlertEmitters.isRuleEnabled` calls `alertRuleRepository.findByCode(code)` directly, with no flip
onto the admin datasource, while every write beneath it — `OpsAlertService.createAlertIfAbsent` and
`createAlert` — correctly wraps itself in `AdminScopedTransactionExecutor`. `alert_rule` is not among
the tables `V41__tenant_isolation_rls.sql` grants to the tenant role, so whenever an emitter is called
on a tenant-scoped connection the gate throws `ERROR: permission denied for table alert_rule` before
the write it guards is ever attempted.

`RateLimitFilter` catches the resulting `RuntimeException` and logs it at debug, so the HTTP contract
is untouched — the 429, the `Retry-After` header and the `ApiError` body are all correct. What is lost
is the alert: **`RATE_LIMIT_BREACH` is never written for any LSP-keyed rule**, which is `lsp-write` and
`docs-lsp` as much as issue 09's new `lsp-loan-events`. The orchestrator's brief for issue 09 asserted
that the breach alert "comes free by construction" from riding the shared filter. The rule does ride
it; the alert does not arrive.

One call site already compensates for exactly this: `LspValidationAuditService` wraps
`emitLspProvidedScheduleViolation` in `adminScopedTransactionExecutor.run(...)`. That is the shape of
the hazard — it is known, and handled in one place rather than at its source.

- **The decision** Whether the admin-scope flip belongs inside `isRuleEnabled` (one fix, every emitter
  covered), at each call site that runs on a request thread (matching `LspValidationAuditService`, but
  leaving the trap in place for the next emitter), or whether the tenant role should simply be granted
  `SELECT` on `alert_rule` — which would widen what a tenant connection can read, against the posture
  V41 sets deliberately.
- **Why deferred** `isRuleEnabled` gates *every* `emit*` method, so moving it onto the admin datasource
  turns on alerts that currently fail silently across several unrelated rules at once. That is a
  behavioural change to the alerting surface and deserves its own ticket and its own test, not a
  drive-by edit inside a rate-limiting ticket.
- **Held in the meantime** Nothing suppresses the log line, so the failure is visible in any run that
  provokes a rejection on an authenticated LSP request. The alert rule itself remains seeded and
  enabled, so no configuration has to change when the scope is fixed.
- **Forces the decision** The first time an operator asks why a partner hammering the API raised no
  alert — or an audit of `RATE_LIMIT_BREACH` coverage, which is part of ADR 0007's stated mitigation
  for the feed being a better exfiltration target than a webhook stream.
- **Related** D10, which is also about `RATE_LIMIT_BREACH`-shaped alerts: subject-less alerts render as
  "System · unknown". Between them, this alert type cannot currently be raised on the LSP surface and
  would not identify itself well if it were.

---

## Settled, but revisit if the position changes

These are decided. They are recorded here only because each names the condition that would reopen
it.

| Decision | Reopen when |
|---|---|
| **PAN and Aadhaar stay visible** as columns on the `/borrowers` list (phase 3, F-29). The asymmetry with `/loan-applications`, which masks borrower names, is a deliberate posture. | The compliance position changes. Note S15 / SEC-01(3) in the engineering register specs a partner-masked / admin-full policy. |
| **Explain computed figures only** — no keyboard-shortcut surface (phase 3, heuristic 10). Legibility of figures an agent must defend to a borrower outranks power-user affordances. | Ops staff become heavy enough daily users that shortcut cost outweighs the legibility work. |
| **"Not required", not "Waived"** for documents that do not apply to a loan (phase 3, F-02). "Waived" implies an approving actor and timestamp the system does not record. | The system starts recording an actor and timestamp for the exemption. |
| **"Mark disbursement retry" keeps the word "retry"**, while the action that makes another attempt is now **"New disbursement attempt"**. `CONTEXT.md` bans "retry" as a synonym for *disbursement attempt*; it does not ban naming the canonical `DISBURSEMENT_RETRY` status, which `PRODUCT.md` lists verbatim. | The lifecycle status itself is renamed, or D1 replaces the control with a request flow. |

---

## Operational residue from the phase-4 audit run

Not decisions — cleanup owed from seeding the audit data.

- Two LSPs were left `INACTIVE` to hold loans in `APPROVED_PENDING_DISBURSAL` and
  `DISBURSEMENT_RETRY` for inspection. Release with
  `python3 scripts/seed_disbursement_pending.py --release <lspId>`.
- `audit.lspwrite`'s password was reset in order to run the single-role live pass, because it had
  never been recorded anywhere. Worth deciding where such fixture credentials should live.
