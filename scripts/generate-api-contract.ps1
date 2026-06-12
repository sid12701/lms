$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot

Push-Location (Join-Path $repoRoot "backend")
try {
  .\mvnw.cmd test "-Dtest=OpenApiContractExportTest" "-Dopenapi.export=true"
} finally {
  Pop-Location
}

Push-Location (Join-Path $repoRoot "frontend")
try {
  npm run generate:api-types
} finally {
  Pop-Location
}

Write-Host "OpenAPI contract refreshed: openapi/openapi.json + frontend/src/lib/api/generated/schema.ts"
