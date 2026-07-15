# LMS validation campaign — 2026-07-12

## Executive result

The backend test suite and frontend unit suite are green, but the repository is not release-green. Frontend lint, formatting, and production build gates fail. Browser E2E and the supplemental Python API harness could not produce application verdicts because the execution environment blocked Chromium launch and dependency installation, respectively.

This campaign tested the existing dirty worktree as found; it did not reset or alter the user's application changes.

## Results

| Surface | Command | Result | Duration / scale |
|---|---|---|---|
| Fallow static analysis | `npx fallow --format json --quiet --explain` | Completed, valid `combined` JSON | 492 files / 3,399 functions |
| Fallow security | `npx fallow security --format json --quiet` | Completed, valid `security` JSON | 1 candidate |
| Backend | `backend\mvnw.cmd test` with JDK 21 | **PASS**: 738 tests, 0 failures, 0 errors, 1 skipped | 6m55s |
| Frontend typecheck | `npm run typecheck` | **PASS** | 1.35s |
| Frontend lint | `npm run lint` | **FAIL**: 3 errors, 1 warning | 30.45s |
| Frontend formatting | `npm run format:check` | **FAIL**: 10 files | 13.94s |
| Frontend encoding | `npm run check:encoding` | **PASS** | 1.40s |
| Frontend unit + coverage | `npm run test:cov` | **PASS**: 121 files, 748 tests | 11m54s |
| Frontend production build | `npm run build` | **FAIL**: 3 TypeScript errors | 8.51s |
| Playwright | `npm run e2e` | **INFRA BLOCKED**: Chromium `spawn EPERM`; 46 reported failed and 9 did not run, none are a reliable app verdict | 55 discovered tests |
| Python edge/API harness | fixture preflight | **INFRA BLOCKED**: `requests` missing; isolated install rejected by environment usage limit | Not executed |

## Backend detail

The Maven wrapper is the canonical backend entry point. It required `JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot` in this environment. Surefire produced 134 XML reports aggregating to 738 tests (737 executed successfully and 1 skipped).

The longest test was `DocumentUploadLocalProfileIntegrationTest` at 44.137s. It is annotated with `@ActiveProfiles("local")`, reads the repository `.env`, and conditionally targets a database whose `LMS_DB_URL` contains `supabase`. That makes the nominal unit/integration suite environment-dependent and potentially capable of touching external state. It should be opt-in and use a disposable Testcontainer by default.

**Remediation (2026-07-13, Spec S2):** `DocumentUploadLocalProfileIntegrationTest` **deleted**. Default upload regression is `DocumentUploadPostgresIntegrationTest` (Testcontainers). Opt-in external variant: `DocumentUploadExternalDbIntegrationTest` (`@Tag("external-db")`, `LMS_IT_EXTERNAL_DB=true`). `IntegrationTestDatabaseTargetGuard` blocks `IntegrationTestDatabaseCleaner` on non-ephemeral JDBC URLs. See `outputs/production-readiness-report-2026-07-12/PRODUCTION-READINESS-REPORT-2026-07-12.md` §19.6.

The only stderr content was Mockito/JDK self-attachment deprecation noise.

## Frontend detail

All Vitest assertions passed. Aggregate coverage was 55.79% statements/lines, 76.32% branches, and 64.57% functions. The run emitted substantial test noise: 69 canvas `getContext` warnings, 47 React Router future warnings, and 20 React `act(...)` warnings.

Lint failures:

- `src/features/audit/components/AuditTable.tsx`: unused `filters`.
- `src/features/audit/page.tsx`: synchronous state update inside an effect.
- `src/features/my-loans/detail-page.tsx`: synchronous state update inside an effect.
- `src/features/my-loans/components/DocumentsSection.tsx`: missing `docLabels` effect dependency (warning, promoted to failure by `--max-warnings 0`).

Production-build failures:

- `AuditTable.tsx`: unused `filters`.
- `detail-page.test.tsx`: fixture lacks required `lastActivity`.
- `users/api.test.ts`: `CreateUserInput` fixture lacks required `lspId`.

Prettier rejected 10 files; see the captured formatting stderr log for the exact list.

## Fallow findings

- Cleanup: 184 findings — 1 unused file, 68 unused exports, and 115 unused types. No unresolved imports, unlisted dependencies, circular dependencies, boundary violations, or stale suppressions were reported.
- Duplication: 4,461 duplicated lines across 55,017 LOC (8.1084%), 19 clone groups, 60 instances.
- Maintainability: 42 functions exceeded the configured threshold: 6 critical, 11 high, and 25 moderate. Average maintainability was 90.4.
- Top complexity risks include `MyLoanDetailPage` (cyclomatic 48, cognitive 75, LOC 488), `backendToDetail` in loan application detail mapping (cyclomatic 44), `DataTable` (cyclomatic 35), and borrower mapping (cyclomatic 29).
- Security candidate: browser-side `fetch` in `src/lib/api/http-client.ts` accepts an absolute URL and attaches the bearer token. No current absolute-URL caller was found, so this is a hardening issue rather than a confirmed exploit. Reject absolute URLs or enforce the configured API origin before attaching credentials.

## Test-system audit

1. `npm run verify` is fail-fast, so lint currently hides formatting, tests, and build. CI should run independent jobs or always collect every gate, as this campaign did.
2. A five-minute outer timeout is invalid for this repository. Backend needs about 7 minutes and serial Vitest coverage about 12 minutes on this machine. CI ceilings should be comfortably above measured runtimes while retaining per-test deadlock protection.
3. Earlier externally-killed Vitest runs left orphan Node workers. The runner should terminate the process tree on cancellation.
4. Playwright's Phase 8 file throws when `E2E_APPLICATION_ID` is absent, causing its remaining tests not to run. Add a preflight or a clearly reported conditional skip and isolate environment-specific projects.
5. The Python harness documents `requests` and `openpyxl` but provides no checked-in lockfile or requirements file. Add a pinned `requirements-e2e.txt` (or a locked project definition) and a reproducible setup command.
6. The backend local-profile integration test should not infer permission to contact Supabase merely from `.env` during ordinary `mvn test`.
7. Test warnings should be made actionable: mock canvas deliberately, enable/resolve Router future flags, and fix `act(...)` warnings.

## Evidence

- `fallow-combined.json` and `fallow-security.json`: machine-readable Fallow results.
- `backend-full-test.stdout.log` and `.stderr.log`: complete Maven output.
- `frontend-campaign-results.json`: command, exit code, and duration for each frontend gate.
- `frontend-*.stdout.log` / `frontend-*.stderr.log`: complete frontend outputs.
- `run-frontend-campaign.ps1`: repeatable independent-gate runner used for this campaign.

## Limitations

- Playwright could not launch Chromium under the sandbox. The required escalation was rejected because the Codex environment had reached its usage limit. The 46/9 Playwright counts therefore must not be interpreted as product failures.
- The Python fixture/API harness could not import `requests`; the isolated dependency install was rejected for the same usage-limit reason. No API edge-case verdict is claimed.
- `graphify update .` was requested after creating the campaign runner, but `graphify` is not installed or discoverable in this environment, so the repository graph was not refreshed.
