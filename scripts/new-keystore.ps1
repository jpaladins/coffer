# SPDX-License-Identifier: GPL-3.0-or-later
# Creates the release signing key OUTSIDE the repository and the
# android/keystore.properties file that points to it (git-ignored).
# Keep the .jks file and its password backed up offline: losing them means
# no more updates on the Play Store under the same app.
param([string]$Out = (Join-Path $env:USERPROFILE 'coffer-release.jks'))
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
if (Test-Path $Out) { throw "$Out already exists: refusing to overwrite a signing key" }
$keytool = Get-ChildItem "$env:ProgramFiles\Android\Android Studio\jbr\bin\keytool.exe", "$env:JAVA_HOME\bin\keytool.exe" -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $keytool) { throw 'keytool not found (Android Studio JBR or JAVA_HOME)' }
$pw = Read-Host 'Password for the new key (min 12 characters)' -AsSecureString
$plain = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($pw))
if ($plain.Length -lt 12) { throw 'password too short' }
& $keytool.FullName -genkeypair -v -keystore $Out -alias coffer -keyalg RSA -keysize 4096 -validity 10000 `
    -storepass $plain -keypass $plain -dname 'CN=Coffer, O=Coffer'
if ($LASTEXITCODE -ne 0) { throw 'keytool failed' }
@"
storeFile=$($Out.Replace('\', '/'))
storePassword=$plain
keyAlias=coffer
keyPassword=$plain
"@ | Set-Content -Encoding utf8 (Join-Path $root 'android\keystore.properties')
Write-Host "Key created at $Out. Back it up offline together with its password."
