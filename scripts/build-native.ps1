# SPDX-License-Identifier: GPL-3.0-or-later
# Builds libcoffer_ffi.so for the Android ABIs and generates the Kotlin
# bindings into android/core/native. Called by Gradle (:core:native:buildNative).
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'env.ps1')
$core = Join-Path $root 'core'
$native = Join-Path $root 'android\core\native\src\main'
# Reproducible native libraries: no local paths and no random build id.
$remap = "--remap-path-prefix=$root=/coffer --remap-path-prefix=$(Join-Path $env:USERPROFILE '.cargo')=/cargo -C link-arg=-Wl,--build-id=none"
foreach ($t in 'AARCH64_LINUX_ANDROID', 'ARMV7_LINUX_ANDROIDEABI', 'X86_64_LINUX_ANDROID') {
    Set-Item -Path "env:CARGO_TARGET_${t}_RUSTFLAGS" -Value $remap
}
Push-Location $core
try {
    cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 -P 26 -o (Join-Path $native 'jniLibs') build --release -p coffer-ffi
    if ($LASTEXITCODE -ne 0) { throw 'cargo ndk failed' }
    cargo build --release -p coffer-ffi --features cli --bin uniffi-bindgen
    if ($LASTEXITCODE -ne 0) { throw 'uniffi-bindgen build failed' }
    cargo build --release -p coffer-ffi --lib
    if ($LASTEXITCODE -ne 0) { throw 'host library build failed' }
    $hostLib = Get-ChildItem (Join-Path $core 'target\release') -Filter 'coffer_ffi.dll' | Select-Object -First 1
    $kt = Join-Path $native 'kotlin'
    & (Join-Path $core 'target\release\uniffi-bindgen.exe') generate --library $hostLib.FullName --language kotlin --out-dir $kt --no-format
    if ($LASTEXITCODE -ne 0) { throw 'binding generation failed' }
} finally {
    Pop-Location
}
