# Bhawana LMS Frontend

React SPA for internal operations and LSP-scoped surfaces. Talks to the Spring Boot backend at `/api/v1/*` (proxied in dev via Vite).

## Prerequisites

- Node.js 20+
- Running backend (default `http://localhost:8080`)

## Setup

```bash
cd frontend
npm ci
cp .env.example .env.local   # set VITE_LOGIN_* credentials for your environment
```

## Scripts

| Command          | Purpose                                  |
| ---------------- | ---------------------------------------- |
| `npm run dev`    | Dev server at http://localhost:5173      |
| `npm run verify` | typecheck + lint + format + test + build |
| `npm run test`   | Vitest unit/component tests              |
| `npm run e2e`    | Playwright smoke tests (see note below)  |
| `npm run build`  | Production bundle → `dist/`              |

**E2E:** `e2e/smoke.spec.ts` runs without a backend. Responsive and loan-lifecycle specs require the Spring Boot API (see `.env.example` credentials).

## Docs

Implementation notes and UI phase history live under `docs/Frontend/`.
