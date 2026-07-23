# Remediation and Follow-Up Verification

## Contents

1. Authorization boundary
2. Planning fixes
3. Behavior protection
4. Implementation principles
5. Domain-specific fix requirements
6. Verification ladder
7. Before/after reporting

## 1. Authorization boundary

A review request does not authorize production edits. Implement only when the user explicitly asks to fix or remediate findings.

Even with implementation authority, request direction before:

- Changing public API/event/schema contracts.
- Applying or rewriting migrations.
- Upgrading dependencies or lockfiles.
- Changing authorization policy or tenant visibility.
- Starting live/shared infrastructure.
- Deleting code whose external use is uncertain.
- Introducing a new framework, datastore, broker, or architectural layer.

## 2. Planning fixes

Order work by risk and dependency:

1. Reproduce or encode the violated invariant.
2. Add focused regression coverage.
3. Correct Critical/High correctness and security issues.
4. Correct transaction, retry, concurrency, and compatibility risks.
5. Perform bounded maintainability refactors that make those corrections safer.
6. Remove confirmed dead/obsolete code only after reachability validation.
7. Re-measure and stop when further abstraction has no demonstrated benefit.

For each fix, state files, ownership boundary, behavior preserved/changed, compatibility, rollout, rollback, and verification.

## 3. Behavior protection

Choose the lowest test level that proves the invariant:

- Pure unit test for deterministic policy or mapping.
- Component/service test for orchestration with controlled dependencies.
- Integration test for transaction, ORM, serialization, security filter, database constraint, queue, or framework proxy behavior.
- Contract test for API/event compatibility.
- Concurrency test for a specific interleaving.
- Migration test against representative old schema/data.

Do not mock away the mechanism under repair. A transaction bug needs a real transaction-capable store; an authorization-filter bug needs the real filter chain; an N+1 fix needs query counting or profiling.

## 4. Implementation principles

- Preserve unrelated working-tree changes.
- Make the smallest coherent production change.
- Put invariants in the layer capable of enforcing them atomically.
- Prefer database constraints for durable uniqueness/referential invariants.
- Keep API validation at the runtime boundary and domain validation in the domain/application owner.
- Keep retry/idempotency policy beside the side effect it governs.
- Avoid controller-to-repository shortcuts that bypass domain/application policy.
- Avoid generic wrappers, base services, manager layers, and helper modules introduced for a single call site.
- Extract only when the new unit owns a coherent policy, side effect, lifecycle, or real variants.
- Preserve error cause, status mapping, correlation, and audit behavior.
- Do not weaken tests, thresholds, analyzers, or warnings to make verification pass.

## 5. Domain-specific fix requirements

### API/contract changes

- Verify old and new clients.
- Preserve fields/status behavior or provide explicit version/deprecation.
- Update schema, generated clients, contract tests, and documentation together.

### Database/transaction changes

- Validate existing data before adding constraints.
- Design backward-compatible expand/migrate/contract sequencing.
- Measure lock/table-scan risk.
- Test rollback, retries, duplicate execution, and partial failure.

### Concurrency/job changes

- Test the exact interleaving or duplicate-delivery sequence.
- Verify bounded retries, acknowledgment timing, lease/shutdown behavior, and idempotency.

### Security changes

- Add negative authorization/input tests.
- Verify audit/log redaction and least privilege.
- Avoid reproducing real secrets or destructive payloads.

### Performance changes

- Capture a baseline and the same after measurement.
- Keep workload/cardinality constant.
- Verify correctness and resource limits, not only latency.

## 6. Verification ladder

Run in order, stopping to diagnose failures:

1. Test that previously reproduced the issue.
2. Focused tests for the affected module and callers.
3. Compile/typecheck and configured static analysis.
4. Integration/contract/migration/concurrency tests relevant to the fix.
5. Full unit/integration suite available in the environment.
6. Coverage comparison for affected critical paths.
7. Re-run the analyzers that generated the original signal.
8. Build/package verification without deployment.
9. Diff review for accidental generated/config/lockfile changes.

When a command cannot run, report the exact blocker and residual risk. Do not replace a skipped integration test with a unit test and call it equivalent.

## 7. Before/after reporting

For every remediated issue, report:

- Previous behavior and root cause.
- New ownership/invariant and why it is safer.
- Files changed.
- Regression tests added or strengthened.
- Exact commands and results.
- Coverage/analyzer delta with interpretation.
- Compatibility, rollout, and remaining risk.

Separate improvements caused by tests from improvements caused by production refactoring. A lower CRAP/complexity score from added coverage is useful evidence, but it does not mean structural complexity disappeared.
