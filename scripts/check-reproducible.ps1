# SPDX-License-Identifier: GPL-3.0-or-later
# Builds the unsigned release APK twice from clean and compares the hashes
# (F-Droid requirement: reproducible builds). Temporarily moves
# android/keystore.properties aside so both builds are unsigned.
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'env.ps1')
$android = Join-Path $root 'android'
$props = Join-Path $android 'keystore.properties'
$moved = $false
if (Test-Path $props) { Move-Item $props "$props.bak"; $moved = $true }
try {
    $hashes = @()
    foreach ($i in 1, 2) {
        Push-Location $android
        try {
            & .\gradlew.bat clean -q
            Remove-Item -Recurse -Force (Join-Path $root 'core\target\aarch64-linux-android'), (Join-Path $root 'core\target\armv7-linux-androideabi'), (Join-Path $root 'core\target\x86_64-linux-android') -ErrorAction SilentlyContinue
            & .\gradlew.bat :app:assembleRelease -q
            if ($LASTEXITCODE -ne 0) { throw "build $i failed" }
        } finally { Pop-Location }
        $apk = Join-Path $android 'app\build\outputs\apk\release\app-release-unsigned.apk'
        $hashes += (Get-FileHash $apk -Algorithm SHA256).Hash
        Write-Host "build ${i}: $($hashes[-1])"
    }
    if ($hashes[0] -ne $hashes[1]) { Write-Host 'NOT REPRODUCIBLE'; exit 1 }
    Write-Host 'REPRODUCIBLE'
} finally {
    if ($moved) { Move-Item "$props.bak" $props }
}
