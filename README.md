# LMS

Loan Management System workspace for the Bhawana multi-tenant platform.

## Scope

- Spring Boot backend for loan origination, lifecycle, servicing, reporting, and integrations
- React frontend with shadcn-style components for internal operations and selective LSP access
- Local infrastructure for PostgreSQL, Redis, RabbitMQ, MinIO, and MailHog
- Architecture, planning, and UI design references under `docs/`

## Current Structure

```text
backend/  Spring Boot service and domain modules
frontend/ React SPA (Vite) for internal ops and LSP surfaces
docs/     Architecture, roadmap, and design references
infra/    Local development infrastructure and deployment scaffolding
```

## Delivery Notes

- The project starts as a modular monolith backend with a separate SPA frontend.
- Backend and frontend should evolve in parallel, with small commits for each verified checkpoint.
- Current architecture and roadmap references live in:
  - `docs/architecture/lms-blueprint.md`
  - `docs/planning/implementation-roadmap.md`

## Development verification

Run gates from each package directory (not the repo root unless noted).

| Area | Command | Notes |
|------|---------|--------|
| Backend tests | `cd backend && mvnw.cmd test` (Windows) or `./mvnw test` | Safe with repo-root `.env` present — see `backend/README.md` |
| Frontend gates | `cd frontend && npm run verify` | Runs typecheck, lint, format, encoding check, tests, and build; reports every failure |
| Frontend dev | `cd frontend && npm run dev` | Requires backend on `:8080` |

**IDE (Cursor / VS Code):** `.vscode/settings.json` configures the Java extension for this monorepo (`backend/` Maven module, `frontend/` excluded from Java analysis). If test-support imports such as `TenantContextTestExecutionListener` show as unresolved while `mvnw test-compile` succeeds, run **Java: Clean Java Language Server Workspace** and reload.
