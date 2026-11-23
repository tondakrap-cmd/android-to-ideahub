param([string]$OutputFile)
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
if (-not $OutputFile) { $OutputFile = Join-Path $root 'releases\TabuleLink-Windows-0.6.1.exe' }
$csc = 'C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe'
if (-not (Test-Path -LiteralPath $csc)) { throw "Chybí .NET Framework kompilátor: $csc" }
$outDir = Split-Path -Parent $OutputFile
if (-not (Test-Path -LiteralPath $outDir)) { New-Item -ItemType Directory -Path $outDir -Force | Out-Null }
$srcDir = Join-Path $root 'windows\src'
$sources = Get-ChildItem -LiteralPath $srcDir -Filter '*.cs' | ForEach-Object { $_.FullName }
& $csc /nologo /target:winexe /platform:x64 /optimize+ /utf8output /out:$OutputFile `
    /r:System.dll /r:System.Drawing.dll /r:System.Windows.Forms.dll $sources
if ($LASTEXITCODE -ne 0) { throw "Sestavení Windows aplikace selhalo: $LASTEXITCODE" }
$hash = (Get-FileHash -LiteralPath $OutputFile -Algorithm SHA256).Hash.ToLowerInvariant()
Write-Output "EXE: $OutputFile"
Write-Output "SHA256: $hash"
