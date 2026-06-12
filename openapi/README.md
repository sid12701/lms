# OpenAPI contract (F2)

Committed snapshot of the Bhawana LMS API, generated from Springdoc.

## Regenerate

From the repo root (PowerShell):

```powershell
.\scripts\generate-api-contract.ps1
```

Or manually:

```bash
cd backend
./mvnw test -Dtest=OpenApiContractExportTest -Dopenapi.export=true
cd ../frontend
npm run generate:api-types
```

## CI

`OpenApiContractExportTest` boots the app on the `test` profile and asserts `/v3/api-docs` includes the ops loan-application detail path. Commit `openapi/openapi.json` and `frontend/src/lib/api/generated/schema.ts` together when backend DTOs change.
