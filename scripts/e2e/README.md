# E2E edge-case coverage harness

Full requirements: [`docs/e2e-edge-cases-full-coverage-requirements.md`](../../docs/e2e-edge-cases-full-coverage-requirements.md)

## Quick start

```bash
# 1. Copy config
cp scripts/e2e/config.example.env scripts/e2e/config.env

# 2. Backend + frontend running (restart backend after API changes — Phase 0 needs
#    POST /api/v1/internal/reports/requests/process and /actuator/health/liveness)

# 3. Newman regression (Phase 0)
npx newman run postman/LMS-E2E-Testing-Admin-and-LSP.postman_collection.json \
  -e postman/LMS-E2E-Local.postman_environment.json

# 4. Build fixtures + Phase 1 API negatives
python scripts/e2e/run_coverage.py --phase 1 --update-matrix

# 5. Webhook mock (Phase 5 prerequisite)
python scripts/e2e/webhook-mock/server.py

# 6. UI phase — follow scripts/e2e/ui-checklist.json with Chrome DevTools MCP
```

## Layout

| Path | Role |
|------|------|
| `config.example.env` | Rate limits, webhook URLs, fixture prefix |
| `fixtures.py` | Creates F01–F10 fixture bundle |
| `run_coverage.py` | Phase orchestrator |
| `phases/phase1_api_negatives.py` | First API-negative batch (more phases TBD) |
| `ui-checklist.json` | 10 UI cases for Chrome MCP |
| `webhook-mock/server.py` | Local webhook receiver |
