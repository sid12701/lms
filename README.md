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
docs/     Architecture, roadmap, and design references
infra/    Local development infrastructure and deployment scaffolding
```

## Delivery Notes

- The project starts as a modular monolith backend with a separate SPA frontend.
- Backend and frontend should evolve in parallel, with small commits for each verified checkpoint.
- Current architecture and roadmap references live in:
  - `docs/architecture/lms-blueprint.md`
  - `docs/planning/implementation-roadmap.md`
