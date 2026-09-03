---
description: Orchestrate a scoped ticket from .scratch/lsp-loan-event-feed/issues end to end — audit, delegate to lms-implementer, verify, review, close, commit.
argument-hint: <ticket number, e.g. 12>
disable-model-invocation: true
---

Deliver ticket **$1** end to end. The ticket file is the one matching
`.scratch/lsp-loan-event-feed/issues/$1-*.md`.

You are the orchestrator. A sub-agent writes the code; **you** own the audit, the solution, the code
quality and the closing of the ticket. You do not get to hand any of that to the sub-agent and accept
what comes back.

## 1. Understand before you delegate

Read, in this order, and actually read them rather than skimming:

- the ticket file for $1 — every checkbox is a deliverable you will have to evidence
- its **Blocked by** ticket, and confirm that one really is `**Status:** resolved`/`done` with its boxes
  ticked; if it is not, stop and say so
- `docs/adr/0007-partner-lifecycle-updates-are-pull-based.md` and `specs/004-lsp-loan-event-feed/spec.md`
- `CONTEXT.md` for domain language — use the terms it defines, never the ones it rejects
- the two or three most recently resolved tickets in that directory, for the house convention on how a
  ticket records its evidence when it closes

Then do your own **deep audit of the real code** the ticket touches, per `CLAUDE.md`. Trace the actual
call paths, migrations, entity mappings, RLS policies, grants, seed data and tests — do not pattern-match
from filenames. Enumerate the exact blast radius: every file, migration and test that must change. Expect
the ticket's own counts to be slightly wrong; earlier tickets found the audits understated the test blast
radius. Your audit, not the ticket's prose, is what the implementer works from.

Decide and write down the **test seam** before delegating — the observable behaviour the first failing
test will assert. `lms-implementer` refuses to start without one.

## 2. Delegate the implementation

Spawn **one** `lms-implementer` sub-agent with a brief containing:

- the ticket text verbatim, with its checkboxes
- your audit findings — file by file, with what changes in each and why
- the pre-agreed test seam, and the exact command that runs that test
- hard scope boundaries: the working tree carries a large amount of unrelated uncommitted work; it stays
  untouched. No opportunistic refactors, no reformatting
- build/test commands: run from `backend/` — `./mvnw -q -o compile`, `./mvnw -q test -Dtest=ClassName`,
  `./mvnw test` for the full suite. Testcontainers against Postgres; Docker must be up
- the instruction **not to commit** — you commit
- the known-baseline caveat: the suite currently carries pre-existing failures from unrelated uncommitted
  work (`LspTenantElevationArchitectureTest` at last count). Establish the baseline on the current tree
  before implementing, so a pre-existing failure is never reported as clean and never mistaken for a
  regression

Delegate the writing, not the thinking. If the ticket needs a judgement call the audit cannot settle,
you make it and put the answer in the brief.

## 3. Verify — do not trust the report

When the sub-agent reports back, treat it as a claim to check, not a result to accept:

- read the actual diff of every file it says it changed, and check for files it changed and did not mention
- re-run the single-class test and the full backend suite **yourself**, and compare against the baseline
- walk the ticket's checkboxes one by one against the tree, not against the report
- confirm the application starts against a database built from migrations alone where the ticket asks for it

If anything is wrong, incomplete, or scope-crept, send it back to the same sub-agent with specifics
(`SendMessage`) rather than starting a fresh one. Fix it yourself only when that is genuinely faster.

## 4. Review the work

Run `/code-review high` over the change. Triage the findings: fix the real ones, and say plainly which
you judged not worth acting on and why. The bar is the code someone would write who had the whole
codebase in their head — not "the tests pass".

## 5. Close the ticket

Edit the ticket file in place, following the convention the resolved tickets in that directory use:

- tick every box, each with concrete evidence after an em dash — file names, counts, what you observed.
  A box you deliberately did not do stays unticked with the reason, as ticket 01 does
- set `**Status:** resolved`
- add the trailing sections where they apply: **Not in the ticket, done here**, **Residue swept
  afterwards**, **Left for a decision (not done)**
- record verbatim test counts, and attribute any failure honestly to this ticket or to the unrelated work
  it came from

Then commit to the current branch, staging **only** the paths this ticket touched — never `git commit -a`,
the tree is full of unrelated work.

## 6. Report

Tell me: what changed and why, the test evidence, what the sub-agent got wrong that you corrected, every
judgement call where a different reading was defensible, and anything left open. If ticket $1 is the last
in the set, say what the ADR 0007 cutover now leaves outstanding.
