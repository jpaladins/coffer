# Dot-source: . .\scripts\env.ps1
$mingw = Join-Path $env:LOCALAPPDATA 'Microsoft\WinGet\Packages\BrechtSanders.WinLibs.POSIX.UCRT_Microsoft.Winget.Source_8wekyb3d8bbwe\mingw64\bin'
$cargo = Join-Path $env:USERPROFILE '.cargo\bin'
$env:Path = "$cargo;$mingw;$env:Path"
$sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$env:ANDROID_HOME = $sdk
$ndk = Get-ChildItem (Join-Path $sdk 'ndk') -Directory | Sort-Object Name -Descending | Select-Object -First 1
if ($ndk) { $env:ANDROID_NDK_HOME = $ndk.FullName }
$env:Path = "$(Join-Path $sdk 'platform-tools');$env:Path"
