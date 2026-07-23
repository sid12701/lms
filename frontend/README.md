# Bhawana LMS Frontend

React SPA for internal operations and LSP-scoped surfaces. Talks to the Spring Boot backend at `/api/v1/*` (proxied in dev via Vite).

## Prerequisites

- Node.js 20+
- Running backend (default `http://localhost:8080`)

## Setup

```bash
cd frontend
npm ci
cp .env.example .env.local   # optionally set role-specific login email prefills
```

## Scripts

| Command                      | Purpose                                                                                  |
| ---------------------------- | ---------------------------------------------------------------------------------------- |
| `npm run dev`                | Dev server at http://localhost:5173                                                      |
| `npm run verify`             | All release gates (see below)                                                            |
| `npm run test`               | Vitest unit/component tests                                                              |
| `npm run build`              | Production bundle → `dist/`                                                              |
| `npm run lint`               | ESLint (`--max-warnings 0`)                                                              |
| `npm run format:check`       | Prettier check                                                                           |
| `npm run generate:api-types` | Regenerate `src/lib/api/generated/schema.ts` from OpenAPI (output is Prettier-formatted) |
| `npm run e2e`                | Playwright specs (see note below)                                                        |

### `npm run verify`

Implemented by `scripts/verify.mjs`. Runs **every** gate in sequence and reports a combined failure at the end (so one broken gate does not hide the others):

1. `typecheck` — `tsc --noEmit`
2. `lint`
3. `format:check`
4. `check:encoding`
5. `test` — Vitest (`testTimeout` 15s globally in `vite.config.ts`)
6. `build` — `tsc -b && vite build`

As of **2026-07-13** (Spec S1 / CI-01 remediation): lint, format, typecheck, build, and the unit suite are green (`754` tests in `123` files). A small number of legacy `act(...)` warnings may still appear in stderr during axe-heavy specs; they do not fail the run.

**E2E (Spec S9 / S10, 2026-07-15):** See `docs/e2e.md`. Env-only credentials (`E2E_ADMIN_EMAIL` / `E2E_ADMIN_PASSWORD`); Playwright `globalSetup` seeds `E2E-*` fixtures; Phase 8 skips with a reason when unseeded. Post-deploy smoke: `npm run e2e:canary`.

## Docs

Implementation notes and UI phase history live under `docs/Frontend/`. For the latest remediation snapshot see the **2026-07-13** block at the top of `docs/Frontend/CURRENT-STATE.md`.
