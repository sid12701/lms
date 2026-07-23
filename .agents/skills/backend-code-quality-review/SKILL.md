---
name: backend-code-quality-review
description: Perform an evidence-backed backend code-quality and production-readiness review across Node.js, Java, Python, Go, .NET, Rust, Ruby, PHP, and mixed-service repositories. Use when asked to audit backend correctness, security, reliability, performance, architecture, dead code, complexity, dependencies, database access, API contracts, concurrency, observability, test quality, or maintainability; to review a backend change or service before release; or to plan and verify backend remediation. Detect the stack and existing tooling first, remain read-only unless fixes are explicitly requested, and ground every reported issue in traced code paths, actual framework/runtime behavior, configuration, callers, tests, side effects, and compensating controls.
---

# Back-End Code Quality Review

Run a staged review that starts with repository facts, measures the existing safety net, uses automation only to focus code inspection, and proves each reported issue from the implementation and runtime semantics. End with prioritized remediation and reproducible verification. Optimize for correctness, security, reliability, performance, maintainability, and operational safety—not style scores.

## Non-negotiable rules

1. Treat review requests as read-only. Do not modify production code, configuration, migrations, dependencies, lockfiles, or external systems unless the user explicitly asks for fixes.
2. Preserve unrelated working-tree changes. Inspect status before running formatters, generators, migrations, or tests that may rewrite files.
3. Detect language, framework, architecture, package manager, service boundaries, and available tools before choosing commands.
4. Prefer repository wrappers, scripts, pinned tools, and CI commands. Do not install a new analyzer merely because it is familiar.
5. Ask before networked installs, dependency upgrades, database mutations, container startup, cloud access, or tests requiring live infrastructure.
6. Never run destructive database commands, production migrations, load tests, exploit payloads, or secret-scanning uploads during a normal review.
7. Treat analyzer output as navigation data only. Do not report it as any kind of issue until the complete code path, callers, framework/runtime semantics, configuration, side effects, tests, and compensating controls satisfy the evidence contract.
8. Exclude cosmetic preferences unless they conceal a material defect. A long method is evidence to investigate, not a defect by itself.
9. Present findings and a remediation plan before changing code. If fixes are later authorized, protect behavior with targeted tests first.
10. Never suppress, ignore, or game a metric to improve a score. Explain false positives with evidence.

## Required resources

Read resources progressively:

- Always read [severity-and-evidence.md](references/severity-and-evidence.md) before classifying findings or writing the final report.
- Read [stack-tooling.md](references/stack-tooling.md) after detection; load only the sections matching the detected ecosystems.
- Read [review-domains.md](references/review-domains.md) before targeted manual review; select domains relevant to the service.
- Read [remediation-and-verification.md](references/remediation-and-verification.md) when planning fixes or performing a follow-up verification.
- Read [examples.md](references/examples.md) when calibrating an unfamiliar finding or report.

## Workflow

### 1. Establish authority and scope

Record:

- Requested scope: full repository, service/module, changed files, branch/PR diff, or named concern.
- Requested mode: findings only, remediation plan, implementation, or follow-up verification.
- Repository instructions and protected paths.
- Current branch/status and unrelated changes to preserve.
- Whether tests may use containers, databases, queues, network services, or credentials.

If scope is ambiguous, default to the backend services reachable from repository entry points and state the assumption. Do not broaden into frontend, infrastructure, or live systems without relevance and authority.

### 2. Detect the backend stack before selecting tools

Run the read-only detector from the repository or requested service root:

```bash
python3 <skill-dir>/scripts/detect_backend_stack.py <repo-or-service-root>
```

Then verify its output against manifests, lockfiles, wrappers, CI workflows, container files, source layout, and framework configuration. Detection is a routing aid, not authority.

Capture:

- Backend roots and independently buildable services.
- Languages and versions.
- Frameworks and runtime model.
- Package/build managers and lockfiles.
- Existing lint, static-analysis, test, coverage, dependency, migration, and security tools.
- Architectural signals such as controllers, handlers, services, domain, repositories, adapters, jobs, and migrations.
- Datastores, ORMs, brokers, schedulers, caches, and external clients.

For a monorepo, review each backend root with its own toolchain. Do not run a Node analyzer over Java or apply one service's conventions globally.

### 3. Build the command plan safely

Use [stack-tooling.md](references/stack-tooling.md) to select commands in this order:

1. Repository verification script or CI workflow.
2. Checked-in wrapper (`mvnw`, `gradlew`, project virtual environment, Make target, task runner).
3. Declared package-manager script or configured analyzer.
4. Ecosystem built-in that requires no dependency mutation.
5. Optional temporary analyzer only after explaining value, scope, network/install effects, and obtaining approval.

Separate commands into:

- Read-only discovery.
- Local analysis that creates only ignored caches/reports.
- Mutating or external commands requiring approval.

Do not run auto-fix, format-write, code generation, dependency update, migration, or cleanup commands in review mode.

### 4. Establish a baseline

Run the narrowest trustworthy sequence first, then widen:

1. Compile/typecheck/build without deployment.
2. Unit tests.
3. Integration/contract tests whose dependencies are safely available.
4. Coverage using existing configuration.
5. Lint/static analysis.
6. Dead-code, duplication, architecture, and dependency checks already configured.

