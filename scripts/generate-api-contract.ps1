$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot

Push-Location (Join-Path $repoRoot "backend")
try {
  $ErrorActionPreference = "Continue"
  .\mvnw.cmd test "-Dtest=OpenApiContractExportTest" "-Dopenapi.export=true"
  $mavenExitCode = $LASTEXITCODE
  $ErrorActionPreference = "Stop"
  if ($mavenExitCode -ne 0) {
    throw "OpenAPI export failed with exit code $mavenExitCode"
  }
} finally {
  $ErrorActionPreference = "Stop"
  Pop-Location
}

Push-Location (Join-Path $repoRoot "frontend")
try {
  $ErrorActionPreference = "Continue"
  npm run generate:api-types
  $npmExitCode = $LASTEXITCODE
  $ErrorActionPreference = "Stop"
  if ($npmExitCode -ne 0) {
    throw "Frontend API generation failed with exit code $npmExitCode"
  }
} finally {
  $ErrorActionPreference = "Stop"
  Pop-Location
}

Write-Host "OpenAPI contract refreshed: openapi/openapi.json + frontend/src/lib/api/generated/schema.ts"
