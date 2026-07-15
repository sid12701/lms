# Browser / API E2E (Spec S9 / S10)

## Supported Playwright invocation

Requires the Vite app (Playwright starts `npm run dev`) and a reachable backend at
`VITE_API_BASE_URL` (default `http://localhost:8080`).

```bash
cd frontend
set E2E_ADMIN_EMAIL=your-admin@example.com
set E2E_ADMIN_PASSWORD=your-password
npm run e2e
```

Post-deploy canary only (no loan-application fixture required):

```bash
npm run e2e:canary
```

Smoke (`smoke.spec.ts`) and `@canary` do not need a loan application id. If the
backend is down, set `E2E_SKIP_FIXTURES=true` or rely on the built-in health
probe — `globalSetup` skips API seeding and Phase 8 tests skip with a printed reason.

## Global setup / teardown

`playwright.config.ts` runs `e2e/global-setup.ts` before the suite:

1. Probes backend health at `E2E_API_BASE` (default `http://localhost:8080`).
2. When healthy and fixtures are not skipped, logs in via `POST /api/v1/auth/login`
   with `{ email, password }` from `E2E_ADMIN_EMAIL` / `E2E_ADMIN_PASSWORD`.
3. Creates an `E2E-`-prefixed LSP, product mapping, API client, and loan application
   (same sequence as `DocumentUploadTestSupport` / `scripts/e2e/fixtures.py`).
4. Writes `frontend/e2e/.auth/e2e-fixtures.json` and admin storage state to
   `frontend/e2e/.auth/phase8-admin.json`.
5. Workers resolve `E2E_APPLICATION_ID` from that fixture file (or from an explicit environment override).

`e2e/global-teardown.ts` best-effort invalidates the seeded loan via the LSP
`/invalid` endpoint. Admin-side LSP/product rows are left in place.

Skip fixture seeding (suite continues; Phase 8 skips):

- `E2E_SKIP_FIXTURES=true`, or
- backend health probe fails, or
- admin credentials are unset (smoke/canary still run; Phase 8 skips).

## Required environment variables

| Variable | Used by |
|----------|---------|
| `E2E_ADMIN_EMAIL` | Admin login + globalSetup fixture seed |
| `E2E_ADMIN_PASSWORD` | Admin login + globalSetup fixture seed |
| `E2E_API_BASE` | globalSetup API calls (default `http://localhost:8080`) |
| `E2E_SKIP_FIXTURES` | When `true`, skip API fixture seed entirely |
| `E2E_APPLICATION_ID` | Optional override; otherwise read from `e2e-fixtures.json` |
| `E2E_EC111_APPLICATION_ID` | Optional EC-111 disbursed fixture override |
| `E2E_LSP_UI_READ_EMAIL` | LSP UI read flows (optional until those specs run) |
| `E2E_LSP_PASSWORD` | LSP UI read flows |

Missing admin credentials fail fast inside specs that sign in (not dozens of cascading selector failures).

## Phase 8 application id resolution

`edge-coverage-phase8.spec.ts` resolves the loan detail id in order:

1. `process.env.E2E_APPLICATION_ID` (an explicit override or one set by `phase8_ui.py`)
2. `applicationId` in `frontend/e2e/.auth/e2e-fixtures.json`
3. If still unset, the file skips with `skipReason` from the fixture file when present

## Python indep harness

```bash
py -m pip install -r scripts/indep-e2e/requirements-e2e.txt
```

## Bootstrap heal (S10)

If the bootstrap admin is wiped without a restart:

`POST /api/v1/internal/system/bootstrap-sync` (SYSTEM_ADMIN, audited) re-runs
`LocalBootstrapAdminSyncService` sync logic. Configure password via
`APP_SECURITY_BOOTSTRAP_PASSWORD` only.
