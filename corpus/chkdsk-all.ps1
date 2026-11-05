# SPDX-License-Identifier: GPL-3.0-or-later
# Runs a read-only chkdsk on every *.hc in a folder (same password for all).
# Self-elevates once; report in <folder>\chkdsk-report.txt.
param([Parameter(Mandatory)][string]$Folder, [Parameter(Mandatory)][string]$Password, [switch]$Elevated)
$report = Join-Path $Folder 'chkdsk-report.txt'
$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
  Remove-Item $report -ErrorAction SilentlyContinue
  $p = Start-Process powershell -Verb RunAs -Wait -PassThru -ArgumentList '-NoProfile','-ExecutionPolicy','Bypass','-File',"`"$PSCommandPath`"",'-Folder',"`"$Folder`"",'-Password',"`"$Password`"",'-Elevated'
  if (Test-Path $report) { Get-Content $report -Encoding UTF8 }
  exit $p.ExitCode
}
$vc = 'C:\Program Files\VeraCrypt\VeraCrypt.exe'
$out = New-Object System.Collections.ArrayList
$bad = 0
foreach ($f in Get-ChildItem $Folder -Filter *.hc) {
  Start-Process -FilePath $vc -ArgumentList '/q','/s','/u','Q' -Wait | Out-Null
  Start-Process -FilePath $vc -ArgumentList '/q','/s','/v',"`"$($f.FullName)`"",'/l','Q','/p',"`"$Password`"",'/hash','sha512','/m','ts','/nowaitdlg' -Wait | Out-Null
  $chk = (cmd /c "echo N| chkdsk Q: 2>&1") -join "`n"
  $code = $LASTEXITCODE
  if ($code -ne 0) { $bad++ }
  $errs = ($chk -split "`n" | Where-Object { $_ -match 'rror|rrore|Convertire|danneggiat|non valid|invalid' }) -join ' | '
  [void]$out.Add(("{0,-14} exit={1} {2}" -f $f.Name, $code, $errs))
  Start-Process -FilePath $vc -ArgumentList '/q','/s','/u','Q' -Wait | Out-Null
}
[IO.File]::WriteAllLines($report, [string[]]$out, (New-Object Text.UTF8Encoding $false))
exit $bad
