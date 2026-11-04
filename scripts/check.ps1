# Coffer quality gate. Stops at the first failing step.
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'env.ps1')
Push-Location (Join-Path $root 'core')
try {
    function Step([string]$name, [scriptblock]$cmd) {
        Write-Host "==> $name" -ForegroundColor Cyan
        & $cmd
        if ($LASTEXITCODE -ne 0) { throw "FAILED: $name (exit $LASTEXITCODE)" }
    }
    Step 'fmt'      { cargo fmt --all -- --check }
    Step 'clippy'   { cargo clippy --workspace --all-targets -- -D warnings }
    Step 'test'     { cargo test --workspace }
    Step 'licenses' { cargo deny check licenses }
    if ($env:COFFER_CORPUS) {
        Step 'corpus' { cargo test -p coffer-volume --release --test corpus -- --ignored --nocapture }
    } else {
        Write-Host '!!! COFFER_CORPUS non impostata: test sul corpus reale SALTATI !!!' -ForegroundColor Yellow
    }
    Step 'android'  { cargo ndk -t arm64-v8a -P 26 build --release }
    Write-Host 'ALL CHECKS PASSED' -ForegroundColor Green
} finally {
    Pop-Location
}
