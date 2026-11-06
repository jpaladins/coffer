# SPDX-License-Identifier: GPL-3.0-or-later
# Windows as the oracle for containers CREATED by Coffer: mounts each
# corpus/out/created/*.hc (made by core/coffer-fs/examples/create.rs or by the
# app), compares the tree and SHA-256 Windows sees with <name>.expected.json
# and runs chkdsk (read-only scan). <name>.job.json gives password, hash, PIM.
# chkdsk needs administrator rights: the script re-launches itself elevated
# (one UAC prompt) and writes the report to corpus/out/created/verify-report.txt.
param([switch]$Elevated, [string]$Only = "*.hc")
$ErrorActionPreference = 'Stop'
$rw = Join-Path $PSScriptRoot 'out\created'
$report = Join-Path $rw 'verify-report.txt'

$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
  Remove-Item $report -ErrorAction SilentlyContinue
  $p = Start-Process powershell -Verb RunAs -Wait -PassThru -ArgumentList '-NoProfile','-ExecutionPolicy','Bypass','-File',"`"$PSCommandPath`"",'-Elevated','-Only',"`"$Only`""
  if (Test-Path $report) { Get-Content $report -Encoding UTF8 }
  exit $p.ExitCode
}

$vc = 'C:\Program Files\VeraCrypt\VeraCrypt.exe'
$drive = 'Q'
$root = "${drive}:\"
$lines = New-Object System.Collections.ArrayList
$failed = 0
function Log($s) { [void]$lines.Add($s) }

$jobs = foreach ($f in Get-ChildItem $rw -Filter $Only) {
  $meta = Get-Content (Join-Path $rw ($f.BaseName + '.job.json')) -Raw -Encoding UTF8 | ConvertFrom-Json
  @{ file = $f.Name; pw = $meta.password; hash = $meta.hash; pim = $meta.pim }
}
try {
  foreach ($j in $jobs) {
    $hc = Join-Path $rw $j.file
    $label = if ($j.label) { $j.label } else { $j.file }
    Start-Process -FilePath $vc -ArgumentList '/q','/s','/u',$drive -Wait | Out-Null
    $vcArgs = @('/q','/s','/v',"`"$hc`"",'/l',$drive,'/p',"`"$($j.pw)`"",'/m','ts','/nowaitdlg')
    # The mount command line rejects /hash argon2 ("error parsing"): let
    # VeraCrypt detect it. /pim only when set.
    if ($j.hash -ne 'argon2') { $vcArgs += @('/hash', $j.hash) }
    if ($j.pim) { $vcArgs += @('/pim', "$($j.pim)") }
    $p = Start-Process -FilePath $vc -ArgumentList $vcArgs -Wait -PassThru
    if (-not (Test-Path $root)) { Log "FAIL $label : mount failed (exit $($p.ExitCode))"; $failed++; continue }
    try {
      $fs = (New-Object System.IO.DriveInfo $drive).DriveFormat
      # Answer "N" to any prompt (read-only scan must never wait for input).
      $chk = (cmd /c "echo N| chkdsk ${drive}: 2>&1") -join "`n"
      $chkExit = $LASTEXITCODE
      if ($chkExit -ne 0) { Log "FAIL $label ($fs): chkdsk exit $chkExit`n$chk"; $failed++ } else { Log "OK   $label ($fs): chkdsk clean" }
      [IO.File]::WriteAllText((Join-Path $rw ("chkdsk-" + [IO.Path]::GetFileNameWithoutExtension($j.file) + ".txt")), $chk)
      $name = [IO.Path]::GetFileNameWithoutExtension($j.file)
      $expFile = if ($j.expected) { Join-Path $rw $j.expected } elseif (-not $j.label) { Join-Path $rw "$name.expected.json" } else { $null }
      if ($expFile) {
        $expected = Get-Content $expFile -Raw -Encoding UTF8 | ConvertFrom-Json
        $want = @{}
        foreach ($e in $expected) { $want[$e.path] = $e }
        $seen = @{}
        foreach ($it in Get-ChildItem -LiteralPath $root -Recurse -Force | Where-Object { $_.FullName -notlike '*System Volume Information*' }) {
          $rel = '/' + $it.FullName.Substring($root.Length).Replace('\', '/')
          $seen[$rel] = $true
          $w = $want[$rel]
          if (-not $w) { Log "FAIL $label : unexpected $rel"; $failed++; continue }
          if ($it.PSIsContainer) {
            if (-not $w.dir) { Log "FAIL $label : $rel is a directory"; $failed++ }
          } else {
            $h = (Get-FileHash -LiteralPath $it.FullName -Algorithm SHA256).Hash.ToLower()
            if ($w.dir -or $it.Length -ne $w.size -or $h -ne $w.sha256) { Log "FAIL $label : $rel differs (size $($it.Length), sha $h)"; $failed++ }
          }
        }
        foreach ($k in $want.Keys) { if (-not $seen[$k]) { Log "FAIL $label : missing $k"; $failed++ } }
        Log "     $label : compared $($want.Count) entries with Windows"
      } else {
        $n = @(Get-ChildItem -LiteralPath $root -Recurse -Force | Where-Object { $_.FullName -notlike '*System Volume Information*' }).Count
        Log "     $label : $n entries"
      }
    } finally {
      Start-Process -FilePath $vc -ArgumentList '/q','/s','/u',$drive -Wait | Out-Null
    }
  }
} catch {
  Log "FAIL script error: $_"; $failed++
}
Log $(if ($failed) { "RESULT: $failed failure(s)" } else { 'RESULT: ALL OK' })
[IO.File]::WriteAllLines($report, [string[]]$lines, (New-Object Text.UTF8Encoding $false))
exit $failed
