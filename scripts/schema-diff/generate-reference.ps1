#Requires -Version 5.1
<#
.SYNOPSIS
  Build normalized reference schema from Flyway migrate on Postgres 17 (Docker).
#>
$ErrorActionPreference = "Stop"

$Root = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$Migrations = Join-Path $Root "backend\src\main\resources\db\migration"
$Artifacts = Join-Path $PSScriptRoot "artifacts"
$Reference = Join-Path $Artifacts "reference-schema.normalized.sql"
$Container = "lms-b12-schema-ref-$PID"
$FlywayImage = if ($env:FLYWAY_IMAGE) { $env:FLYWAY_IMAGE } else { "flyway/flyway:11.1.0" }
$PostgresImage = if ($env:POSTGRES_IMAGE) { $env:POSTGRES_IMAGE } else { "postgres:17-alpine" }

New-Item -ItemType Directory -Force -Path $Artifacts | Out-Null

try {
    docker run -d --name $Container `
        -e POSTGRES_DB=lms `
        -e POSTGRES_USER=lms `
        -e POSTGRES_PASSWORD=lms `
        $PostgresImage | Out-Null

    for ($i = 0; $i -lt 60; $i++) {
        docker exec $Container pg_isready -U lms -d lms 2>$null | Out-Null
        if ($LASTEXITCODE -eq 0) { break }
        Start-Sleep -Seconds 1
    }

    if (-not (Test-Path $Migrations) -or -not (Get-ChildItem "$Migrations\*.sql" -ErrorAction SilentlyContinue)) {
        throw "No SQL migrations found under $Migrations"
    }

    docker run --rm --network "container:$Container" `
        -v "${Migrations}:/flyway/sql:ro" `
        $FlywayImage `
        --% -locations=filesystem:/flyway/sql `
        -url=jdbc:postgresql://localhost:5432/lms `
        -user=lms `
        -password=lms `
        -placeholders.tenant_app_role=lms_tenant_app `
        -placeholders.tenant_app_password=lms_tenant_app_password `
        migrate

    docker exec $Container pg_dump --schema-only --no-owner --no-privileges --schema=public -U lms lms |
        python (Join-Path $PSScriptRoot "normalize_schema.py") |
        Set-Content -Encoding UTF8 -Path $Reference

    $lines = (Get-Content $Reference).Count
    if ($lines -lt 100) {
        throw "Reference schema looks too small ($lines lines). Check Docker volume mount."
    }
    Write-Host "Wrote $Reference ($lines lines)"
}
finally {
    docker rm -f $Container 2>$null | Out-Null
}
