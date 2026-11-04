# SPDX-License-Identifier: GPL-3.0-or-later
# Note: VeraCrypt 1.26.29 Format.exe accepts "argon2" (not "argon2id"); PIM < 485 requires a password of >= 20 chars.
# Generates the VeraCrypt compatibility corpus (black-box oracle for Coffer tests).
# Output: corpus/out/*.hc, keyfiles, manifest.json (git-ignored).
# Usage:  powershell -ExecutionPolicy Bypass -File corpus\generate.ps1 [-Only <regex>]
param([string]$Only = '.')
$ErrorActionPreference = 'Stop'
$fmt = 'C:\Program Files\VeraCrypt\VeraCrypt Format.exe'
$out = Join-Path $PSScriptRoot 'out'
New-Item -ItemType Directory -Force $out | Out-Null

$ciphers = 'AES','Serpent','Twofish','Camellia','Kuznyechik','AES(Twofish)','AES(Twofish(Serpent))',
  'Serpent(AES)','Serpent(Twofish(AES))','Twofish(Serpent)','Camellia(Kuznyechik)','Kuznyechik(Twofish)',
  'Camellia(Serpent)','Kuznyechik(AES)','Kuznyechik(Serpent(Camellia))'
$prfs = [ordered]@{ sha512='SHA-512'; sha256='SHA-256'; whirlpool='Whirlpool'; blake2s='BLAKE2s-256'; streebog='Streebog'; argon2='Argon2id' }
function CipherName([string]$cli) { $cli.Replace('(', '-').Replace(')', '') }
function Slug([string]$s) { ($s.ToLower() -replace '[^a-z0-9]+', '-').Trim('-') }

# Deterministic keyfiles (content is arbitrary; fixed seed for reproducibility)
$rng = New-Object System.Random 4242
foreach ($kf in @(@{n='kf-4k.bin'; s=4096}, @{n='kf-2m.bin'; s=2MB})) {
  $p = Join-Path $out $kf.n
  if (-not (Test-Path $p)) { $b = New-Object byte[] $kf.s; $rng.NextBytes($b); [IO.File]::WriteAllBytes($p, $b) }
}

$jobs = New-Object System.Collections.ArrayList
foreach ($c in $ciphers) { [void]$jobs.Add(@{ cipher=$c; hash='sha512'; fs='FAT'; size='10M'; pw='coffer-test'; pim=0; kf=@() }) }
foreach ($h in @('sha256','whirlpool','blake2s','streebog','argon2')) { [void]$jobs.Add(@{ cipher='AES'; hash=$h; fs='FAT'; size='10M'; pw='coffer-test'; pim=0; kf=@() }) }
foreach ($pim in 1, 485) { [void]$jobs.Add(@{ cipher='AES'; hash='sha512'; fs='FAT'; size='10M'; pw='coffer-test-long-passphrase'; pim=$pim; kf=@() }) }
[void]$jobs.Add(@{ cipher='AES'; hash='sha512'; fs='FAT'; size='10M'; pw='coffer-test'; pim=0; kf=@('kf-4k.bin','kf-2m.bin'); tag='kf2' })
[void]$jobs.Add(@{ cipher='AES'; hash='sha512'; fs='FAT'; size='10M'; pw=''; pim=0; kf=@('kf-4k.bin'); tag='kf-nopass' })
[void]$jobs.Add(@{ cipher='AES'; hash='sha512'; fs='FAT'; size='64M'; pw='coffer-test'; pim=0; kf=@(); tag='fat32' })
[void]$jobs.Add(@{ cipher='AES'; hash='sha512'; fs='ExFAT'; size='64M'; pw='coffer-test'; pim=0; kf=@() })
[void]$jobs.Add(@{ cipher='AES'; hash='argon2'; fs='ExFAT'; size='64M'; pw='coffer-test'; pim=0; kf=@() })
[void]$jobs.Add(@{ cipher='Serpent(Twofish(AES))'; hash='argon2'; fs='FAT'; size='10M'; pw='coffer-test'; pim=0; kf=@() })
[void]$jobs.Add(@{ cipher='AES'; hash='sha512'; fs='FAT'; size='10M'; pw=('abcdefghij' * 10); pim=0; kf=@('kf-4k.bin'); tag='longpw-kf' })
[void]$jobs.Add(@{ cipher='AES'; hash='sha512'; fs='FAT'; size='10M'; pw='pàsswörd€'; pim=0; kf=@(); tag='utf8-pass' })

$manifestPath = Join-Path $out 'manifest.json'
$manifest = New-Object System.Collections.ArrayList
if (Test-Path $manifestPath) { foreach ($e in (Get-Content $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json)) { [void]$manifest.Add($e) } }
$failed = 0
foreach ($j in $jobs) {
  $name = (Slug (CipherName $j.cipher)) + '-' + $j.hash + '-' + $j.fs.ToLower()
  if ($j.pim) { $name += "-pim$($j.pim)" }
  if ($j.tag) { $name += "-$($j.tag)" }
  $file = "$name.hc"
  if ($file -notmatch $Only) { continue }
  $path = Join-Path $out $file
  $args = @('/create', "`"$path`"", '/size', $j.size, '/password', "`"$($j.pw)`"", '/hash', $j.hash,
            '/encryption', $j.cipher, '/filesystem', $j.fs, '/quick', '/force', '/silent')
  if ($j.pim) { $args += @('/pim', "$($j.pim)") }
  foreach ($k in $j.kf) { $args += @('/keyfile', "`"$(Join-Path $out $k)`"") }
  if (Test-Path $path) { Remove-Item $path -Force }
  $sw = [Diagnostics.Stopwatch]::StartNew()
  $p = Start-Process -FilePath $fmt -ArgumentList $args -Wait -PassThru
  $expected = [int64]($j.size.TrimEnd('M')) * 1MB
  $ok = ($p.ExitCode -eq 0) -and (Test-Path $path) -and ((Get-Item $path).Length -eq $expected)
  Write-Host ("{0,-60} {1} ({2:n1}s)" -f $file, $(if ($ok) { 'OK' } else { "FAIL exit=$($p.ExitCode)" }), $sw.Elapsed.TotalSeconds)
  if (-not $ok) { $failed++; continue }
  $fsName = if ($j.fs -eq 'ExFAT') { 'exFAT' } else { 'FAT' }
  $entry = [pscustomobject]@{ file=$file; password=$j.pw; pim=$j.pim; keyfiles=@($j.kf); cipher=(CipherName $j.cipher);
                              prf=$prfs[$j.hash]; fs=$fsName; size=$expected; kind='standard' }
  $manifest = [System.Collections.ArrayList]@($manifest | Where-Object { $_.file -ne $file })
  [void]$manifest.Add($entry)
}
$json = ConvertTo-Json -InputObject @($manifest | Sort-Object file) -Depth 4
[IO.File]::WriteAllText($manifestPath, $json, (New-Object Text.UTF8Encoding $false))
Write-Host "manifest: $($manifest.Count) entries, $failed failures"
if ($failed) { exit 1 }