Record exact commands, exit codes, skipped suites, warnings, duration, test counts, coverage type, and generated artifacts. Distinguish test failure from environment failure.

Coverage is evidence, not a target by itself. Prefer branch and critical-path coverage over broad line percentages. Never add meaningless tests to inflate a score.

### 5. Run automated analysis to focus code inspection

Route tools by detected stack. Cover, where supported:

- Unused/dead/unreachable code and dependencies.
- Duplicate code and copy-pasted policy logic.
- Cyclomatic/cognitive complexity and oversized units.
- Type, nullability, dataflow, bug-pattern, and concurrency analysis.
- Dependency declaration and vulnerability observations requiring reachability inspection.
- Coverage gaps and mutation-test results when already configured.
- Architecture rules, package cycles, and forbidden dependencies.
- Migration validation and schema drift checks when safe and configured.

Do not assign severity or issue status to this output. Use it to choose symbols and paths for source inspection. A scanner cannot establish reachability, impact, or framework behavior by itself. Do not upload proprietary source or findings to an external scoring/scanning service without explicit approval and understood payloads.

### 6. Perform targeted manual review

Read [review-domains.md](references/review-domains.md). Prioritize code identified by multiple signals:

- High complexity plus low branch coverage.
- High fan-in or cross-boundary reach.
- Authentication, authorization, money, state transitions, migrations, jobs, retries, and destructive operations.
- Database loops, transaction boundaries, external calls, shared state, and exception conversion.
- Changed code with weak tests or a large blast radius.

Trace each suspected problem from entry point to side effect. Inspect callers, tests, configuration, framework defaults, database constraints, and external contracts. Use raw source to confirm behavior; do not infer production impact from filenames or metrics alone.

### 7. Prove or reject every potential issue from code

For each potential issue selected for investigation:

1. Trace the production entry point through callers and callees to the relevant side effect.
2. Inspect the exact language and framework behavior that controls registration, lifecycle, transactions, serialization, validation, retries, concurrency, or cleanup.
3. Inspect effective configuration, environment selection, generated code, reflection, dependency injection, persistence mappings, and external contracts relevant to that path.
4. Inspect focused tests and identify what behavior they prove rather than relying on coverage percentages.
5. Reproduce or construct a concrete trigger when safe.
6. Establish reachability, the violated invariant, realistic impact, and deployment relevance.
7. Search for compensating controls: validation, constraints, retries, transactions, authorization, rate limits, monitoring, or tests.
8. Record file paths, symbols, tight line references, and the traced execution sequence.
9. Assign severity, confidence, and disposition using [severity-and-evidence.md](references/severity-and-evidence.md).

Only a code-grounded issue may be classified as confirmed or probable. `Probable` means the implementation path and framework behavior are established but one material external or deployment fact remains unavailable. It must never be used as a holding area for unvalidated analyzer output. If the evidence contract cannot be completed, omit the observation from issue sections and record only the specific analysis limitation when it materially affects review coverage.

Do not call code unused merely because static references are absent when reflection, framework discovery, routing, dependency injection, templates, migrations, plugins, serialization, or external API consumption may load it.

### 8. Explain root cause and remediation

For every code-grounded confirmed or probable issue, provide:

- Root cause, not just the symptom.
- Smallest production-grade correction.
- Alternatives and tradeoffs when material.
- Required regression tests and observability.
- Migration, rollout, compatibility, and rollback concerns.
- Verification commands.

Prefer direct, local ownership boundaries. Avoid generic repositories, manager layers, wrapper services, helper proliferation, or framework replacement unless they eliminate demonstrated complexity across multiple real consumers.

### 9. Stop and report before fixes

Unless implementation was explicitly requested, stop after findings and remediation plan. Do not silently fix even obvious issues.

If fixes are authorized, follow [remediation-and-verification.md](references/remediation-and-verification.md): add focused tests, make bounded edits, rerun targeted checks, then rerun the full baseline and affected analyzers.

### 10. Produce the final report

Use the template in [severity-and-evidence.md](references/severity-and-evidence.md). Order content as:

1. Outcome and scope.
2. Confirmed findings by severity.
3. Code-grounded probable issues with the exact unavailable external or deployment fact.
4. False positives and why they were rejected.
5. Tool/environment limitations and untested areas.
6. Coverage and test-quality assessment.
7. Prioritized remediation plan.
8. Commands run and verification results.

If no confirmed issues exist, say so directly, but still report residual risk and limitations. Never claim production safety solely because tools pass.

## Invocation examples

```text
Use $backend-code-quality-review to audit the backend without modifying code.
```

```text
Use $backend-code-quality-review on services/payments, include database and idempotency risks, and give me a remediation plan.
```

```text
Use $backend-code-quality-review to verify the fixes from the previous audit and compare tests, coverage, and remaining severity.
```

## Completion criteria

A review is complete only when stack detection is verified, commands and limitations are recorded, critical paths receive manual review, every reported issue satisfies the full code-grounding evidence contract, automated observations are either proved, rejected, or excluded from findings, and the final report separates confirmed issues, code-grounded probable issues, false positives, and limitations.
