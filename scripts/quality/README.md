# Quality gates

Why these gates and not stricter linting: see [ADR 0008](../../docs/adr/0008-tiered-quality-gates.md).
Short version — of the 66 findings in `docs/audits/`, about three were reachable by a type
checker, a linter or a formatter. The rest are invariant violations, so the gates are built
around executable invariants instead.

## Running it locally

```bash
./scripts/quality/gate.sh          # fast tier, only the stacks you touched (~1 min)
./scripts/quality/gate.sh --full   # full local stack gate, including the slow suites
./scripts/quality/gate.sh --all    # ignore change detection, check the whole repo
```

The fast tier runs automatically on `git push` via `frontend/.husky/pre-push`.

`git push --no-verify` bypasses it. That exists for pushing a work-in-progress branch, not for
getting a red gate out of the way.

## One-time setup

The hook is installed by husky's `prepare` script. If `git config core.hooksPath` does not print
`frontend/.husky/_`, run:

```bash
npm --prefix frontend install
```

## What runs where

| Tier | Check | pre-push | CI |
|---|---|---|---|
| 0 | frontend typecheck / lint / format / encoding | ✅ | ✅ |
| 0 | frontend unit tests + coverage ratchet, build, bundle boundaries | `--full` | ✅ |
| 1 | architecture fitness functions | ✅ | ✅ |
| 2 | API contract drift (backend ↔ OpenAPI ↔ generated types) | `--full` | ✅ |
| 2 | Flyway schema drift | on migration changes | ✅ |
| 3 | backend tests + coverage ratchet | `--full` | ✅ |
| 4 | backend-connected journey E2E | — | reports while soaking (non-blocking) |
| 5 | CodeQL, secret scan, dependency audit, Dependabot | — | ✅ |

## Make the checks merge-blocking on GitHub

The workflow files cannot prevent a direct push by themselves. Configure a repository ruleset
for `main` with these settings:

- Require a pull request before merging.
- Require the status checks listed below and require the branch to be up to date.
- Block force pushes and branch deletion.
- Do not allow ordinary bypasses; reserve any emergency bypass for a named administrator role.

Required checks:

- `Architecture fitness functions`
- `Tests and coverage ratchet`
- `Schema drift`
- `Typecheck, lint, format, coverage, build`
- `Browser smoke`
- `Cookie transport (H22)`
- `GitHub workflow lint`
- `API contract drift`
- `Secret scan`
- `Dependency audit`
- `Dependency change review`
- `CodeQL (java-kotlin)`
- `CodeQL (javascript-typescript)`

All required-check workflows run without path filters so GitHub always emits their status. The
journey workflow reports separately for pull requests and pushes to `main`, but must not be added
to the required list until its demo fixtures are deterministic. The other workflows also report
on feature-branch pushes before a pull request exists.

## Adding a fitness function

Rules live in `backend/src/test/java/com/bhawana/lms/architecture/`. The shape to copy is
`LspTenantElevationArchitectureTest`: walk the graph, collect violations, fail with the list.

Two conventions matter.

**Exceptions are allowlist entries with a written reason, never package exemptions.** A reason
that cannot be written down is a bug, not an exception.

**Allowlists are themselves tested.** Each rule has a companion test asserting its allowlist
entries still refer to code that exists and still has the property being excused. Without that,
the list quietly becomes decoration as the code moves underneath it.

## Changing the coverage ratchet

`backend/pom.xml` sets 85% line / 64% branch. `frontend/vite.config.ts` sets 65% statements,
57% branches, 61% functions and 68% lines. Raise them when coverage rises. Lowering a ratchet to
make a build pass must be treated as a policy change requiring review.
