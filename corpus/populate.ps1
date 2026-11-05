# SPDX-License-Identifier: GPL-3.0-or-later
# Mounts a corpus container with VeraCrypt, writes a deterministic file tree
# into it (written by Windows: the black-box oracle for Coffer's filesystem
# readers), records content-<container>.json with sizes and SHA-256, unmounts.
# Usage: powershell -ExecutionPolicy Bypass -File corpus\populate.ps1 -File <name.hc> -Password <pw> [-Hash sha512] [-Small]
param(
  [Parameter(Mandatory)] [string]$File,
  [Parameter(Mandatory)] [string]$Password,
  [string]$Hash = 'sha512',
  [switch]$Small
)
$ErrorActionPreference = 'Stop'
$vc = 'C:\Program Files\VeraCrypt\VeraCrypt.exe'
$out = Join-Path $PSScriptRoot 'out'
$hc = Join-Path $out $File
$drive = 'Q'
$root = "${drive}:\"

Start-Process -FilePath $vc -ArgumentList '/q','/s','/u',$drive -Wait | Out-Null
$p = Start-Process -FilePath $vc -ArgumentList '/q','/s','/v',"`"$hc`"",'/l',$drive,'/p',"`"$Password`"",'/hash',$Hash,'/m','ts','/nowaitdlg' -Wait -PassThru
if ($p.ExitCode -ne 0 -or -not (Test-Path $root)) { throw "mount failed for $File (exit $($p.ExitCode))" }
try {
  Get-ChildItem -LiteralPath $root -Force | Where-Object { $_.Name -ne 'System Volume Information' } | Remove-Item -Recurse -Force
  $rng = New-Object System.Random 1234
  function Put([string]$rel, [int]$size) {
    $path = Join-Path $root $rel
    $dir = Split-Path -Parent $path
    if (-not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
    $b = New-Object byte[] $size
    $rng.NextBytes($b)
    [IO.File]::WriteAllBytes($path, $b)
  }
  Put 'empty.txt' 0
  Put 'one.bin' 1
  Put 'sector.bin' 512
  Put 'sector-plus.bin' 513
  Put 'odd.bin' 4097
  Put 'big.bin' $(if ($Small) { 256KB } else { 3MB })
  Put 'UPPER.TXT' 100
  Put 'lower.txt' 100
  Put 'àèìòù €.txt' 333
  Put (('n' * 116) + '.txt') 50
  Put 'Cartella Con Spazi\sotto cartella\deep.txt' 777
  New-Item -ItemType Directory -Force -Path (Join-Path $root 'vuota') | Out-Null
  for ($i = 0; $i -lt 300; $i++) { Put ('many\f{0:000}.txt' -f $i) 10 }
  # Fragmented file: free a hole between two allocations, then fill it and beyond.
  Put 'a.bin' 100KB
  Put 'b.bin' 100KB
  Remove-Item -LiteralPath (Join-Path $root 'a.bin') -Force
  Put 'frag.bin' 300KB

  $entries = New-Object System.Collections.ArrayList
  foreach ($it in Get-ChildItem -LiteralPath $root -Recurse -Force | Where-Object { $_.FullName -notlike '*System Volume Information*' }) {
    $rel = '/' + $it.FullName.Substring($root.Length).Replace('\', '/')
    if ($it.PSIsContainer) {
      [void]$entries.Add([pscustomobject]@{ path = $rel; dir = $true; size = 0; sha256 = '' })
    } else {
      $h = (Get-FileHash -LiteralPath $it.FullName -Algorithm SHA256).Hash.ToLower()
      [void]$entries.Add([pscustomobject]@{ path = $rel; dir = $false; size = $it.Length; sha256 = $h })
    }
  }
  $fs = (New-Object System.IO.DriveInfo $drive).DriveFormat
  $json = ConvertTo-Json -InputObject @($entries | Sort-Object path) -Depth 3
  $name = [IO.Path]::GetFileNameWithoutExtension($File)
  [IO.File]::WriteAllText((Join-Path $out "content-$name.json"), $json, (New-Object Text.UTF8Encoding $false))
  Write-Host ("{0,-40} {1,-6} {2} entries" -f $File, $fs, $entries.Count)
} finally {
  Start-Process -FilePath $vc -ArgumentList '/q','/s','/u',$drive -Wait | Out-Null
}
