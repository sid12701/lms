$ErrorActionPreference = "Continue"

$repoRoot = "D:\Desktop-New\Folders\LMS"
$frontendRoot = Join-Path $repoRoot "frontend"
$outputRoot = Join-Path $repoRoot "outputs\validation-2026-07-12"
$npm = "C:\Program Files\nodejs\npm.cmd"

$steps = @(
    @{ Name = "typecheck"; Arguments = @("run", "typecheck") },
    @{ Name = "lint"; Arguments = @("run", "lint") },
    @{ Name = "format-check"; Arguments = @("run", "format:check") },
    @{ Name = "encoding"; Arguments = @("run", "check:encoding") },
    @{ Name = "vitest-coverage"; Arguments = @("run", "test:cov") },
    @{ Name = "build"; Arguments = @("run", "build") }
)

$results = @()
Push-Location $frontendRoot
try {
    foreach ($step in $steps) {
        $name = $step.Name
        $stdout = Join-Path $outputRoot "frontend-$name.stdout.log"
        $stderr = Join-Path $outputRoot "frontend-$name.stderr.log"
        $startedAt = Get-Date
        $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()

        & $npm @($step.Arguments) 1> $stdout 2> $stderr
        $exitCode = $LASTEXITCODE
        $stopwatch.Stop()

        $results += [pscustomobject]@{
            name = $name
            command = "npm " + ($step.Arguments -join " ")
            exitCode = $exitCode
            startedAt = $startedAt.ToString("o")
            durationSeconds = [math]::Round($stopwatch.Elapsed.TotalSeconds, 3)
            stdout = $stdout
            stderr = $stderr
        }
        $results | ConvertTo-Json -Depth 5 | Set-Content -Encoding utf8 (Join-Path $outputRoot "frontend-campaign-results.json")
    }
}
finally {
    Pop-Location
}

if (($results | Where-Object { $_.exitCode -ne 0 }).Count -gt 0) {
    exit 1
}
exit 0
